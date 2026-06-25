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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sparse scrub-bar thumbnails for a torrent stream.
 *
 * A torrent downloads sequentially, so only the already-buffered head can be decoded without
 * blocking. To give a preview across the WHOLE timeline anyway, once playback is healthy we ask the
 * engine to sparsely pre-fetch ~[SAMPLE_COUNT] evenly-spaced slices (at a relaxed deadline that never
 * competes with playback), then decode ONE keyframe at each as its bytes land and cache a small
 * bitmap. Scrubbing shows the nearest cached frame, or nothing (timecode only) for a slice that
 * hasn't arrived yet.
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
    @Volatile private var infoHash: String? = null
    @Volatile private var durationMs: Long = 0L
    @Volatile private var fileLength: Long = 0L
    @Volatile private var intervalMs: Long = 0L

    /** sample index -> decoded thumbnail (nulls until decoded). Guarded by [lock]. */
    private val frames = HashMap<Int, Bitmap>()
    private val lock = Any()

    /** Bumped whenever a new thumbnail lands, so the UI can recompose. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    /**
     * Begin sampling for [streamUrl]. Idempotent for the same URL; switching URL resets everything.
     * Call once playback is up and [durationMs] is known (so the container index/moov is present).
     */
    fun start(streamUrl: String, hash: String, durationMs: Long, fileLength: Long) {
        if (streamUrl == url && job?.isActive == true) return
        reset()
        if (durationMs <= 0L || fileLength <= 0L) return
        url = streamUrl
        infoHash = hash
        this.durationMs = durationMs
        this.fileLength = fileLength
        val count = SAMPLE_COUNT
        intervalMs = (durationMs / count).coerceAtLeast(1L)
        job = scope.launch { run(streamUrl, hash, count) }
    }

    /** Nearest decoded thumbnail to [positionMs], or null if that part of the timeline isn't sampled
     *  yet. Searches a couple of neighbouring buckets so a scrub always shows the closest frame. */
    fun thumbnailAt(positionMs: Long): Bitmap? {
        val iv = intervalMs
        if (iv <= 0L) return null
        val center = (positionMs / iv).toInt()
        synchronized(lock) {
            if (frames.isEmpty()) return null
            for (d in 0..NEIGHBOUR_SEARCH) {
                frames[center + d]?.let { return it }
                if (d > 0) frames[center - d]?.let { return it }
            }
        }
        return null
    }

    private suspend fun run(streamUrl: String, hash: String, count: Int) {
        // Approximate byte offset of each sample's keyframe (linear time→byte; refined by the
        // container's own index inside getFrameAtTime). Prefetch them all up front, relaxed.
        val offsets = (0 until count).map { i -> sampleByteOffset(i) }
        runCatching { streamer.prefetchPreviewOffsets(hash, offsets) }

        val retriever = runCatching {
            MediaMetadataRetriever().apply { setDataSource(streamUrl, HashMap<String, String>()) }
        }.getOrNull() ?: run {
            Log.w(TAG, "thumbnail retriever setDataSource failed for $streamUrl")
            return
        }

        try {
            val done = HashSet<Int>()
            var idleRounds = 0
            while (scope.isActive && done.size < count) {
                var progressed = false
                for (i in 0 until count) {
                    if (i in done) continue
                    if (!scope.isActive) break
                    val offset = offsets[i]
                    if (!streamer.isByteAvailable(hash, offset)) continue
                    val bmp = decodeFrame(retriever, i)
                    if (bmp != null) {
                        synchronized(lock) { frames[i] = bmp }
                        done += i
                        progressed = true
                        _version.value = _version.value + 1
                    } else {
                        // Decoded null despite bytes present (rare) — don't hammer it; mark done.
                        done += i
                    }
                }
                // Re-arm deadlines for the slices still missing so libtorrent keeps sipping them.
                if (done.size < count) {
                    val missing = (0 until count).filter { it !in done }.map { offsets[it] }
                    runCatching { streamer.prefetchPreviewOffsets(hash, missing) }
                }
                idleRounds = if (progressed) 0 else idleRounds + 1
                // Back off as the buffered region stops growing; never give up entirely (the user may
                // still be downloading) but don't spin tightly either.
                delay(if (idleRounds > 6) IDLE_POLL_MS else ACTIVE_POLL_MS)
            }
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decodeFrame(retriever: MediaMetadataRetriever, index: Int): Bitmap? {
        val timeUs = (index * intervalMs + intervalMs / 2) * 1000L
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

    private fun sampleByteOffset(index: Int): Long {
        val t = (index * intervalMs + intervalMs / 2).toDouble()
        return ((t / durationMs) * fileLength).toLong().coerceIn(0L, (fileLength - 1).coerceAtLeast(0L))
    }

    private fun reset() {
        job?.cancel()
        job = null
        url = null
        infoHash = null
        durationMs = 0L
        fileLength = 0L
        intervalMs = 0L
        synchronized(lock) {
            frames.values.forEach { runCatching { it.recycle() } }
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
        /** How many points across the timeline we sample. ~24 ≈ one every few minutes for a feature. */
        const val SAMPLE_COUNT = 24
        const val THUMB_HEIGHT_PX = 180
        const val NEIGHBOUR_SEARCH = 2
        const val ACTIVE_POLL_MS = 600L
        const val IDLE_POLL_MS = 2500L
    }
}
