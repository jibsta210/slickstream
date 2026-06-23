package com.slickstream.feature.auth

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slickstream.core.model.DataResult
import com.slickstream.core.model.UserProfile
import com.slickstream.core.repository.AuthRepository
import com.slickstream.data.sync.FirebaseSync
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Phone side of "phone-confirms-TV" pairing: the signed-in phone takes the code shown on the TV,
 * fetches a fresh Google id-token for the current account, and writes it into the TV's Firestore
 * mailbox so the TV can sign itself in.
 */
@HiltViewModel
class LinkTvViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val firebaseSync: FirebaseSync,
) : ViewModel() {

    sealed interface State {
        data object Idle : State
        data object Working : State
        data object Success : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun submit(activity: Activity, code: String) {
        if (code.trim().length != 6) {
            _state.value = State.Error("Enter the 6-digit code shown on your TV.")
            return
        }
        _state.value = State.Working
        viewModelScope.launch {
            if (!firebaseSync.isAvailable) {
                _state.value = State.Error("Cloud pairing isn't set up yet (Firebase).")
                return@launch
            }
            when (val r = authRepository.acquireIdToken(activity)) {
                is DataResult.Success -> {
                    val profile: UserProfile? = authRepository.currentUser.value
                    val ok = firebaseSync.claimPairing(
                        code.trim(), r.data, profile?.displayName, profile?.email, profile?.photoUrl,
                    )
                    _state.value = if (ok) State.Success
                    else State.Error("Couldn't reach the pairing service. Check Firestore is enabled.")
                }
                is DataResult.Error -> _state.value = State.Error(r.message)
            }
        }
    }

    fun reset() { _state.value = State.Idle }
}
