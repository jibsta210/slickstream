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

        cache.touch(infoHash)
        // Make room if we're over budget (never evict the one we just added).
        runCatching { cache.enforceBudget(protectedHash = infoHash) }

        val server = ensureServer()
        val streamUrl = server.urlFor(infoHash)

        // BUFFERING phase.
        trySend(buildStatus(source, infoHash, StreamState.BUFFERING, streamUrl = null))

        // Poll until enough head (+tail) is buffered, then flip to READY and keep emitting.
        val pollJob = launch {
            var pollCount = 0
            var headReadySince = 0L
            while (isActive) {
                val snap = engine.snapshot(infoHash)
                if (snap == null) {
                    delay(POLL_INTERVAL_MS)
                    continue
                }

                // Self-heal: a still-running Details prewarm can race in and pause the torrent we're
                // actively streaming (the >1min "stuck buffering" bug). If we see it paused mid-stream,
                // resume it so the download is never silently killed.
                if (snap.isPaused) runCatching { engine.resume(infoHash) }

                val headBytes = engine.contiguousHeadBytes(infoHash)
                val headReady = headBytes >= READY_HEAD_BYTES
                if (headReady && headReadySince == 0L) headReadySince = System.currentTimeMillis()
                // Prefer having the tail (mp4 moov / mkv cues) so playback starts instantly, but
                // don't block forever if those pieces lag — fall back after a short grace window.
                val tailReady = engine.tailAvailable(infoHash)
                val tailGraceElapsed =
                    headReadySince > 0L && System.currentTimeMillis() - headReadySince > TAIL_GRACE_MS
                val canStart = headReady && (tailReady || tailGraceElapsed)

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
                                streamUrl = streamUrl, snap = snap,
                            ),
                        )
                    }
                    emittedReady -> {
                        trySend(
                            buildStatus(
                                source, infoHash, StreamState.READY,
                                streamUrl = streamUrl, snap = snap,
                            ),
                        )
                    }
                    else -> {
                        trySend(
                            buildStatus(
                                source, infoHash, StreamState.BUFFERING,
                                streamUrl = null, snap = snap,
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
        infoHash
    }

    override fun cachedTorrents(): List<String> = cache.cachedTorrents()

    override suspend fun clearCache() = withContext(Dispatchers.IO) {
        cache.clearCache()
    }

    override fun cacheSizeBytes(): Long = cache.cacheSizeBytes()

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
        )
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

        /** Max wait for the tail (moov/cues) after the head is ready before starting anyway. */
        private const val TAIL_GRACE_MS = 8_000L

        /** Emit a diagnostic log line every Nth poll (~2 s at a 500 ms interval). */
        private const val DIAG_EVERY = 4

        /** Head bytes to pre-buffer when warming the next episode (~2 MB — cheap, just enough). */
        private const val PREFETCH_HEAD_BYTES = 2L * 1024L * 1024L

        /** Hard wall-clock cap on one warm attempt so a dead swarm can't tie up a coroutine. */
        private const val PREFETCH_BUDGET_MS = 45_000L
    }
}
