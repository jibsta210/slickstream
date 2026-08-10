package com.slickstream.data.torrent

import com.slickstream.core.model.StartupBlocker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The readiness gate, exercised over realistic piece-availability shapes: head present/absent, tail
 * present/absent, contiguous vs scattered, and a resume landing mid-file.
 *
 * The invariant these all defend is the one the user reported as broken: a genuinely contiguous,
 * decodable head at the playhead must start playback IMMEDIATELY, and the numbers the UI renders must
 * be the same numbers the gate decided on.
 */
class StartGateTest {

    private val header = StartGate.HEADER_BYTES
    private val tail = StartGate.MOOV_TAIL_BYTES
    private val mb = 1024L * 1024L

    /** A from-start MKV — the common case. Only the contiguous head matters. */
    private fun mkv(
        head: Long,
    ) = StartGate.decide(
        contiguousHeadBytes = head,
        contiguousResumeBytes = head,
        resumeIsHead = true,
        containerMayNeedMoov = false,
        moovInHead = null,
        tailPresent = false,
    )

    /** A from-start MP4. [moovInHead] null = undecided, false = index at EOF, true = faststart. */
    private fun mp4(
        head: Long,
        moovInHead: Boolean?,
        tailPresent: Boolean,
        tailProgress: Long = 0L,
    ) = StartGate.decide(
        contiguousHeadBytes = head,
        contiguousResumeBytes = head,
        resumeIsHead = true,
        containerMayNeedMoov = true,
        moovInHead = moovInHead,
        tailPresent = tailPresent,
        tailProgressBytes = tailProgress,
    )

    // --- head present / absent -------------------------------------------------------------------

    @Test
    fun `contiguous head alone starts a non-moov container immediately`() {
        val d = mkv(head = header)
        assertTrue(d.canStart)
        assertEquals(StartupBlocker.NONE, d.blocker)
        assertEquals(0L, d.remainingBytes)
        assertEquals(1f, d.fillFraction, 0f)
    }

    @Test
    fun `a head far larger than the requirement does not raise the bar`() {
        // The user's report: several solid teal cells at the head. That is emphatically enough.
        val d = mkv(head = 400 * mb)
        assertTrue(d.canStart)
        assertEquals(header, d.requiredBytes)
        assertEquals(0L, d.remainingBytes)
    }

    @Test
    fun `no head yet blocks on the container header and asks for exactly the missing bytes`() {
        val d = mkv(head = 0L)
        assertFalse(d.canStart)
        assertEquals(StartupBlocker.CONTAINER_HEADER, d.blocker)
        assertEquals(header, d.remainingBytes)
        assertEquals(0f, d.fillFraction, 0f)
    }

    @Test
    fun `a partial head is charged only for what is missing`() {
        val d = mkv(head = header / 2)
        assertFalse(d.canStart)
        assertEquals(header / 2, d.remainingBytes)
        assertEquals(0.5f, d.fillFraction, 0.001f)
    }

    @Test
    fun `scattered bytes elsewhere in the file are worth nothing at the head`() {
        // contiguousHeadBytes is by construction the contiguous run from offset 0, so a torrent that is
        // 40% downloaded but has a hole in piece 0 reports 0 here and must NOT start. This is the
        // "downloads fast, never plays" case.
        val scattered = mkv(head = 0L)
        assertFalse(scattered.canStart)
        assertEquals(StartupBlocker.CONTAINER_HEADER, scattered.blocker)
    }

    // --- container-aware tail --------------------------------------------------------------------

    @Test
    fun `a faststart mp4 never waits for the tail`() {
        val d = mp4(head = header, moovInHead = true, tailPresent = false)
        assertTrue(d.canStart)
        assertEquals(header, d.requiredBytes)   // the EOF band was never charged
    }

    @Test
    fun `a non-faststart mp4 with the head but no tail blocks on the index`() {
        val d = mp4(head = header, moovInHead = false, tailPresent = false)
        assertFalse(d.canStart)
        assertEquals(StartupBlocker.MOOV_INDEX, d.blocker)
        assertTrue(d.needsMoovFetch)
        assertEquals(header + tail, d.requiredBytes)
        assertEquals(tail, d.remainingBytes)
    }

    @Test
    fun `a non-faststart mp4 starts as soon as the tail lands`() {
        val d = mp4(head = header, moovInHead = false, tailPresent = true)
        assertTrue(d.canStart)
        assertEquals(StartupBlocker.NONE, d.blocker)
        assertEquals(0L, d.remainingBytes)
    }

    @Test
    fun `an undecided moov stays conservative and still requires the tail`() {
        val d = mp4(head = header, moovInHead = null, tailPresent = false)
        assertFalse(d.canStart)
        assertEquals(StartupBlocker.MOOV_INDEX, d.blocker)
    }

    @Test
    fun `partial tail progress moves the countdown while a single huge EOF piece downloads`() {
        val d = mp4(head = header, moovInHead = false, tailPresent = false, tailProgress = 6 * mb)
        assertFalse(d.canStart)
        assertEquals(tail - 6 * mb, d.remainingBytes)
        // header (done) + 6 of 8 MB of tail, over a 10 MB requirement.
        assertEquals(0.8f, d.fillFraction, 0.001f)
    }

    @Test
    fun `tail progress overshooting the band never credits more than the band`() {
        // startupTailProgressBytes counts whole pieces, which on a 32 MB-piece torrent exceeds the 8 MB
        // band. It must not drive remainingBytes negative or the fill above 100%.
        val d = mp4(head = header, moovInHead = false, tailPresent = false, tailProgress = 64 * mb)
        assertEquals(0L, d.remainingBytes)                  // credit is clamped: never negative
        assertFalse(d.canStart)                             // bytes credited, but the gate is honest
        assertEquals(StartupBlocker.MOOV_INDEX, d.blocker)
        // ...and so is the BAR. remainingBytes bottoming out here is a resolution limit (an 8 MB
        // notional band paid off by blocks of a 32 MB piece), not completion — so the fill must stay
        // short of 100%. Reporting 1.0 here is precisely the "100% · Almost ready…" that never starts.
        assertTrue(d.fillFraction < 1f)
    }

    @Test
    fun `the header is still required even when the tail is already present`() {
        val d = mp4(head = 0L, moovInHead = false, tailPresent = true)
        assertFalse(d.canStart)
        assertEquals(StartupBlocker.CONTAINER_HEADER, d.blocker)
    }

    // --- resume mid-file -------------------------------------------------------------------------

    @Test
    fun `a resume needs the header AND the bytes under the playhead`() {
        val d = StartGate.decide(
            contiguousHeadBytes = header,
            contiguousResumeBytes = 0L,
            resumeIsHead = false,
            containerMayNeedMoov = false,
            moovInHead = null,
            tailPresent = false,
        )
        assertFalse(d.canStart)
        assertEquals(StartupBlocker.PLAYHEAD, d.blocker)
        assertEquals(2 * header, d.requiredBytes)
        assertEquals(header, d.remainingBytes)
    }

    @Test
    fun `a resume with both regions buffered starts immediately`() {
        val d = StartGate.decide(
            contiguousHeadBytes = header,
            contiguousResumeBytes = 90 * mb,
            resumeIsHead = false,
            containerMayNeedMoov = false,
            moovInHead = null,
            tailPresent = false,
        )
        assertTrue(d.canStart)
        assertEquals(0L, d.remainingBytes)
    }

    @Test
    fun `a resume anchored at the file head is charged once, not twice`() {
        // Guards the ETA against doubling on a from-start play, where the engine reports the same
        // contiguous run for both the head and the resume anchor.
        val d = mkv(head = 0L)
        assertEquals(header, d.requiredBytes)
    }

    @Test
    fun `the header is reported before the playhead when both are missing`() {
        val d = StartGate.decide(
            contiguousHeadBytes = 0L,
            contiguousResumeBytes = 0L,
            resumeIsHead = false,
            containerMayNeedMoov = false,
            moovInHead = null,
            tailPresent = false,
        )
        assertEquals(StartupBlocker.CONTAINER_HEADER, d.blocker)
        assertEquals(2 * header, d.remainingBytes)
    }

    @Test
    fun `a resume on a non-faststart mp4 accumulates all three requirements`() {
        val d = StartGate.decide(
            contiguousHeadBytes = 0L,
            contiguousResumeBytes = 0L,
            resumeIsHead = false,
            containerMayNeedMoov = true,
            moovInHead = false,
            tailPresent = false,
        )
        assertEquals(2 * header + tail, d.requiredBytes)
        assertEquals(2 * header + tail, d.remainingBytes)
        assertEquals(0f, d.fillFraction, 0f)
    }

    // --- properties the UI depends on ------------------------------------------------------------

    @Test
    fun `the fill fraction does not read complete while the index is still outstanding`() {
        val blocked = mp4(head = 400 * mb, moovInHead = false, tailPresent = false)
        assertTrue(blocked.fillFraction < 1f)
        assertFalse(blocked.canStart)

        val open = mp4(head = 400 * mb, moovInHead = false, tailPresent = true)
        assertEquals(1f, open.fillFraction, 0f)
        assertTrue(open.canStart)
    }

    @Test
    fun `a whole-piece tail credit cannot fill the bar to 100 percent while the gate is shut`() {
        // The EOF requirement is a fixed 8 MB, but the credit is counted in finished blocks of pieces
        // that are routinely 32 MB — so ONE part-finished EOF piece can pay the whole notional band off
        // while the moov is still incomplete. Without a clamp the bar read "100%" and the countdown
        // froze at its floor, with playback not started: the exact "100% · Almost ready…" this gate is
        // supposed to make impossible.
        val overCredited = mp4(head = 400 * mb, moovInHead = false, tailPresent = false, tailProgress = tail)
        assertFalse(overCredited.canStart)
        assertTrue(overCredited.fillFraction < 1f)

        // Even absurd over-credit (a 32 MB piece against an 8 MB band) must not read complete.
        val wildlyOver = mp4(head = 400 * mb, moovInHead = false, tailPresent = false, tailProgress = 32 * mb)
        assertFalse(wildlyOver.canStart)
        assertTrue(wildlyOver.fillFraction < 1f)
    }

    @Test
    fun `fill reads exactly complete if and only if the gate is open`() {
        val cases = listOf(
            mkv(head = 0L),
            mkv(head = header),
            mp4(head = header, moovInHead = false, tailPresent = false, tailProgress = tail),
            mp4(head = header, moovInHead = false, tailPresent = true),
            mp4(head = header, moovInHead = true, tailPresent = false),
            mp4(head = 0L, moovInHead = null, tailPresent = false, tailProgress = Long.MAX_VALUE),
        )
        cases.forEach { d ->
            assertEquals(d.canStart, d.fillFraction >= 1f)
        }
    }

    @Test
    fun `needsMoovFetch is true only while the index is the sole outstanding requirement`() {
        assertFalse(mkv(head = 0L).needsMoovFetch)
        assertFalse(mp4(head = 0L, moovInHead = false, tailPresent = false).needsMoovFetch)
        assertTrue(mp4(head = header, moovInHead = false, tailPresent = false).needsMoovFetch)
        assertFalse(mp4(head = header, moovInHead = false, tailPresent = true).needsMoovFetch)
    }

    @Test
    fun `remaining bytes never exceed required bytes and never go negative`() {
        val cases = listOf(
            mkv(head = -1L),
            mkv(head = Long.MAX_VALUE),
            mp4(head = Long.MAX_VALUE, moovInHead = false, tailPresent = false, tailProgress = -5L),
            mp4(head = 0L, moovInHead = null, tailPresent = false, tailProgress = Long.MAX_VALUE),
        )
        cases.forEach { d ->
            assertTrue(d.remainingBytes >= 0L)
            assertTrue(d.remainingBytes <= d.requiredBytes)
            assertTrue(d.fillFraction in 0f..1f)
        }
    }

    // --- a PROVEN moov extent replaces the 8 MB fallback band --------------------------------------
    //
    // Once the engine reads the file's own box chain it knows the moov's real extent, and passes that
    // (piece-rounded) as tailBytes. The gate itself is unchanged and still pure — but everything the
    // user sees must now be scaled to the real requirement, not the guess.

    /** A from-start MP4 whose index requirement has been PROVEN to be [tailBytes] rather than 8 MB. */
    private fun mp4Exact(
        head: Long,
        tailBytes: Long,
        tailPresent: Boolean,
        tailProgress: Long = 0L,
    ) = StartGate.decide(
        contiguousHeadBytes = head,
        contiguousResumeBytes = head,
        resumeIsHead = true,
        containerMayNeedMoov = true,
        moovInHead = false,
        tailPresent = tailPresent,
        tailProgressBytes = tailProgress,
        tailBytes = tailBytes,
    )

    @Test
    fun `a proven small moov shrinks what the gate ever asks for`() {
        // The whole point: a 2 MB moov must not be billed as an 8 MB requirement.
        val exact = mp4Exact(head = header, tailBytes = 2 * mb, tailPresent = false)
        val guessed = mp4(head = header, moovInHead = false, tailPresent = false)
        assertEquals(header + 2 * mb, exact.requiredBytes)
        assertEquals(header + tail, guessed.requiredBytes)
        assertTrue(exact.requiredBytes < guessed.requiredBytes)
        assertFalse(exact.canStart)
        assertEquals(StartupBlocker.MOOV_INDEX, exact.blocker)
    }

    @Test
    fun `a proven moov LARGER than the old guess raises the requirement`() {
        // The failure the 8 MB guess hid: a 12 MB moov starts OUTSIDE the guessed window, so the gate
        // passed on bytes that were never fetched and the player stranded on "Almost ready…".
        val d = mp4Exact(head = header, tailBytes = 12 * mb, tailPresent = false, tailProgress = 8 * mb)
        assertEquals(header + 12 * mb, d.requiredBytes)
        assertFalse(d.canStart)
        assertEquals(4 * mb, d.remainingBytes)
    }

    @Test
    fun `progress scales against the proven requirement, not the fallback band`() {
        // Half of a proven 4 MB moov is half done — under the old 8 MB denominator it read as a quarter,
        // so the bar crawled and the ETA over-promised on exactly the fast case.
        val d = mp4Exact(head = header, tailBytes = 4 * mb, tailPresent = false, tailProgress = 2 * mb)
        assertEquals(2 * mb, d.remainingBytes)
        assertEquals((header + 2 * mb).toFloat() / (header + 4 * mb), d.fillFraction, 0.001f)
        assertFalse(d.fillFraction >= 1f)
    }

    @Test
    fun `the proven path still reads 100 percent only when the gate is open`() {
        // The honesty invariant, on the new denominator: tailProgress is counted in WHOLE PIECES, so it
        // can pay off the requirement while the covering piece — and thus the moov — is still missing.
        val blocked = mp4Exact(head = header, tailBytes = 3 * mb, tailPresent = false, tailProgress = 3 * mb)
        assertFalse(blocked.canStart)
        assertFalse(blocked.fillFraction >= 1f)
        val open = mp4Exact(head = header, tailBytes = 3 * mb, tailPresent = true, tailProgress = 3 * mb)
        assertTrue(open.canStart)
        assertEquals(1f, open.fillFraction, 0f)
    }
}
