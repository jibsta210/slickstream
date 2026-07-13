package com.slickstream.feature.player

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import com.slickstream.core.repository.TorrentStreamer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Scrub-bar thumbnails for a torrent stream, sampled only from pieces playback already downloaded.
 *
 * A torrent downloads sequentially, so only the already-buffered region can be decoded without
 * blocking. To give a useful film-strip scrub experience we sample at two densities:
 *
 *  - COARSE tier: ~[COARSE_COUNT] evenly-spaced points across the whole timeline. These appear as the
 *    sequential download reaches them; preview generation never changes torrent piece priority.
 *
 *  - DENSE tier: ~1 frame per MINUTE of content. These are NEVER prefetched — they're decoded
 *    free-of-charge ONLY where the covering bytes are ALREADY on disk
 *    ([TorrentStreamer.isByteAvailable] == true). As the user watches (the contiguous downloaded
 *    head grows), more and more dense samples become decodable for
 *    free; the poll loop re-scans and fills them in. Net effect: the already-downloaded region of the
 *    timeline ends up ~1 frame/min (free), the rest stays at coarse density until its bytes arrive.
 *
 * CRITICAL bandwidth note: torrent prefetch is piece-granular (pieceLength is commonly 8–33 MB and
 * pulls WHOLE pieces). Prefetching ~1 sample/min across a feature would download essentially the
 * whole file, defeating the point of a cheap preview — hence the dense tier is strictly free-only.
 *
 * Safety: every frame decode runs on a background thread and is GATED on the covering piece being on
 * disk (so [MediaMetadataRetriever] won't trigger the HTTP server's blocking range-fetch). Even if a
 * decode does stall, it's a background thread — the UI only ever reads the finished [Bitmap] cache,
 * so there is no ANR path.
 */
class FrameThumbnailExtractor(
    private val streamer: TorrentStreamer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile private var url: String? = null
    @Volatile private var filePath: String? = null
    @Volatile private var infoHash: String? = null
    @Volatile private var durationMs: Long = 0L
    @Volatile private var fileLength: Long = 0L

    /** Spacing of the dense tier (≈ one decoded frame per minute). Drives [thumbnailAt]'s grid. */
    @Volatile private var denseIntervalMs: Long = 0L

    /** Last position the UI asked a thumbnail for — used as the anchor when evicting a full cache. */
    @Volatile private var lastScrubMs: Long = 0L

    /** timestamp(ms at frame centre) -> decoded thumbnail. Guarded by [lock]. Keyed by time (not
     *  sample index) because the two tiers sit on different grids and the dense grid is sparse. */
    private val frames = HashMap<Long, Bitmap>()
    private val lock = Any()

    /** Bumped whenever a new thumbnail lands, so the UI can recompose. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    /**
     * Begin sampling for [streamUrl]. Idempotent for the same URL; switching URL resets everything.
     * Call once playback is up and [durationMs] is known (so the container index/moov is present).
     */
    fun start(streamUrl: String, hash: String, durationMs: Long, fileLength: Long, filePath: String? = null) {
        if (streamUrl == url && job?.isActive == true) return
        reset()
        if (durationMs <= 0L || fileLength <= 0L) return
        url = streamUrl
        this.filePath = filePath
        infoHash = hash
        this.durationMs = durationMs
        this.fileLength = fileLength

        // Coarse tier: bounded whole-timeline grid filled opportunistically from available pieces.
        val coarseCount = COARSE_COUNT
        // Dense tier: ~1 frame/min, clamped so very short clips don't go below the coarse density and
        // very long ones don't blow past the cache cap.
        val durationMinutes = (durationMs / 60_000L).toInt()
        val denseCount = durationMinutes.coerceIn(coarseCount, MAX_DENSE_COUNT)
        denseIntervalMs = (durationMs / denseCount).coerceAtLeast(1L)

        job = scope.launch { run(streamUrl, hash, coarseCount, denseCount) }
    }

    /** Nearest decoded thumbnail to [positionMs], or null if nothing near it is decoded yet. Scans a
     *  window of dense-interval buckets on either side so a scrub between samples still finds the
     *  closest frame on the (possibly sparse) grid. */
    fun thumbnailAt(positionMs: Long): Bitmap? {
        lastScrubMs = positionMs
        val iv = denseIntervalMs
        if (iv <= 0L) return null
        synchronized(lock) {
            if (frames.isEmpty()) return null
            var best: Bitmap? = null
            var bestDist = Long.MAX_VALUE
            // Bound the search window so an arbitrary scrub doesn't walk the whole map; NEIGHBOUR_SPAN
            // dense intervals on either side comfortably covers the gap between any two decoded frames
            // (dense where bytes are present, coarse elsewhere).
            val window = iv * NEIGHBOUR_SPAN
            for ((ts, bmp) in frames) {
                val dist = abs(ts - positionMs)
                if (dist <= window && dist < bestDist) {
                    bestDist = dist
                    best = bmp
                }
            }
            return best
        }
    }

    private suspend fun run(streamUrl: String, hash: String, coarseCount: Int, denseCount: Int) {
        // Coarse + dense sample centres. Both are strictly opportunistic: a 20-point whole-timeline
        // prefetch used to mark 40 widely-scattered whole pieces time-critical (hundreds of MB on old
        // torrents), directly starving the next playback piece on thin swarms.
        val coarseInterval = (durationMs / coarseCount).coerceAtLeast(1L)
        val coarseTimes = (0 until coarseCount).map { i -> i * coarseInterval + coarseInterval / 2 }
        val coarseOffsets = coarseTimes.map { byteOffsetForTime(it) }

        val denseTimes = (0 until denseCount).map { i -> i * denseIntervalMs + denseIntervalMs / 2 }

        // Prefer the on-disk file (libtorrent allocates the whole file up front; we only ever decode
        // slices that isByteAvailable() confirms are present). MediaMetadataRetriever reading the local
        // file is far more reliable than pointing it at the NanoHTTPD URL, which silently fails to
        // decode on some TVs (the "chunk bar works but no preview frames" bug). Fall back to the URL.
        val path = filePath
        var retriever: MediaMetadataRetriever? = null
        var attempts = 0
        while (currentCoroutineContext().isActive && retriever == null) {
            retriever = runCatching {
                val candidate = MediaMetadataRetriever()
                try {
                    if (path != null && java.io.File(path).exists()) candidate.setDataSource(path)
                    else candidate.setDataSource(streamUrl, HashMap<String, String>())
                    candidate
                } catch (t: Throwable) {
                    runCatching { candidate.release() }
                    throw t
                }
            }.getOrElse {
                // Sparse MKV/WebM files may not expose enough container metadata on the first attempt.
                // Retry as sequential playback fills more bytes; this never requests torrent pieces.
                if (attempts++ == 0) {
                    Log.w(TAG, "thumbnail retriever waiting for container metadata (path=$path)")
                }
                null
            }
            if (retriever == null) delay(RETRIEVER_RETRY_MS)
        }
        val activeRetriever = retriever ?: return

        try {
            // Frame centres we've already attempted (decoded or proven-empty) — keyed by time, so we
            // never re-decode a slice and the coarse/dense tiers share one done-set.
            val done = HashSet<Long>()
            // Coarse points still missing get their deadlines re-armed each round so libtorrent keeps
            // sipping them; dense points are free-only and never re-armed.
            var idleRounds = 0
            // The coarse tier defines "complete enough to back off"; the dense tier is opportunistic
            // and may keep filling for the whole session as the download head grows, so we don't
            // require denseCount to finish before idling.
            // Cancellation checks MUST be against THIS job (reset()/start() cancel it), not the
            // long-lived scope — a round spends seconds inside getFrameAtTime, and a job-cancelled
            // loop that only watched scope.isActive kept decoding the OLD source into the cache the
            // new source had just cleared (previous movie's frames in the new scrub filmstrip).
            while (currentCoroutineContext().isActive) {
                var progressed = false

                val pending = buildList<Pair<Long, Long>> {
                    coarseTimes.forEachIndexed { i, t -> if (t !in done) add(t to coarseOffsets[i]) }
                    denseTimes.forEach { t -> if (t !in done) add(t to byteOffsetForTime(t)) }
                }.distinctBy { it.first }
                // One native bitfield snapshot per round, rather than up to ~200 synchronous JNI calls
                // contending with the local HTTP reader's global engine lock.
                val available = streamer.availableByteOffsets(hash, pending.map { it.second })
                var decodedThisRound = 0
                for (i in pending.indices) {
                    if (!currentCoroutineContext().isActive || decodedThisRound >= MAX_DECODES_PER_ROUND) break
                    if (!available.getOrElse(i) { false }) continue
                    val t = pending[i].first
                    if (decodeAndStore(activeRetriever, t)) progressed = true
                    done += t
                    decodedThisRound++
                }

                // If the coarse tier is fully decoded AND every dense point has been attempted, there's
                // nothing left that could ever land — stop polling.
                val coarseDone = coarseTimes.all { it in done }
                val denseDone = denseTimes.all { it in done }
                if (coarseDone && denseDone) break

                idleRounds = if (progressed) 0 else idleRounds + 1
                // Back off as the buffered region stops growing; never give up entirely (the user may
                // still be downloading more, unlocking more free dense frames) but don't spin tightly.
                delay(if (idleRounds > 6) IDLE_POLL_MS else ACTIVE_POLL_MS)
            }
        } finally {
            runCatching { activeRetriever.release() }
        }
    }

    /** Decode the frame centred at [timeMs], store it (capped + evicting) and bump version. Returns
     *  true if a bitmap was actually added. */
    private fun decodeAndStore(retriever: MediaMetadataRetriever, timeMs: Long): Boolean {
        val bmp = decodeFrame(retriever, timeMs) ?: return false
        synchronized(lock) {
            // Don't double-store (another tier may share an identical centre on tiny clips).
            if (frames.containsKey(timeMs)) {
                bmp.recycle()
                return false
            }
            evictIfFullLocked(timeMs)
            frames[timeMs] = bmp
        }
        _version.value = _version.value + 1
        return true
    }

    /** If the cache is at capacity, drop the entry whose timestamp is farthest from the most recent
     *  scrub position (the part of the timeline the user is least likely looking at). Caller holds
     *  [lock]. [incomingTs] is the frame about to be added, so we don't evict to make room for
     *  something even farther away. */
    private fun evictIfFullLocked(incomingTs: Long) {
        if (frames.size < MAX_FRAMES) return
        val anchor = lastScrubMs
        var farKey: Long? = null
        var farDist = abs(incomingTs - anchor)
        for (ts in frames.keys) {
            val dist = abs(ts - anchor)
            if (dist > farDist) {
                farDist = dist
                farKey = ts
            }
        }
        // Only evict if some existing frame is farther from the anchor than the incoming one;
        // otherwise the incoming frame is the least useful and we simply skip adding it.
        // NEVER recycle() evicted bitmaps: thumbnailAt hands the live Bitmap to the Compose
        // filmstrip, which may still be drawing it — recycling under it is a "trying to use a
        // recycled bitmap" crash. Dropping the reference is enough; thumbs are small and GC'd.
        if (farKey != null) {
            frames.remove(farKey)
        }
        // If farKey is null every existing frame is at least as close as the incoming one — but we
        // still need room, so drop the single farthest existing entry to honour the cap.
        if (frames.size >= MAX_FRAMES) {
            val drop = frames.keys.maxByOrNull { abs(it - anchor) }
            if (drop != null) frames.remove(drop)
        }
    }

    private fun decodeFrame(retriever: MediaMetadataRetriever, timeMs: Long): Bitmap? {
        val timeUs = timeMs * 1000L
        val full = runCatching {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }.getOrNull() ?: return null
        return runCatching {
            val w = full.width.coerceAtLeast(1)
            val h = full.height.coerceAtLeast(1)
            val targetH = THUMB_HEIGHT_PX
            val targetW = (targetH * w / h).coerceAtLeast(1)
            Bitmap.createScaledBitmap(full, targetW, targetH, true)
        }.getOrNull().also {
            if (it !== full) full.recycle()
        }
    }

    /** Approximate file byte offset of the keyframe nearest [timeMs] (linear time→byte; the
     *  container's own index refines the actual frame inside getFrameAtTime). */
    private fun byteOffsetForTime(timeMs: Long): Long {
        val t = timeMs.toDouble()
        return ((t / durationMs) * fileLength).toLong().coerceIn(0L, (fileLength - 1).coerceAtLeast(0L))
    }

    private fun reset() {
        job?.cancel()
        job = null
        url = null
        infoHash = null
        durationMs = 0L
        fileLength = 0L
        denseIntervalMs = 0L
        lastScrubMs = 0L
        synchronized(lock) {
            // No recycle(): a failover calls this mid-scrub while the filmstrip may still be drawing
            // one of these bitmaps (see evictIfFullLocked). Dropping the references is enough.
            frames.clear()
        }
        _version.value = 0
    }

    /** Drop the current samples (e.g. when switching source) but keep the extractor reusable. */
    fun clear() = reset()

    /** Stop sampling and free everything. The owning ViewModel calls this in onCleared(). */
    fun release() {
        reset()
        runCatching { scope.cancel() }
    }

    private companion object {
        const val TAG = "FrameThumbs"

        /** Coarse tier: evenly-spaced points across the whole timeline, never actively downloaded. */
        const val COARSE_COUNT = 20

        /** Dense tier upper bound (~1 frame/min, clamped to this). Each thumb ≈ 230 KB, so the cap
         *  keeps the bitmap cache well under ~40 MB even when fully populated. */
        const val MAX_DENSE_COUNT = 180

        /** Hard cap on cached bitmaps (≈ MAX_DENSE_COUNT plus a little headroom). Evict farthest from
         *  the most-recent scrub when exceeded. ~180 × ~230 KB ≈ <40 MB. */
        const val MAX_FRAMES = 180

        const val THUMB_HEIGHT_PX = 180

        /** How many dense intervals on each side [thumbnailAt] scans for the nearest decoded frame.
         *  Wide enough that a scrub landing in an undownloaded (coarse-only) gap still resolves to the
         *  closest coarse frame. */
        const val NEIGHBOUR_SPAN = 8L

        const val ACTIVE_POLL_MS = 600L
        const val IDLE_POLL_MS = 2500L
        const val RETRIEVER_RETRY_MS = 2500L
        /** Bound background decoder CPU so weak TVs keep their cores for live video + piece hashing. */
        const val MAX_DECODES_PER_ROUND = 2
    }
}
