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

    /**
     * Serializes EVERY native libtorrent handle/session call. The piece-status readers
     * (status(QUERY_PIECES) -> bitfield) run from the 500ms poll coroutine and from every NanoHTTPD
     * read worker, concurrently with stop()/session.remove() (source-switch + cache eviction). A
     * read racing a remove is a use-after-free in the release .so (asserts stripped) -> SIGSEGV that
     * kills the whole process — the real "app lock". runCatching can't catch a native crash and
     * isValid is TOCTOU; one lock closes the race. All these calls are OFF the main thread, so this
     * can never cause a UI ANR. Re-entrant (snapshot -> contiguousHeadBytes both lock).
     */
    private val nativeLock = Any()

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
            setInteger(settings_pack.int_types.connections_limit.swigValue(), if (lowPower) 60 else 800)
            setInteger(settings_pack.int_types.active_downloads.swigValue(), if (lowPower) 4 else 8)
            setInteger(settings_pack.int_types.active_seeds.swigValue(), if (lowPower) 4 else 8)
            setInteger(settings_pack.int_types.active_limit.swigValue(), if (lowPower) 8 else 16)
            // Peer-acquisition ramp. TV was throttled hard (8/12) to avoid a buffering-time freeze, but
            // that starved pickup — only ~8 peers after 45 s, so the head crawled in. The freeze ceiling
            // is the 8 MB/s download cap below (bounds hashing/eMMC), NOT the socket count, so a faster
            // connect ramp safely finds head-bearing peers sooner. 40/40 on TV; capable stays 200/500.
            setInteger(settings_pack.int_types.torrent_connect_boost.swigValue(), if (lowPower) 40 else 200)
            setInteger(settings_pack.int_types.connection_speed.swigValue(), if (lowPower) 40 else 500)
            // Announce to only the first working tier on low-power (less inbound peer flood to crypt).
            setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), !lowPower)
            setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), !lowPower)
            // Keep DHT (needed for poorly-seeded discovery), but drop LSD/UPnP/NAT-PMP background
            // network+CPU work a pure leech/stream TV client never needs.
            setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
            // Seed the DHT with known-good routers so a COLD DHT (fresh install / after cache clear)
            // finds nodes in seconds instead of stalling on a single default introducer — a chunk of
            // the cold metadata-fetch latency. Guarded in case the key name differs in this build.
            runCatching {
                setString(
                    settings_pack.string_types.dht_bootstrap_nodes.swigValue(),
                    "router.bittorrent.com:6881,router.utorrent.com:6881,dht.transmissionbt.com:6881,router.bitcomet.com:6881,dht.libtorrent.org:25401",
                )
            }
            setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), !lowPower)
            setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), !lowPower)
            setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), !lowPower)
            // Cap upload to 100 KB/s on ALL devices. We're a streaming/leech client, not a seedbox —
            // an uncapped upload saturates the (usually small) home upstream and tanks the whole
            // connection while a cached torrent keeps seeding after you've watched. 100 KB/s is plenty
            // for tit-for-tat peer reciprocity without choking the link.
            setInteger(settings_pack.int_types.upload_rate_limit.swigValue(), UPLOAD_RATE_LIMIT)
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
            // Lower so peers aren't encouraged to grab whole LATER pieces they happen to hold instead
            // of converging on the deadlined head band (part of the block-scatter fix).
            setInteger(settings_pack.int_types.whole_pieces_threshold.swigValue(), 20)
            // Bounded outstanding-request pipeline. A very deep queue (the old 2500) under a cold head
            // fills with SCATTERED blocks from peers that lack the head before the deadline reorder can
            // land — the prime cause of "downloads fast but the contiguous head stays 0". 800 (~12 MB in
            // flight at 16 KB blocks) still saturates any single video bitrate while letting the
            // deadlined head band dominate new requests. Tunable: raise toward 1500 if pickup throughput
            // regresses on fast links. Low-power TV stays 500.
            setInteger(settings_pack.int_types.max_out_request_queue.swigValue(), if (lowPower) 500 else 800)
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
            // session.remove races the piece-status readers — serialize (see nativeLock).
            synchronized(nativeLock) { runCatching { session.remove(handle) } }
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
     * Add (or re-attach to) a magnet, select the playable video file, switch the torrent to
     * sequential download with a high-priority head/tail, and return the lowercase info-hash.
     *
     * Pickup is the latency-critical path. We add the magnet EXACTLY ONCE and keep every peer it
     * finds: a live add (download(magnet, …)) fetches the metadata on the same torrent that then
     * downloads, so the swarm discovered during the metadata phase carries straight into streaming.
     * The previous fetchMagnet → re-download flow discovered peers, REMOVED the torrent (throwing
     * them away), then re-discovered from scratch — roughly doubling the time-to-first-byte and the
     * cause of the "desktop streams in seconds, we take a minute" gap. The magnet is also tracker-
     * boosted so discovery isn't DHT-only.
     */
    suspend fun addMagnet(magnetUri: String, preferredFileIndex: Int?): String {
        ensureStarted()
        val parsedHash = parseInfoHash(magnetUri)

        // Fast path: a Details prewarm (or a prior open) already has this torrent live — re-attach
        // instead of re-resolving (a second metadata fetch on an added torrent dead-latches 60s).
        parsedHash?.let { h ->
            torrents[h]?.takeIf { it.handle?.isValid == true }?.let { existing ->
                existing.handle?.let { applySequentialAndPriority(it, existing) }
                return h
            }
        }

        // Metadata cache hit (a torrent opened before this build cached its .torrent): add ONCE with
        // full info — peers are discovered a single time, straight into the download, no network
        // metadata fetch at all.
        parsedHash?.let { loadCachedMetadata(it) }?.let { cachedInfo ->
            Log.i(TAG, "metadata cache hit for $parsedHash")
            return addWithInfo(cachedInfo, preferredFileIndex)
        }

        // Cache miss (first watch): add the magnet LIVE, FORCE it active so its metadata fetch runs at
        // full tilt, read metadata off the handle, then stream — keeping every discovered peer (no
        // fetchMagnet discard-and-rediscover).
        val boosted = withTrackers(magnetUri)
        // Resolve the info-hash up front (handles v1-hex AND base32/v2 magnets) and register it in the
        // map BEFORE adding, so a metadata timeout or VM failover can always find + remove the torrent —
        // a non-40-hex magnet used to be added to the session then orphaned with no removable key.
        val infoHash = parsedHash
            ?: runCatching { AddTorrentParams.parseMagnetUri(boosted).infoHashes.best.toHex().lowercase() }.getOrNull()
            ?: error("Unparseable magnet (no info-hash)")
        val active = torrents.getOrPut(infoHash) { ActiveTorrent(infoHash) }

        val handle = findHandle(infoHash)?.takeIf { it.isValid }
            ?: run {
                // 3-arg live add: (magnet, saveDir, flags). flags must be non-null (libtorrent4j ORs them
                // with no null guard). AUTO_MANAGED here is immediately overridden below.
                session.download(boosted, savePath, TorrentFlags.AUTO_MANAGED)
                awaitHandle(infoHash) ?: run {
                    torrents.remove(infoHash)
                    error("Failed to obtain torrent handle")
                }
            }
        active.handle = handle

        // Take manual control NOW — DON'T wait on the queue auto-manager. A freshly-added AUTO_MANAGED
        // torrent can sit queued/paused while other torrents (a Details prewarm, cached seeders) hold the
        // active-download slots, so its ut_metadata fetch never starts: the "spins 45 s on N seeders,
        // never buffers, fails over" stall. Unmanaging + resuming makes metadata fetch immediately, just
        // as the old fetchMagnet did implicitly.
        runCatching {
            handle.unsetFlags(
                TorrentFlags.AUTO_MANAGED.or_(TorrentFlags.PAUSED).or_(TorrentFlags.UPLOAD_MODE),
            )
            handle.resume()
        }.onFailure { Log.w(TAG, "force-active failed for $infoHash", it) }

        val info = awaitMetadataFromHandle(handle) ?: run {
            // Cold/dead magnet: free the session slot + map entry instead of stranding a live torrent.
            runCatching { stop(infoHash, removeFiles = true) }
            error("Timed out fetching torrent metadata")
        }
        selectFile(handle, info, active, preferredFileIndex)
        applySequentialAndPriority(handle, active)
        handle.resume()
        return infoHash
    }

    /** Add a torrent we already have full [TorrentInfo] for (metadata cache hit / re-open), select the
     *  file and start sequential streaming. One add, peers discovered once. */
    private suspend fun addWithInfo(info: TorrentInfo, preferredFileIndex: Int?): String {
        val infoHash = info.infoHash().toHex().lowercase()
        torrents[infoHash]?.takeIf { it.handle?.isValid == true }?.let { existing ->
            existing.handle?.let { applySequentialAndPriority(it, existing) }
            return infoHash
        }
        val active = torrents.getOrPut(infoHash) { ActiveTorrent(infoHash) }
        val resumeFile = resumeFileFor(infoHash).takeIf { it.exists() }
        // 6-arg form: (info, saveDir, resumeFile, filePriorities, peers, flags). flags non-null (see
        // addMagnet). resumeFile re-checks the partial already on disk instead of re-downloading it.
        session.download(info, savePath, resumeFile, null, null, TorrentFlags.AUTO_MANAGED)
        val handle = awaitHandle(infoHash) ?: error("Failed to obtain torrent handle for $infoHash")
        active.handle = handle
        selectFile(handle, info, active, preferredFileIndex)
        applySequentialAndPriority(handle, active)
        handle.resume()
        return infoHash
    }

    /** Read previously-cached bencoded metadata for [hash] (written by an older build), or null. */
    private fun loadCachedMetadata(hash: String): TorrentInfo? {
        val cached = metadataFileFor(hash).takeIf { it.exists() } ?: return null
        return runCatching { TorrentInfo.bdecode(cached.readBytes()) }.getOrNull()
    }

    /**
     * Append a curated set of high-availability public trackers to a magnet so peer discovery (and
     * the ut_metadata fetch) isn't limited to DHT, which is the slow, flaky part of cold pickup.
     * libtorrent dedups against any trackers already in the magnet, so re-appending is harmless.
     */
    private fun withTrackers(magnetUri: String): String = buildString {
        append(magnetUri)
        for (tr in BOOST_TRACKERS) {
            append("&tr=")
            append(java.net.URLEncoder.encode(tr, "UTF-8"))
        }
    }

    /** Poll an already-added torrent's handle for its metadata (avoids the dead-latch fetchMagnet). */
    private suspend fun awaitMetadataFromHandle(handle: TorrentHandle): TorrentInfo? {
        val deadline = System.currentTimeMillis() + METADATA_TIMEOUT_SECONDS * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (!handle.isValid) return null
            val hasMeta = runCatching { handle.status().hasMetadata() }.getOrDefault(false)
            if (hasMeta) return runCatching { handle.torrentFile() }.getOrNull()
            delay(HANDLE_POLL_INTERVAL_MS)
        }
        return null
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

        // A supplied preferred index is only honoured if it actually points at a (non-sample) video
        // file — otherwise an indexer's stale fileIdx could land us on a .nfo/.srt/sample clip.
        val prefValid = preferredFileIndex
            ?.takeIf { it in 0 until numFiles && isVideoFile(files.fileName(it)) && !isSampleFile(files.fileName(it)) }

        // The largest REAL video file (sample clips excluded). Deliberately NO fallback to "largest
        // file overall": a RAR/disc release (.rar/.r00/.iso/.001…) contains no playable video, and
        // handing the biggest archive part to ExoPlayer just dead-ends on a parse error. Fail hard
        // instead so the player auto-fails-over to a real single-file source (the player treats this
        // thrown error as a source failure and advances to the next candidate).
        val largestVideo = (0 until numFiles)
            .filter { isVideoFile(files.fileName(it)) && !isSampleFile(files.fileName(it)) }
            .maxByOrNull { files.fileSize(it) }

        val chosen = prefValid ?: largestVideo
            ?: error("No playable video file in this torrent (archive/RAR release)")

        active.fileIndex = chosen
        active.fileLength = files.fileSize(chosen)
        active.fileOffset = files.fileOffset(chosen)
        active.pieceLength = info.pieceLength()
        active.firstPiece = (active.fileOffset / active.pieceLength).toInt()
        active.lastPiece = ((active.fileOffset + active.fileLength - 1) / active.pieceLength).toInt()

        val relPath = files.filePath(chosen)
        active.filePath = File(savePath, relPath).absolutePath

        Log.i(
            TAG,
            "selectFile name='${files.fileName(chosen)}' numFiles=$numFiles idx=$chosen " +
                "fileLen=${active.fileLength} fileOffset=${active.fileOffset} pieceLen=${active.pieceLength} " +
                "pieces=${active.firstPiece}..${active.lastPiece} path=${active.filePath}",
        )

        // De-prioritize everything else so bandwidth concentrates on the chosen file; the head/tail
        // ordering is driven by the staggered deadlines in prioritizeHeadAndTail.
        // NOTE: never call prioritizeFiles() AFTER prioritizeHeadAndTail — file priorities reset every
        // piece priority and would wipe the head/tail deadlines.
        val priorities = Array(numFiles) { Priority.IGNORE }
        priorities[chosen] = Priority.DEFAULT
        handle.prioritizeFiles(priorities)
    }

    private fun applySequentialAndPriority(handle: TorrentHandle, active: ActiveTorrent) {
        // Force an actively-downloading, RUNNING state and take manual control, THEN prime the
        // deadlines. resume() runs BEFORE prioritizeHeadAndTail: set_piece_deadline only enters the
        // time-critical list on a RUNNING torrent, so deadlines applied while still PAUSED (the
        // Details-prewarm pause/resume path) silently didn't stick. SEQUENTIAL_DOWNLOAD stays on — it
        // gives the in-order steady-state buffer-ahead playback relies on; the staggered head/tail
        // deadlines (prioritizeHeadAndTail) ride ON TOP to pull the first pieces + the moov/cues to the
        // front of that order.
        runCatching {
            handle.unsetFlags(
                TorrentFlags.UPLOAD_MODE
                    .or_(TorrentFlags.PAUSED)
                    .or_(TorrentFlags.AUTO_MANAGED),
            )
            handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
            handle.resume()
        }.onFailure { Log.w(TAG, "set streaming flags failed", it) }
        prioritizeHeadAndTail(active)
    }

    /**
     * Drive the swarm to fill the START of the file IN ORDER so the readiness gate (contiguous head)
     * is reached fast. SEQUENTIAL_DOWNLOAD alone is only best-effort — when the connected peers are
     * leechers that lack the next piece, libtorrent grabs whatever they DO have, scattering bandwidth
     * across the file while the contiguous head dribbles in (observed: ~210 MB downloaded, only ~58 MB
     * of contiguous head — "downloads fast but never starts buffering"). Deadlining a BAND of head
     * pieces with staggered deadlines makes those pieces time-critical and pulled in order.
     *
     * The tail (EOF) is only fetched up front for moov-critical mp4/m4v/mov, whose moov atom is
     * MANDATORY before the first frame. mkv/webm carry only seek cues at EOF — not needed to START —
     * so for them the tail is left to download naturally (or on-demand when the user seeks, via
     * [ensureRange]); fetching it at top priority at startup just steals bandwidth from the head and
     * was a big part of the slow time-to-first-frame.
     */
    private fun prioritizeHeadAndTail(active: ActiveTorrent) {
        val handle = active.handle?.takeIf { it.isValid } ?: return
        val pieceLen = active.pieceLength
        if (pieceLen <= 0) return
        // BYTE-bounded head band, NOT a fixed piece count. The readiness gate only needs ~READY_HEAD
        // (2 MB), so deadline just the pieces covering ~HEAD_PRIORITY_BYTES of head: 1 piece when the
        // torrent has big pieces (8-32 MB), a few when pieces are small. A fixed 5-piece band was the
        // killer on big-piece torrents — 5 x 32 MB = 160 MB fetched in PARALLEL, so piece 0 (and thus
        // "head ready") didn't land for ~a minute even at 3 MB/s.
        // A multi-piece ORDERED head band — NOT a byte budget that collapses to 1 piece on big-piece
        // torrents. With 8 MB pieces, ceilDiv(6MB,8MB)=1, so only piece 0 was ever time-critical and the
        // rest of the request queue scattered. Deadlining the first HEAD_PRIORITY_PIECES with STRICTLY
        // STAGGERED deadlines (0, 20, 40… ms) keeps the head pipeline continuously fed and forces them
        // to complete IN ORDER (0→1→2→3) rather than in parallel — so the contiguous head actually
        // fills. Capped small so a big-piece torrent doesn't deadline a huge band.
        val headPieces = maxOf(HEAD_PRIORITY_PIECES, ceilDiv(HEAD_PRIORITY_BYTES, pieceLen))
        val tailPieces = maxOf(1, ceilDiv(TAIL_PRIORITY_BYTES, pieceLen))
        val ext = active.filePath?.substringAfterLast('.', "")?.lowercase()
        val moovCritical = ext == "mp4" || ext == "m4v" || ext == "mov"
        // The EOF tail holds the mp4 moov AND the mkv cues (ExoPlayer reads it during prepare):
        //  - mp4/mov: moov is MANDATORY before the first frame -> fetch in parallel with the head (0ms).
        //  - mkv/webm: cues aren't needed to START, so give them a far deadline — they must NOT compete
        //    with a cold head for the few head-bearing peers; they fill once the head is in.
        val tailBase = if (moovCritical) 0 else MKV_TAIL_DEFER_MS

        // Best-effort: a concurrent stop()/removal can invalidate the handle between native calls,
        // which then throw — prioritisation isn't worth crashing over. Serialized (see nativeLock).
        synchronized(nativeLock) { runCatching {
            // Clear any residue (e.g. from a prewarm prime) so a re-attach doesn't layer a new band over
            // a stale one. Then raise + deadline the fresh head/tail bands. The deadline STEP must be
            // LARGE (not ~20 ms): libtorrent works all time-critical pieces toward their deadlines in
            // parallel, so near-equal deadlines let pieces complete in peer-speed order (observed: piece
            // 2 landing before piece 0). A big per-piece step makes piece 0 FAR more overdue than 1,2,3,
            // so the swarm converges on it first, then 1, then 2 — strict in-order head fill.
            handle.clearPieceDeadlines()
            for (i in 0 until headPieces) {
                val p = active.firstPiece + i
                if (p in active.firstPiece..active.lastPiece) {
                    handle.piecePriority(p, Priority.TOP_PRIORITY)
                    handle.setPieceDeadline(p, i * HEAD_DEADLINE_STEP_MS)
                }
            }
            for (i in 0 until tailPieces) {
                val p = active.lastPiece - i
                if (p in active.firstPiece..active.lastPiece) {
                    handle.piecePriority(p, Priority.TOP_PRIORITY)
                    handle.setPieceDeadline(p, tailBase + i * HEAD_DEADLINE_STEP_MS)
                }
            }
        }.onFailure { Log.w(TAG, "prioritizeHeadAndTail failed", it) } }
    }

    /** Ceiling of [a]/[b] for positive ints. */
    private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

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

        // If this request reaches into the tail/EOF band, it's the player fetching the moov atom /
        // mkv cues. Under SEQUENTIAL_DOWNLOAD the sequential cursor sits at the head and will NOT
        // reach EOF pieces for minutes, so deadline-fetch EVERYTHING from here to the last piece in
        // one shot — not just the 512 KB slice blockForRange asked for. Otherwise the moov read walks
        // forward one 1 MB chunk at a time, each chunk racing the head cursor, and stalls for >1 min.
        // set_piece_deadline marks these "time critical" and libtorrent fetches them AHEAD of the
        // sequential cursor, so the whole moov arrives promptly and the moov gate never deadlocks.
        val tailPieces = (TAIL_PRIORITY_BYTES / active.pieceLength + 1)
        val tailFrom = active.lastPiece - tailPieces + 1
        val deadlineLast = if (lastNeeded >= tailFrom) active.lastPiece else lastNeeded

        // Bump priority + deadlines so libtorrent fetches these next, in order. Best-effort: a
        // concurrent stop() can invalidate the handle mid-loop, making the native call throw.
        synchronized(nativeLock) {
            runCatching {
                var deadline = 0
                for (p in firstNeeded..deadlineLast) {
                    if (p in active.firstPiece..active.lastPiece) {
                        handle.piecePriority(p, Priority.TOP_PRIORITY)
                        handle.setPieceDeadline(p, deadline)
                        deadline += 30
                    }
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
    ): Boolean = synchronized(nativeLock) {
        val status = runCatching { handle.status(TorrentHandle.QUERY_PIECES) }.getOrNull()
            ?: return@synchronized false
        // pieces() never returns null — but the underlying native bitfield may be EMPTY (size 0,
        // null buffer) before any piece data exists (just-added/checking/paused window). getBit()
        // on it dereferences a null native buffer (the bounds/null asserts are compiled out in the
        // release .so) -> SIGSEGV. size() is null-safe (returns 0), so gate on it and bounds-check.
        val pieces = status.pieces()
        val pieceCount = pieces.size()
        if (pieceCount == 0) return@synchronized false
        for (p in firstNeeded..lastNeeded) {
            if (p in active.firstPiece..active.lastPiece) {
                if (p >= pieceCount || !pieces.getBit(p)) return@synchronized false
            }
        }
        true
    }

    /** How many contiguous head bytes (from the start of the file) are downloaded. */
    fun contiguousHeadBytes(infoHash: String): Long = synchronized(nativeLock) {
        val active = torrents[infoHash] ?: return@synchronized 0L
        val handle = active.handle?.takeIf { it.isValid } ?: return@synchronized 0L
        if (active.pieceLength <= 0) return@synchronized 0L
        val status = runCatching { handle.status(TorrentHandle.QUERY_PIECES) }.getOrNull()
            ?: return@synchronized 0L
        val pieces = status.pieces()
        val pieceCount = pieces.size()
        if (pieceCount == 0) return@synchronized 0L

        var contiguousPieces = 0
        var p = active.firstPiece
        while (p <= active.lastPiece && p < pieceCount && pieces.getBit(p)) {
            contiguousPieces++
            p++
        }
        if (contiguousPieces == 0) return@synchronized 0L
        val bytesFromPieceStart = contiguousPieces.toLong() * active.pieceLength
        // The first piece may begin before the file (shared with the previous file).
        val headOffsetInFirstPiece = active.fileOffset % active.pieceLength
        (bytesFromPieceStart - headOffsetInFirstPiece).coerceIn(0L, active.fileLength)
    }

    /** True if the tail pieces (needed for mp4 moov atoms) are present. */
    fun tailAvailable(infoHash: String): Boolean = synchronized(nativeLock) {
        val active = torrents[infoHash] ?: return@synchronized false
        val handle = active.handle?.takeIf { it.isValid } ?: return@synchronized false
        if (active.pieceLength <= 0) return@synchronized false
        val tailPieces = (TAIL_PRIORITY_BYTES / active.pieceLength + 1)
        val status = runCatching { handle.status(TorrentHandle.QUERY_PIECES) }.getOrNull()
            ?: return@synchronized false
        val pieces = status.pieces()
        val pieceCount = pieces.size()
        if (pieceCount == 0) return@synchronized false
        for (i in 0 until tailPieces) {
            val p = active.lastPiece - i
            if (p in active.firstPiece..active.lastPiece) {
                if (p >= pieceCount || !pieces.getBit(p)) return@synchronized false
            }
        }
        true
    }

    /**
     * Best-effort, NON-BLOCKING: nudge libtorrent to fetch the pieces covering these file byte-offsets
     * ahead of the sequential cursor, but at RELAXED deadlines so the head buffer + read-ahead (which
     * carry 50 ms-class deadlines) always win the swarm. Used to sparsely sample frames across the
     * timeline for scrub-bar previews once playback is healthy. Each offset pulls its piece + the next
     * one (a keyframe can straddle the boundary). Cheap to call repeatedly — libtorrent dedups.
     */
    fun prefetchByteOffsets(infoHash: String, offsets: List<Long>) {
        val active = torrents[infoHash] ?: return
        val handle = active.handle?.takeIf { it.isValid } ?: return
        if (active.pieceLength <= 0) return
        synchronized(nativeLock) {
            runCatching {
                offsets.forEachIndexed { i, off ->
                    if (off < 0L) return@forEachIndexed
                    val base = ((active.fileOffset + off) / active.pieceLength).toInt()
                    for (p in base..base + 1) {
                        if (p in active.firstPiece..active.lastPiece) {
                            handle.setPieceDeadline(p, PREVIEW_PREFETCH_DEADLINE_MS + i * 200)
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "prefetchByteOffsets failed", it) }
        }
    }

    /** Non-blocking: is the piece covering this file byte-offset present on disk yet? */
    fun isByteAvailable(infoHash: String, byteOffset: Long): Boolean = synchronized(nativeLock) {
        val active = torrents[infoHash] ?: return@synchronized false
        val handle = active.handle?.takeIf { it.isValid } ?: return@synchronized false
        if (active.pieceLength <= 0 || byteOffset < 0L) return@synchronized false
        val piece = ((active.fileOffset + byteOffset) / active.pieceLength).toInt()
        if (piece !in active.firstPiece..active.lastPiece) return@synchronized false
        val status = runCatching { handle.status(TorrentHandle.QUERY_PIECES) }.getOrNull()
            ?: return@synchronized false
        val pieces = status.pieces()
        val pieceCount = pieces.size()
        if (pieceCount == 0 || piece >= pieceCount) return@synchronized false
        runCatching { pieces.getBit(piece) }.getOrDefault(false)
    }

    /** Absolute path of the selected file, once chosen. */
    fun filePath(infoHash: String): String? = torrents[infoHash]?.filePath

    /**
     * Lowercase extension of the selected file (e.g. "mp4" / "mkv"), or null until a file is chosen.
     * The streamer uses this to know whether the container needs its EOF moov atom before the first
     * frame (mp4/m4v/mov) — those must not start on the short tail-grace the way an mkv can.
     */
    fun selectedFileExt(infoHash: String): String? =
        torrents[infoHash]?.filePath?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotBlank() }

    /** Length in bytes of the selected file. */
    fun fileLength(infoHash: String): Long = torrents[infoHash]?.fileLength ?: 0L

    /** Live status snapshot, or null if the torrent isn't active. */
    fun snapshot(infoHash: String): EngineStatus? = synchronized(nativeLock) {
        val active = torrents[infoHash] ?: return@synchronized null
        val handle = active.handle?.takeIf { it.isValid } ?: return@synchronized null
        val st = handle.status()
        val fileTotal = active.fileLength.takeIf { it > 0 } ?: st.total()
        val downloadedFile = (contiguousHeadBytes(infoHash)).coerceAtMost(fileTotal) // re-entrant lock
        // Progress on the selected file (sequential => contiguous head is a good proxy),
        // but never report less than libtorrent's own file-aware progress.
        val byHead = if (fileTotal > 0) downloadedFile.toFloat() / fileTotal else 0f
        val progress = maxOf(byHead, st.progress()).coerceIn(0f, 1f)
        EngineStatus(
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
        synchronized(nativeLock) { torrents[infoHash]?.handle?.takeIf { it.isValid }?.pause() }
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
            // session.remove frees the native torrent; serialize against the piece-status readers
            // or a concurrent read use-after-frees -> SIGSEGV (the whole-app lock).
            synchronized(nativeLock) {
                if (handle != null && handle.isValid) {
                    session.remove(handle, session_handle.delete_files)
                }
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
                synchronized(nativeLock) { handle.pause() }
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
        // Never strip deadlines off the tail/moov band. ExoPlayer re-reads the moov atom / mkv cues
        // (at EOF) on every seek; if a forward-walking read head reset those pieces, a later seek
        // would have to re-fetch the moov against the sequential cursor and stall again. Keep the
        // tail permanently time-critical.
        val tailPieces = (TAIL_PRIORITY_BYTES / active.pieceLength + 1)
        val tailFrom = active.lastPiece - tailPieces + 1
        synchronized(nativeLock) { runCatching {
            if (prev >= 0) {
                for (p in prev until curPiece) {
                    if (p in active.firstPiece..active.lastPiece && p < tailFrom) {
                        handle.resetPieceDeadline(p)
                    }
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
        }.onFailure { Log.w(TAG, "advanceReadHead failed", it) } }
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

        /** ~6 MB head buffer + ~8 MB tail (mp4 moov atom / mkv cues at EOF).
         *
         * The moov of a 2 h 1080p/4K mp4 routinely runs several MB (one stco/stsz/stts table per
         * track, scaled by frame count). A 1 MB tail only covered the last 1 MB of the file, so the
         * REST of the moov landed on un-prioritised EOF pieces. With SEQUENTIAL_DOWNLOAD on, the
         * sequential cursor sits at the head and won't reach those EOF pieces for many minutes —
         * yet ExoPlayer must read the entire moov before the first frame. Result: the readiness gate
         * flips to READY in <8 s (head + last-1 MB present), the player attaches, ranges to the moov
         * at EOF, and then blocks for >1 min waiting on moov pieces sequential download won't fetch.
         * 8 MB comfortably spans a feature-length moov so the whole atom is deadline-fetched up front. */
        const val HEAD_PRIORITY_BYTES = 6 * 1024 * 1024
        const val TAIL_PRIORITY_BYTES = 8 * 1024 * 1024

        /** Minimum number of head pieces to deadline IN ORDER, regardless of piece size. On big-piece
         *  torrents a byte budget collapses to 1 piece, leaving only piece 0 time-critical while the
         *  request queue scatters; a small staggered-deadline band keeps the head pipeline fed. */
        const val HEAD_PRIORITY_PIECES = 4

        /** mkv/webm cues are seek-only — deadline them far out so they never preempt a cold head. */
        const val MKV_TAIL_DEFER_MS = 30_000

        /** Per-piece deadline STEP for the head band. Must be large: libtorrent works all time-critical
         *  pieces toward their deadlines concurrently, so near-equal deadlines (~20 ms) complete in
         *  peer-speed order. A multi-second step keeps only the earliest head piece "overdue" so the
         *  swarm converges on it first, then the next — a strict in-order contiguous head fill. */
        const val HEAD_DEADLINE_STEP_MS = 3_000

        /** Relaxed deadline (ms) for scrub-preview sample pieces — far behind the 50 ms-class head/moov
         *  deadlines, so previews only sip spare swarm capacity and never delay playback. */
        const val PREVIEW_PREFETCH_DEADLINE_MS = 4000

        /** Seed/upload cap (bytes/s) — keep a streaming client from saturating the home upstream. */
        const val UPLOAD_RATE_LIMIT = 100 * 1024

        /** Sliding look-ahead band kept hot ahead of the player's read position. Bigger = more pieces
         *  in flight concurrently = better swarm utilisation under SEQUENTIAL_DOWNLOAD (closer to a
         *  desktop client's throughput), at the cost of more buffered-ahead RAM. Capable devices only. */
        const val READAHEAD_BYTES = 32 * 1024 * 1024

        /** Look-ahead on Android TV / low-RAM devices. Must be deep enough to keep the player's
         *  steady-state buffer (now ~24 MB) prioritised AHEAD of the read head, or the read outruns the
         *  prioritised window into un-deadlined pieces and rebuffers mid-stream on 4K VBR peaks. */
        const val LOW_POWER_READAHEAD_BYTES = 24 * 1024 * 1024

        /** High-uptime public trackers appended to every magnet so cold pickup finds peers fast
         *  instead of waiting on DHT. Curated from the well-known best-uptime lists (udp-first). */
        private val BOOST_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.tracker.cl:1337/announce",
            "udp://open.demonii.com:1337/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://explodie.org:6969/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.dler.org:6969/announce",
            "udp://opentracker.i2p.rocks:6969/announce",
        )

        private val VIDEO_EXTS = setOf("mp4", "mkv", "avi", "webm", "mov", "m4v", "flv", "ts")

        fun isVideoFile(name: String?): Boolean {
            val ext = name?.substringAfterLast('.', "")?.lowercase() ?: return false
            return ext in VIDEO_EXTS
        }

        /** A "sample" clip bundled alongside the real release — never the file we want to stream. */
        fun isSampleFile(name: String?): Boolean = name?.contains("sample", ignoreCase = true) == true
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
