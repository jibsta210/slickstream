package com.slickstream.data.torrent

import com.slickstream.core.model.StartupBlocker

/**
 * The verdict of one [StartGate.decide] evaluation.
 *
 * [requiredBytes]/[remainingBytes] are the SAME arithmetic the ETA and the startup progress bar are
 * built from, so "the bar says 100%" and "the gate says wait" can no longer disagree: the bar is the
 * gate, rendered.
 */
data class StartDecision(
    /** True when every byte the container actually needs to produce a first frame is on disk. */
    val canStart: Boolean,
    /** The single thing still missing (the first unmet requirement), for the UI to name out loud. */
    val blocker: StartupBlocker,
    /** Total bytes this gate ever asked for — the denominator of the startup progress bar. */
    val requiredBytes: Long,
    /** Bytes still missing across every requirement — the numerator of the ETA. */
    val remainingBytes: Long,
) {
    /** The head is in and the EOF index is the ONLY thing left: fetch exactly that, don't wait for it. */
    val needsMoovFetch: Boolean get() = blocker == StartupBlocker.MOOV_INDEX

    /** 0f..1f toward playable. Fills to exactly 1.0 as the gate opens — never before. */
    val fillFraction: Float
        get() = if (requiredBytes <= 0L) 1f
        else ((requiredBytes - remainingBytes).toFloat() / requiredBytes).coerceIn(0f, 1f)
}

/**
 * Decides when a torrent may start playing, as a PURE function of what is genuinely on disk.
 *
 * The decision is deliberately made of three real, physical requirements and nothing else — no fixed
 * percentage of the file, and no wall-clock "it's probably fine by now" escape:
 *
 *  1. CONTAINER HEADER — the first [HEADER_BYTES] contiguous from the file start. ExoPlayer must parse
 *     ftyp/moov or EBML/Tracks at offset 0 before it can decode OR seek, so this is required even on a
 *     resume. Measured as CONTIGUOUS bytes: scattered pieces elsewhere in the file are worth nothing
 *     here, which is exactly why a "43% downloaded" torrent can still be unable to start.
 *  2. PLAYHEAD — [HEADER_BYTES] contiguous from the RESUME anchor (resume anchoring: the user starts
 *     at 40 min, so the bytes at 40 min are what decide whether playback can begin, not the file head
 *     they will never watch). For a from-start play this is the same region as (1) and is charged once.
 *  3. MOOV INDEX — container-aware, and only when the container REALLY needs it. Only ISO-BMFF
 *     (mp4/m4v/mov) can carry its index at EOF, and even then only when it is not "faststart". mkv,
 *     webm and avi keep their index at the front and start on the head alone. [moovInHead] `null`
 *     means "not enough head to tell yet" and stays conservative until the head resolves it.
 *
 * Everything the caller feeds in is an observation, never a timer — so this whole file is testable
 * against realistic piece-availability inputs without a swarm, a device or a clock.
 */
object StartGate {

    /** Contiguous bytes required at the file header AND at the playhead (~2 MB — enough to start). */
    const val HEADER_BYTES = 2L * 1024L * 1024L

    /** EOF band an mp4/m4v/mov must also have before its first frame. The moov of a feature-length
     *  1080p/4K file routinely runs several MB (one stco/stsz/stts table per track), so this must span
     *  a whole real moov — a 1 MB band left the rest of the atom on un-prioritised EOF pieces, and the
     *  player then blocked reading an atom sequential download would not reach for minutes.
     *  Matches [TorrentEngine.TAIL_PRIORITY_BYTES]. */
    const val MOOV_TAIL_BYTES = 8L * 1024L * 1024L

    /**
     * @param contiguousHeadBytes contiguous bytes from the selected file's start.
     * @param contiguousResumeBytes contiguous bytes from the resume anchor (== head for a from-start play).
     * @param resumeIsHead true for a from-start play, so the playhead requirement is not charged twice.
     * @param containerMayNeedMoov true only for mp4/m4v/mov — every other container starts on the head.
     * @param moovInHead true = faststart (index already in the head), false = index at EOF,
     *        null = not enough head to decide yet (treated as "needs it", conservatively).
     * @param tailPresent whether the whole EOF band is on disk.
     * @param tailProgressBytes bytes already fetched inside the EOF band, INCLUDING finished blocks of a
     *        still-incomplete piece — without this a single 32 MB EOF piece shows no progress at all and
     *        the countdown freezes while the swarm is in fact working.
     */
    fun decide(
        contiguousHeadBytes: Long,
        contiguousResumeBytes: Long,
        resumeIsHead: Boolean,
        containerMayNeedMoov: Boolean,
        moovInHead: Boolean?,
        tailPresent: Boolean,
        tailProgressBytes: Long = 0L,
        headerBytes: Long = HEADER_BYTES,
        tailBytes: Long = MOOV_TAIL_BYTES,
    ): StartDecision {
        val headerRequired = headerBytes.coerceAtLeast(0L)
        val headHave = contiguousHeadBytes.coerceIn(0L, headerRequired)
        val headerOk = headHave >= headerRequired

        // (2) Only charged when the user is resuming somewhere other than the file head.
        val playheadRequired = if (resumeIsHead) 0L else headerRequired
        val playheadHave = contiguousResumeBytes.coerceIn(0L, playheadRequired)
        val playheadOk = resumeIsHead || playheadHave >= playheadRequired

        // (3) Container-aware. A faststart mp4 (moov already in the head) needs NOTHING from the EOF.
        val needsMoov = containerMayNeedMoov && moovInHead != true
        val moovRequired = if (needsMoov) tailBytes.coerceAtLeast(0L) else 0L
        val moovOk = !needsMoov || tailPresent
        val moovHave = if (moovOk) moovRequired else tailProgressBytes.coerceIn(0L, moovRequired)

        val required = headerRequired + playheadRequired + moovRequired
        val have = headHave + playheadHave + moovHave
        val remaining = (required - have).coerceAtLeast(0L)

        val blocker = when {
            !headerOk -> StartupBlocker.CONTAINER_HEADER
            !playheadOk -> StartupBlocker.PLAYHEAD
            !moovOk -> StartupBlocker.MOOV_INDEX
            else -> StartupBlocker.NONE
        }

        return StartDecision(
            canStart = blocker == StartupBlocker.NONE,
            blocker = blocker,
            requiredBytes = required,
            remainingBytes = remaining,
        )
    }
}
