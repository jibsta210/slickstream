package com.slickstream.data.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The box-chain reader, exercised over the real mp4 shapes that decide whether we may require the
 * EXACT moov extent (1-3 MB) instead of the blind 8 MB EOF band (16-64 MB once piece-rounded).
 *
 * The bias under test is deliberate and one-directional: anything ambiguous must come back as
 * "unresolved"/null so the caller keeps today's conservative behaviour. A wrong exact answer either
 * strands the player on an atom nobody fetched, or fails the decode outright — both far worse than
 * being slow.
 */
class Mp4BoxScanTest {

    // ---- byte-array helpers -------------------------------------------------------------------

    /** One top-level box header: 32-bit big-endian size + 4-char type. */
    private fun box(size: Long, type: String): ByteArray {
        val b = ByteArray(8)
        b[0] = ((size shr 24) and 0xFF).toByte()
        b[1] = ((size shr 16) and 0xFF).toByte()
        b[2] = ((size shr 8) and 0xFF).toByte()
        b[3] = (size and 0xFF).toByte()
        for (i in 0..3) b[4 + i] = type[i].code.toByte()
        return b
    }

    /** A reader over a flat byte array — the same contract as reading the on-disk head. */
    private fun reader(bytes: ByteArray): (Long, Int) -> ByteArray? = { pos, len ->
        if (pos < 0 || len <= 0 || pos + len > bytes.size) null
        else bytes.copyOfRange(pos.toInt(), (pos + len).toInt())
    }

    /** Concatenate box headers into a head buffer (contents between headers are irrelevant here). */
    private fun head(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var at = 0
        parts.forEach { it.copyInto(out, at); at += it.size }
        return out
    }

    private val scanLimit = 64L * 1024L * 1024L

    private fun scan(bytes: ByteArray, headBytes: Long = bytes.size.toLong()) =
        Mp4BoxScan.scanHead(reader(bytes), headBytes, scanLimit)

    // ---- scanHead -----------------------------------------------------------------------------

    @Test
    fun `faststart file reports moov first`() {
        // ftyp(32) then moov — the web-optimized layout. No EOF wait may be armed for this.
        val bytes = head(box(32, "ftyp"), ByteArray(24), box(4096, "moov"))
        assertEquals(Mp4BoxScan.HeadScan.MoovFirst, scan(bytes))
    }

    @Test
    fun `mdat first yields the exact offset of the following box`() {
        // ftyp at 0 (32 bytes), mdat header at 32 declaring 1_000_000 -> next box at 1_000_032.
        val bytes = head(box(32, "ftyp"), ByteArray(24), box(1_000_000, "mdat"))
        assertEquals(Mp4BoxScan.HeadScan.MediaFirst(1_000_032L), scan(bytes))
    }

    @Test
    fun `a 3GB mdat is read unsigned, not as a negative size`() {
        // 32-bit sizes go to 4 GB-1; a signed read would make this negative and lose the offset.
        val big = 3_000_000_000L
        val bytes = head(box(big, "mdat"))
        assertEquals(Mp4BoxScan.HeadScan.MediaFirst(big), scan(bytes))
    }

    @Test
    fun `fragmented mp4 is unresolved`() {
        val bytes = head(box(32, "ftyp"), ByteArray(24), box(512, "moof"))
        assertEquals(Mp4BoxScan.HeadScan.MediaFirstUnresolved, scan(bytes))
    }

    @Test
    fun `mdat with 64-bit largesize is unresolved`() {
        val bytes = head(box(1, "mdat"), ByteArray(8))
        assertEquals(Mp4BoxScan.HeadScan.MediaFirstUnresolved, scan(bytes))
    }

    @Test
    fun `mdat that runs to EOF is unresolved`() {
        val bytes = head(box(0, "mdat"))
        assertEquals(Mp4BoxScan.HeadScan.MediaFirstUnresolved, scan(bytes))
    }

    @Test
    fun `mdat with an impossible declared length is unresolved`() {
        val bytes = head(box(5, "mdat"))
        assertEquals(Mp4BoxScan.HeadScan.MediaFirstUnresolved, scan(bytes))
    }

    @Test
    fun `a head too short to hold one box header is undecided`() {
        assertNull(scan(ByteArray(4)))
        assertNull(Mp4BoxScan.scanHead(reader(ByteArray(64)), 0L, scanLimit))
    }

    @Test
    fun `a chain that has not reached media or index yet is undecided`() {
        // ftyp then free, and the head stops before whatever comes next.
        val bytes = head(box(32, "ftyp"), ByteArray(24), box(64, "free"))
        assertNull(scan(bytes))
    }

    @Test
    fun `a box chain walking past the scan limit is undecided`() {
        // One 'free' box longer than the limit: we refuse to keep walking rather than guess.
        val bytes = head(box(32, "ftyp"), ByteArray(24), box(1024, "free"))
        assertNull(Mp4BoxScan.scanHead(reader(bytes), bytes.size.toLong(), 16L))
    }

    @Test
    fun `a skipped box with a nonsense length is undecided`() {
        val bytes = head(box(3, "free"), ByteArray(64))
        assertNull(scan(bytes))
    }

    @Test
    fun `an unreadable head is undecided`() {
        assertNull(Mp4BoxScan.scanHead({ _, _ -> null }, 4096L, scanLimit))
    }

    // ---- moovExtentAt -------------------------------------------------------------------------

    private val fileLen = 4L * 1024L * 1024L * 1024L

    @Test
    fun `a well-formed moov header yields its exact inclusive extent`() {
        val moovSize = 1_500_000L
        val at = 3_000_000_000L
        val ext = Mp4BoxScan.moovExtentAt(box(moovSize, "moov") + ByteArray(8), at, fileLen)
        assertEquals(at, ext?.first)
        assertEquals(at + moovSize - 1, ext?.last)
    }

    @Test
    fun `a moov followed by trailing free still ends before EOF`() {
        // The moov need not be the last box; the extent must stop at the moov, not run to EOF.
        val moovSize = 2_000_000L
        val at = 3_000_000_000L
        val ext = Mp4BoxScan.moovExtentAt(box(moovSize, "moov") + ByteArray(8), at, fileLen)!!
        assertTrue(ext.last < fileLen - 1)
    }

    @Test
    fun `a free box sitting between mdat and moov is rejected`() {
        // Single hop only: we do not chain-walk, because each hop costs another fetched piece.
        assertNull(Mp4BoxScan.moovExtentAt(box(64, "free") + ByteArray(8), 3_000_000_000L, fileLen))
    }

    @Test
    fun `an all-zero read at the target is rejected`() {
        // The signature of a sparse, not-yet-downloaded region: bad magic AND size 0.
        assertNull(Mp4BoxScan.moovExtentAt(ByteArray(16), 3_000_000_000L, fileLen))
    }

    @Test
    fun `moov sizes of 0 1 and below a header are rejected`() {
        val at = 3_000_000_000L
        assertNull(Mp4BoxScan.moovExtentAt(box(0, "moov") + ByteArray(8), at, fileLen))
        assertNull(Mp4BoxScan.moovExtentAt(box(1, "moov") + ByteArray(8), at, fileLen))
        assertNull(Mp4BoxScan.moovExtentAt(box(7, "moov") + ByteArray(8), at, fileLen))
    }

    @Test
    fun `an extent running past the end of the file is rejected`() {
        assertNull(Mp4BoxScan.moovExtentAt(box(1_000_000, "moov") + ByteArray(8), fileLen - 100, fileLen))
    }

    @Test
    fun `an implausibly large declared moov is rejected`() {
        val huge = 300L * 1024L * 1024L
        assertNull(Mp4BoxScan.moovExtentAt(box(huge, "moov") + ByteArray(8), 1024L, 8L * 1024 * 1024 * 1024))
    }

    @Test
    fun `an offset too early to be a real moov is rejected`() {
        assertNull(Mp4BoxScan.moovExtentAt(box(4096, "moov") + ByteArray(8), 8L, fileLen))
    }

    @Test
    fun `a short header buffer is rejected`() {
        assertNull(Mp4BoxScan.moovExtentAt(ByteArray(4), 3_000_000_000L, fileLen))
    }
}
