package com.slickstream.data.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind the two numbers the user caught disagreeing: "7.7 MB/s" beside "60%" on a
 * 1.9 GB file three minutes in. Every case here is one of the concrete situations that produced a
 * number nobody could reconcile with the label above it.
 */
class TransferAccountingTest {

    private val mb = 1024L * 1024L

    // --- fileProgress ---------------------------------------------------------------------------

    @Test
    fun `partial blocks count — the 2 GB torrent that displayed 0 percent`() {
        // 30 s at 3.2 MB/s = ~96 MB, spread over ~20 unfinished 32 MB pieces. The contiguous
        // whole-piece walk sees nothing; libtorrent's accurate total_wanted_done sees 96 MB of 2 GB.
        val byHead = 0f
        val byWanted = (96 * mb).toFloat() / (2048 * mb)
        val progress = TransferAccounting.fileProgress(byHead, byWanted, isFinished = false)
        assertEquals(0.0469f, progress, 0.001f)
        assertTrue("must not round to 0%", (progress * 100).toInt() >= 4)
    }

    @Test
    fun `contiguous head is a floor, never a ceiling`() {
        // Scatter: 60% of the file is on disk but only 5% of it is contiguous from the start.
        assertEquals(0.60f, TransferAccounting.fileProgress(0.05f, 0.60f, isFinished = false), 0.0001f)
        // ...and the reverse (sequential fill, accurate counter momentarily behind) keeps the head.
        assertEquals(0.40f, TransferAccounting.fileProgress(0.40f, 0.38f, isFinished = false), 0.0001f)
    }

    @Test
    fun `never claims 100 percent until libtorrent says finished`() {
        // Accurate counters credit RECEIVED-but-unverified blocks, so the ratio can graze 1.0 while the
        // last piece is still being hash-checked. PieceBar prints "✓ fully downloaded" at >= 0.999, and
        // the chunk bar's contract is that 100% means done.
        val nearly = TransferAccounting.fileProgress(0.98f, 1f, isFinished = false)
        assertTrue("must stay under the 0.999 'fully downloaded' threshold", nearly < 0.999f)
        assertEquals(1f, TransferAccounting.fileProgress(0.9f, 1f, isFinished = true), 0.0001f)
    }

    @Test
    fun `a fully verified contiguous file is 100 percent without waiting on a status flag`() {
        // byHead is a walk of HASH-VERIFIED pieces covering the whole file. The offline downloader ends
        // its flow on progress >= 0.999, so it must not hang on is_finished with the file on disk.
        assertEquals(1f, TransferAccounting.fileProgress(1f, 0.99f, isFinished = false), 0.0001f)
    }

    @Test
    fun `degenerate inputs do not produce a NaN percentage`() {
        assertEquals(0f, TransferAccounting.fileProgress(Float.NaN, Float.NaN, false), 0.0001f)
        assertEquals(0f, TransferAccounting.fileProgress(-1f, 0f, false), 0.0001f)
    }

    // --- displayRate ----------------------------------------------------------------------------

    @Test
    fun `display rate prefers payload but survives the metadata phase`() {
        // Steady state: print what became file, not what crossed the wire.
        assertEquals(6_300_000, TransferAccounting.displayRate(6_300_000, 7_700_000))
        // Metadata fetch: ut_metadata traffic is not piece payload, so a strict payload rate would
        // print "0 KB/s" over a torrent that is visibly working.
        assertEquals(120_000, TransferAccounting.displayRate(0, 120_000))
        assertEquals(0, TransferAccounting.displayRate(0, 0))
    }

    // --- waste ----------------------------------------------------------------------------------

    @Test
    fun `waste fraction reproduces the reported 1_9 GB case`() {
        // ~1386 MB moved on the payload channel, of which 246 MB delivered nothing.
        // payloadBytes is what libtorrent's totalPayloadDownload() ACTUALLY reports: it is counted at
        // wire-parse time, before a block is classified redundant/hash-failed, so it ALREADY INCLUDES
        // the wasted bytes. Passing 1140 here would be modelling a number the engine never produces.
        val payload = 1386 * mb
        val wasted = 246 * mb
        assertEquals(0.177f, TransferAccounting.wastedFraction(payload, wasted), 0.005f)
        assertEquals(17, TransferAccounting.wastedPercentToShow(payload, wasted))
    }

    @Test
    fun `waste is not reported before there is enough traffic to mean anything`() {
        // One duplicated 16 KiB block at t=0 is "50% wasted" and would be a lie on screen.
        assertNull(TransferAccounting.wastedPercentToShow(16 * 1024L, 16 * 1024L))
        // Nor when it is genuinely small — 3% is not worth a word on the diagnostic line.
        assertNull(TransferAccounting.wastedPercentToShow(970 * mb, 30 * mb))
        // A healthy swarm reports nothing at all.
        assertEquals(0f, TransferAccounting.wastedFraction(1000 * mb, 0), 0.0001f)
        assertNull(TransferAccounting.wastedPercentToShow(1000 * mb, 0))
    }

    @Test
    fun `waste fraction cannot exceed 100 percent or divide by zero`() {
        assertEquals(0f, TransferAccounting.wastedFraction(0, 0), 0.0001f)
        // No payload accounted yet -> no ratio to report (0, not 100%): with payload as the denominator
        // a zero payload is "nothing measured", and claiming 100% wasted from it would be the bar
        // overstating, which is the one thing it must never do.
        assertEquals(0f, TransferAccounting.wastedFraction(0, 100 * mb), 0.0001f)
        // Waste can never exceed the payload it is a share of.
        assertEquals(1f, TransferAccounting.wastedFraction(10 * mb, 50 * mb), 0.0001f)
    }
}
