package com.slickstream.feature.update

import kotlinx.serialization.Serializable

/**
 * Remote version descriptor the app fetches on launch. Hosted as a static JSON asset
 * (GitHub Releases recommended). [versionCode] is compared to BuildConfig.VERSION_CODE.
 */
@Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String = "",
    val apkUrl: String,
    val notes: String = "",
    val mandatory: Boolean = false,
    /** Optional lowercase hex sha256 of the APK for integrity verification. */
    val sha256: String? = null,
)

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class Available(val manifest: UpdateManifest) : UpdateUiState
    data class Downloading(val percent: Int) : UpdateUiState
    data class ReadyToInstall(val manifest: UpdateManifest) : UpdateUiState
    data class Error(val message: String) : UpdateUiState
    data object Dismissed : UpdateUiState

    /** Result of an explicit Settings "Check for updates" when already on the latest build. */
    data object UpToDate : UpdateUiState
}
