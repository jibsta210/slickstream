package com.slickstream.data.torrent

/**
 * Scheduling arithmetic for the sliding read-ahead window — pure, so the ladder it declares to
 * libtorrent can be checked against real piece sizes in a unit test instead of on a TV.
 *
 * Both functions exist to stop the window telling libtorrent things that are not true.
 */
object ReadaheadSchedule {

    /** The fill rate the deadline ladder DECLARES: ~4 MB/s (32 Mbps). Comfortably above any <=1080p
     *  bitrate this app plays (a 1.9 GB 2 h release is ~2.2 Mbps), a third of the TV's 12 MiB/s session
     *  cap, and reachable by a handful of ordinary public-swarm peers at 0.2-1 MB/s each. */
    const val TARGET_FILL_BYTES_PER_SEC = 4L * 1024L * 1024L

    /** Floor = the old flat step, so small-piece torrents (<=1 MiB) schedule exactly as they did. */
    const val MIN_STEP_MS = 250

    /** Ceiling for exotic >32 MiB pieces — past that the ladder stops meaning "4 MB/s" and just stays
     *  sane rather than declaring the window's tail due minutes from now. */
    const val MAX_STEP_MS = 8_000

    /**
     * Per-piece deadline STEP for the read-ahead window, proportional to the piece size.
     *
     * The step used to be a flat 250 ms regardless of piece length, which declares a required fill
     * rate of pieceLength ÷ 250 ms:
     *
     *   1 MiB pieces ->   4.2 MB/s     (fine)
     *   2 MiB pieces ->   8.4 MB/s
     *   4 MiB pieces ->  16.8 MB/s     (above this session's own 12 MiB/s download cap)
     *   8 MiB pieces ->  33.6 MB/s
     *  32 MiB pieces -> 134 MB/s
     *
     * A good public-swarm peer moves 0.2-1 MB/s, i.e. 30-160 s for one 32 MiB piece. So libtorrent's
     * "can this peer deliver this time-critical piece before its deadline?" test failed for EVERY peer
     * on EVERY piece in the window, on every tick — and that is precisely the condition under which
     * libtorrent adds MORE peers to the same blocks (endgame-style duplication) and discards whichever
     * copies lose the race. Those discards are counted in total_redundant_bytes: real bytes crossing a
     * bounded TV link and becoming nothing, taken from the very window that exists to prevent
     * rebuffering.
     *
     * Proportional, the ladder says one honest thing at every piece size: "fill this window at
     * [TARGET_FILL_BYTES_PER_SEC]". The window's FIRST piece still gets deadline 0 (maximally
     * time-critical), and [TorrentEngine.ensureRange] still puts deadline 0 on whatever piece a read is
     * actually blocked on, so nothing about first-frame or stall recovery gets slower.
     */
    fun deadlineStepMs(pieceLength: Int): Int {
        if (pieceLength <= 0) return MIN_STEP_MS
        val step = pieceLength.toLong() * 1000L / TARGET_FILL_BYTES_PER_SEC
        return step.coerceIn(MIN_STEP_MS.toLong(), MAX_STEP_MS.toLong()).toInt()
    }

    /**
     * Which pieces of the new window need to be (re-)armed, or null when none do.
     *
     * The old code re-issued piecePriority + setPieceDeadline for the WHOLE window on every one-piece
     * advance — 25 pieces at 2 MiB, 49 at 1 MiB, of which all but one were already time-critical with
     * a correct deadline. At a 12 Mbps stream the read head crosses a 1 MiB piece every ~0.7 s, so that
     * is ~150-210 JNI calls/second held under nativeLock for no change in state.
     *
     * On a plain forward slide only the pieces that just ENTERED the window need arming, and arming
     * them keeps the ladder monotone by construction: their offset is measured from the window's nose,
     * so a piece armed now at (windowLast - windowFirst) × step lands later in wall-clock than a piece
     * armed several ticks ago with a small offset. Pieces already in the window keep the absolute
     * deadlines they were given, which quietly become more overdue as the playhead approaches them —
     * exactly the urgency ordering we want.
     *
     * A backward seek, or a jump clean past the old window, re-arms everything: the old absolute
     * deadlines describe a play position that no longer exists.
     */
    fun armRange(armedFirst: Int, armedLast: Int, windowFirst: Int, windowLast: Int): IntRange? {
        if (windowLast < windowFirst) return null
        val slidForward = armedFirst in 0..windowFirst &&
            armedLast >= armedFirst &&
            windowFirst <= armedLast + 1
        if (!slidForward) return windowFirst..windowLast
        val from = armedLast + 1
        return if (from > windowLast) null else from..windowLast
    }
}
