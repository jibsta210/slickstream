package com.slickstream.feature.player

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import com.slickstream.core.repository.TorrentStreamer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Scrub-bar film-strip thumbnails. Two sampling strategies, picked by the source kind:
 *
 * TORRENT-BACKED ([start] with `torrentBacked = true`) — sampled ONLY from pieces playback has
 * already downloaded, decoded off the on-disk file. See the two-tier note below.
 *
 * DIRECT ([start] with `torrentBacked = false`: Real-Debrid / free-addon HTTP links and offline
 * `file://` downloads) — there is no piece bitfield to gate on and nothing local to read, so the
 * retriever is pointed at the URL itself WITH the host's required request headers (Referer/Origin/
 * User-Agent — without a UA many CDNs, RD included, reject the request, which is why RD scrubbing
 * showed empty slots while torrents showed frames). Because every remote decode costs range requests
 * over the network, the direct path is deliberately frugal and defensive:
 *   - the coarse tier fills eagerly (a cheap whole-timeline baseline);
 *   - the dense tier is decoded only NEAR the position the user is actually scrubbing, and only once
 *     they have scrubbed at all, so a viewer who never touches the seek bar pays nothing for it;
 *   - every open and every decode is bounded by a wall-clock timeout, one decode is in flight at a
 *     time, and a timed-out retriever is abandoned (released when its orphaned native call returns)
 *     rather than reused — a missing preview is always better than a wedged decoder or a crash;
 *   - repeated empty decodes (HLS playlists, audio-only, a host that won't serve ranges) stop the
 *     sampler for good instead of hammering the host.
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

    /** Request headers the direct host demands (Referer/Origin/User-Agent). Empty for torrents. */
    @Volatile private var headers: Map<String, String> = emptyMap()

    /** True once the UI has asked for at least one thumbnail, i.e. the user really is scrubbing. Gates
     *  the direct path's dense tier so a straight-through viewing costs zero extra range requests. */
    @Volatile private var scrubbed: Boolean = false

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
     * Begin sampling for [streamUrl]. Idempotent for the same URL — the caller re-invokes this on every
     * READY re-emit (seeks, rebuffers), and a restart would only throw away the frames already decoded
     * and pay for them again. Switching URL resets everything.
     * Call once playback is up and [durationMs] is known (so the container index/moov is present).
     *
     * @param fileLength size of the backing file; required for the torrent path's time→byte mapping,
     *   ignored (and legitimately 0) for a direct source.
     * @param headers request headers a direct host requires; ignored for torrents.
     * @param torrentBacked false for a direct/offline source: no piece-availability gating, decode
     *   straight from the URL (or the local file behind a `file://` URL).
     */
    fun start(
        streamUrl: String,
        hash: String,
        durationMs: Long,
        fileLength: Long,
        filePath: String? = null,
        headers: Map<String, String> = emptyMap(),
        torrentBacked: Boolean = true,
    ) {
        if (streamUrl == url) return
        reset()
        if (durationMs <= 0L) return
        // Only the torrent path maps time→byte to ask the bitfield what's on disk; a direct source has
        // no such gate, so a missing length must not silently kill its previews (the RD bug).
        if (torrentBacked && fileLength <= 0L) return
        url = streamUrl
        this.filePath = if (torrentBacked) filePath else (filePath?.takeIf(::fileExists) ?: localPathOrNull(streamUrl))
        infoHash = hash
        this.durationMs = durationMs
        this.fileLength = fileLength
        this.headers = headers

        // Coarse tier: bounded whole-timeline grid filled opportunistically from available pieces.
        val coarseCount = COARSE_COUNT
        // Dense tier: ~1 frame/min, clamped so very short clips don't go below the coarse density and
        // very long ones don't blow past the cache cap.
        val durationMinutes = (durationMs / 60_000L).toInt()
        val denseCount = durationMinutes.coerceIn(coarseCount, MAX_DENSE_COUNT)
        denseIntervalMs = (durationMs / denseCount).coerceAtLeast(1L)

        job = scope.launch {
            // Previews are a nicety: a sampler that throws must degrade to "no frames", never take the
            // player down with it (this job has no CoroutineExceptionHandler, so an escaping throwable
            // would reach the default handler).
            try {
                if (torrentBacked) run(streamUrl, hash, coarseCount, denseCount)
                else runDirect(streamUrl, coarseCount, denseCount)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "scrub preview sampling stopped: $t")
            }
        }
    }

    /** Nearest decoded thumbnail to [positionMs], or null if nothing near it is decoded yet. Scans a
     *  window of dense-interval buckets on either side so a scrub between samples still finds the
     *  closest frame on the (possibly sparse) grid. */
    fun thumbnailAt(positionMs: Long): Bitmap? {
        lastScrubMs = positionMs
        scrubbed = true
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

    /**
     * Direct-source sampling (Real-Debrid / free-addon HTTP links, and offline `file://` downloads).
     *
     * No piece bitfield exists here, so nothing gates the decodes — instead every step is bounded:
     * the open and each decode have a wall-clock timeout, exactly one decode is in flight, remote
     * decodes draw on a fixed budget, and any timeout or a run of empty decodes ends sampling for
     * this source (empty film-strip slots, never a stuck decoder or a released-under-use retriever).
     */
    private suspend fun runDirect(streamUrl: String, coarseCount: Int, denseCount: Int) {
        val coarseInterval = (durationMs / coarseCount).coerceAtLeast(1L)
        val coarseTimes = (0 until coarseCount).map { i -> i * coarseInterval + coarseInterval / 2 }
        val denseTimes = (0 until denseCount).map { i -> i * denseIntervalMs + denseIntervalMs / 2 }

        // An offline download is a plain local file: decode it directly (no headers, no network).
        val local = filePath
        val isRemote = local == null

        if (isRemote) {
            // Don't open a SECOND connection to the host until the user actually scrubs. Some direct
            // hosts cap concurrent connections per link, and stealing throughput from the stream that
            // IS playing to prefetch previews nobody asked for is a bad trade. Once they scrub, the
            // coarse tier fills nearest-first so the strip populates around where they're looking.
            while (currentCoroutineContext().isActive && !scrubbed) delay(SCRUB_WAIT_POLL_MS)
            if (!currentCoroutineContext().isActive) return
        }

        val retriever = openRetrieverBounded(streamUrl, local, headers) ?: run {
            Log.w(TAG, "no scrub previews: retriever wouldn't open for direct source")
            return
        }

        val decodeTimeoutMs = if (isRemote) REMOTE_DECODE_TIMEOUT_MS else LOCAL_DECODE_TIMEOUT_MS
        // Non-null while a native decode owns [retriever]; the finally must NOT release it under one.
        var inFlight: Deferred<Bitmap?>? = null
        try {
            val done = HashSet<Long>()
            var emptyDecodes = 0
            var budget = if (isRemote) REMOTE_DECODE_BUDGET else Int.MAX_VALUE
            while (currentCoroutineContext().isActive) {
                // Re-read the scrub anchor every round so the dense tier follows the user.
                val anchor = lastScrubMs
                val pending = buildList {
                    coarseTimes.forEach { if (it !in done) add(it) }
                    if (scrubbed) {
                        denseTimes.forEach { if (it !in done && abs(it - anchor) <= DENSE_WINDOW_MS) add(it) }
                    }
                }.distinct().sortedBy { abs(it - anchor) }

                if (pending.isEmpty() || budget <= 0) {
                    // Nothing decodable right now. Keep idling cheaply: a scrub to a fresh part of the
                    // timeline opens up new dense candidates without restarting the extractor.
                    delay(IDLE_POLL_MS)
                    continue
                }

                var decodedThisRound = 0
                for (t in pending) {
                    if (!currentCoroutineContext().isActive) break
                    if (decodedThisRound >= MAX_DECODES_PER_ROUND || budget <= 0) break

                    val work = scope.async(Dispatchers.IO) { decodeFrame(retriever, t) }
                    inFlight = work
                    val bmp = withTimeoutOrNull(decodeTimeoutMs) { work.await() }
                    if (bmp == null && !work.isCompleted) {
                        // Host (or decoder) is too slow. The native call still owns the retriever, so it
                        // can be neither reused nor released here — leave it to the finally, which hands
                        // it to a completion hook, and stop sampling this source.
                        Log.w(TAG, "scrub preview decode timed out at ${t}ms — previews off for this source")
                        return
                    }
                    inFlight = null
                    done += t
                    decodedThisRound++
                    budget--

                    if (bmp != null) {
                        storeFrame(t, bmp)
                        emptyDecodes = 0
                    } else if (++emptyDecodes >= MAX_EMPTY_DECODES) {
                        // HLS playlist, audio-only, or a host that won't serve ranges: nothing will ever
                        // decode, so stop rather than keep asking.
                        Log.w(TAG, "scrub previews: $emptyDecodes empty decodes — giving up on this source")
                        return
                    }

                    // The user may have scrubbed elsewhere while that decode ran — re-plan around the
                    // new anchor instead of finishing a now-irrelevant round.
                    if (abs(lastScrubMs - anchor) > denseIntervalMs) break
                }
                // Small gap between rounds so background decodes never monopolise the CPU a weak TV
                // needs for live video.
                delay(DIRECT_ROUND_GAP_MS)
            }
        } finally {
            releaseWhenIdle(retriever, inFlight)
        }
    }

    /** Open a retriever for a direct source, bounded by [OPEN_TIMEOUT_MS] per attempt. Returns null
     *  (previews simply don't appear) rather than ever blocking the sampler indefinitely. */
    private suspend fun openRetrieverBounded(
        streamUrl: String,
        path: String?,
        hdrs: Map<String, String>,
    ): MediaMetadataRetriever? {
        // Holds whatever the (possibly abandoned) open produced, so a late success can still be freed.
        val slot = AtomicReference<MediaMetadataRetriever?>()
        repeat(OPEN_ATTEMPTS) { attempt ->
            if (!currentCoroutineContext().isActive) return null
            slot.set(null)
            val work = scope.async(Dispatchers.IO) { openRetriever(streamUrl, path, hdrs)?.also(slot::set) }
            val opened = withTimeoutOrNull(OPEN_TIMEOUT_MS) { work.await() }
            if (opened != null) return opened
            if (!work.isCompleted) {
                // setDataSource is wedged on a hostile/slow host: abandon it and release it if it ever
                // returns. Retrying would only stack more blocked threads.
                work.invokeOnCompletion { cause -> if (cause == null) runCatching { slot.get()?.release() } }
                return null
            }
            if (attempt < OPEN_ATTEMPTS - 1) delay(OPEN_RETRY_MS)
        }
        return null
    }

    /** Blocking open. Local path when we have one, otherwise the URL plus the host's headers — a bare
     *  request (no User-Agent/Referer) is what CDNs and RD reject, leaving every slot empty. */
    private fun openRetriever(
        streamUrl: String,
        path: String?,
        hdrs: Map<String, String>,
    ): MediaMetadataRetriever? = runCatching {
        val candidate = MediaMetadataRetriever()
        try {
            if (path != null) candidate.setDataSource(path)
            else candidate.setDataSource(streamUrl, HashMap(hdrs))
            candidate
        } catch (t: Throwable) {
            runCatching { candidate.release() }
            throw t
        }
    }.getOrElse {
        Log.w(TAG, "direct thumbnail retriever open failed: ${it.message}")
        null
    }

    /** Release [retriever] now if idle, else once [inFlight] finishes normally. Releasing while a
     *  native decode is running on it is a hard crash; if that decode was itself cancelled we let GC
     *  reclaim the handle instead (at most one per source, and only after we've already given up). */
    private fun releaseWhenIdle(retriever: MediaMetadataRetriever, inFlight: Deferred<*>?) {
        if (inFlight == null || inFlight.isCompleted) {
            runCatching { retriever.release() }
            return
        }
        inFlight.invokeOnCompletion { cause -> if (cause == null) runCatching { retriever.release() } }
    }

    /** file:// URL (offline download) or bare path → an existing local path, else null. */
    private fun localPathOrNull(streamUrl: String): String? {
        val path = when {
            streamUrl.startsWith("file://") -> runCatching { java.io.File(java.net.URI(streamUrl)).path }.getOrNull()
            streamUrl.startsWith("/") -> streamUrl
            else -> null
        } ?: return null
        return path.takeIf(::fileExists)
    }

    private fun fileExists(path: String): Boolean = runCatching { java.io.File(path).exists() }.getOrDefault(false)

    /** Decode the frame centred at [timeMs], store it (capped + evicting) and bump version. Returns
     *  true if a bitmap was actually added. */
    private fun decodeAndStore(retriever: MediaMetadataRetriever, timeMs: Long): Boolean {
        val bmp = decodeFrame(retriever, timeMs) ?: return false
        return storeFrame(timeMs, bmp)
    }

    /** Cache an already-decoded frame (capped + evicting) and bump version. */
    private fun storeFrame(timeMs: Long, bmp: Bitmap): Boolean {
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
        filePath = null
        infoHash = null
        durationMs = 0L
        fileLength = 0L
        headers = emptyMap()
        denseIntervalMs = 0L
        lastScrubMs = 0L
        scrubbed = false
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

        // --- Direct (Real-Debrid / addon HTTP / offline file) path ---

        /** How far either side of the scrub anchor the dense tier is decoded for a direct source.
         *  Comfortably wider than the widest film-strip (TV shows ±4 one-minute slots), so the strip is
         *  fully covered without paying range requests for the rest of the timeline. */
        const val DENSE_WINDOW_MS = 6 * 60_000L

        /** Wall-clock bound on one remote frame decode (several HTTP range requests). Past this the
         *  retriever is abandoned and previews stop — a stuck decoder must never accumulate. */
        const val REMOTE_DECODE_TIMEOUT_MS = 8_000L

        /** Same bound for an offline file: local, so only a pathological decoder can reach it. */
        const val LOCAL_DECODE_TIMEOUT_MS = 15_000L

        /** Bound on one setDataSource (connect + container probe), and how many times we retry it. */
        const val OPEN_TIMEOUT_MS = 12_000L
        const val OPEN_ATTEMPTS = 3
        const val OPEN_RETRY_MS = 2_000L

        /** Ceiling on remote decodes for one source, so a long scrubbing session can't quietly pull
         *  hundreds of range requests. ~MAX_FRAMES worth of useful frames, no more. */
        const val REMOTE_DECODE_BUDGET = 150

        /** Consecutive decodes that returned nothing before we conclude this source can't be sampled
         *  at all (HLS playlist, audio-only, host refusing ranges) and stop for good. */
        const val MAX_EMPTY_DECODES = 5

        /** Breather between direct sampling rounds. */
        const val DIRECT_ROUND_GAP_MS = 150L

        /** How often the remote path checks whether the user has started scrubbing (it stays off the
         *  network until they do). Short enough that the strip starts filling right away. */
        const val SCRUB_WAIT_POLL_MS = 400L
    }
}
