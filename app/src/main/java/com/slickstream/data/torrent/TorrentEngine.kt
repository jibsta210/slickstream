package com.slickstream.data.torrent

import android.content.Context
import android.util.Log
import com.slickstream.core.common.DeviceProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.SaveResumeDataAlert
import org.libtorrent4j.alerts.SaveResumeDataFailedAlert
import org.libtorrent4j.alerts.TorrentRemovedAlert
import org.libtorrent4j.swig.session_handle
import org.libtorrent4j.swig.settings_pack
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single shared libtorrent4j session. Owns the [SessionManager], adds magnet links,
 * configures sequential download and prioritizes the largest video file in a torrent,
 * and exposes a snapshot of live status plus the on-disk path of the selected file.
 *
 * All blocking libtorrent calls are expected to be invoked from a background dispatcher
 * (the streamer calls into here on [kotlinx.coroutines.Dispatchers.IO]).
 */
@Singleton
class TorrentEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceProfile: DeviceProfile,
) {

    private val session = SessionManager()
    private val started = AtomicBoolean(false)

    /** Sliding look-ahead band size (smaller on low-power devices). Set in [ensureStarted]. */
    @Volatile
    private var readaheadBytes: Int = READAHEAD_BYTES

    /** infoHash (lowercase hex) -> handle bookkeeping. */
    private val torrents = ConcurrentHashMap<String, ActiveTorrent>()

    /** infoHashes awaiting their SAVE_RESUME_DATA alert before being removed from the session. */
    private val pendingRemoval = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Root directory all torrents download into. */
    val savePath: File by lazy {
        File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
    }

    private val resumeDir: File by lazy {
        File(savePath, ".resume").apply { mkdirs() }
    }

    /** Cached bencoded torrent metadata, keyed by info-hash, so re-opens skip the network fetch. */
    private val metadataDir: File by lazy {
        File(savePath, ".meta").apply { mkdirs() }
    }

    /** Persisted libtorrent session state (DHT routing table) for fast cold-start metadata. */
    private val sessionStateFile: File by lazy { File(savePath, ".session_state") }

    /** Bookkeeping for a single active torrent. */
    private data class ActiveTorrent(
        val infoHash: String,
        @Volatile var handle: TorrentHandle? = null,
        @Volatile var fileIndex: Int = -1,
        @Volatile var fileLength: Long = 0L,
        @Volatile var filePath: String? = null,
        @Volatile var pieceLength: Int = 0,
        @Volatile var firstPiece: Int = 0,
        @Volatile var lastPiece: Int = 0,
        @Volatile var fileOffset: Long = 0L,
        @Volatile var readHeadPiece: Int = -1,
    )

    @Synchronized
    fun ensureStarted() {
        if (started.get()) return
        // Android TV boxes / low-RAM devices choke on aggressive torrenting (hundreds of peer
        // connections + piece hashing + decode saturate a weak SoC and freeze the UI). Throttle.
        val lowPower = deviceProfile.isLowPower
        readaheadBytes = if (lowPower) LOW_POWER_READAHEAD_BYTES else READAHEAD_BYTES
        val sp = SettingsPack().apply {
            // --- Peer load (the freeze comes from too many sockets + per-peer crypto on a weak SoC) ---
            // 20 peers still saturates any single video bitrate; 400 stays for capable devices.
            setInteger(settings_pack.int_types.connections_limit.swigValue(), if (lowPower) 20 else 400)
            setInteger(settings_pack.int_types.active_downloads.swigValue(), if (lowPower) 4 else 8)
            setInteger(settings_pack.int_types.active_seeds.swigValue(), if (lowPower) 4 else 8)
            setInteger(settings_pack.int_types.active_limit.swigValue(), if (lowPower) 8 else 16)
            // Flatten the connection burst/ramp on TV — the 30-conn boost spike landed right at the
            // buffering moment the device froze. 8/12 still gives the sequential head ample peers.
            setInteger(settings_pack.int_types.torrent_connect_boost.swigValue(), if (lowPower) 8 else 200)
            setInteger(settings_pack.int_types.connection_speed.swigValue(), if (lowPower) 12 else 500)
            // Announce to only the first working tier on low-power (less inbound peer flood to crypt).
            setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), !lowPower)
            setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), !lowPower)
            // Keep DHT (needed for poorly-seeded discovery), but drop LSD/UPnP/NAT-PMP background
            // network+CPU work a pure leech/stream TV client never needs.
            setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
            setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), !lowPower)
            setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), !lowPower)
            setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), !lowPower)
            setInteger(settings_pack.int_types.upload_rate_limit.swigValue(), 0)
            // THE biggest freeze lever: an 8 MB/s download cap on low-power bounds SHA-1 hashing
            // throughput + eMMC write pressure + ExoPlayer buffer-fill rate all at once. 8 (not 4)
            // MB/s keeps the 6 MB head + 1 MB moov startup fuel arriving in <1s (preserves the
            // sequential-download latency fix). Capable devices stay uncapped.
            setInteger(
                settings_pack.int_types.download_rate_limit.swigValue(),
                if (lowPower) 8 * 1024 * 1024 else 0,
            )

            // Pin the disk/hash worker pools to 1 on low-power — libtorrent otherwise scales them to
            // hardware_concurrency and saturates every weak core + contends for slow eMMC. Throughput
            // is already bounded by the rate cap above, not by parallel hashing. Guarded in case a
            // key enum is absent in this libtorrent4j build.
            runCatching {
                setInteger(settings_pack.int_types.aio_threads.swigValue(), if (lowPower) 1 else 4)
                setInteger(settings_pack.int_types.hashing_threads.swigValue(), if (lowPower) 1 else 2)
            }
            // Bound dirty writeback + open handles + unchoke slots on low-power so libtorrent flushes
            // small steady batches instead of bursting the slow eMMC.
            if (lowPower) {
                runCatching {
                    setInteger(settings_pack.int_types.max_queued_disk_bytes.swigValue(), 1 * 1024 * 1024)
                    setInteger(settings_pack.int_types.file_pool_size.swigValue(), 4)
                    setBoolean(settings_pack.bool_types.no_atime_storage.swigValue(), true)
                    setInteger(settings_pack.int_types.unchoke_slots_limit.swigValue(), 4)
                }
            }

            // Streaming-oriented tuning (libtorrent 2.0-correct; NO cache_size — removed in 2.0).
            setInteger(
                settings_pack.int_types.suggest_mode.swigValue(),
                settings_pack.suggest_mode_t.suggest_read_cache.swigValue(),
            )
            setInteger(settings_pack.int_types.whole_pieces_threshold.swigValue(), 60)
            setInteger(settings_pack.int_types.max_out_request_queue.swigValue(), if (lowPower) 500 else 1500)
            setInteger(settings_pack.int_types.peer_connect_timeout.swigValue(), 8)
            setInteger(settings_pack.int_types.request_timeout.swigValue(), 20)
            setInteger(settings_pack.int_types.predictive_piece_announce.swigValue(), 3)
            setInteger(
                settings_pack.int_types.send_buffer_watermark.swigValue(),
                if (lowPower) 1 * 1024 * 1024 else 3 * 1024 * 1024,
            )
        }
        Log.i(TAG, "libtorrent starting (lowPower=$lowPower)")

        // Restore DHT/session state for fast cold metadata (skips DHT bootstrap), else start fresh.
        val params: SessionParams = loadSessionState()?.let { blob ->
            runCatching { SessionParams(blob).also { it.setSettings(sp) } }.getOrNull()
        } ?: SessionParams(sp)

        session.start(params)
        session.addListener(sessionListener)
        started.set(true)
        return
    }

    /** Android TV or a system-flagged low-RAM device — both warrant gentle torrent + read settings. */
    val isLowPower: Boolean get() = deviceProfile.isLowPower

    private val sessionListener = object : AlertListener {
        override fun types(): IntArray = intArrayOf(
            AlertType.TORRENT_REMOVED.swig(),
            AlertType.SAVE_RESUME_DATA.swig(),
            AlertType.SAVE_RESUME_DATA_FAILED.swig(),
        )

        override fun alert(alert: Alert<*>) {
            when (alert) {
                is TorrentRemovedAlert -> {
                    val ih = runCatching { alert.infoHashes.best.toHex().lowercase() }.getOrNull()
                    if (ih != null) torrents.remove(ih)
                }
                is SaveResumeDataAlert -> {
                    persistResumeBlob(alert)
                    completeDeferredRemoval(alert.handle())
                }
                is SaveResumeDataFailedAlert -> completeDeferredRemoval(alert.handle())
                else -> Unit
            }
        }
    }

    /** Free the session slot for a torrent once its resume blob has been (attempted to be) written. */
    private fun completeDeferredRemoval(handle: TorrentHandle) {
        val ih = runCatching { handle.infoHash().toHex().lowercase() }.getOrNull() ?: return
        if (pendingRemoval.remove(ih) && handle.isValid) {
            runCatching { session.remove(handle) }
        }
    }

    private fun persistResumeBlob(alert: SaveResumeDataAlert) {
        runCatching {
            val params = alert.params()
            val ih = alert.handle().infoHash().toHex().lowercase()
            val buf = AddTorrentParams.writeResumeDataBuf(params)
            resumeFileFor(ih).parentFile?.mkdirs()
            resumeFileFor(ih).writeBytes(buf)
        }.onFailure { Log.w(TAG, "persistResumeBlob failed", it) }
    }

    /** True once native libtorrent is loaded and the session is up. */
    fun isAvailable(): Boolean = try {
        ensureStarted()
        true
    } catch (t: Throwable) {
        Log.e(TAG, "libtorrent unavailable", t)
        false
    }

    /**
     * Add (or re-attach to) a magnet. Resolves metadata if needed, selects the playable
     * video file, switches the torrent to sequential download and high-priority head/tail.
     * Returns the lowercase info-hash once the file has been chosen.
     */
    suspend fun addMagnet(magnetUri: String, preferredFileIndex: Int?): String {
        ensureStarted()

        val info = loadOrFetchMetadata(magnetUri)
        val infoHash = info.infoHash().toHex().lowercase()

        val existing = torrents[infoHash]
        if (existing?.handle?.isValid == true) {
            existing.handle?.let { applySequentialAndPriority(it, existing) }
            return infoHash
        }

        val active = ActiveTorrent(infoHash)
        torrents[infoHash] = active

        val resumeFile = resumeFileFor(infoHash).takeIf { it.exists() }

        // Download via the resolved TorrentInfo so we can pick the file immediately.
        // 6-arg form: (info, saveDir, resumeFile, filePriorities, peers, flags).
        // NB: flags must be non-null — libtorrent4j does params.flags().or_(flags) with no null
        // guard, so passing null dereferences a null torrent_flags_t in native code. AUTO_MANAGED
        // is already part of the default flags, so this is a behaviourally-neutral non-null value.
        // applySequentialAndPriority() (below, once the handle exists) flips on SEQUENTIAL_DOWNLOAD
        // and the head/tail deadlines that actually drive streaming order.
        session.download(info, savePath, resumeFile, null, null, TorrentFlags.AUTO_MANAGED)

        val handle = awaitHandle(infoHash)
            ?: error("Failed to obtain torrent handle for $infoHash")
        active.handle = handle

        selectFile(handle, info, active, preferredFileIndex)
        applySequentialAndPriority(handle, active)
        handle.resume()
        return infoHash
    }

    /**
     * Resolve the magnet's [TorrentInfo]. The bencoded metadata is cached per info-hash, so a
     * torrent you've opened before resolves from disk instantly — skipping the network metadata
     * fetch (DHT/peer round-trips), which is the slow part of "first load", not your bandwidth.
     */
    private suspend fun loadOrFetchMetadata(magnetUri: String): TorrentInfo {
        parseInfoHash(magnetUri)?.let { hash ->
            val cached = metadataFileFor(hash)
            if (cached.exists()) {
                runCatching { TorrentInfo.bdecode(cached.readBytes()) }.getOrNull()?.let {
                    Log.i(TAG, "metadata cache hit for $hash")
                    return it
                }
            }
        }
        // Cache miss — fetch over the network (60s covers DHT bootstrap), then persist for next time.
        val data = session.fetchMagnet(magnetUri, METADATA_TIMEOUT_SECONDS, savePath)
            ?: error("Timed out fetching torrent metadata")
        val info = TorrentInfo.bdecode(data)
        runCatching {
            metadataFileFor(info.infoHash().toHex().lowercase()).writeBytes(data)
        }.onFailure { Log.w(TAG, "metadata cache write failed", it) }
        return info
    }

    private fun metadataFileFor(infoHash: String): File = File(metadataDir, "$infoHash.torrent")

    /** Extract the v1 info-hash (40-hex) from a magnet's `xt=urn:btih:` parameter, if present. */
    private fun parseInfoHash(magnetUri: String): String? =
        Regex("xt=urn:btih:([A-Fa-f0-9]{40})").find(magnetUri)?.groupValues?.get(1)?.lowercase()

    private suspend fun awaitHandle(infoHash: String): TorrentHandle? {
        repeat(HANDLE_POLL_ATTEMPTS) {
            val h = findHandle(infoHash)
            if (h != null && h.isValid) return h
            delay(HANDLE_POLL_INTERVAL_MS)
        }
        return findHandle(infoHash)
    }

    private fun findHandle(infoHash: String): TorrentHandle? =
        session.find(Sha1Hash.parseHex(infoHash))

    /** Choose the file to stream: explicit index if valid, else the largest video file. */
    private fun selectFile(
        handle: TorrentHandle,
        info: TorrentInfo,
        active: ActiveTorrent,
        preferredFileIndex: Int?,
    ) {
        val files = info.files()
        val numFiles = files.numFiles()

        val chosen = preferredFileIndex
            ?.takeIf { it in 0 until numFiles }
            ?: (0 until numFiles)
                .filter { isVideoFile(files.fileName(it)) }
                .maxByOrNull { files.fileSize(it) }
            ?: (0 until numFiles).maxByOrNull { files.fileSize(it) }
            ?: 0

        active.fileIndex = chosen
        active.fileLength = files.fileSize(chosen)
        active.fileOffset = files.fileOffset(chosen)
        active.pieceLength = info.pieceLength()
        active.firstPiece = (active.fileOffset / active.pieceLength).toInt()
        active.lastPiece = ((active.fileOffset + active.fileLength - 1) / active.pieceLength).toInt()

        val relPath = files.filePath(chosen)
        active.filePath = File(savePath, relPath).absolutePath

        // De-prioritize everything else so bandwidth concentrates on the chosen file.
        val priorities = Array(numFiles) { Priority.IGNORE }
        priorities[chosen] = Priority.DEFAULT
        handle.prioritizeFiles(priorities)
    }

    private fun applySequentialAndPriority(handle: TorrentHandle, active: ActiveTorrent) {
        // Force an actively-downloading state. The metadata-fetch phase can leave the torrent
        // upload-only / paused / auto-managed (which downloads nothing); clear those and take
        // manual control.
        runCatching {
            handle.unsetFlags(
                TorrentFlags.UPLOAD_MODE
                    .or_(TorrentFlags.PAUSED)
                    .or_(TorrentFlags.AUTO_MANAGED),
            )
            // Strict in-order download. Without this, a fast swarm fills pieces out of order:
            // you get 9 MB/s of *body* the player can't use yet while the contiguous head — the
            // only thing the readiness gate counts — dribbles in (the "9 MB/s but still buffering
            // for 45 s" bug). Sequential makes the head fill at the FULL swarm rate. The tail/moov
            // pieces carry explicit deadlines in prioritizeHeadAndTail(); set_piece_deadline marks
            // them "time critical" and libtorrent fetches those AHEAD of the sequential cursor, so
            // the moov still arrives early and the moov gate never deadlocks.
            handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        }.onFailure { Log.w(TAG, "set streaming flags failed", it) }
        prioritizeHeadAndTail(active)
        runCatching { handle.resume() }
    }

    /** Push the first and last pieces of the file to the front of the download queue. */
    private fun prioritizeHeadAndTail(active: ActiveTorrent) {
        val handle = active.handle?.takeIf { it.isValid } ?: return
        if (active.pieceLength <= 0) return
        val headPieces = (HEAD_PRIORITY_BYTES / active.pieceLength + 1)
        val tailPieces = (TAIL_PRIORITY_BYTES / active.pieceLength + 1)

        // Best-effort: a concurrent stop()/removal can invalidate the handle between native calls,
        // which then throw — prioritisation isn't worth crashing over.
        runCatching {
            for (i in 0 until headPieces) {
                val p = active.firstPiece + i
                if (p in active.firstPiece..active.lastPiece) {
                    handle.piecePriority(p, Priority.TOP_PRIORITY)
                    handle.setPieceDeadline(p, (i + 1) * 50)
                }
            }
            // Tail = the mp4 moov atom / mkv cues the player needs BEFORE the first frame, so
            // fetch it as aggressively as the head (top priority + tight deadlines), concurrently.
            for (i in 0 until tailPieces) {
                val p = active.lastPiece - i
                if (p in active.firstPiece..active.lastPiece) {
                    handle.piecePriority(p, Priority.TOP_PRIORITY)
                    handle.setPieceDeadline(p, (i + 1) * 50)
                }
            }
        }.onFailure { Log.w(TAG, "prioritizeHeadAndTail failed", it) }
    }

    /**
     * Ensure the byte range [start, endInclusive] of the selected file is downloaded,
     * raising the priority of the covering pieces and polling until they arrive (or timeout).
     * Returns true if the range is fully available.
     */
    suspend fun ensureRange(infoHash: String, start: Long, endInclusive: Long, timeoutMs: Long): Boolean {
        val active = torrents[infoHash] ?: return false
        val handle = active.handle?.takeIf { it.isValid } ?: return false
        if (active.pieceLength <= 0) return false

        // Map file-relative offsets to absolute piece indices via the file's torrent offset.
        val firstNeeded = ((active.fileOffset + start) / active.pieceLength).toInt()
        val lastNeeded = ((active.fileOffset + endInclusive) / active.pieceLength).toInt()

        // Bump priority + deadlines so libtorrent fetches these next, in order. Best-effort: a
        // concurrent stop() can invalidate the handle mid-loop, making the native call throw.
        runCatching {
            var deadline = 0
            for (p in firstNeeded..lastNeeded) {
                if (p in active.firstPiece..active.lastPiece) {
                    handle.piecePriority(p, Priority.TOP_PRIORITY)
                    handle.setPieceDeadline(p, deadline)
                    deadline += 30
                }
            }
        }

        val deadlineAt = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadlineAt) {
            if (rangeAvailable(handle, active, firstNeeded, lastNeeded)) return true
            delay(RANGE_POLL_INTERVAL_MS)
        }
        return rangeAvailable(handle, active, firstNeeded, lastNeeded)
    }

    private fun rangeAvailable(
        handle: TorrentHandle,
        active: ActiveTorrent,
        firstNeeded: Int,
        lastNeeded: Int,
    ): Boolean {
        val status = runCatching { handle.status(TorrentHandle.QUERY_PIECES) }.getOrNull() ?: return false
        // pieces() never returns null — but the underlying native bitfield may be EMPTY (size 0,
        // null buffer) before any piece data exists (just-added/checking/paused window). getBit()
        // on it dereferences a null native buffer (the bounds/null asserts are compiled out in the
        // release .so) -> SIGSEGV. size() is null-safe (returns 0), so gate on it and bounds-check.
        val pieces = status.pieces()
        val pieceCount = pieces.size()
        if (pieceCount == 0) return false
        for (p in firstNeeded..lastNeeded) {
            if (p in active.firstPiece..active.lastPiece) {
                if (p >= pieceCount || !pieces.getBit(p)) return false
            }
        }
        return true
    }

    /** How many contiguous head bytes (from the start of the file) are downloaded. */
    fun contiguousHeadBytes(infoHash: String): Long {
        val active = torrents[infoHash] ?: return 0L
        val handle = active.handle?.takeIf { it.isValid } ?: return 0L
        if (active.pieceLength <= 0) return 0L
        val status = runCatching { handle.status(TorrentHandle.QUERY_PIECES) }.getOrNull() ?: return 0L
        val pieces = status.pieces()
        val pieceCount = pieces.size()
        if (pieceCount == 0) return 0L

        var contiguousPieces = 0
        var p = active.firstPiece
        while (p <= active.lastPiece && p < pieceCount && pieces.getBit(p)) {
            contiguousPieces++
            p++
        }
        if (contiguousPieces == 0) return 0L
        val bytesFromPieceStart = contiguousPieces.toLong() * active.pieceLength
        // The first piece may begin before the file (shared with the previous file).
        val headOffsetInFirstPiece = active.fileOffset % active.pieceLength
        return (bytesFromPieceStart - headOffsetInFirstPiece).coerceIn(0L, active.fileLength)
    }

    /** True if the tail pieces (needed for mp4 moov atoms) are present. */
    fun tailAvailable(infoHash: String): Boolean {
        val active = torrents[infoHash] ?: return false
        val handle = active.handle?.takeIf { it.isValid } ?: return false
        if (active.pieceLength <= 0) return false
        val tailPieces = (TAIL_PRIORITY_BYTES / active.pieceLength + 1)
        val status = runCatching { handle.status(TorrentHandle.QUERY_PIECES) }.getOrNull() ?: return false
        val pieces = status.pieces()
        val pieceCount = pieces.size()
        if (pieceCount == 0) return false
        for (i in 0 until tailPieces) {
            val p = active.lastPiece - i
            if (p in active.firstPiece..active.lastPiece) {
                if (p >= pieceCount || !pieces.getBit(p)) return false
            }
        }
        return true
    }

    /** Absolute path of the selected file, once chosen. */
    fun filePath(infoHash: String): String? = torrents[infoHash]?.filePath

    /** Length in bytes of the selected file. */
    fun fileLength(infoHash: String): Long = torrents[infoHash]?.fileLength ?: 0L

    /** Live status snapshot, or null if the torrent isn't active. */
    fun snapshot(infoHash: String): EngineStatus? {
        val active = torrents[infoHash] ?: return null
        val handle = active.handle?.takeIf { it.isValid } ?: return null
        val st = handle.status()
        val fileTotal = active.fileLength.takeIf { it > 0 } ?: st.total()
        val downloadedFile = (contiguousHeadBytes(infoHash)).coerceAtMost(fileTotal)
        // Progress on the selected file (sequential => contiguous head is a good proxy),
        // but never report less than libtorrent's own file-aware progress.
        val byHead = if (fileTotal > 0) downloadedFile.toFloat() / fileTotal else 0f
        val progress = maxOf(byHead, st.progress()).coerceIn(0f, 1f)
        return EngineStatus(
            progress = progress,
            downloadRate = st.downloadRate(),
            uploadRate = st.uploadRate(),
            seeders = st.numSeeds(),
            peers = st.numPeers(),
            downloadedBytes = (progress * fileTotal).toLong().coerceAtMost(fileTotal),
            totalBytes = fileTotal,
            isFinished = st.isFinished,
            isPaused = st.flags().and_(TorrentFlags.PAUSED).non_zero(),
        )
    }

    fun pause(infoHash: String) {
        torrents[infoHash]?.handle?.takeIf { it.isValid }?.pause()
        saveResume(infoHash)
    }

    fun resume(infoHash: String) {
        val active = torrents[infoHash] ?: return
        active.handle?.takeIf { it.isValid }?.let {
            it.resume()
            applySequentialAndPriority(it, active)
        }
    }

    /**
     * Request fast-resume data so the torrent can re-attach instantly later. The actual blob
     * is written to disk asynchronously by [persistResumeBlob] when the SAVE_RESUME_DATA alert
     * fires.
     */
    fun saveResume(infoHash: String) {
        val handle = torrents[infoHash]?.handle?.takeIf { it.isValid } ?: return
        if (!handle.status().hasMetadata()) return
        runCatching {
            resumeDir.mkdirs()
            handle.saveResumeData()
        }.onFailure { Log.w(TAG, "saveResume failed for $infoHash", it) }
    }

    /**
     * Stop a torrent. [removeFiles]=false pauses + persists resume data (kept in cache);
     * [removeFiles]=true removes it from the session and deletes its files.
     */
    fun stop(infoHash: String, removeFiles: Boolean) {
        val active = torrents[infoHash]
        val handle = active?.handle
        if (removeFiles) {
            if (handle != null && handle.isValid) {
                session.remove(handle, session_handle.delete_files)
            }
            torrents.remove(infoHash)
            runCatching {
                resumeFileFor(infoHash).delete()
                torrentFileFor(infoHash).delete()
            }
            active?.filePath?.let { runCatching { File(it).delete() } }
        } else {
            if (handle != null && handle.isValid) {
                // Keep the partial file on disk but free the session slot once the resume
                // blob is durably written. saveResume() queues SAVE_RESUME_DATA; its alert ->
                // persistResumeBlob -> completeDeferredRemoval frees the slot, and the ensuing
                // TorrentRemovedAlert clears the torrents map (keeping snapshot()/isActive()
                // consistent until the blob exists).
                pendingRemoval.add(infoHash)
                handle.pause()
                saveResume(infoHash)
                saveSessionState()
            } else {
                torrents.remove(infoHash)
            }
        }
    }

    fun isActive(infoHash: String): Boolean =
        torrents[infoHash]?.handle?.isValid == true

    fun activeCount(): Int = torrents.values.count { it.handle?.isValid == true }

    /** Persist the live session (DHT routing table + settings) for the next cold start. */
    fun saveSessionState() {
        if (!started.get()) return
        runCatching {
            val blob = session.saveState() ?: return
            val tmp = File(sessionStateFile.parentFile, ".session_state.tmp")
            tmp.writeBytes(blob)
            if (!tmp.renameTo(sessionStateFile)) {
                sessionStateFile.writeBytes(blob)
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "saveSessionState failed", it) }
    }

    private fun loadSessionState(): ByteArray? =
        sessionStateFile.takeIf { it.exists() && it.length() > 0 }
            ?.let { runCatching { it.readBytes() }.getOrNull() }

    /**
     * Advance the sliding read-ahead window to follow the player's live read position [filePos]
     * (file-relative byte offset). Prioritises the next [READAHEAD_BYTES] of pieces ahead of the
     * read head with staggered deadlines, and resets deadlines on pieces now behind the head so
     * libtorrent stops fetching already-consumed data out of order. Cheap no-op unless the read
     * head crossed into a new piece, so it is safe to call on every read().
     */
    fun advanceReadHead(infoHash: String, filePos: Long) {
        val active = torrents[infoHash] ?: return
        val handle = active.handle?.takeIf { it.isValid } ?: return
        if (active.pieceLength <= 0) return
        val curPiece = ((active.fileOffset + filePos) / active.pieceLength).toInt()
        if (curPiece == active.readHeadPiece) return
        val prev = active.readHeadPiece
        active.readHeadPiece = curPiece

        val readahead = (readaheadBytes / active.pieceLength + 1)
        runCatching {
            if (prev >= 0) {
                for (p in prev until curPiece) {
                    if (p in active.firstPiece..active.lastPiece) handle.resetPieceDeadline(p)
                }
            }
            var deadline = 0
            for (i in 0 until readahead) {
                val p = curPiece + i
                if (p in active.firstPiece..active.lastPiece) {
                    handle.piecePriority(p, Priority.TOP_PRIORITY)
                    handle.setPieceDeadline(p, deadline)
                    deadline += 25
                }
            }
        }.onFailure { Log.w(TAG, "advanceReadHead failed", it) }
    }

    private fun resumeFileFor(infoHash: String) = File(resumeDir, "$infoHash.resume")
    private fun torrentFileFor(infoHash: String) = File(resumeDir, "$infoHash.torrent")

    companion object {
        private const val TAG = "TorrentEngine"
        const val CACHE_DIR_NAME = "torrents"

        private const val METADATA_TIMEOUT_SECONDS = 60
        private const val HANDLE_POLL_ATTEMPTS = 100
        private const val HANDLE_POLL_INTERVAL_MS = 50L
        private const val RANGE_POLL_INTERVAL_MS = 100L

        /** ~6 MB head buffer + ~1 MB tail (mp4 moov atom). */
        const val HEAD_PRIORITY_BYTES = 6 * 1024 * 1024
        const val TAIL_PRIORITY_BYTES = 1 * 1024 * 1024

        /** ~16 MB sliding look-ahead band kept hot ahead of the player's read position. */
        const val READAHEAD_BYTES = 16 * 1024 * 1024

        /** Smaller look-ahead on Android TV / low-RAM devices to ease CPU/memory pressure. */
        const val LOW_POWER_READAHEAD_BYTES = 6 * 1024 * 1024

        private val VIDEO_EXTS = setOf("mp4", "mkv", "avi", "webm", "mov", "m4v", "flv", "ts")

        fun isVideoFile(name: String?): Boolean {
            val ext = name?.substringAfterLast('.', "")?.lowercase() ?: return false
            return ext in VIDEO_EXTS
        }
    }
}

/** Flat status snapshot read off a [TorrentHandle]. */
data class EngineStatus(
    val progress: Float,
    val downloadRate: Int,
    val uploadRate: Int,
    val seeders: Int,
    val peers: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val isFinished: Boolean,
    val isPaused: Boolean,
)
