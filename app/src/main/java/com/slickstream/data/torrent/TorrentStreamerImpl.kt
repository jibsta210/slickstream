package com.slickstream.data.torrent

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.slickstream.core.model.StartupBlocker
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

        // Anchor the bulk fill at the RESUME position (not the file head the user skips past).
        //
        // CONCENTRATION (base the file at IGNORE so only a moving window downloads) is DISABLED for now:
        // it shipped unverified in v1.6.9, and starving everything outside the window is exactly the kind
        // of change that can CAUSE a mid-stream stall. Playback still buffering with the file on disk is
        // being diagnosed with the STALL breadcrumb + slow-read log first; concentration only comes back
        // if the data says scatter (not the read/serve path) is the bottleneck. Param kept so the call
        // sites and the offline path don't churn.
        runCatching { engine.setStreamStart(infoHash, startPositionFraction, concentrate = false) }
        // Matches setStreamStart's own threshold for "this is a from-start play", so the gate charges the
        // playhead requirement exactly when the engine actually moved the anchor off the file head.
        val resumeIsHead = startPositionFraction <= RESUME_ANCHOR_EPSILON

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
            /** Basis the high-water mark above was measured against; the moov probe can narrow it. */
            var lastIndexRequiredBytes = -1L
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
                // Deliberately the WIRE rate, not the payload rate. A non-zero wire rate with zero
                // payload means peers ARE talking to us (handshakes, metadata, choked-but-connected) —
                // forcing a fresh announce there would fire spuriously against a swarm that is fine.
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
                // WHERE the moov is, not just whether it's up front. When the engine can prove the exact
                // extent from the file's own box chain, the requirement shrinks from a blind 8 MB EOF
                // band (16-64 MB once rounded up to 8-32 MB pieces) to the atom itself — and, just as
                // importantly, GROWS to cover a >8 MB moov that used to start outside the guess, pass the
                // gate, and strand the player on "Almost ready…" reading bytes nobody prioritised.
                val moovLoc = if (containerNeedsTail) engine.moovLocation(infoHash, headBytes) else null
                val moovInHead = moovLoc?.inHead
                if (moovInHead == true) engine.cancelStartupTail(infoHash)
                val needsTail = containerNeedsTail && moovInHead != true
                val tailReady = !needsTail || engine.tailAvailable(infoHash)

                // ONE decision, made from what is genuinely on disk: contiguous bytes at the file header,
                // contiguous bytes at the playhead, and the container's real index requirement. No fixed
                // percentage of the file, no wall-clock escape. Everything the user is shown — the ETA, the
                // startup progress bar, the chunk bar's verdict — is rendered from THIS, so the UI cannot
                // disagree with the engine about whether we can start.
                val decision = StartGate.decide(
                    contiguousHeadBytes = headBytes,
                    contiguousResumeBytes = snap.contiguousResumeBytes,
                    resumeIsHead = resumeIsHead,
                    containerMayNeedMoov = containerNeedsTail,
                    moovInHead = moovInHead,
                    tailPresent = tailReady,
                    tailProgressBytes = snap.startupTailProgressBytes,
                    // On the EXACT path the numerator and denominator are finally the same units — both
                    // whole-piece counts over the SAME range — so the bar rises linearly and reaches 1.0
                    // exactly when tailAvailable flips. The fallback path keeps the literal 8 MB so its
                    // arithmetic (and therefore its ETA and bar) is byte-identical to before.
                    tailBytes = if (moovLoc is MoovLocation.AtEofExact && snap.startupIndexRequiredBytes > 0L) {
                        snap.startupIndexRequiredBytes
                    } else {
                        StartGate.MOOV_TAIL_BYTES
                    },
                )
                val headReady = decision.blocker != StartupBlocker.CONTAINER_HEADER &&
                    decision.blocker != StartupBlocker.PLAYHEAD

                // The head is in and the EOF index is now the ONLY thing between the user and a frame:
                // go GET it instead of polling for it. At startup the band was deliberately queued behind
                // the head; those deadlines are stale the moment the head lands.
                if (decision.needsMoovFetch) runCatching { engine.promoteStartupTail(infoHash) }

                if (headReady && needsTail && !tailReady) {
                    // This is a STALL timeout, not a wall-clock cap. Whole-piece availability exposes
                    // no partial progress for a 32 MB EOF piece, but selected-file progress still tells
                    // us the swarm is alive; reset while bytes advance so thin old swarms and offline
                    // MP4 downloads are not killed merely for being slow.
                    // The BASIS can change underneath this high-water mark: when the moov probe commits
                    // an exact extent the band NARROWS, so the progress measured over it legitimately
                    // DROPS (a piece that counted in the blind 8 MB band may sit outside the real moov).
                    // Left alone, `progress > lastProgress` could then never fire again, tailWaitSince
                    // would freeze at its pre-shrink value, and this stall detector would degenerate into
                    // a hard 4-minute WALL-CLOCK cap that kills a perfectly healthy stream — the exact
                    // no-time-escape rule this gate exists to honour. Re-baseline whenever the
                    // requirement changes so we keep measuring "are bytes still arriving", not "how long".
                    if (snap.startupIndexRequiredBytes != lastIndexRequiredBytes) {
                        lastIndexRequiredBytes = snap.startupIndexRequiredBytes
                        lastTailProgressBytes = -1L
                        tailWaitSince = now
                    }
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
                val canStart = decision.canStart

                // ETA to first frame, against the real gate, refreshed each poll.
                val eta = estimateEta(snap, decision)

                // Refresh the chunk bar's cache on EVERY poll. It used to ride on the DIAG_EVERY log
                // counter, i.e. once per 2 s, while the TV overlay reads it on its own 1 s tick — so the
                // bar could sit up to ~3 s behind the "% downloaded" text beside it and look like it
                // disagreed with it. Cost is one bitfield reduction at 2 Hz.
                engine.pieceMap(infoHash, PIECE_MAP_CACHE_BUCKETS)
                    .takeIf { it.isNotEmpty() }
                    ?.let { pieceMapCache[infoHash] = it }

                if (pollCount++ % DIAG_EVERY == 0) {
                    Log.d(
                        TAG,
                        // wire vs payload vs waste, so the deadline posture can be tuned against a
                        // MEASURED discard rate instead of a guess. waste = (failed + redundant) as a
                        // share of accounted traffic; if it is large, that bandwidth came out of the
                        // read-ahead window and is a direct cause of mid-stream rebuffering.
                        "stream=$infoHash wire=${snap.downloadRate}B/s payload=${snap.payloadRate}B/s " +
                            "waste=${(TransferAccounting.wastedFraction(snap.payloadBytes, snap.wastedBytes) * 100f).toInt()}% " +
                            "peers=${snap.peers} seeds=${snap.seeders} head=$headBytes " +
                            "prog=${snap.progress} paused=${snap.isPaused} ready=$emittedReady",
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
                                decision = decision,
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
                    engine.moovLocation(infoHash, headBytes).inHead
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
        decision: StartDecision? = null,
    ): StreamStatus {
        if (snap == null) return baseStatus(source, state, 0f, streamUrl).copy(infoHash = infoHash)
        return StreamStatus(
            infoHash = infoHash,
            state = state,
            progress = snap.progress,
            downloadRateBytes = snap.downloadRate,
            payloadRateBytes = TransferAccounting.displayRate(snap.payloadRate, snap.downloadRate),
            payloadBytes = snap.payloadBytes,
            wastedBytes = snap.wastedBytes,
            uploadRateBytes = snap.uploadRate,
            seeders = snap.seeders,
            peers = snap.peers,
            downloadedBytes = snap.downloadedBytes,
            contiguousHeadBytes = snap.contiguousHeadBytes,
            totalBytes = snap.totalBytes.takeIf { it > 0 } ?: (source.sizeBytes ?: 0L),
            streamUrl = streamUrl,
            etaSeconds = etaSeconds,
            // One source of truth: the watchdog's "don't punish a live moov fetch" hatch and the UI's
            // "say what we're waiting for" label are the SAME gate verdict, not two parallel guesses.
            awaitingStartupTail = decision?.needsMoovFetch == true,
            startupBlocker = decision?.blocker ?: StartupBlocker.NONE,
            startupRequiredBytes = decision?.requiredBytes ?: 0L,
            startupRemainingBytes = decision?.remainingBytes ?: 0L,
            startupFillFraction = decision?.fillFraction ?: 0f,
            isChecking = snap.isChecking,
        )
    }

    /**
     * Seconds until first frame, measured against the SAME gate that flips to READY — literally its
     * [StartDecision.remainingBytes], so the countdown cannot describe a different finish line from the
     * one that actually opens the gate. Counting the EOF index is what makes the number match reality:
     * without it the countdown hit zero on the head and the player then stalled reading the moov.
     * Partial EOF-band progress is credited (see [StartGate.decide]), so the number keeps falling while
     * a single 32 MB tail piece downloads. Null while there's no download rate yet (still finding peers
     * / fetching metadata).
     */
    private fun estimateEta(snap: EngineStatus, decision: StartDecision): Int? {
        // PAYLOAD rate, not the wire rate: remainingBytes is a payload requirement, so dividing it by a
        // wire rate that includes overhead + discarded duplicates made every countdown optimistic by
        // exactly the waste fraction — "starts in ~12s" that took 20. Only the denominator changes; the
        // null-when-no-rate rule and the 1..900 clamp below are untouched, so no gate behaviour moves.
        val rate = TransferAccounting.displayRate(snap.payloadRate, snap.downloadRate)
        if (rate <= 0) return null
        // A closed gate with nothing "remaining" means the byte count has run out of resolution, not
        // that we are done: the EOF requirement is a fixed 8 MB while the credit comes from real blocks
        // of pieces that can be 32 MB, so one part-finished piece pays the whole notional band off.
        // Publishing a number here froze the countdown at its 4 s floor while playback had not begun.
        // Say nothing instead — the blocker label ("fetching MP4 index") already tells the honest story.
        if (!decision.canStart && decision.remainingBytes <= 0L) return null
        return ((decision.remainingBytes / rate) + PREPARE_MARGIN_SECONDS).toInt().takeIf { it in 1..900 }
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

        /** Below this start fraction the engine leaves the anchor at the file head, so head and playhead
         *  are the same region. MUST match [TorrentEngine.setStreamStart]'s own `fraction > 0.001f`. */
        private const val RESUME_ANCHOR_EPSILON = 0.001f

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
