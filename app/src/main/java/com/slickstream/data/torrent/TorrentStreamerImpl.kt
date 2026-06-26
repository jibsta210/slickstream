package com.slickstream.data.torrent

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.slickstream.core.model.StreamSource
import com.slickstream.core.model.StreamState
import com.slickstream.core.model.StreamStatus
import com.slickstream.core.repository.TorrentStreamer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single application-wide [TorrentStreamer]. Owns the [TorrentEngine] and a lazily-started
 * [StreamHttpServer], and emits a [StreamStatus] flow per stream:
 *
 *   METADATA -> BUFFERING -> READY (with streamUrl) -> progress... -> COMPLETED
 *
 * Starts [TorrentStreamService] (foreground) on the first active stream and stops it when the
 * last stream ends. Stopping with removeFiles=false keeps the partial download in the LRU cache.
 */
@Singleton
class TorrentStreamerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: TorrentEngine,
    private val cache: TorrentCacheManager,
) : TorrentStreamer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var httpServer: StreamHttpServer? = null

    /** Number of flows currently collecting (drives the foreground service lifecycle). */
    private val activeStreams = AtomicInteger(0)

    /** infoHash -> source, so resume()/pause() can re-derive parameters if needed. */
    private val sources = ConcurrentHashMap<String, StreamSource>()

    /** infoHashes currently being STREAMED by a player — a prefetch must never pause these. */
    private val streamingHashes = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** The most recently warmed (next-episode) info-hash. Protected from cache eviction so a long
     *  current episode can't evict the precache before the user advances. Cleared once it streams. */
    @Volatile private var warmedHash: String? = null

    override fun start(source: StreamSource): Flow<StreamStatus> = callbackFlow {
        sources[source.infoHash] = source

        if (!engine.isAvailable()) {
            trySend(errorStatus(source, "Torrent engine unavailable on this device"))
            close()
            return@callbackFlow
        }

        onStreamStarted()

        var emittedReady = false

        // METADATA phase.
        trySend(baseStatus(source, StreamState.METADATA, progress = 0f, streamUrl = null))

        val infoHash = try {
            engine.addMagnet(source.magnetUri, source.fileIndex)
        } catch (t: Throwable) {
            Log.e(TAG, "addMagnet failed", t)
            trySend(errorStatus(source, t.message ?: "Failed to load torrent"))
            onStreamStopped()
            close()
            return@callbackFlow
        }

        // Mark this torrent as actively streaming so a still-running Details prewarm can't pause it.
        streamingHashes.add(infoHash)
        // If we're now streaming the torrent we'd warmed for next-episode, it's no longer "the warm".
        if (infoHash == warmedHash) warmedHash = null

        cache.touch(infoHash)
        // Make room if we're over budget — but protect the active stream(s) AND the warmed next-episode
        // torrent. The warm is PAUSED (not active), so without this it was the first thing evicted while
        // the current episode kept downloading, which is why "precache did nothing" after a long episode.
        runCatching { cache.enforceBudget(streamingHashes + setOfNotNull(infoHash, warmedHash)) }

        val server = ensureServer()
        val streamUrl = server.urlFor(infoHash)

        // Only mp4/m4v/mov CAN need the EOF moov atom before ExoPlayer parses a frame — and even then,
        // only when the moov is at the END (not a "faststart"/web-optimized file whose moov is up front
        // in the head). mkv/webm/avi keep their index near the front and never wait. The faststart check
        // needs head bytes, so it's resolved per-poll below.
        val containerNeedsTail = engine.selectedFileExt(infoHash) in MOOV_CONTAINERS

        // BUFFERING phase.
        trySend(buildStatus(source, infoHash, StreamState.BUFFERING, streamUrl = null))

        // Poll until enough head (+tail) is buffered, then flip to READY and keep emitting.
        val pollJob = launch {
            var pollCount = 0
            var zeroRateSince = 0L
            var lastReannounce = 0L
            val streamStartMs = System.currentTimeMillis()
            while (isActive) {
                val snap = engine.snapshot(infoHash)
                if (snap == null) {
                    delay(POLL_INTERVAL_MS)
                    continue
                }

                // Self-heal: a still-running Details prewarm can race in and pause the torrent we're
                // actively streaming (the >1min "stuck buffering" bug). If we see it paused mid-stream,
                // resume it so the download is never silently killed — UNLESS it's already 100% complete,
                // in which case it's paused on purpose (below) to stop all up/down, and must stay paused.
                if (snap.isPaused && !snap.isFinished) runCatching { engine.resume(infoHash) }

                // Stall recovery: if the download has been at 0 B/s for a few seconds while the file
                // isn't complete (its peers dropped — the "buffers mid-stream, 0 KB/s, never finishes"
                // case), force a fresh tracker + DHT announce to re-find peers instead of sitting dead
                // until libtorrent's next scheduled announce (which can be ~30 min away). Rate-limited.
                val now = System.currentTimeMillis()
                if (!snap.isFinished && !snap.isPaused && snap.downloadRate == 0) {
                    if (zeroRateSince == 0L) zeroRateSince = now
                    if (now - zeroRateSince >= STALL_REANNOUNCE_AFTER_MS &&
                        now - lastReannounce >= REANNOUNCE_INTERVAL_MS
                    ) {
                        runCatching { engine.reannounce(infoHash) }
                        lastReannounce = now
                    }
                } else {
                    zeroRateSince = 0L
                }

                val headBytes = engine.contiguousHeadBytes(infoHash)
                val headReady = headBytes >= READY_HEAD_BYTES
                // Tail gate is CONTAINER-AWARE: mkv/webm start on head alone; only mp4 waits for the EOF
                // moov (so prepare() never ranges into an absent atom). mp4 needs the moov ONLY when it's
                // not faststart (moov already up front in the head).
                //
                // There is deliberately NO per-tail "grace" hatch any more. The old 15s tail-grace emitted
                // READY before the moov was actually on disk; ExoPlayer then got a file it can't decode and
                // sat in STATE_BUFFERING forever ("100% · Almost ready…" that never plays, or only starts
                // tens-of-% into the download once the moov happens to arrive). The head-only watchdog never
                // caught it because the overall download kept progressing. READY now requires the moov to be
                // GENUINELY present — OR the head-independent overall hard cap as a true last resort, which
                // the VM's startup-failover ceiling then backs up (bail to another source if the player
                // still can't reach first frame).
                val needsTail = containerNeedsTail && engine.mp4MoovInHead(infoHash) != true
                val tailReady = !needsTail || engine.tailAvailable(infoHash)
                val overallGrace = now - streamStartMs > OVERALL_HARD_CAP_MS
                val canStart = (headReady && tailReady) || (overallGrace && headBytes > 0L)

                // ETA to first frame, against the real gate (head + tail-when-needed), refreshed each poll.
                val eta = estimateEta(snap, headBytes, tailReady)

                if (pollCount++ % DIAG_EVERY == 0) {
                    Log.d(
                        TAG,
                        "stream=$infoHash rate=${snap.downloadRate}B/s peers=${snap.peers} " +
                            "seeds=${snap.seeders} head=$headBytes prog=${snap.progress} " +
                            "paused=${snap.isPaused} ready=$emittedReady",
                    )
                }

                when {
                    snap.isFinished -> {
                        // 100% downloaded: stop ALL network activity on this file — nothing left to
                        // download, and (the point) no more seeding/upload. The HTTP server keeps serving
                        // playback straight off the finished file on disk, so this is invisible to the
                        // viewer; it just stops eating up/down bandwidth, freeing it for the next-episode
                        // precache. Idempotent — pausing an already-paused torrent is a no-op.
                        if (!snap.isPaused) runCatching { engine.pause(infoHash) }
                        trySend(
                            buildStatus(
                                source, infoHash, StreamState.COMPLETED,
                                streamUrl = streamUrl, snap = snap,
                            ),
                        )
                    }
                    !emittedReady && canStart -> {
                        emittedReady = true
                        cache.touch(infoHash)
                        trySend(
                            buildStatus(
                                source, infoHash, StreamState.READY,
                                streamUrl = streamUrl, snap = snap, etaSeconds = eta,
                            ),
                        )
                    }
                    emittedReady -> {
                        trySend(
                            buildStatus(
                                source, infoHash, StreamState.READY,
                                streamUrl = streamUrl, snap = snap, etaSeconds = eta,
                            ),
                        )
                    }
                    else -> {
                        trySend(
                            buildStatus(
                                source, infoHash, StreamState.BUFFERING,
                                streamUrl = null, snap = snap, etaSeconds = eta,
                            ),
                        )
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }

        awaitClose {
            pollJob.cancel()
            streamingHashes.remove(infoHash)
            // The flow was cancelled (player left). Pause + persist; keep files in cache.
            scope.launch {
                runCatching { engine.pause(infoHash) }
                cache.touch(infoHash)
                onStreamStopped()
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun pause(infoHash: String) = withContext(Dispatchers.IO) {
        engine.pause(infoHash)
        cache.touch(infoHash)
    }

    override suspend fun resume(infoHash: String) = withContext(Dispatchers.IO) {
        engine.resume(infoHash)
        cache.touch(infoHash)
    }

    override suspend fun stop(infoHash: String, removeFiles: Boolean) = withContext(Dispatchers.IO) {
        engine.stop(infoHash, removeFiles)
        if (removeFiles) {
            cache.evict(infoHash)
        } else {
            cache.touch(infoHash)
        }
        sources.remove(infoHash)
        Unit
    }

    override suspend fun prefetch(
        source: StreamSource,
        protectedHashes: Set<String>,
    ): String? = withContext(Dispatchers.IO) {
        if (!engine.isAvailable()) return@withContext null
        // Deliberately NO onStreamStarted()/ensureServer() — warming must not spin up the
        // foreground service or the HTTP bridge. It is a quiet background head-fetch.
        val infoHash = try {
            engine.addMagnet(source.magnetUri, source.fileIndex)
        } catch (t: Throwable) {
            Log.w(TAG, "prefetch addMagnet failed for ${source.infoHash}", t)
            return@withContext null
        }

        cache.touch(infoHash)
        // Make room if over budget, but never evict the playing torrent OR the one we're warming.
        runCatching { cache.enforceBudget(protectedHashes + infoHash) }

        // Buffer a small head so first-frame is instant later, then park the torrent.
        val deadline = System.currentTimeMillis() + PREFETCH_BUDGET_MS
        while (currentCoroutineContext().isActive && System.currentTimeMillis() < deadline) {
            if (engine.contiguousHeadBytes(infoHash) >= PREFETCH_HEAD_BYTES) break
            delay(POLL_INTERVAL_MS)
        }
        // Park the warmed torrent — UNLESS a player has meanwhile started streaming it (Play pressed
        // during the warm), or we'd pause the live stream out from under the player.
        if (infoHash !in streamingHashes) runCatching { engine.pause(infoHash) }
        cache.touch(infoHash)
        warmedHash = infoHash   // protect it from eviction until the user advances to it
        infoHash
    }

    override fun cachedTorrents(): List<String> = cache.cachedTorrents()

    override suspend fun clearCache() = withContext(Dispatchers.IO) {
        cache.clearCache()
    }

    override fun cacheSizeBytes(): Long = cache.cacheSizeBytes()

    override fun fileLength(infoHash: String): Long = engine.fileLength(infoHash)

    override fun filePath(infoHash: String): String? = engine.filePath(infoHash)

    override fun prefetchPreviewOffsets(infoHash: String, offsets: List<Long>) =
        engine.prefetchByteOffsets(infoHash, offsets)

    override fun isByteAvailable(infoHash: String, byteOffset: Long): Boolean =
        engine.isByteAvailable(infoHash, byteOffset)

    override fun pieceMap(infoHash: String, buckets: Int): FloatArray =
        engine.pieceMap(infoHash, buckets)

    // --- internals -------------------------------------------------------------------------

    @Synchronized
    private fun ensureServer(): StreamHttpServer {
        httpServer?.let { return it }
        val server = StreamHttpServer(engine)
        // SOCKET_READ_TIMEOUT 0 = no timeout (long-lived video streaming connections).
        server.start(0, false)
        httpServer = server
        Log.i(TAG, "Stream HTTP server up at ${server.baseUrl}")
        return server
    }

    private fun onStreamStarted() {
        if (activeStreams.getAndIncrement() == 0) {
            startForegroundService()
        }
    }

    private fun onStreamStopped() {
        if (activeStreams.decrementAndGet() <= 0) {
            activeStreams.set(0)
            stopForegroundService()
            shutdownServerIfIdle()
        }
    }

    @Synchronized
    private fun shutdownServerIfIdle() {
        if (engine.activeCount() == 0) {
            httpServer?.let { runCatching { it.stop() } }
            httpServer = null
        }
    }

    private fun startForegroundService() {
        val intent = Intent(context, TorrentStreamService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun stopForegroundService() {
        context.stopService(Intent(context, TorrentStreamService::class.java))
    }

    // --- status builders -------------------------------------------------------------------

    private fun baseStatus(
        source: StreamSource,
        state: StreamState,
        progress: Float,
        streamUrl: String?,
    ) = StreamStatus(
        infoHash = source.infoHash,
        state = state,
        progress = progress,
        downloadRateBytes = 0,
        uploadRateBytes = 0,
        seeders = source.seeders ?: 0,
        peers = 0,
        downloadedBytes = 0,
        totalBytes = source.sizeBytes ?: 0L,
        streamUrl = streamUrl,
    )

    private fun buildStatus(
        source: StreamSource,
        infoHash: String,
        state: StreamState,
        streamUrl: String?,
        snap: EngineStatus? = engine.snapshot(infoHash),
        etaSeconds: Int? = null,
    ): StreamStatus {
        if (snap == null) return baseStatus(source, state, 0f, streamUrl).copy(infoHash = infoHash)
        return StreamStatus(
            infoHash = infoHash,
            state = state,
            progress = snap.progress,
            downloadRateBytes = snap.downloadRate,
            uploadRateBytes = snap.uploadRate,
            seeders = snap.seeders,
            peers = snap.peers,
            downloadedBytes = snap.downloadedBytes,
            totalBytes = snap.totalBytes.takeIf { it > 0 } ?: (source.sizeBytes ?: 0L),
            streamUrl = streamUrl,
            etaSeconds = etaSeconds,
        )
    }

    /**
     * Seconds until first frame, measured against the SAME gate that flips to READY: the contiguous
     * head reaching [READY_HEAD_BYTES] PLUS the EOF tail (moov/cues), which the player needs to prepare.
     * Counting the tail is what makes the countdown match reality — without it the number hit zero on the
     * head and the player then stalled ~15s reading the tail. Whole-file downloaded bytes (which counts
     * the head AND the deadline-fetched tail pieces) is the credit, so the number keeps falling as the
     * tail arrives. Null while there's no download rate yet (still finding peers / fetching metadata).
     */
    private fun estimateEta(snap: EngineStatus, headBytes: Long, tailReady: Boolean): Int? {
        val rate = snap.downloadRate
        if (rate <= 0) return null
        val tailNeeded = if (tailReady) 0L else MOOV_TAIL_BYTES
        val target = READY_HEAD_BYTES + tailNeeded
        val headRemaining = (READY_HEAD_BYTES - headBytes).coerceAtLeast(0L)
        val remaining = (target - snap.downloadedBytes).coerceAtLeast(headRemaining)
        return ((remaining / rate) + PREPARE_MARGIN_SECONDS).toInt().takeIf { it in 1..900 }
    }

    private fun errorStatus(source: StreamSource, message: String) = StreamStatus(
        infoHash = source.infoHash,
        state = StreamState.ERROR,
        progress = 0f,
        downloadRateBytes = 0,
        seeders = source.seeders ?: 0,
        peers = 0,
        downloadedBytes = 0,
        totalBytes = source.sizeBytes ?: 0L,
        streamUrl = null,
        errorMessage = message,
    )

    companion object {
        private const val TAG = "TorrentStreamer"
        private const val POLL_INTERVAL_MS = 500L

        /** Contiguous head bytes required before we declare READY (~2 MB — enough to start). */
        private const val READY_HEAD_BYTES = 2L * 1024L * 1024L

        /** Approx moov/tail bytes an mp4/m4v/mov must also have before its first frame — folded into
         *  the ETA so the countdown reflects the moov wait, not just the head. Matches the engine's
         *  TAIL_PRIORITY_BYTES (8 MB). */
        private const val MOOV_TAIL_BYTES = 8L * 1024L * 1024L

        /** Fixed seconds added to the byte ETA for the player's own prepare/first-frame after the
         *  bytes are present (container parse, decoder init, initial buffer fill). */
        private const val PREPARE_MARGIN_SECONDS = 4L

        /** Head-INDEPENDENT hard cap: the gate can never sit BUFFERING longer than this, even if the
         *  contiguous head is pinned below READY by a slow early piece (the "fast download, never plays"
         *  trap). After it, we start with whatever head exists and let player retries + failover work. */
        private const val OVERALL_HARD_CAP_MS = 25_000L

        /** Containers whose index (moov atom) lives at EOF and MUST be present before the first frame.
         *  Everything else (mkv/webm/avi…) starts on the head alone. */
        private val MOOV_CONTAINERS = setOf("mp4", "m4v", "mov")

        /** Emit a diagnostic log line every Nth poll (~2 s at a 500 ms interval). */
        private const val DIAG_EVERY = 4

        /** After this long at 0 B/s on an incomplete torrent, force a re-announce to re-find peers. */
        private const val STALL_REANNOUNCE_AFTER_MS = 5_000L

        /** Don't force a re-announce more often than this (trackers rate-limit / ban abusive clients). */
        private const val REANNOUNCE_INTERVAL_MS = 20_000L

        /** Head bytes to pre-buffer when warming the next episode (~2 MB — cheap, just enough). */
        private const val PREFETCH_HEAD_BYTES = 8L * 1024L * 1024L

        /** Hard wall-clock cap on one warm attempt so a dead swarm can't tie up a coroutine. */
        private const val PREFETCH_BUDGET_MS = 90_000L
    }
}
