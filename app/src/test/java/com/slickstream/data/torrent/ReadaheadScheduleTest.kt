package com.slickstream.data.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The read-ahead window's schedule, checked against the piece sizes real releases actually use.
 *
 * What these defend: the ladder must describe a fill rate a public swarm can meet (an impossible
 * deadline is what makes libtorrent duplicate requests across peers and discard the losers), and a
 * one-piece advance must not re-issue the whole ladder.
 */
class ReadaheadScheduleTest {

    private val kib = 1024
    private val mib = 1024 * 1024

    @Test
    fun `step declares roughly 4 MB per second at every real piece size`() {
        // The old flat 250 ms step demanded pieceLength / 0.25 s: 16.8 MB/s at 4 MiB pieces (above the
        // session's own 12 MiB/s cap) and 134 MB/s at 32 MiB. A good public-swarm peer does 0.2-1 MB/s.
        for (piece in listOf(1 * mib, 2 * mib, 4 * mib, 8 * mib, 16 * mib)) {
            val declared = piece.toDouble() / (ReadaheadSchedule.deadlineStepMs(piece) / 1000.0)
            assertTrue(
                "piece=$piece declares ${declared / mib} MB/s",
                declared <= 4.5 * mib,
            )
        }
    }

    @Test
    fun `small pieces schedule exactly as they did before`() {
        // 1 MiB pieces already demanded a reachable 4.2 MB/s, so nothing about those torrents moves.
        assertEquals(ReadaheadSchedule.MIN_STEP_MS, ReadaheadSchedule.deadlineStepMs(1 * mib))
        assertEquals(ReadaheadSchedule.MIN_STEP_MS, ReadaheadSchedule.deadlineStepMs(256 * kib))
    }

    @Test
    fun `big pieces get proportionally longer steps, bounded`() {
        assertEquals(500, ReadaheadSchedule.deadlineStepMs(2 * mib))
        assertEquals(2_000, ReadaheadSchedule.deadlineStepMs(8 * mib))
        assertEquals(8_000, ReadaheadSchedule.deadlineStepMs(32 * mib))
        // Exotic 64 MiB pieces stay bounded rather than declaring the window's tail due minutes out.
        assertEquals(ReadaheadSchedule.MAX_STEP_MS, ReadaheadSchedule.deadlineStepMs(64 * mib))
        // A snapshot taken before selectFile has run must not divide by zero.
        assertEquals(ReadaheadSchedule.MIN_STEP_MS, ReadaheadSchedule.deadlineStepMs(0))
    }

    @Test
    fun `first arm covers the whole window`() {
        assertEquals(100..124, ReadaheadSchedule.armRange(-1, -1, 100, 124))
    }

    @Test
    fun `a one-piece forward slide arms exactly one piece`() {
        // This is the case that cost ~150-210 JNI calls per second under nativeLock for no state change.
        assertEquals(125..125, ReadaheadSchedule.armRange(100, 124, 101, 125))
    }

    @Test
    fun `nothing new to arm when the window is pinned at the end of the file`() {
        assertNull(ReadaheadSchedule.armRange(100, 130, 105, 130))
    }

    @Test
    fun `a backward seek re-arms everything`() {
        // The old absolute deadlines describe a play position that no longer exists.
        assertEquals(40..64, ReadaheadSchedule.armRange(100, 124, 40, 64))
    }

    @Test
    fun `a forward jump clean past the old window re-arms everything`() {
        assertEquals(400..424, ReadaheadSchedule.armRange(100, 124, 400, 424))
    }

    @Test
    fun `an adjacent jump is still treated as a slide`() {
        // windowFirst == armedLast + 1: the ladder is contiguous, so only the new tail needs arming.
        assertEquals(125..149, ReadaheadSchedule.armRange(100, 124, 125, 149))
    }
}
