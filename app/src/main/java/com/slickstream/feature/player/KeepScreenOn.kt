package com.slickstream.feature.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Hold the device awake (no display timeout AND no Android-TV Daydream/screensaver) ONLY while
 * [enabled] — i.e. while a stream is actively playing or loading.
 *
 * Uses the AUTHORITATIVE window flag [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON] on the host
 * Activity, not a view-level `keepScreenOn` — the view-level flag did NOT suppress the screensaver on
 * some TVs (it was set on the Compose root view, which the SurfaceView-backed player can leave the
 * window-aggregator unaware of). The window flag is the documented way to keep the screen on during
 * media playback and prevents the Daydream screensaver. Backend-agnostic (ExoPlayer + libVLC) and
 * always cleared on dispose so leaving the player lets the screen sleep normally.
 */
@Composable
fun KeepScreenOn(enabled: Boolean) {
    val context = LocalContext.current
    val window = remember(context) { context.findActivity()?.window }
    DisposableEffect(window, enabled) {
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (enabled) window?.addFlags(flag) else window?.clearFlags(flag)
        onDispose { window?.clearFlags(flag) }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
