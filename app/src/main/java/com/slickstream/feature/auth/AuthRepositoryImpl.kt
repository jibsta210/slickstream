package com.slickstream.feature.auth

import android.app.Activity
import android.content.Context
import android.util.Base64
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import dagger.hilt.android.qualifiers.ApplicationContext
import com.slickstream.core.common.Auth
import com.slickstream.core.model.DataResult
import com.slickstream.core.model.UserProfile
import com.slickstream.core.repository.AuthRepository
import com.slickstream.data.sync.FirebaseSync
import com.slickstream.data.sync.LibrarySyncCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google OAuth via the Jetpack [CredentialManager] + the Google Identity `googleid` library.
 *
 * The signed-in [UserProfile] is mirrored into a DataStore Preferences file so the session
 * survives process death / restarts; [currentUser] is seeded from that store on construction
 * via [restoreSession].
 */
@Singleton
class AuthRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val firebaseSync: FirebaseSync,
    private val syncCoordinator: LibrarySyncCoordinator,
) : AuthRepository {

    private val credentialManager: CredentialManager by lazy { CredentialManager.create(context) }

    private val _currentUser = MutableStateFlow<UserProfile?>(null)
    override val currentUser: StateFlow<UserProfile?> = _currentUser.asStateFlow()

    /**
     * Launch the Google credential flow. Requires an [Activity] so Credential Manager can show
     * its system UI. On success the resolved [UserProfile] is persisted and pushed to
     * [currentUser]; failures map to a friendly [DataResult.Error].
     */
    override suspend fun signIn(activity: Activity): DataResult<UserProfile> {
        val serverClientId = Auth.GOOGLE_WEB_CLIENT_ID
        if (serverClientId.isBlank()) {
            return DataResult.Error(
                "Google sign-in isn't configured yet. Add a GOOGLE_WEB_CLIENT_ID to enable it.",
            )
        }

        return try {
            val response = credentialManager.getCredential(
                context = activity,
                request = buildSignInRequest(serverClientId),
            )
            val googleCredential = response.asGoogleIdCredential()
                ?: return DataResult.Error("Couldn't read your Google account. Please try again.")

            // Best-effort: exchange the Google ID token for a Firebase session so favourites and
            // watch history sync across devices. No-ops when Firebase isn't configured.
            runCatching { firebaseSync.signInWithGoogle(googleCredential.idToken) }

            val profile = googleCredential.toUserProfile()
            persist(profile)
            _currentUser.value = profile
            syncCoordinator.onSignedIn()
            DataResult.Success(profile)
        } catch (e: GetCredentialCancellationException) {
            DataResult.Error("Sign-in was cancelled.", e)
        } catch (e: NoCredentialException) {
            DataResult.Error(
                "No Google accounts available on this device. Add one in Settings and try again.",
                e,
            )
        } catch (e: GoogleIdTokenParsingException) {
            DataResult.Error("Couldn't verify your Google account. Please try again.", e)
        } catch (e: GetCredentialException) {
            DataResult.Error("Google sign-in failed. Please try again.", e)
        } catch (e: Exception) {
            DataResult.Error(e.message ?: "Google sign-in failed. Please try again.", e)
        }
    }

    /**
     * Sign in from a Google ID token acquired via the TV device-authorization flow. Mirrors the
     * success branch of [signIn]: exchange for a Firebase session, persist, emit, start sync.
     */
    override suspend fun signInWithIdToken(idToken: String): DataResult<UserProfile> {
        val profile = profileFromIdToken(idToken)
            ?: return DataResult.Error("Couldn't verify your Google account. Please try again.")
        return try {
            runCatching { firebaseSync.signInWithGoogle(idToken) }
            persist(profile)
            _currentUser.value = profile
            syncCoordinator.onSignedIn()
            DataResult.Success(profile)
        } catch (e: Exception) {
            DataResult.Error(e.message ?: "Google sign-in failed. Please try again.", e)
        }
    }

    /** Fetch a fresh Google ID token for the current account (to hand over to a TV being linked). */
    override suspend fun acquireIdToken(activity: Activity): DataResult<String> {
        val serverClientId = Auth.GOOGLE_WEB_CLIENT_ID
        if (serverClientId.isBlank()) {
            return DataResult.Error("Google sign-in isn't configured yet.")
        }
        return try {
            val response = credentialManager.getCredential(
                context = activity,
                request = buildSignInRequest(serverClientId),
            )
            val cred = response.asGoogleIdCredential()
                ?: return DataResult.Error("Couldn't read your Google account. Please try again.")
            DataResult.Success(cred.idToken)
        } catch (e: GetCredentialCancellationException) {
            DataResult.Error("Sign-in was cancelled.", e)
        } catch (e: NoCredentialException) {
            DataResult.Error("No Google account on this phone. Sign in first, then link the TV.", e)
        } catch (e: GetCredentialException) {
            DataResult.Error("Couldn't get a fresh sign-in. Please try again.", e)
        } catch (e: Exception) {
            DataResult.Error(e.message ?: "Couldn't get a fresh sign-in.", e)
        }
    }

    /** Build a [UserProfile] from the standard OIDC claims in a Google ID token (no verification). */
    private fun profileFromIdToken(idToken: String): UserProfile? {
        val claims = decodeJwtClaims(idToken) ?: return null
        val sub = claims.optString("sub").takeIf { it.isNotBlank() } ?: return null
        val email = claims.optString("email").takeIf { it.isNotBlank() }
        val name = claims.optString("name").takeIf { it.isNotBlank() }
            ?: claims.optString("given_name").takeIf { it.isNotBlank() }
        val picture = claims.optString("picture").takeIf { it.isNotBlank() }
        return UserProfile(id = sub, displayName = name, email = email, photoUrl = picture)
    }

    /** Decode (without verifying) the payload of a JWT into a JSONObject. */
    private fun decodeJwtClaims(idToken: String): JSONObject? = try {
        val parts = idToken.split(".")
        if (parts.size < 2) null
        else {
            val bytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            JSONObject(String(bytes, Charsets.UTF_8))
        }
    } catch (_: Exception) {
        null
    }

    /** Clear the Credential Manager state and wipe the persisted session. */
    override suspend fun signOut() {
        runCatching {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        }
        syncCoordinator.onSignedOut()
        dataStore.edit { it.remove(KEY_USER) }
        _currentUser.value = null
    }

    /** Re-hydrate [currentUser] from DataStore (no UI). Safe to call repeatedly. */
    override suspend fun restoreSession() {
        val prefs = dataStore.data.firstOrNull() ?: return
        val restored = prefs[KEY_USER]?.let(::decodeUser)
        _currentUser.value = restored
        // Firebase persists its own session; if it's still signed in, resume cross-device sync.
        if (restored != null && firebaseSync.uid() != null) syncCoordinator.onSignedIn()
    }

    // --- request building -----------------------------------------------------------------

    /**
     * Try an authorized-accounts flow first (the polished one-tap experience for returning
     * users), and fall back to the broad "Sign in with Google" button option for first-time
     * users with no previously authorized account.
     */
    private fun buildSignInRequest(serverClientId: String): GetCredentialRequest {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(true)
            .build()

        val signInWithGoogleOption = GetSignInWithGoogleOption.Builder(serverClientId)
            .build()

        return GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .addCredentialOption(signInWithGoogleOption)
            .build()
    }

    // --- mapping --------------------------------------------------------------------------

    private fun GetCredentialResponse.asGoogleIdCredential(): GoogleIdTokenCredential? {
        val cred = credential
        if (cred is CustomCredential &&
            cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            return runCatching { GoogleIdTokenCredential.createFrom(cred.data) }.getOrNull()
        }
        return null
    }

    /** In the googleid library `id` is the account email; the stable id is the JWT `sub` claim. */
    private fun GoogleIdTokenCredential.toUserProfile(): UserProfile {
        val email = id.takeIf { it.contains('@') }
        val subject = subjectFromIdToken(idToken) ?: id
        return UserProfile(
            id = subject,
            displayName = displayName ?: givenName,
            email = email,
            photoUrl = profilePictureUri?.toString(),
        )
    }

    /** Decode the `sub` claim from a JWT ID token without verifying the signature. */
    private fun subjectFromIdToken(idToken: String): String? = try {
        val parts = idToken.split(".")
        if (parts.size < 2) {
            null
        } else {
            val payloadBytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            val sub = JSONObject(String(payloadBytes, Charsets.UTF_8)).optString("sub")
            sub.takeIf { it.isNotBlank() }
        }
    } catch (_: Exception) {
        null
    }

    // --- persistence ----------------------------------------------------------------------

    private suspend fun persist(profile: UserProfile) {
        dataStore.edit { it[KEY_USER] = encodeUser(profile) }
    }

    /** Serialise without pulling in extra deps: tab-separated, with a sentinel for nulls. */
    private fun encodeUser(profile: UserProfile): String = listOf(
        profile.id,
        profile.displayName.orEmpty(),
        profile.email.orEmpty(),
        profile.photoUrl.orEmpty(),
    ).joinToString(FIELD_SEP)

    private fun decodeUser(raw: String): UserProfile? {
        val parts = raw.split(FIELD_SEP)
        if (parts.isEmpty() || parts[0].isBlank()) return null
        return UserProfile(
            id = parts[0],
            displayName = parts.getOrNull(1)?.takeIf { it.isNotEmpty() },
            email = parts.getOrNull(2)?.takeIf { it.isNotEmpty() },
            photoUrl = parts.getOrNull(3)?.takeIf { it.isNotEmpty() },
        )
    }

    private companion object {
        const val FIELD_SEP = "" // unit separator — never appears in the payload fields
        val KEY_USER = stringPreferencesKey("google_user_profile")
    }
}
