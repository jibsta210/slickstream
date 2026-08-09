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
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** Active collectors per info-hash. A set was wrong when playback + an offline download shared a
     *  torrent: closing either collector removed protection and paused/evicted bytes under the other. */
    private val streamingRefs = ConcurrentHashMap<String, AtomicInteger>()
    private val acquiringRefs = ConcurrentHashMap<String, AtomicInteger>()
    private val activeSelections = ConcurrentHashMap<String, StreamSelection>()
    private val selectionLocks = ConcurrentHashMap<String, Mutex>()

    /** Piece-map snapshots are produced by the IO poller and consumed by Compose. This keeps synchronous
     *  libtorrent status() calls (and nativeLock waits) off the main/UI thread. */
    private val pieceMapCache = ConcurrentHashMap<String, FloatArray>()

    /** The most recently warmed (next-episode) info-hash. Protected from cache eviction so a long
     *  current episode can't evict the precache before the user advances. Cleared once it streams. */
    @Volatile private var warmedHash: String? = null

    override fun start(
        source: StreamSource,
        startPositionFraction: Float,
        concentrate: Boolean,
    ): Flow<StreamStatus> = callbackFlow {
        sources[source.infoHash] = source

        if (!engine.isAvailable()) {
            trySend(errorStatus(source, "Torrent engine unavailable on this device"))
            close()
            return@callbackFlow
        }

        try {
            onStreamStarted()
        } catch (t: Throwable) {
            trySend(errorStatus(source, "Failed to start torrent service: ${t.message ?: "unknown error"}"))
            close()
            return@callbackFlow
        }

        var emittedReady = false

        // METADATA phase.
        trySend(baseStatus(source, StreamState.METADATA, progress = 0f, streamUrl = null))

        val requestedHash = source.infoHash.lowercase()
        val selection = StreamSelection(source.fileIndex, source.expectedSeason, source.expectedEpisode)
        markAcquiring(requestedHash)
        val infoHash = try {
            selectionLocks.getOrPut(requestedHash) { Mutex() }.withLock {
                var activeSelection = activeSelections[requestedHash]
                if (isStreaming(requestedHash) && activeSelection != null && activeSelection != selection) {
                    // Player source/episode transitions cancel the old collector without joining it.
                    // Give awaitClose a brief chance to release the one mutable pack-file selection;
                    // true concurrent playback/download ownership remains rejected below.
                    repeat(SELECTION_HANDOFF_POLLS) {
                        if (!isStreaming(requestedHash)) return@repeat
                        delay(SELECTION_HANDOFF_POLL_MS)
                    }
                    activeSelection = activeSelections[requestedHash]
                }
                if (isStreaming(requestedHash) && activeSelection != null && activeSelection != selection) {
                    error("A different episode from this season pack is already in use")
                }
                engine.addMagnet(
                    source.magnetUri,
                    source.fileIndex,
                    source.expectedSeason,
                    source.expectedEpisode,
                    acquireStreamingLease = true,
                ).also { canonicalHash ->
                    // Source mapping canonicalizes v1 hashes, so these normally match. Store under the
                    // engine key as the final authority and claim ownership before releasing the lock.
                    activeSelections[canonicalHash] = selection
                    markStreaming(canonicalHash)
                }
            }
        } catch (cancelled: CancellationException) {
            onStreamStopped()
            throw cancelled
        } catch (t: Throwable) {
            Log.e(TAG, "addMagnet failed", t)
            trySend(errorStatus(source, t.message ?: "Failed to load torrent"))
            onStreamStopped()
            close()
            return@callbackFlow
        } finally {
            unmarkAcquiring(requestedHash)
        }

        // Shape the download for streaming: anchor the bulk fill at the RESUME position (not the file
        // head the user skips past), AND — when concentrating — base the file at IGNORE so the swarm
        // pours into the head/read-ahead window instead of scattering across the whole file. Offline
        // downloads pass concentrate=false (they want every piece). Playback can't starve — ensureRange
        // force-raises whatever each read needs regardless of the base.
        runCatching { engine.setStreamStart(infoHash, startPositionFraction, concentrate) }

        // Mark this torrent as actively streaming so a still-running Details prewarm can't pause it.
        // If we're now streaming the torrent we'd warmed for next-episode, it's no longer "the warm".
        if (infoHash == warmedHash) warmedHash = null

        cache.touch(infoHash)
        // Make room if we're over budget — but protect the active stream(s) AND the warmed next-episode
        // torrent. The warm is PAUSED (not active), so without this it was the first thing evicted while
        // the current episode kept downloading, which is why "precache did nothing" after a long episode.
        scope.launch {
            runCatching {
                cache.enforceBudget(streamingHashes() + acquiringHashes() + setOfNotNull(warmedHash))
            }
        }

        val server = try {
            ensureServer()
        } catch (t: Throwable) {
            val stillStreaming = unmarkStreaming(infoHash)
            if (!stillStreaming) activeSelections.remove(infoHash, selection)
            if (!stillStreaming) runCatching { engine.pause(infoHash) }
            onStreamStopped()
            trySend(errorStatus(source, "Failed to start local stream bridge: ${t.message ?: "unknown error"}"))
            close()
            return@callbackFlow
        }
        val streamUrl = server.urlFor(infoHash)

        // Only mp4/m4v/mov CAN need the EOF moov atom before ExoPlayer parses a frame — and even then,
        // only when the moov is at the END (not a "faststart"/web-optimized file whose moov is up front
        // in the head). mkv/webm/avi keep their index near the front and never wait. The faststart check
        // needs head bytes, so it's resolved per-poll below.
        val containerNeedsTail = runCatching { engine.selectedFileExt(infoHash) in MOOV_CONTAINERS }
            .getOrDefault(false)

        // BUFFERING phase.
        trySend(
            buildStatus(
                source,
                infoHash,
                StreamState.BUFFERING,
                streamUrl = null,
                snap = runCatching { engine.snapshot(infoHash) }.getOrNull(),
            ),
        )

        // Poll until enough head (+tail) is buffered, then flip to READY and keep emitting.
        val pollJob = launch {
            var pollCount = 0
            var zeroRateSince = 0L
            var lastReannounce = 0L
            var tailWaitSince = 0L
            var lastTailProgressBytes = -1L
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

                val headBytes = snap.contiguousHeadBytes
                // Container header at the file start (needed to PARSE the container) AND the RESUME region
                // (needed to PLAY from where the user actually starts). For a from-start play the two are
                // the same piece, so resumeReady == headReady and nothing changes; on a resume this also
                // waits for the bytes under the resume point so READY→seek doesn't instantly rebuffer.
                val headReady = headBytes >= READY_HEAD_BYTES && snap.contiguousResumeBytes >= READY_HEAD_BYTES
                // Tail gate is CONTAINER-AWARE: mkv/webm start on head alone; only mp4 waits for the EOF
                // moov (so prepare() never ranges into an absent atom). mp4 needs the moov ONLY when it's
                // not faststart (moov already up front in the head).
                //
                // There is deliberately NO per-tail "grace" hatch any more. The old 15s tail-grace emitted
                // READY before the moov was actually on disk; ExoPlayer then got a file it can't decode and
                // sat in STATE_BUFFERING forever ("100% · Almost ready…" that never plays, or only starts
                // tens-of-% into the download once the moov happens to arrive). The head-only watchdog never
                // caught it because the overall download kept progressing. READY now requires the moov to be
                // GENUINELY present. The VM's stall watchdog advances only when a swarm stops making
                // contiguous progress, so a slow-but-live old torrent remains viable.
                val moovInHead = if (containerNeedsTail) engine.mp4MoovInHead(infoHash, headBytes) else null
                if (moovInHead == true) engine.cancelStartupTail(infoHash)
                val needsTail = containerNeedsTail && moovInHead != true
                val tailReady = !needsTail || engine.tailAvailable(infoHash)
                if (headReady && needsTail && !tailReady) {
                    // This is a STALL timeout, not a wall-clock cap. Whole-piece availability exposes
                    // no partial progress for a 32 MB EOF piece, but selected-file progress still tells
                    // us the swarm is alive; reset while bytes advance so thin old swarms and offline
                    // MP4 downloads are not killed merely for being slow.
                    if (snap.startupTailProgressBytes > lastTailProgressBytes) {
                        lastTailProgressBytes = snap.startupTailProgressBytes
                        tailWaitSince = now
                    } else if (tailWaitSince == 0L) {
                        tailWaitSince = now
                    }
                    if (now - tailWaitSince >= TAIL_STALL_TIMEOUT_MS) {
                        trySend(errorStatus(source, "Required MP4 index pieces are unavailable in this swarm"))
                        close()
                        return@launch
                    }
                } else {
                    tailWaitSince = 0L
                    lastTailProgressBytes = -1L
                }
                // Never hand a known-incomplete startup range to the player. On a weak but live swarm,
                // the old hard cap started HTTP early and the VM failed it before the range wait could
                // finish. The stall watchdog still advances away from truly dead swarms.
                val canStart = headReady && tailReady

                // ETA to first frame, against the real gate (head + tail-when-needed), refreshed each poll.
                val eta = estimateEta(snap, headBytes, tailReady)

                if (pollCount++ % DIAG_EVERY == 0) {
                    engine.pieceMap(infoHash, PIECE_MAP_CACHE_BUCKETS)
                        .takeIf { it.isNotEmpty() }
                        ?.let { pieceMapCache[infoHash] = it }
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
                                awaitingStartupTail = headReady && needsTail && !tailReady,
                            ),
                        )
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }

        awaitClose {
            pollJob.cancel()
            val stillStreaming = unmarkStreaming(infoHash)
            if (!stillStreaming) activeSelections.remove(infoHash, selection)
            if (!stillStreaming) pieceMapCache.remove(infoHash)
            // The flow was cancelled (player left). Pause + persist; keep files in cache.
            scope.launch {
                // A next-episode start can claim this hash after unmarkStreaming() but before this IO
                // cleanup runs. Recheck live ownership so the old generation never pauses the new one.
                if (!stillStreaming && !isStreaming(infoHash)) runCatching { engine.pause(infoHash) }
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
        if (isStreaming(infoHash)) return@withContext
        if (removeFiles) {
            cache.evict(infoHash)
        } else {
            engine.stop(infoHash, removeFiles = false)
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
        if (source.isDirect || source.magnetUri.isBlank()) return@withContext null
        val requestedHash = source.infoHash.lowercase()
        // One ActiveTorrent has one selected file. Re-selecting a different episode from the same pack
        // while this hash is playing mutates the HTTP route underneath the current player.
        if (isStreaming(requestedHash)) return@withContext null
        markAcquiring(requestedHash)
        // Deliberately NO onStreamStarted()/ensureServer() — warming must not spin up the
        // foreground service or the HTTP bridge. It is a quiet background head-fetch.
        val acquiredHash = try {
            selectionLocks.getOrPut(requestedHash) { Mutex() }.withLock {
                // Re-check under the same lock used by start(); the player may have claimed this hash
                // after the optimistic check above.
                if (isStreaming(requestedHash)) return@withLock null
                engine.addMagnet(
                    source.magnetUri,
                    source.fileIndex,
                    source.expectedSeason,
                    source.expectedEpisode,
                )
            }
        } catch (cancelled: CancellationException) {
            unmarkAcquiring(requestedHash)
            throw cancelled
        } catch (t: Throwable) {
            unmarkAcquiring(requestedHash)
            Log.w(TAG, "prefetch addMagnet failed for ${source.infoHash}", t)
            return@withContext null
        }
        if (acquiredHash == null) {
            unmarkAcquiring(requestedHash)
            return@withContext null
        }
        val infoHash = acquiredHash
        // Publish protection before any budget walk. acquiringRefs remains held for the entire warm,
        // closing the gap where memory pressure could evict the just-created payload mid-prefetch.
        warmedHash = infoHash

        var completedWarm = false
        try {
            cache.touch(infoHash)
            // Make room if over budget, but never evict the playing torrent OR the one we're warming.
            runCatching { cache.enforceBudget(protectedHashes + infoHash) }

            // Buffer a small head AND the moov tail (for non-faststart mp4) so first-frame is INSTANT
            // when the user advances. Re-check the container each tick because metadata may have only
            // just arrived when addMagnet returns.
            val deadline = System.currentTimeMillis() + PREFETCH_BUDGET_MS
            while (currentCoroutineContext().isActive && System.currentTimeMillis() < deadline) {
                val headBytes = engine.contiguousHeadBytes(infoHash)
                val headReady = headBytes >= PREFETCH_HEAD_BYTES
                val moovInHead = if (engine.selectedFileExt(infoHash) in MOOV_CONTAINERS) {
                    engine.mp4MoovInHead(infoHash, headBytes)
                } else null
                if (moovInHead == true) engine.cancelStartupTail(infoHash)
                val needsTail = engine.selectedFileExt(infoHash) in MOOV_CONTAINERS && moovInHead != true
                val tailReady = !needsTail || engine.tailAvailable(infoHash)
                if (headReady && tailReady) break
                delay(POLL_INTERVAL_MS)
            }
            completedWarm = true
            // Keep it protected unless a player claimed it during the warm.
            val nowStreaming = isStreaming(infoHash)
            warmedHash = infoHash.takeUnless { nowStreaming }
            infoHash
        } finally {
            if (!isStreaming(infoHash)) runCatching { engine.pause(infoHash) }
            cache.touch(infoHash)
            if (!completedWarm && warmedHash == infoHash) warmedHash = null
            unmarkAcquiring(requestedHash)
        }
    }

    override fun cachedTorrents(): List<String> = cache.cachedTorrents()

    override suspend fun clearCache() = withContext(Dispatchers.IO) {
        // Never delete files out from under an active stream — its live handle would keep claiming
        // pieces exist and the HTTP server would serve zeros from a recreated sparse file.
        cache.clearCache(
            protectedHashes = streamingHashes() + acquiringHashes() + setOfNotNull(warmedHash),
        )
    }

    override fun onMemoryPressure(maxBytes: Long) {
        // Fire-and-forget onto IO: onTrimMemory arrives on the main thread, and enforceBudget walks
        // the whole multi-GB cache tree + removes torrents from the native session — an ANR if run
        // inline. Uses the SET overload (the single-hash one filtered on engine.isActive(), which
        // excludes every paused-in-session torrent and made pressure eviction a no-op).
        scope.launch {
            runCatching {
                cache.enforceBudget(
                    streamingHashes() + acquiringHashes() + setOfNotNull(warmedHash),
                    maxBytes = maxBytes,
                )
            }
        }
    }

    override fun cacheSizeBytes(): Long = cache.cacheSizeBytes()

    override fun isStreaming(infoHash: String): Boolean = (streamingRefs[infoHash]?.get() ?: 0) > 0

    override fun fileLength(infoHash: String): Long = engine.fileLength(infoHash)

    override fun filePath(infoHash: String): String? = engine.filePath(infoHash)

    override fun prefetchPreviewOffsets(infoHash: String, offsets: List<Long>) =
        engine.prefetchByteOffsets(infoHash, offsets)

    override fun isByteAvailable(infoHash: String, byteOffset: Long): Boolean =
        engine.isByteAvailable(infoHash, byteOffset)

    override fun availableByteOffsets(infoHash: String, byteOffsets: List<Long>): BooleanArray =
        engine.availableByteOffsets(infoHash, byteOffsets)

    override fun pieceMap(infoHash: String, buckets: Int): FloatArray {
        if (buckets <= 0) return FloatArray(0)
        val raw = pieceMapCache[infoHash] ?: return FloatArray(0)
        if (raw.size == buckets) return raw.copyOf()
        return FloatArray(buckets) { bucket ->
            val from = (bucket.toLong() * raw.size / buckets).toInt()
            val to = (((bucket + 1L) * raw.size / buckets).toInt()).coerceAtLeast(from + 1)
            var sum = 0f
            var count = 0
            for (i in from until minOf(to, raw.size)) {
                sum += raw[i]
                count++
            }
            if (count == 0) 0f else sum / count
        }
    }

    // --- internals -------------------------------------------------------------------------

    private data class StreamSelection(
        val fileIndex: Int?,
        val season: Int?,
        val episode: Int?,
    )

    private fun markStreaming(infoHash: String) {
        streamingRefs.compute(infoHash) { _, count ->
            (count ?: AtomicInteger()).also { it.incrementAndGet() }
        }
    }

    /** Returns true when another collector still owns this hash. */
    private fun unmarkStreaming(infoHash: String): Boolean {
        var remaining = 0
        var released = false
        streamingRefs.computeIfPresent(infoHash) { _, count ->
            released = true
            remaining = count.decrementAndGet().coerceAtLeast(0)
            count.takeIf { remaining > 0 }
        }
        if (released) engine.releaseStreamingLease(infoHash)
        return remaining > 0
    }

    private fun streamingHashes(): Set<String> = streamingRefs.entries
        .asSequence()
        .filter { it.value.get() > 0 }
        .map { it.key }
        .toSet()

    private fun markAcquiring(infoHash: String) {
        acquiringRefs.compute(infoHash) { _, count ->
            (count ?: AtomicInteger()).also { it.incrementAndGet() }
        }
    }

    private fun unmarkAcquiring(infoHash: String) {
        acquiringRefs.computeIfPresent(infoHash) { _, count ->
            count.takeIf { it.decrementAndGet() > 0 }
        }
    }

    private fun acquiringHashes(): Set<String> = acquiringRefs.entries
        .asSequence()
        .filter { it.value.get() > 0 }
        .map { it.key }
        .toSet()

    @Synchronized
    private fun ensureServer(): StreamHttpServer {
        httpServer?.let { return it }
        val server = StreamHttpServer(engine)
        // Bound idle keep-alive/header reads. Active response-body streaming is not cut off by this.
        server.start(HTTP_SOCKET_READ_TIMEOUT_MS, false)
        httpServer = server
        Log.i(TAG, "Stream HTTP server up at ${server.baseUrl}")
        return server
    }

    @Synchronized
    private fun onStreamStarted() {
        if (activeStreams.getAndIncrement() == 0) {
            try {
                startForegroundService()
            } catch (t: Throwable) {
                activeStreams.decrementAndGet()
                throw t
            }
        }
    }

    @Synchronized
    private fun onStreamStopped() {
        val remaining = activeStreams.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        if (remaining == 0) {
            stopForegroundService()
            shutdownServerIfIdle()
        }
    }

    @Synchronized
    private fun shutdownServerIfIdle() {
        if (activeStreams.get() == 0) {
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
        contiguousHeadBytes = 0,
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
        awaitingStartupTail: Boolean = false,
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
            contiguousHeadBytes = snap.contiguousHeadBytes,
            totalBytes = snap.totalBytes.takeIf { it > 0 } ?: (source.sizeBytes ?: 0L),
            streamUrl = streamUrl,
            etaSeconds = etaSeconds,
            awaitingStartupTail = awaitingStartupTail,
            isChecking = snap.isChecking,
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
        val headRemaining = (READY_HEAD_BYTES - headBytes).coerceAtLeast(0L)
        // Overall torrent progress includes scattered/preview/tail pieces and previously made this hit
        // zero while the contiguous head was still absent. Only credit the bytes tied to the real gate.
        val remaining = headRemaining + tailNeeded
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
        contiguousHeadBytes = 0,
        totalBytes = source.sizeBytes ?: 0L,
        streamUrl = null,
        errorMessage = message,
    )

    companion object {
        private const val TAG = "TorrentStreamer"
        private const val POLL_INTERVAL_MS = 500L
        /** Header/idle timeout only; active response-body streaming is not capped by this value. */
        private const val HTTP_SOCKET_READ_TIMEOUT_MS = 10_000
        private const val SELECTION_HANDOFF_POLLS = 20
        private const val SELECTION_HANDOFF_POLL_MS = 50L

        /** Contiguous head bytes required before we declare READY (~2 MB — enough to start). */
        private const val READY_HEAD_BYTES = 2L * 1024L * 1024L

        /** Approx moov/tail bytes an mp4/m4v/mov must also have before its first frame — folded into
         *  the ETA so the countdown reflects the moov wait, not just the head. Matches the engine's
         *  TAIL_PRIORITY_BYTES (8 MB). */
        private const val MOOV_TAIL_BYTES = 8L * 1024L * 1024L

        /** Fixed seconds added to the byte ETA for the player's own prepare/first-frame after the
         *  bytes are present (container parse, decoder init, initial buffer fill). */
        private const val PREPARE_MARGIN_SECONDS = 4L

        /** Containers whose index (moov atom) lives at EOF and MUST be present before the first frame.
         *  Everything else (mkv/webm/avi…) starts on the head alone. */
        private val MOOV_CONTAINERS = setOf("mp4", "m4v", "mov")

        /** Emit a diagnostic log line every Nth poll (~2 s at a 500 ms interval). */
        private const val DIAG_EVERY = 4
        private const val PIECE_MAP_CACHE_BUCKETS = 256

        /** After this long at 0 B/s on an incomplete torrent, force a re-announce to re-find peers. */
        private const val STALL_REANNOUNCE_AFTER_MS = 5_000L

        /** Don't force a re-announce more often than this (trackers rate-limit / ban abusive clients). */
        private const val REANNOUNCE_INTERVAL_MS = 20_000L
        /** A peer set can have the sequential head but not the EOF moov. Avoid an infinite pre-player wait,
         *  while still allowing a 32MB tail piece several minutes on a genuinely slow old swarm. */
        private const val TAIL_STALL_TIMEOUT_MS = 4L * 60_000L

        /** Head bytes to pre-buffer when warming the next episode (~2 MB — cheap, just enough). */
        private const val PREFETCH_HEAD_BYTES = 8L * 1024L * 1024L

        /** Hard wall-clock cap on one warm attempt so a dead swarm can't tie up a coroutine. */
        private const val PREFETCH_BUDGET_MS = 90_000L
    }
}
