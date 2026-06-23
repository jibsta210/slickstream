package com.slickstream.feature.auth

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slickstream.core.model.DataResult
import com.slickstream.core.model.UserProfile
import com.slickstream.core.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Transient sign-in interaction state (not the persisted user, which lives in the repo). */
private data class AuthInteraction(
    val isSigningIn: Boolean = false,
    val errorMessage: String? = null,
)

/** Immutable snapshot driving [LoginScreen] and any profile/account UI. */
data class AuthUiState(
    val user: UserProfile? = null,
    val isSigningIn: Boolean = false,
    val errorMessage: String? = null,
) {
    val isSignedIn: Boolean get() = user != null
}

/**
 * Drives Google sign-in. [user] mirrors the repository's persisted session (seeded from
 * DataStore), so every screen collecting [uiState] reflects sign-in/out across the whole app.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val interaction = MutableStateFlow(AuthInteraction())

    /** The signed-in user as kept by the repository (seeded from DataStore on init). */
    val user: StateFlow<UserProfile?> = authRepository.currentUser

    val uiState: StateFlow<AuthUiState> =
        combine(authRepository.currentUser, interaction) { user, ui ->
            AuthUiState(
                user = user,
                isSigningIn = ui.isSigningIn,
                errorMessage = ui.errorMessage,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AuthUiState(user = authRepository.currentUser.value),
        )

    init {
        // Re-hydrate the persisted session silently (updates currentUser -> uiState).
        viewModelScope.launch { authRepository.restoreSession() }
    }

    /**
     * Launch the Google credential flow. [activity] is required by Credential Manager to host
     * its system UI — screens pass `LocalContext.current as Activity`.
     */
    fun signIn(activity: Activity) {
        if (interaction.value.isSigningIn) return
        viewModelScope.launch {
            interaction.value = AuthInteraction(isSigningIn = true, errorMessage = null)
            when (val result = authRepository.signIn(activity)) {
                is DataResult.Success ->
                    interaction.value = AuthInteraction(isSigningIn = false, errorMessage = null)

                is DataResult.Error ->
                    interaction.value =
                        AuthInteraction(isSigningIn = false, errorMessage = result.message)
            }
        }
    }

    fun signOut() {
        viewModelScope.launch { authRepository.signOut() }
    }

    /** Dismiss a surfaced error (e.g. after the user reads it). */
    fun clearError() {
        interaction.value = interaction.value.copy(errorMessage = null)
    }
}
