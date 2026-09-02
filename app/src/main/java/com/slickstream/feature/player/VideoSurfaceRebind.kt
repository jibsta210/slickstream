package com.slickstream.feature.player

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.core.view.doOnPreDraw
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

/**
 * Repairs the player's VIDEO OUTPUT when the app comes back to the foreground.
 *
 * THE BUG THIS PREVENTS: pause a movie, let the Android-TV screensaver start, come back and press
 * play — audio plays, the picture is BLACK, forever. Audio needs no surface; video does. On Android
 * 14+ media3's `PlayerView` puts its SurfaceView in `SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT`, and a
 * screensaver (a Dream) STOPS the activity without DETACHING the view — so `surfaceDestroyed` and
 * `surfaceCreated` never fire, and every automatic re-attach in the stack (ExoPlayer's own
 * `ComponentListener`, and [com.slickstream.data.vlc.VlcPlayer]'s `surfaceCallback`) is bypassed.
 * Re-running the `AndroidView` update block cannot save it either: `PlayerView.setPlayer` opens with
 * `if (this.player == player) return`, so it never reaches `setVideoSurfaceView`.
 *
 * THE FIX: on returning to the foreground, put the video output through a null round trip —
 * `clearVideoSurfaceView` then `setVideoSurfaceView`. The round trip is REQUIRED rather than a plain
 * re-set, because `MediaCodecVideoRenderer.setOutput` early-returns when the Surface is identical;
 * going through null is what makes it re-issue `MediaCodec.setOutputSurface()` against the live
 * surface. It is written against the [Player] interface, so it repairs the libVLC fallback too:
 * `VlcPlayer` advertises `COMMAND_SET_VIDEO_SURFACE` and routes the same two calls to
 * `detachSurface()` / `attachSurface()` -> a real vout rebuild.
 *
 * WHAT IT DELIBERATELY DOES NOT DO: touch position, media items, `prepare()`, or the torrent. Only
 * the surface binding changes, so playback resumes exactly where it was with no visible re-buffer.
 * It also does not pause on background — SlickStream is a movie player and must hold its session —
 * and it does not hold the screen on while paused (see [KeepScreenOn]): the screensaver starting on a
 * paused movie is CORRECT; only the black picture afterwards was the bug.
 *
 * The decision of whether a rebind is actually due lives in [SurfaceRebindGate] (unit-tested), which
 * also covers the general case this was NOT narrowly keyed to: any same-view/new-Surface swap —
 * Home button, PiP exit, another app taking the decoder.
 */
@Composable
@UnstableApi
fun RebindVideoSurfaceOnResume(player: Player?, playerView: PlayerView?) {
    val gate = remember { SurfaceRebindGate() }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, player, playerView) {
        val surfaceView = playerView?.videoSurfaceView as? SurfaceView

        // Watch the surface ourselves so we can tell the two worlds apart. On API < 34 the SurfaceView
        // is still visibility-scoped, the destroy/create cycle really fires, and ExoPlayer/libVLC
        // re-attach on their own — recording the recreated surface here clears the pending-background
        // flag so the resume costs nothing. On API 34+ none of these fire, which is the whole problem,
        // and the gate falls through to the lifecycle branch below.
        val holderCallback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = gate.markBound(holder.surface)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) =
                gate.markResized(holder.surface)
            override fun surfaceDestroyed(holder: SurfaceHolder) = gate.onSurfaceLost()
        }
        surfaceView?.holder?.addCallback(holderCallback)
        // Seed the gate if the surface is already live at bind time (the common Compose ordering:
        // PlayerView is handed to us after it has been laid out and its surface created).
        surfaceView?.holder?.surface?.takeIf { it.isValid }?.let(gate::markBound)

        // One attempt. Safe to call repeatedly: the gate returns false once the rebind has happened,
        // and false when there is nothing to rebind to yet.
        fun attemptRebind() {
            val p = player ?: return
            val sv = surfaceView ?: return
            val live = sv.holder?.surface?.takeIf { it.isValid } ?: return
            if (!p.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) return
            if (!gate.needsRebind(live, sdkAtLeast34 = android.os.Build.VERSION.SDK_INT >= 34)) return
            // Null round trip. Surface-only: no seek, no prepare(), no media-item change, so the
            // position, the buffer and the torrent are all untouched.
            runCatching {
                p.clearVideoSurfaceView(sv)
                p.setVideoSurfaceView(sv)
            }
            // Re-read the holder: the clear/set may have handed us a different Surface object.
            gate.markBound(sv.holder?.surface?.takeIf { it.isValid })
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> gate.onBackgrounded()
                // DELIBERATELY NOT ON_START. media3 grants exactly ONE free frame per surface change:
                // setOutput -> lowerFirstFrameState(FIRST_FRAME_NOT_RENDERED) makes shouldForceRelease()
                // return true for the very next frame, then latches FIRST_FRAME_RENDERED — there is no
                // second one. ON_START runs before the window is visible, so rebinding there SPENT that
                // free frame into a window nobody could see, and the picture then had to wait for
                // ordinary frame timing: the reported "black for about 3 seconds". Waiting for the first
                // PRE-DRAW after ON_RESUME puts the forced frame on screen instead. doOnPreDraw is the
                // real beat: View.post() runs on the next handler message, which normally lands BEFORE
                // the traversal, so it is not late enough to help.
                Lifecycle.Event.ON_RESUME -> {
                    val v = playerView ?: surfaceView
                    if (v != null) v.doOnPreDraw { attemptRebind() } else attemptRebind()
                    // Belt and braces: if that view never draws (it was detached before the pre-draw),
                    // the gate stays armed and this post picks it up. A no-op whenever the pre-draw
                    // already succeeded.
                    surfaceView?.post { attemptRebind() }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            surfaceView?.holder?.removeCallback(holderCallback)
        }
    }
}
