package com.slickstream.data.sync

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.core.model.PlaybackProgress
import com.slickstream.core.model.Profile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cloud mirror for the local library, keyed by the Firebase Auth uid (obtained by exchanging the
 * Google ID token from the existing Credential Manager sign-in). Everything is fully guarded: if
 * Firebase isn't configured (no google-services.json) or the user is signed out, every call is a
 * safe no-op and the app stays local-only.
 *
 * Layout:  users/{uid}/profiles/{profileId}
 *          users/{uid}/favorites/{profileId__TYPE_id}
 *          users/{uid}/history/{profileId__TYPE_mediaId_season_episode}
 *
 * Every favourite/history doc carries a "profileId" field so a device pulling them can re-attach
 * each item to its ORIGIN profile (never the receiving device's active profile).
 */
@Singleton
class FirebaseSync @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Firebase only auto-initialises when google-services.json was present at build time. */
    private val available: Boolean by lazy {
        runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)
    }

    private val auth: FirebaseAuth? get() = if (available) runCatching { FirebaseAuth.getInstance() }.getOrNull() else null
    private val db: FirebaseFirestore? get() = if (available) runCatching { FirebaseFirestore.getInstance() }.getOrNull() else null

    val isAvailable: Boolean get() = available

    fun uid(): String? = auth?.currentUser?.uid

    /** Exchange the Google ID token for a Firebase session so Firestore is keyed by uid. */
    suspend fun signInWithGoogle(idToken: String): String? {
        val a = auth ?: return null
        return try {
            a.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await().user?.uid
        } catch (t: Throwable) {
            Log.w(TAG, "Firebase sign-in failed", t)
            null
        }
    }

    fun signOut() {
        runCatching { auth?.signOut() }
    }

    // --- TV pairing mailbox -------------------------------------------------
    // A short-lived Firestore doc keyed by a code the TV shows. The signed-in phone writes its
    // Google id-token + profile into it; the TV polls, signs in, and deletes it. Works without a
    // Firebase Auth session (rules allow the /pairing collection for the brief window).

    /** TV: create the pending pairing doc for [code]. Returns false if Firestore is unavailable. */
    suspend fun createPairing(code: String): Boolean {
        val d = db ?: return false
        return try {
            d.collection(PAIRING).document(code).set(
                mapOf("createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(), "status" to "waiting"),
            ).await()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "createPairing failed", t); false
        }
    }

    /** Phone: write the account's id-token + profile into the TV's [code]. */
    suspend fun claimPairing(code: String, idToken: String, name: String?, email: String?, photoUrl: String?): Boolean {
        val d = db ?: return false
        return try {
            d.collection(PAIRING).document(code).set(
                mapOf(
                    "idToken" to idToken,
                    "name" to name, "email" to email, "photoUrl" to photoUrl,
                    "claimedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                ),
                com.google.firebase.firestore.SetOptions.merge(),
            ).await()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "claimPairing failed", t); false
        }
    }

    /** TV: returns the claimed id-token once the phone has filled it in (null while still waiting). */
    suspend fun readPairingToken(code: String): String? {
        val d = db ?: return null
        return try {
            val snap = d.collection(PAIRING).document(code).get().await()
            if (!snap.exists()) return null
            snap.getString("idToken")
        } catch (t: Throwable) {
            Log.w(TAG, "readPairingToken failed", t); null
        }
    }

    suspend fun deletePairing(code: String) {
        runCatching { db?.collection(PAIRING)?.document(code)?.delete()?.await() }
    }

    // --- Profiles -----------------------------------------------------------

    fun pushProfile(p: Profile) {
        val col = profileCol() ?: return
        runCatching { col.document(p.id).set(p.toMap()) }
    }

    suspend fun pushProfileNow(p: Profile) {
        val col = profileCol() ?: return
        runCatching { col.document(p.id).set(p.toMap()).await() }
    }

    fun removeProfile(id: String) {
        val col = profileCol() ?: return
        runCatching { col.document(id).delete() }
    }

    suspend fun pullProfiles(): List<Profile> {
        val col = profileCol() ?: return emptyList()
        return runCatching {
            col.get().await().documents.mapNotNull { it.data?.toProfile() }
        }.getOrDefault(emptyList())
    }

    /** Live listener that surfaces profiles created/edited on other devices. */
    fun listenProfiles(onItem: (Profile) -> Unit): ListenerRegistration? {
        val col = profileCol() ?: return null
        return col.addSnapshotListener { snap, _ ->
            snap?.documents?.forEach { d -> d.data?.toProfile()?.let(onItem) }
        }
    }

    // --- Favourites ---------------------------------------------------------

    suspend fun pushFavorite(profileId: String, item: MediaItem) {
        val col = favCol() ?: return
        runCatching {
            col.document(favKey(profileId, item.mediaType, item.id))
                .set(item.toMap() + ("profileId" to profileId))
                .await()
        }
    }

    suspend fun removeFavorite(profileId: String, id: Int, type: MediaType) {
        val col = favCol() ?: return
        runCatching { col.document(favKey(profileId, type, id)).delete().await() }
    }

    /** Pulls (profileId, item) pairs so each favourite re-attaches to its origin profile. */
    suspend fun pullFavorites(): List<Pair<String, MediaItem>> {
        val col = favCol() ?: return emptyList()
        return runCatching {
            col.get().await().documents.mapNotNull { d ->
                val data = d.data ?: return@mapNotNull null
                val pid = data["profileId"] as? String ?: return@mapNotNull null
                val media = data.toMediaItem() ?: return@mapNotNull null
                pid to media
            }
        }.getOrDefault(emptyList())
    }

    /** Live listener: favourites added on other devices, with their origin profileId (additive). */
    fun listenFavorites(onItem: (String, MediaItem) -> Unit): ListenerRegistration? {
        val col = favCol() ?: return null
        return col.addSnapshotListener { snap, _ ->
            snap?.documents?.forEach { d ->
                val data = d.data ?: return@forEach
                val pid = data["profileId"] as? String ?: return@forEach
                data.toMediaItem()?.let { onItem(pid, it) }
            }
        }
    }

    // --- History ------------------------------------------------------------

    suspend fun pushHistory(profileId: String, item: MediaItem, progress: PlaybackProgress) {
        val col = histCol() ?: return
        runCatching {
            col.document(histKey(profileId, progress))
                .set(item.toMap() + progress.toMap() + ("profileId" to profileId))
                .await()
        }
    }

    /** Pulls (profileId, item, progress) triples so each row re-attaches to its origin profile. */
    suspend fun pullHistory(): List<Triple<String, MediaItem, PlaybackProgress>> {
        val col = histCol() ?: return emptyList()
        return runCatching {
            col.get().await().documents.mapNotNull { d ->
                val data = d.data ?: return@mapNotNull null
                val pid = data["profileId"] as? String ?: return@mapNotNull null
                val media = data.toMediaItem() ?: return@mapNotNull null
                val progress = data.toPlaybackProgress() ?: return@mapNotNull null
                Triple(pid, media, progress)
            }
        }.getOrDefault(emptyList())
    }

    // --- helpers ------------------------------------------------------------

    private fun profileCol() = uid()?.let { db?.collection("users")?.document(it)?.collection("profiles") }
    private fun favCol() = uid()?.let { db?.collection("users")?.document(it)?.collection("favorites") }
    private fun histCol() = uid()?.let { db?.collection("users")?.document(it)?.collection("history") }

    private fun favKey(profileId: String, type: MediaType, id: Int) = "${profileId}__${type.name}_$id"
    private fun histKey(profileId: String, p: PlaybackProgress) =
        "${profileId}__${p.mediaType.name}_${p.mediaId}_${p.season ?: -1}_${p.episode ?: -1}"

    private fun Profile.toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "isKids" to isKids,
        "colorIndex" to colorIndex,
        "createdAt" to createdAt,
    )

    private fun Map<String, Any?>.toProfile(): Profile? {
        val id = this["id"] as? String ?: return null
        return Profile(
            id = id,
            name = this["name"] as? String ?: "",
            isKids = this["isKids"] as? Boolean ?: false,
            colorIndex = (this["colorIndex"] as? Number)?.toInt() ?: 0,
            createdAt = (this["createdAt"] as? Number)?.toLong() ?: 0L,
        )
    }

    private fun MediaItem.toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "mediaType" to mediaType.name,
        "title" to title,
        "overview" to overview,
        "posterUrl" to posterUrl,
        "backdropUrl" to backdropUrl,
        "voteAverage" to voteAverage,
        "releaseDate" to releaseDate,
        "genreIds" to genreIds,
        "imdbId" to imdbId,
        "addedAt" to System.currentTimeMillis(),
    )

    private fun Map<String, Any?>.toMediaItem(): MediaItem? {
        val id = (this["id"] as? Number)?.toInt() ?: return null
        val type = (this["mediaType"] as? String)?.let { runCatching { MediaType.valueOf(it) }.getOrNull() } ?: return null
        return MediaItem(
            id = id,
            mediaType = type,
            title = this["title"] as? String ?: "",
            overview = this["overview"] as? String ?: "",
            posterUrl = this["posterUrl"] as? String,
            backdropUrl = this["backdropUrl"] as? String,
            voteAverage = (this["voteAverage"] as? Number)?.toDouble() ?: 0.0,
            releaseDate = this["releaseDate"] as? String,
            genreIds = (this["genreIds"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList(),
            imdbId = this["imdbId"] as? String,
        )
    }

    // Progress fields use distinct keys ("mt"/"mediaId") so they don't collide with the media map.
    private fun PlaybackProgress.toMap(): Map<String, Any?> = mapOf(
        "mediaId" to mediaId,
        "mt" to mediaType.name,
        "season" to season,
        "episode" to episode,
        "positionMs" to positionMs,
        "durationMs" to durationMs,
        "updatedAt" to updatedAt,
        "infoHash" to infoHash,
    )

    private fun Map<String, Any?>.toPlaybackProgress(): PlaybackProgress? {
        val mediaId = (this["mediaId"] as? Number)?.toInt() ?: return null
        val type = (this["mt"] as? String)?.let { runCatching { MediaType.valueOf(it) }.getOrNull() } ?: return null
        return PlaybackProgress(
            mediaId = mediaId,
            mediaType = type,
            season = (this["season"] as? Number)?.toInt(),
            episode = (this["episode"] as? Number)?.toInt(),
            positionMs = (this["positionMs"] as? Number)?.toLong() ?: 0L,
            durationMs = (this["durationMs"] as? Number)?.toLong() ?: 0L,
            updatedAt = (this["updatedAt"] as? Number)?.toLong() ?: 0L,
            infoHash = this["infoHash"] as? String,
        )
    }

    private companion object {
        const val TAG = "FirebaseSync"
        const val PAIRING = "pairing"
    }
}
