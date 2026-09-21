package com.slickstream.feature.update

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val checker: UpdateChecker,
    private val installer: ApkInstaller,
) : ViewModel() {

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var downloadedApk: File? = null

    /**
     * A dismissal lasts until the app is next BACKGROUNDED — not for the life of the process.
     *
     * The old rule ("forgotten on the next cold start") assumed cold starts happen. On a TV they
     * do not: the app is backgrounded with Home and reopened from the launcher for days, the
     * process never dies, and one press of Later (or an accidental Back, which lands on the same
     * dismiss) silenced the prompt until the user force-stopped the app. Process ON_STOP is the
     * honest "you left the app" signal, so it is where the dismissal is forgotten; the existing
     * ON_START re-check then re-prompts on the way back in.
     */
    private val foregroundObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            checker.clearDismissal()
            if (_state.value is UpdateUiState.Dismissed) _state.value = UpdateUiState.Idle
        }
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundObserver)
        viewModelScope.launch {
            _state.value = UpdateUiState.Checking
            val manifest = checker.check()
            _state.value = if (manifest != null) UpdateUiState.Available(manifest) else UpdateUiState.Idle
        }
    }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(foregroundObserver)
        super.onCleared()
    }

    /**
     * Re-check on every foreground (ON_START), not just fresh start. Skips if a check/download/
     * prompt is already in flight so it never interrupts the user; the checker's dismissed-version
     * gate keeps a previously-dismissed update from popping again.
     */
    fun checkNow() {
        when (_state.value) {
            is UpdateUiState.Checking,
            is UpdateUiState.Downloading,
            is UpdateUiState.Available,
            is UpdateUiState.ReadyToInstall -> return
            else -> Unit
        }
        viewModelScope.launch {
            val manifest = checker.check()
            if (manifest != null) _state.value = UpdateUiState.Available(manifest)
        }
    }

    /** User-initiated check (Settings). Always re-checks and ignores a session dismissal. */
    fun forceCheck() {
        viewModelScope.launch {
            _state.value = UpdateUiState.Checking
            val manifest = checker.check(ignoreDismiss = true)
            _state.value = if (manifest != null) UpdateUiState.Available(manifest) else UpdateUiState.UpToDate
        }
    }

    fun startDownload(manifest: UpdateManifest) {
        viewModelScope.launch {
            _state.value = UpdateUiState.Downloading(0)
            runCatching {
                checker.download(manifest) { pct -> _state.value = UpdateUiState.Downloading(pct) }
            }.onSuccess { file ->
                downloadedApk = file
                _state.value = UpdateUiState.ReadyToInstall(manifest)
                launchInstall()
            }.onFailure {
                _state.value = UpdateUiState.Error(it.message ?: "Download failed")
            }
        }
    }

    /** Launch the installer, routing to permission settings first if needed. */
    fun launchInstall() {
        val apk = downloadedApk ?: return
        if (installer.canInstall()) installer.install(apk) else installer.requestPermission()
    }

    /** Call from onResume to retry install after the user grants unknown-sources. */
    fun onResume() {
        if (_state.value is UpdateUiState.ReadyToInstall && installer.canInstall()) {
            downloadedApk?.let(installer::install)
        }
    }

    fun dismiss(manifest: UpdateManifest) {
        checker.dismiss(manifest.versionCode)
        _state.value = UpdateUiState.Dismissed
    }
}
