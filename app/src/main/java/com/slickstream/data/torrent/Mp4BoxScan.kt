package com.slickstream.data.torrent

/**
 * ISO-BMFF (mp4/m4v/mov) top-level box-chain reader — the PURE half of "where is the moov?".
 *
 * A non-faststart mp4 keeps its index (`moov`) at the END of the file, and ExoPlayer cannot decode a
 * single frame without it. The engine used to handle that by blindly requiring the LAST 8 MB of the
 * file, rounded up to whole pieces — on a torrent with 8-32 MB pieces that is 16-64 MB of data
 * demanded at the far end of the file, where the sequential cursor will not reach for minutes, purely
 * to obtain an atom that is typically 1-3 MB. It is also wrong in the other direction: a moov LARGER
 * than 8 MB starts outside the guessed window, so the gate passes, READY fires, and the player then
 * sits on "Almost ready…" reading an atom nothing ever prioritised.
 *
 * The exact answer is cheap and already in the file: walk the top-level boxes from offset 0, and when
 * the media box (`mdat`) is reached, `pos + boxLen` is exactly where the NEXT top-level box begins —
 * normally the `moov`. Read 16 bytes there and its declared size gives the exact extent to require.
 *
 * This object does the parsing ONLY: byte reads come in through a lambda so it is unit-testable with
 * no device, no torrent and no swarm. Every ambiguous shape returns a "cannot claim an offset" verdict
 * so the caller falls back to the old 8 MB band rather than guessing — guessing here means either a
 * stall on an atom nobody fetched, or a decode failure, both of which are worse than being slow.
 */
internal object Mp4BoxScan {

    /** Where the index lives, as far as the already-downloaded head can prove. */
    sealed interface HeadScan {
        /** `moov` appears before any media box — a "faststart"/web-optimized file. No EOF wait at all. */
        object MoovFirst : HeadScan

        /**
         * Media (`mdat`) comes first, and its box length resolved cleanly, so the box AFTER it starts at
         * exactly [nextBoxOffset]. That is where the `moov` normally is — the caller must still READ the
         * header there and verify, because a `free`/`skip`/`udta` can sit in between.
         */
        data class MediaFirst(val nextBoxOffset: Long) : HeadScan

        /**
         * Media comes first — so an EOF index IS required — but no exact offset can be claimed:
         * fragmented mp4 (`moof`), a 64-bit `largesize` mdat, an mdat that runs to EOF (size 0), or an
         * impossible declared length. The caller keeps the old blind 8 MB EOF band.
         */
        object MediaFirstUnresolved : HeadScan
    }

    /**
     * Walk the top-level box chain from offset 0.
     *
     * @param readAt (position, length) -> exactly [length] bytes at [position], or null if they are not
     *        readable (past the contiguous head, I/O error, negative offset).
     * @param headBytes how many CONTIGUOUS bytes from the file start are genuinely on disk. Boxes are
     *        only walked inside this window — a torrent's head fills front-to-back, so anything beyond
     *        it may be a sparse hole full of zeros.
     * @param scanLimit stop walking after this many bytes (ftyp+moov sit right at the front of a
     *        faststart file, so a real answer arrives almost immediately; this only bounds pathology).
     * @return null when the head is too short / the chain has not resolved yet — the caller must stay
     *         conservative and re-walk once more of the head has landed.
     */
    fun scanHead(readAt: (Long, Int) -> ByteArray?, headBytes: Long, scanLimit: Long): HeadScan? {
        if (headBytes < BOX_HEADER_BYTES) return null
        var pos = 0L
        while (pos + BOX_HEADER_BYTES <= headBytes && pos < scanLimit) {
            val hdr = readAt(pos, BOX_HEADER_BYTES) ?: return null
            if (hdr.size < BOX_HEADER_BYTES) return null
            val size32 = beU32(hdr, 0)
            when (boxType(hdr)) {
                "moov" -> return HeadScan.MoovFirst          // index up front → faststart, no EOF wait
                "moof" -> return HeadScan.MediaFirstUnresolved // fragmented mp4 — no single trailing moov
                "mdat" -> {
                    // THE WHOLE POINT: the old scanner threw this length away and returned "moov is at
                    // EOF somewhere". pos + boxLen is exactly where the next top-level box starts.
                    // Read the size UNSIGNED here (unlike the skipped-box path below, which is left
                    // byte-for-byte as it was): a 2-4 GB mdat is entirely normal for a feature-length
                    // remux and its length does not fit in a signed Int.
                    if (size32 == 0L) return HeadScan.MediaFirstUnresolved  // runs to EOF: no next box
                    if (size32 == 1L) return HeadScan.MediaFirstUnresolved  // 64-bit largesize (>4 GB)
                    if (size32 < BOX_HEADER_BYTES) return HeadScan.MediaFirstUnresolved // impossible
                    val next = pos + size32
                    // The next box must start after this one and be far enough in to be a real trailing
                    // index (an mdat at offset 0 with an 8-byte length is not a file).
                    if (next <= pos || next < MIN_INDEX_OFFSET) return HeadScan.MediaFirstUnresolved
                    return HeadScan.MediaFirst(next)
                }
            }
            // --- Skipped box (ftyp/free/wide/…): length maths preserved EXACTLY as the original
            //     scanner had it. Changing it here would change the faststart verdict itself, which
            //     must stay bit-identical for files that are already fast to start.
            val boxLen: Long = when {
                size32 == 1L -> {                                  // 64-bit largesize
                    if (pos + LARGE_HEADER_BYTES > headBytes) return null
                    val lb = readAt(pos + BOX_HEADER_BYTES, 8) ?: return null
                    if (lb.size < 8) return null
                    var s = 0L
                    for (b in lb) s = (s shl 8) or (b.toLong() and 0xFF)
                    s
                }
                size32 == 0L -> return HeadScan.MediaFirstUnresolved // box runs to EOF before any moov
                // Signed narrowing on purpose: this is what the original did, and a >2 GB ftyp/free is
                // not a real shape. It lands on the "impossible length" guard below → undecided.
                else -> size32.toInt().toLong()
            }
            if (boxLen < BOX_HEADER_BYTES) return null
            if (pos > Long.MAX_VALUE - boxLen) return null          // overflow → cannot walk further
            pos += boxLen
        }
        return null
    }

    /**
     * Validate the 16 bytes read AT [offset] (the box that follows `mdat`) and turn them into the exact
     * inclusive byte range the `moov` occupies.
     *
     * Returns null — meaning "fall back to the blind 8 MB band" — for every shape we cannot prove:
     *  - the box is not `moov` at all (a `free`/`skip`/`udta`/second `mdat` sits in between; deliberately
     *    NO chain-walking, because each extra hop costs another fetched piece);
     *  - `size == 0` (runs to EOF) or `size == 1` (64-bit largesize) or an impossible `size < 8`;
     *  - the extent would run past the end of the file, or start impossibly early;
     *  - a size above [MAX_MOOV_BYTES], which means we are reading garbage, not a box header.
     *
     * An all-zero read — the signature of a sparse, not-yet-downloaded region — is rejected twice over:
     * by the type magic AND by `size == 0`.
     */
    fun moovExtentAt(header: ByteArray, offset: Long, fileLength: Long): LongRange? {
        if (header.size < BOX_HEADER_BYTES) return null
        if (offset < MIN_INDEX_OFFSET) return null
        if (fileLength <= 0L) return null
        if (offset + BOX_HEADER_BYTES > fileLength) return null
        if (boxType(header) != "moov") return null
        val size = beU32(header, 0)
        if (size < BOX_HEADER_BYTES) return null            // covers 0 (to EOF) and 1 (largesize)
        if (size > MAX_MOOV_BYTES) return null
        if (offset > fileLength - size) return null         // overflow-safe "offset + size > fileLength"
        return offset..(offset + size - 1)
    }

    /** Big-endian unsigned 32-bit read — sizes up to 4 GB-1 must not come back negative. */
    private fun beU32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or
            ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or
            (b[at + 3].toLong() and 0xFF)

    private fun boxType(b: ByteArray): String = String(b, 4, 4, Charsets.US_ASCII)

    /** size(4) + type(4). */
    const val BOX_HEADER_BYTES = 8

    /** Earliest byte offset a trailing `moov` could plausibly start at: a file has at least an ftyp and
     *  an mdat header ahead of it. Anything lower means the chain walk produced nonsense. */
    const val MIN_INDEX_OFFSET = 16L

    /** size(4) + type(4) + largesize(8). */
    private const val LARGE_HEADER_BYTES = 16

    /** Sanity ceiling on a declared moov size. Real feature-length moovs run single-digit MB; anything
     *  this large means the 4 bytes we read are not a box header, so refuse to require them. */
    private const val MAX_MOOV_BYTES = 256L * 1024L * 1024L
}
