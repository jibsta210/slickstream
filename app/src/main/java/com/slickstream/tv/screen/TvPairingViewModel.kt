package com.slickstream.tv.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slickstream.core.common.Auth
import com.slickstream.core.model.DataResult
import com.slickstream.core.repository.AuthRepository
import com.slickstream.feature.auth.DeviceAuthClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface PairingUiState {
    data object Idle : PairingUiState
    data object Starting : PairingUiState
    data class AwaitingApproval(val userCode: String, val verificationUrl: String) : PairingUiState
    data object Success : PairingUiState
    data class Error(val message: String) : PairingUiState
}

@HiltViewModel
class TvPairingViewModel @Inject constructor(
    private val deviceAuth: DeviceAuthClient,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<PairingUiState>(PairingUiState.Idle)
    val state: StateFlow<PairingUiState> = _state.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (Auth.GOOGLE_TV_CLIENT_ID.isBlank() || Auth.GOOGLE_TV_CLIENT_SECRET.isBlank()) {
            _state.value = PairingUiState.Error(
                "TV sign-in isn't configured yet. Add the TV OAuth client to enable it.",
            )
            return
        }
        if (job?.isActive == true) return
        _state.value = PairingUiState.Starting
        job = viewModelScope.launch {
            try {
                val code = withContext(Dispatchers.IO) { deviceAuth.requestCode(Auth.GOOGLE_TV_CLIENT_ID) }
                _state.value = PairingUiState.AwaitingApproval(code.userCode, code.verificationUrl)
                pollUntilDone(code)
            } catch (t: Throwable) {
                _state.value = PairingUiState.Error("Couldn't start pairing. Check your connection and try again.")
            }
        }
    }

    private suspend fun pollUntilDone(code: DeviceAuthClient.DeviceCode) {
        var intervalMs = code.intervalSec.coerceAtLeast(5) * 1000L
        val deadline = System.currentTimeMillis() + code.expiresInSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(intervalMs)
            val result = withContext(Dispatchers.IO) {
                deviceAuth.poll(Auth.GOOGLE_TV_CLIENT_ID, Auth.GOOGLE_TV_CLIENT_SECRET, code.deviceCode)
            }
            when (result) {
                is DeviceAuthClient.PollResult.Pending -> Unit
                is DeviceAuthClient.PollResult.SlowDown -> intervalMs += 5_000L
                is DeviceAuthClient.PollResult.Denied -> {
                    _state.value = PairingUiState.Error("Pairing was denied on your phone.")
                    return
                }
                is DeviceAuthClient.PollResult.Expired -> {
                    _state.value = PairingUiState.Error("This code expired. Please try again.")
                    return
                }
                is DeviceAuthClient.PollResult.Error -> {
                    _state.value = PairingUiState.Error("Sign-in failed. Please try again.")
                    return
                }
                is DeviceAuthClient.PollResult.Success -> {
                    when (val r = authRepository.signInWithIdToken(result.idToken)) {
                        is DataResult.Success -> _state.value = PairingUiState.Success
                        is DataResult.Error -> _state.value = PairingUiState.Error(r.message)
                    }
                    return
                }
            }
        }
        _state.value = PairingUiState.Error("This code expired. Please try again.")
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = PairingUiState.Idle
    }

    override fun onCleared() {
        job?.cancel()
        super.onCleared()
    }
}
