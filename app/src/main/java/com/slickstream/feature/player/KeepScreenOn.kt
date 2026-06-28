package com.slickstream.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Hold the device awake (no screensaver / display timeout) ONLY while [enabled] — i.e. while a stream
 * is actively playing or loading. Drives `keepScreenOn` on the Compose root view, which maps to the
 * window's FLAG_KEEP_SCREEN_ON for as long as the view is attached, so it's backend-agnostic (works for
 * both ExoPlayer and the libVLC fallback, on phone and TV) and is automatically released when [enabled]
 * goes false or the player screen leaves composition (onDispose resets it).
 *
 * Some Android TVs/phones let the screensaver kick in mid-playback because nothing requested the wake
 * lock; this is that request, scoped to playback so a paused/idle player still lets the screen sleep.
 */
@Composable
fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}
