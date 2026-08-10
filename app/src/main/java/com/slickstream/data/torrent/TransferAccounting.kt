package com.slickstream.data.torrent

/**
 * The arithmetic behind every transfer number the player prints, as pure functions so it can be
 * tested without a swarm, a device or a clock.
 *
 * It exists because the two headline numbers used to be measured on DIFFERENT things and therefore
 * could not be reconciled by the person reading them: a 1.9 GB movie, three minutes in, showed
 * "7.7 MB/s" and "60% downloaded". 1.9 GB ÷ 7.7 MB/s is ~4 minutes for the WHOLE file, so three
 * minutes in should read ~73%. The speed was libtorrent's WIRE rate (protocol overhead + hash-failed
 * bytes + redundant duplicate blocks); the percentage was hash-complete pieces only. Neither was
 * "bytes that became file", so rate × time could never equal Δprogress.
 *
 * The rule these functions encode: a number is published under a label only if it measures what the
 * label says, and a 100% is only ever printed when the thing is actually done.
 */
object TransferAccounting {

    /**
     * Ceiling on the reported file progress while libtorrent says the torrent is NOT finished.
     *
     * `query_accurate_download_counters` credits blocks that have been RECEIVED but not yet hash-
     * verified, so the ratio can graze 1.0 for one poll tick while the final piece is still being
     * checked. The chunk bar's contract is that 100% means "done / playable now", and PieceBar prints
     * "✓ fully downloaded" at >= 0.999 — so stay just under that until libtorrent's own is_finished
     * agrees. Costs one tick of "99%" on a completed file; buys the invariant.
     */
    const val MAX_PROGRESS_WHILE_UNFINISHED = 0.9989f

    /** Below this share of accounted traffic, waste is not worth a word on the diagnostic line. */
    const val WASTE_REPORT_FLOOR = 0.10f

    /** ...and below this many accounted bytes the ratio is noise (a single duplicated 16 KiB block at
     *  t=0 is "50% wasted"). ~4 s of a 7.7 MB/s stream. */
    const val WASTE_MIN_SAMPLE_BYTES = 32L * 1024L * 1024L

    /**
     * Fraction of the selected file that is genuinely on disk.
     *
     * [byWanted] is libtorrent's own total_wanted_done ÷ total_wanted (accurate counters ON, so it
     * includes the finished 16 KiB blocks of pieces still in flight). [byHead] is the contiguous
     * whole-piece walk from the file start, kept only as a FLOOR — it is both whole-piece and
     * contiguous, so under piece scatter it is the smaller of the two and never the source of an
     * over-report.
     */
    fun fileProgress(byHead: Float, byWanted: Float, isFinished: Boolean): Float {
        // byHead >= 1 is its own proof of completeness: it is a walk of HASH-VERIFIED pieces covering
        // the whole file, so it may report 1.0 without waiting for is_finished. That matters — the
        // offline downloader terminates its flow on `progress >= 0.999f`, and it must never be left
        // hanging on a status flag while the finished file sits on disk.
        if (isFinished || byHead >= 1f) return 1f
        val best = maxOf(byHead, byWanted)
        if (best.isNaN()) return 0f
        return best.coerceIn(0f, MAX_PROGRESS_WHILE_UNFINISHED)
    }

    /**
     * The rate to PRINT: bytes that are becoming file, i.e. libtorrent's payload rate.
     *
     * Falls back to the wire rate only when there is no payload rate at all, which is exactly the
     * metadata phase — ut_metadata traffic is not piece payload, so a strict payload rate would print
     * "0 KB/s" over a torrent that is visibly working.
     *
     * Deliberately NOT used for any control decision (failover health, stall detection): those keep
     * the wire rate, because moving them to the payload rate makes failover fire sooner and that is a
     * playback-reliability change, not a labelling one.
     */
    fun displayRate(payloadRate: Int, wireRate: Int): Int =
        if (payloadRate > 0) payloadRate else maxOf(wireRate, 0)

    /**
     * The rate at which the FILE is actually growing.
     *
     * libtorrent's downloadPayloadRate() is not that number: the payload channel is counted at the
     * wire-parsing layer when a piece message is decoded, BEFORE the block is found to be redundant
     * (add_redundant_bytes) and long before a hash failure is found (add_failed_bytes). Neither
     * subtracts from it, so it still includes bytes that never became file — the same dishonesty as the
     * wire rate, just smaller. Discounting by the measured waste share gives a number the user's own
     * arithmetic can reconcile with the percentage next to it, which is the whole point.
     *
     * Below [WASTE_MIN_SAMPLE_BYTES] the ratio is too noisy to apply, so the raw payload rate stands.
     */
    fun effectiveRate(payloadRate: Int, payloadBytes: Long, wastedBytes: Long): Int {
        if (payloadRate <= 0) return maxOf(payloadRate, 0)
        if (payloadBytes < WASTE_MIN_SAMPLE_BYTES) return payloadRate
        val keep = 1f - wastedFraction(payloadBytes, wastedBytes)
        return (payloadRate * keep).toInt().coerceAtLeast(0)
    }

    /** 0f..1f of accounted traffic that was thrown away (hash-failed + redundant duplicate blocks). */
    fun wastedFraction(payloadBytes: Long, wastedBytes: Long): Float {
        // Denominator is payloadBytes ALONE, not payload+wasted. libtorrent counts the payload channel
        // at wire-parse time, BEFORE a block is classified as redundant or hash-failed, so the failed and
        // redundant totals are already INSIDE totalPayloadDownload. Adding them again understated the
        // waste (246 MB of 1386 MB read as 15% instead of the true 17.7%) and therefore left the
        // displayed speed a few percent above the rate the file was actually growing — the very
        // reconcilability this exists to provide.
        if (payloadBytes <= 0L || wastedBytes <= 0L) return 0f
        return (wastedBytes.toFloat() / payloadBytes).coerceIn(0f, 1f)
    }

    /**
     * Whole-percent waste to show on the chunk bar's info line, or null when there is nothing worth
     * saying. This is real bandwidth the TV's bounded link spent on bytes that were discarded — it is
     * what steals from the read-ahead window and drives mid-stream rebuffering — so when it is large
     * the user is entitled to see it rather than have it silently inflate the speed.
     */
    fun wastedPercentToShow(payloadBytes: Long, wastedBytes: Long): Int? {
        if (payloadBytes < WASTE_MIN_SAMPLE_BYTES) return null
        val fraction = wastedFraction(payloadBytes, wastedBytes)
        if (fraction < WASTE_REPORT_FLOOR) return null
        return (fraction * 100f).toInt().coerceIn(1, 100)
    }
}
