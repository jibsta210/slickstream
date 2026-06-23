package com.slickstream.tv.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slickstream.core.model.DataResult
import com.slickstream.core.repository.AuthRepository
import com.slickstream.data.sync.FirebaseSync
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * TV side of "phone-confirms-TV" pairing: the TV shows a 6-digit code (written to a Firestore
 * mailbox), then polls until the signed-in phone fills it with a Google id-token; the TV signs in
 * with that token and deletes the doc. No TV keyboard needed; the master account lives on the phone.
 */
@HiltViewModel
class TvCodePairViewModel @Inject constructor(
    private val firebaseSync: FirebaseSync,
    private val authRepository: AuthRepository,
) : ViewModel() {

    sealed interface State {
        data object Starting : State
        data class Showing(val code: String) : State
        data object Success : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Starting)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private var code: String? = null

    fun start() {
        if (job?.isActive == true) return
        _state.value = State.Starting
        job = viewModelScope.launch {
            if (!firebaseSync.isAvailable) {
                _state.value = State.Error("Cloud pairing isn't set up yet (add google-services.json + enable Firestore).")
                return@launch
            }
            val c = (100000..999999).random().toString()
            code = c
            if (!firebaseSync.createPairing(c)) {
                _state.value = State.Error("Couldn't reach the pairing service. Make sure Cloud Firestore is enabled.")
                return@launch
            }
            _state.value = State.Showing(c)

            val deadline = System.currentTimeMillis() + 5 * 60_000L
            while (System.currentTimeMillis() < deadline) {
                delay(2_500)
                val token = firebaseSync.readPairingToken(c) ?: continue
                when (val r = authRepository.signInWithIdToken(token)) {
                    is DataResult.Success -> {
                        firebaseSync.deletePairing(c)
                        _state.value = State.Success
                        return@launch
                    }
                    is DataResult.Error -> {
                        firebaseSync.deletePairing(c)
                        _state.value = State.Error(r.message)
                        return@launch
                    }
                }
            }
            firebaseSync.deletePairing(c)
            _state.value = State.Error("This code expired. Try again.")
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        code?.let { c -> viewModelScope.launch { firebaseSync.deletePairing(c) } }
    }

    override fun onCleared() {
        cancel()
        super.onCleared()
    }
}
