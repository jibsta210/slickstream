package com.slickstream.feature.player

import android.graphics.Rect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf

/** Bridge that lets the player screen drive Picture-in-Picture on the host Activity. */
interface PipController {
    val isSupported: Boolean

    /** Live Compose state: true while the host Activity is in Picture-in-Picture. */
    val isInPip: State<Boolean>

    /** Report whether the player wants (auto-)PiP, the video aspect, and its on-screen bounds. */
    fun update(enabled: Boolean, aspect: Float, sourceHint: Rect?)

    /** Enter PiP now (e.g. from a button). No-op when unsupported or disabled. */
    fun enter()
}

val LocalPipController = staticCompositionLocalOf<PipController?> { null }

/** True while the host Activity is in Picture-in-Picture mode (recomposes on change). */
@Composable
fun rememberIsInPipMode(): Boolean = LocalPipController.current?.isInPip?.value ?: false
