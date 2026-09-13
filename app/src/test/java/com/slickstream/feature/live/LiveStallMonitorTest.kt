package com.slickstream.feature.live

import com.slickstream.feature.live.LiveRecoveryPlan.LiveFailureKind
import com.slickstream.feature.live.LiveStallMonitor.State
import com.slickstream.feature.live.LiveStallMonitor.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the DETECTION half of the live retry engine — the part the shipped player was missing
 * entirely, and the reason "some streams freeze every few minutes" produced no spinner, no log and
 * no recovery attempt.
 *
 * The old live path reacted only to `onPlaybackStateChanged`, with
 * `STATE_BUFFERING -> if (uiState !is Playing) uiState = Buffering`. That made `Playing` absorbing.
 * These tests drive the monitor with the shapes a real live feed produces, one 1 s tick at a time.
 */
class LiveStallMonitorTest {

    /** A tiny driver so each test reads as a timeline rather than as six argument lists. */
    private class Feed(val monitor: LiveStallMonitor = LiveStallMonitor()) {
        var nowMs = 100_000L
        var positionMs = 0L
        var bufferedMs = 8_000L

        /** One healthy second: the clock and the buffer both move. */
        fun tickPlaying(): Verdict = tick(advanceMs = 1_000L, bufferAdvanceMs = 1_000L, isPlaying = true, state = State.READY)

        /** One second of a FROZEN PICTURE that still claims to be playing — the reported bug. */
        fun tickFrozen(bufferMoving: Boolean = true): Verdict =
            tick(advanceMs = 0L, bufferAdvanceMs = if (bufferMoving) 1_000L else 0L, isPlaying = true, state = State.READY)

        /** One second of a rebuffer that is going nowhere. */
        fun tickRebuffering(): Verdict =
            tick(advanceMs = 0L, bufferAdvanceMs = 0L, isPlaying = false, state = State.BUFFERING)

        fun tick(
            advanceMs: Long,
            bufferAdvanceMs: Long,
            isPlaying: Boolean,
            state: Int,
            playWhenReady: Boolean = true,
        ): Verdict {
            nowMs += 1_000L
            positionMs += advanceMs
            bufferedMs += bufferAdvanceMs
            return monitor.sample(nowMs, positionMs, bufferedMs, playWhenReady, isPlaying, state)
        }
    }

    // --- The reported bug ------------------------------------------------------------------------

    @Test
    fun `READY and isPlaying but the clock is dead - the freeze the old code could not see`() {
        val f = Feed()
        repeat(5) { f.tickPlaying() }
        // The picture freezes. ExoPlayer reports nothing: HlsChunkSource returns with no chunk and no
        // error when a live playlist stops advancing, and PlaylistStuckException needs 3.5 x
        // targetDuration (~21 s on 6 s segments) before it even exists.
        f.tickFrozen()
        f.tickFrozen()
        val verdict = f.tickFrozen()
        assertTrue("got $verdict", verdict is Verdict.Stalled)
        assertEquals(LiveFailureKind.POSITION_STALL, (verdict as Verdict.Stalled).kind)
    }

    @Test
    fun `the stall is called in about three seconds, not the twenty-one media3 needs`() {
        val f = Feed()
        repeat(3) { f.tickPlaying() }
        val start = f.nowMs
        var stalledAt = 0L
        repeat(10) {
            if (stalledAt == 0L && f.tickFrozen() is Verdict.Stalled) stalledAt = f.nowMs
        }
        assertTrue("never stalled", stalledAt != 0L)
        assertTrue("took ${stalledAt - start} ms", stalledAt - start <= 4_000L)
    }

    @Test
    fun `a single late segment is NOT a stall - a two-tick hiccup must not trip the ladder`() {
        val f = Feed()
        repeat(5) { f.tickPlaying() }
        assertTrue(f.tickFrozen() !is Verdict.Stalled)
        assertTrue(f.tickFrozen() !is Verdict.Stalled)
        // ...and it recovers on its own, as most of them do.
        assertTrue(f.tickPlaying() is Verdict.Healthy)
    }

    @Test
    fun `a wedged rebuffer is a stall too - STATE_BUFFERING that never ends`() {
        val f = Feed()
        repeat(5) { f.tickPlaying() }
        repeat(2) { f.tickRebuffering() }
        val verdict = f.tickRebuffering()
        assertTrue("got $verdict", verdict is Verdict.Stalled)
        assertEquals(LiveFailureKind.BUFFER_STALL, (verdict as Verdict.Stalled).kind)
    }

    // --- The signal that picks the ladder rung ---------------------------------------------------

    @Test
    fun `a flat buffer is reported, because that is what rules the cheap seek out`() {
        val f = Feed()
        repeat(3) { f.tickPlaying() }
        repeat(4) { f.tickFrozen(bufferMoving = false) }
        val verdict = f.tickFrozen(bufferMoving = false) as Verdict.Stalled
        assertTrue(!verdict.bufferAdvancing)
    }

    @Test
    fun `a still-filling buffer behind a dead clock keeps the cheap seek on the table`() {
        val f = Feed()
        repeat(3) { f.tickPlaying() }
        repeat(4) { f.tickFrozen(bufferMoving = true) }
        val verdict = f.tickFrozen(bufferMoving = true) as Verdict.Stalled
        assertTrue(verdict.bufferAdvancing)
    }

    // --- States the old `else -> Unit` swallowed -------------------------------------------------

    @Test
    fun `STATE_ENDED on a live feed means the feed hung up, not that the show is over`() {
        val f = Feed()
        repeat(3) { f.tickPlaying() }
        val verdict = f.tick(advanceMs = 0L, bufferAdvanceMs = 0L, isPlaying = false, state = State.ENDED)
        assertEquals(LiveFailureKind.STREAM_ENDED, (verdict as Verdict.Stalled).kind)
    }

    @Test
    fun `STATE_IDLE while we still want to play is a stall, and is reported immediately`() {
        val f = Feed()
        repeat(3) { f.tickPlaying() }
        val verdict = f.tick(advanceMs = 0L, bufferAdvanceMs = 0L, isPlaying = false, state = State.IDLE)
        assertEquals(LiveFailureKind.PLAYER_IDLE, (verdict as Verdict.Stalled).kind)
    }

    // --- Not fighting the user or ourselves ------------------------------------------------------

    @Test
    fun `a deliberate pause is never treated as a freeze`() {
        val f = Feed()
        repeat(3) { f.tickPlaying() }
        repeat(10) {
            val v = f.tick(advanceMs = 0L, bufferAdvanceMs = 0L, isPlaying = false, state = State.READY, playWhenReady = false)
            assertTrue("got $v", v !is Verdict.Stalled)
        }
    }

    @Test
    fun `a repair in flight suppresses the watchdog until it has had a chance to land`() {
        val f = Feed()
        repeat(3) { f.tickPlaying() }
        repeat(4) { f.tickFrozen() }
        // The ladder just issued a seek. The picture is still frozen while the chunk is fetched, and
        // without this the 1 s watchdog would stack rung after rung and reach a teardown for a stall
        // that was already being fixed.
        f.monitor.onRecoveryIssued(f.nowMs, settleMs = 4_000L)
        repeat(3) {
            assertTrue(f.tickFrozen() !is Verdict.Stalled)
        }
    }

    @Test
    fun `a re-prepare jumps the position - the baseline is dropped, not compared across the jump`() {
        val f = Feed()
        repeat(3) { f.tickPlaying() }
        // A re-prepare restarts at the live edge, which can move `currentPosition` backwards by a lot.
        f.monitor.onRecoveryIssued(f.nowMs, settleMs = 0L)
        f.positionMs = 0L
        f.bufferedMs = 0L
        // First sample after the reset is a baseline, not evidence — and a backwards jump must not be
        // mistaken for a dead clock.
        assertTrue(f.tickPlaying() is Verdict.Settling)
        repeat(4) { f.tickPlaying() }
        assertTrue(f.tickPlaying() is Verdict.Healthy)
    }

    // --- The budget clock ------------------------------------------------------------------------

    @Test
    fun `clean-run length is measured and grows, so the budget can reset on it`() {
        val f = Feed()
        f.tickPlaying()
        var last = 0L
        repeat(10) {
            val v = f.tickPlaying()
            if (v is Verdict.Healthy) {
                assertTrue(v.playingForMs >= last)
                last = v.playingForMs
            }
        }
        assertTrue("clean run never accumulated", last >= 5_000L)
    }

    @Test
    fun `a stall restarts the clean-run clock - a minute of health has to be UNBROKEN`() {
        val f = Feed()
        repeat(30) { f.tickPlaying() }
        val before = (f.tickPlaying() as Verdict.Healthy).playingForMs
        assertTrue(before >= 25_000L)
        repeat(4) { f.tickFrozen() }
        f.tickPlaying()
        val after = f.tickPlaying()
        assertTrue("got $after", after is Verdict.Healthy)
        assertTrue((after as Verdict.Healthy).playingForMs < before)
    }
}
