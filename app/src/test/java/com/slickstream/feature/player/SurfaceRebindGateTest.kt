package com.slickstream.feature.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the rules behind the "pause, screensaver, come back to BLACK video with working audio" fix.
 *
 * Each case here is a way the shipped player either stayed black forever or would have paid a
 * pointless codec surface swap. Surfaces are modelled as distinct object identities, because that is
 * exactly what the real bug turns on: `android.view.Surface` identity, NOT `SurfaceView` identity.
 */
class SurfaceRebindGateTest {

    /** Stand-in for an `android.view.Surface` — only its identity matters. */
    private class Surf(val name: String) { override fun toString() = name }

    // --- The reported bug ---------------------------------------------------------------------

    @Test
    fun `android 14 screensaver - stopped and resumed with no destroy-create cycle - rebinds`() {
        val gate = SurfaceRebindGate()
        val surface = Surf("s1")
        gate.markBound(surface)

        // The Dream STOPS the activity but never DETACHES the view, so with
        // SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT no surfaceDestroyed/surfaceCreated arrives at all.
        gate.onBackgrounded()

        // Same Surface object comes back. Nothing in ExoPlayer or libVLC revalidated it, and
        // PlayerView.setPlayer early-returns on player identity, so this is the ONLY chance to fix it.
        assertTrue(gate.needsRebind(surface))
    }

    @Test
    fun `same view, brand new surface - rebinds even without any lifecycle round trip`() {
        val gate = SurfaceRebindGate()
        gate.markBound(Surf("old"))

        // A View-identity check reports "already attached" here. That is how the mid-stream-failover
        // black screen happened, and it is reachable without any stop at all (PiP, window recreate).
        assertTrue(gate.needsRebind(Surf("new")))
    }

    @Test
    fun `rebinding once is enough - the resume does not loop`() {
        val gate = SurfaceRebindGate()
        val surface = Surf("s1")
        gate.markBound(surface)
        gate.onBackgrounded()

        assertTrue(gate.needsRebind(surface))
        gate.markBound(surface)                 // we just did the clear/set round trip
        // ON_RESUME follows ON_START; the second look must be free.
        assertFalse(gate.needsRebind(surface))
    }

    // --- Cases that must NOT pay a surface swap ------------------------------------------------

    @Test
    fun `api below 34 - the platform already rebound, so the resume costs nothing`() {
        val gate = SurfaceRebindGate()
        val first = Surf("s1")
        gate.markBound(first)

        // Here the SurfaceView is still visibility-scoped: the real destroy/create cycle runs and
        // ExoPlayer's own ComponentListener (or VlcPlayer.surfaceCallback) re-attaches for us.
        gate.onBackgrounded()
        gate.onSurfaceLost()
        val recreated = Surf("s2")
        gate.markBound(recreated)

        assertFalse(gate.needsRebind(recreated))
    }

    @Test
    fun `first ever bind belongs to PlayerView, not to us`() {
        val gate = SurfaceRebindGate()
        // Nothing bound yet: forcing a clear/set here would swap the codec's output surface during
        // start-up for no reason, and PlayerView's own factory bind is about to happen anyway.
        assertFalse(gate.needsRebind(Surf("s1")))
    }

    @Test
    fun `a stop before anything ever played cannot provoke a rebind`() {
        val gate = SurfaceRebindGate()
        gate.onBackgrounded()
        assertFalse(gate.needsRebind(Surf("s1")))
    }

    @Test
    fun `no live surface - nothing to rebind to`() {
        val gate = SurfaceRebindGate()
        gate.markBound(Surf("s1"))
        gate.onBackgrounded()
        // The normal API < 34 shape at ON_START: the surface really was destroyed and has not come
        // back yet. The holder callback will drive the bind; acting now would bind to nothing.
        assertFalse(gate.needsRebind(null))
    }

    @Test
    fun `a foreground round trip with no stop in between changes nothing`() {
        val gate = SurfaceRebindGate()
        val surface = Surf("s1")
        gate.markBound(surface)
        // ON_START/ON_RESUME churn from a dialog or a config change: same surface, no stop.
        assertFalse(gate.needsRebind(surface))
    }

    // --- Bookkeeping edges --------------------------------------------------------------------

    @Test
    fun `surface lost then a new one live with nobody rebinding it - still repairs`() {
        val gate = SurfaceRebindGate()
        gate.markBound(Surf("s1"))
        gate.onSurfaceLost()
        // Destroy fired but the automatic re-attach did not land (the exact shape of the earlier
        // failover bug). A live surface with no recorded binding must be treated as needing one.
        assertTrue(gate.needsRebind(Surf("s2")))
    }

    @Test
    fun `markBound with a null token is a loss, never a bind`() {
        val gate = SurfaceRebindGate()
        gate.markBound(null)
        // Must not have latched "we have played before" off a null surface, or the very first real
        // bind would be treated as a repair.
        assertFalse(gate.needsRebind(Surf("s1")))
    }

    @Test
    fun `two stops in a row still rebind exactly once on the way back`() {
        val gate = SurfaceRebindGate()
        val surface = Surf("s1")
        gate.markBound(surface)
        gate.onBackgrounded()
        gate.onBackgrounded()
        assertTrue(gate.needsRebind(surface))
        gate.markBound(surface)
        assertFalse(gate.needsRebind(surface))
    }

    // =============================================================================================
    // The gate must not disarm itself
    // =============================================================================================

    @Test
    fun `a resize does not count as a rebind`() {
        // surfaceChanged is a RESIZE notification, not a rebind: media3's ComponentListener.surfaceChanged
        // only calls maybeNotifySurfaceSizeChanged and never re-issues the video output. Treating it as a
        // bind cleared the pending-background flag, so the whole fix silently no-opped on exactly the
        // Android 14+ hardware it exists for.
        val g = SurfaceRebindGate()
        val surface = Any()
        g.markBound(surface)            // first real bind
        g.onBackgrounded()              // screensaver
        g.markResized(surface)          // a resize arrives on the way back — must NOT disarm
        assertTrue(g.needsRebind(surface, sdkAtLeast34 = true))
    }

    @Test
    fun `a genuine surfaceCreated does disarm the gate`() {
        // On API < 34 the destroy/create pair really fires and both backends re-attach themselves, so a
        // real surfaceCreated means no further help is needed.
        val g = SurfaceRebindGate()
        val old = Any()
        g.markBound(old)
        g.onBackgrounded()
        g.onSurfaceLost()
        val fresh = Any()
        g.markBound(fresh)              // surfaceCreated
        assertFalse(g.needsRebind(fresh, sdkAtLeast34 = true))
    }

    @Test
    fun `below API 34 a survived background does not force a round trip`() {
        // The blocking clear/set costs a renderer round trip. Below 34 the surface is visibility-scoped
        // and really is destroyed/recreated, so paying it on every PiP or Home return buys nothing.
        val g = SurfaceRebindGate()
        val surface = Any()
        g.markBound(surface)
        g.onBackgrounded()
        assertFalse(g.needsRebind(surface, sdkAtLeast34 = false))
        assertTrue(g.needsRebind(surface, sdkAtLeast34 = true))
    }

    @Test
    fun `a new surface behind the same view always rebinds, on any API level`() {
        // Same-view/new-Surface is the failure that yields audio over a black picture. It is not
        // version-specific, so it must not be gated on the SDK level.
        val g = SurfaceRebindGate()
        g.markBound(Any())
        assertTrue(g.needsRebind(Any(), sdkAtLeast34 = false))
    }
}
