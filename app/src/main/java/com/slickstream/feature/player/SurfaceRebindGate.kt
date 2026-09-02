package com.slickstream.feature.player

/**
 * Decides WHEN the player's video output has to be forcibly re-bound to its SurfaceView.
 *
 * Pure state machine with no Android types: the caller feeds it the identity of the live
 * `android.view.Surface` (as an opaque token) plus the two things that can invalidate it — a real
 * background/foreground round trip, and a surface destroy.
 *
 * WHY THIS EXISTS — the "pause, let the Google TV screensaver start, come back to BLACK video with
 * working AUDIO" report. Audio needs no surface; video does. Three facts, all verified against the
 * artifacts actually on the classpath (media3 1.5.1, libVLC 3.6.5), combine into a dead end:
 *
 *  1. media3's `PlayerView` constructor does, for the default `surface_type=surface_view`,
 *     `if (SDK_INT >= 34) PlayerView$Api34.setSurfaceLifecycleToFollowsAttachment(surfaceView)` —
 *     which is literally `surfaceView.setSurfaceLifecycle(SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)`.
 *     That ties the surface to view ATTACHMENT instead of VISIBILITY. An Android-TV screensaver is a
 *     Dream: it STOPS the activity but never DETACHES the view, so on Android 14+ boxes
 *     `surfaceDestroyed`/`surfaceCreated` NEVER FIRE.
 *  2. Every self-healing path in the stack hangs off exactly those callbacks — `ExoPlayerImpl`'s
 *     `ComponentListener` (surfaceCreated -> setVideoOutputInternal) and, on the fallback backend,
 *     `VlcPlayer.surfaceCallback`. Both are therefore bypassed, and the decoder keeps writing into a
 *     surface nobody revalidated.
 *  3. Nothing in the app could repair it afterwards either: `PlayerView.setPlayer` opens with
 *     `if (this.player == player) return`, so re-running the `AndroidView` update block (which is all
 *     the player screens ever did) is a proven no-op — it never reaches `setVideoSurfaceView`.
 *
 * The gate is deliberately NOT keyed to the screensaver. It fires on ANY return to the foreground
 * after a real stop (screensaver, Home button, another app, a full-screen dialog activity) and,
 * independently, on ANY new `Surface` behind the SAME `SurfaceView` — the case a View-identity check
 * structurally cannot see, and the one that produced the earlier mid-stream-failover black screen.
 *
 * It is equally careful NOT to fire when the platform already did the work for us: on API < 34 the
 * destroy/create cycle still runs, [markBound] records the recreated surface, and the pending
 * background flag is cleared — so a resume there costs nothing.
 */
class SurfaceRebindGate {

    /** True once a real surface has ever been bound — before that, the initial bind is PlayerView's job. */
    private var everBound = false

    /** Identity of the Surface the video output was last known to be bound to. */
    private var boundToken: Any? = null

    /** The activity was STOPPED since the last bind, so the bound surface can no longer be trusted. */
    private var backgrounded = false

    /**
     * Record that [token] (a live `android.view.Surface`) is the video output right now — either
     * because the platform's own surfaceCreated/surfaceChanged path bound it, or because we just
     * forced the re-bind ourselves. Clears the pending-background flag: a surface that was rebound
     * after the stop needs no further help.
     *
     * A null token is treated as a loss, not a bind ([onSurfaceLost]).
     */
    fun markBound(token: Any?) {
        if (token == null) {
            onSurfaceLost()
            return
        }
        everBound = true
        boundToken = token
        backgrounded = false
    }

    /**
     * `surfaceChanged`: the surface was RESIZED (or re-reported), which is NOT proof the video output
     * was rebound. Verified in media3 1.5.1 bytecode: ExoPlayerImpl$ComponentListener.surfaceChanged
     * compiles to a single maybeNotifySurfaceSizeChanged() and never touches setVideoOutputInternal;
     * only surfaceCreated does. Treating it as a bind let the gate DISARM ITSELF on the way back from
     * the screensaver — the fix would then silently do nothing on exactly the Android 14+ hardware it
     * exists for. So: track the token, never clear the pending-background flag.
     */
    fun markResized(token: Any?) {
        if (token == null) return
        boundToken = token
    }

    /** `surfaceDestroyed`: whatever was bound is gone. Keeps [everBound] — we HAVE played before. */
    fun onSurfaceLost() {
        boundToken = null
    }

    /**
     * `ON_STOP`: the window went away. Only meaningful once something has actually been bound, so a
     * stop before playback ever started can't provoke a pointless surface swap on the way back in.
     */
    fun onBackgrounded() {
        if (everBound) backgrounded = true
    }

    /**
     * @param token the Surface that is live right now, or null if there isn't one.
     * @return true when the video output must be force-rebound to [token].
     *
     * Null token: nothing to bind to — the holder callback will drive the bind when the surface
     * arrives (this is the normal API < 34 shape at ON_START, where the surface really was destroyed).
     * Never bound: the first bind belongs to PlayerView; forcing one here would swap the codec's
     * output surface during start-up for no reason.
     */
    fun needsRebind(token: Any?, sdkAtLeast34: Boolean = true): Boolean {
        if (token == null || !everBound) return false
        // SAME VIEW, NEW SURFACE. A View-identity check reports "already attached" here and skips the
        // re-attach, which is exactly how audio ends up playing over a black picture.
        if (boundToken !== token) return true
        // Same Surface object, but it survived a stop with no destroy/create cycle to revalidate it —
        // the Android 14+ FOLLOWS_ATTACHMENT case, and ONLY that case. Below 34 the surface really is
        // visibility-scoped, the destroy/create pair really fires, and both backends re-attach on their
        // own; forcing a round trip there would buy nothing and cost a blocking renderer round trip on
        // every return from PiP or the Home button.
        return backgrounded && sdkAtLeast34
    }
}
