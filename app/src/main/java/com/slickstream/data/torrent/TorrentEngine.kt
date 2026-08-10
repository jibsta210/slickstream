package com.slickstream.data.torrent

import android.content.Context
import android.util.Log
import com.slickstream.core.common.DeviceProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Entry
import org.libtorrent4j.FileStorage
import org.libtorrent4j.Priority
import org.libtorrent4j.PieceIndexBitfield
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
import org.libtorrent4j.swig.libtorrent
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
    /** Serializes acquisition/metadata/file selection per hash without blocking unrelated torrents. */
    private val acquisitionLocks = ConcurrentHashMap<String, Mutex>()
    /** Bridges suspendable acquisition locks with synchronous cache eviction. An eviction claims the
     *  hash before deletion; acquisitions wait for it, and eviction refuses while any add is in flight. */
    private val acquisitionStateLock = Any()
    private val acquisitionCounts = mutableMapOf<String, Int>()
    private val streamingLeaseCounts = mutableMapOf<String, Int>()
    private val evictingHashes = mutableSetOf<String>()
    /** Hashes created by the old shared-root layout. They continue using that root until their normal
     *  eviction, preserving every already-downloaded episode/padding file in a season pack. */
    private val legacyLayoutHashes = ConcurrentHashMap.newKeySet<String>()

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
        @Volatile var startupTailArmed: Boolean = false,
        /** Piece the BULK download should begin at — the file's first piece normally, but on a RESUME the
         *  piece under the resume position, so we buffer where the user will actually watch instead of
         *  wasting the startup window downloading the file head they'll skip. Set by [setStreamStart]. */
        @Volatile var streamStartPiece: Int = 0,
        /** STREAMING concentration: the selected file's base priority is IGNORE, so libtorrent can only
         *  download the head/resume/read-ahead/moov windows raised to TOP (+ whatever a read forces via
         *  ensureRange) — a MOVING DOWNLOAD WINDOW that stops the swarm scattering across the whole file.
         *  False for offline downloads, which want every piece. Set by [setStreamStart]. */
        @Volatile var concentrate: Boolean = false,
    )

    @Synchronized
    fun ensureStarted() {
        if (started.get()) return
        migrateLegacyStorageIfNeeded()
        // Android TV boxes / low-RAM devices choke on aggressive torrenting (hundreds of peer
        // connections + piece hashing + decode saturate a weak SoC and freeze the UI). Throttle.
        val lowPower = deviceProfile.isLowPower
        readaheadBytes = if (lowPower) LOW_POWER_READAHEAD_BYTES else READAHEAD_BYTES
        val sp = SettingsPack().apply {
            // --- Peer / socket load -----------------------------------------------------------------
            // High connection budget so we actually grab a big slice of the swarm (an "80 seeder"
            // torrent connecting to only 7 is just too few sockets), AND a download-rate cap below as
            // the SOLE anti-tank lever: with bandwidth bounded, a large idle/choked socket count doesn't
            // saturate a gigabit link. 1000 on capable; the TV (lowPower) stays a bit lower (600) only to
            // bound per-peer crypto on a weak SoC, not for bandwidth.
            setInteger(settings_pack.int_types.connections_limit.swigValue(), if (lowPower) 600 else 1000)
            setInteger(settings_pack.int_types.active_downloads.swigValue(), if (lowPower) 4 else 8)
            setInteger(settings_pack.int_types.active_seeds.swigValue(), if (lowPower) 2 else 4)
            setInteger(settings_pack.int_types.active_limit.swigValue(), if (lowPower) 8 else 16)
            setInteger(settings_pack.int_types.torrent_connect_boost.swigValue(), if (lowPower) 80 else 200)
            setInteger(settings_pack.int_types.connection_speed.swigValue(), if (lowPower) 80 else 300)
            // A tracker can respond successfully with zero peers. Stopping at that tier strands thin
            // swarms even when another tracker knows the only seed, so discovery must span all tiers.
            setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
            setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), true)
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
            // Port mapping is particularly valuable for obscure swarms: without it we can only reach
            // outbound-connectable peers. Its steady-state CPU cost is negligible compared with hashing.
            setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true)
            setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true)
            // Keep upload bounded, but leave enough for BitTorrent tit-for-tat. The old 100 KB/s cap
            // caused leecher-heavy/old swarms to choke this client even when they had the needed pieces.
            // An uncapped upload can still saturate a small home upstream, so this remains conservative.
            setInteger(
                settings_pack.int_types.upload_rate_limit.swigValue(),
                if (lowPower) LOW_POWER_UPLOAD_RATE_LIMIT else UPLOAD_RATE_LIMIT,
            )
            // Cap the DOWNLOAD on EVERY device. Under sequential streaming the engine pulls the WHOLE
            // file ahead at line rate in the background while you only play at the bitrate — uncapped on
            // a gigabit link that saturates the pipe and bufferbloats every other device in the house
            // (the "my whole internet tanks while it streams" report). 12 MB/s (~96 Mbps) still fills the
            // head + moov in ~1s and stays way ahead of even 4K playback, while leaving the bulk of a
            // gigabit link free. TV stays 8 MB/s (also bounds SHA-1 hashing / eMMC write pressure).
            setInteger(
                settings_pack.int_types.download_rate_limit.swigValue(),
                if (lowPower) 12 * 1024 * 1024 else 16 * 1024 * 1024,
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
                    // 1 MB is smaller than a normal piece/request pipeline and hard-throttles libtorrent's
                    // disk thread. Keep this bounded, but large enough to batch writes on slow flash.
                    setInteger(settings_pack.int_types.max_queued_disk_bytes.swigValue(), 8 * 1024 * 1024)
                    setInteger(settings_pack.int_types.file_pool_size.swigValue(), 16)
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
            setInteger(settings_pack.int_types.peer_connect_timeout.swigValue(), 15)
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

    /** Payloads before layout v2 shared one root. Moving only the selected path would orphan the other
     *  downloaded episodes/padding in a pack, while moving a top-level folder can steal files shared by
     *  another torrent. Keep known legacy hashes on their original save path until normal eviction;
     *  every newly-seen hash uses the isolated v2 directory. */
    private fun migrateLegacyStorageIfNeeded() {
        val layoutPrefs = context.getSharedPreferences(STORAGE_LAYOUT_PREFS, Context.MODE_PRIVATE)
        val legacyRoot = File(context.cacheDir, CACHE_DIR_NAME)
        legacyRoot.mkdirs()
        val pathPrefs = context.getSharedPreferences("torrent_cache_paths", Context.MODE_PRIVATE)
        for ((hash, rawPath) in pathPrefs.all) {
            if (!INFO_HASH_REGEX.matches(hash)) continue
            val source = (rawPath as? String)?.let(::File) ?: continue
            val relative = runCatching { source.relativeTo(legacyRoot) }.getOrNull() ?: continue
            if (!relative.path.startsWith("$hash${File.separator}")) legacyLayoutHashes += hash
        }
        if (layoutPrefs.getInt(STORAGE_LAYOUT_VERSION_KEY, 0) < STORAGE_LAYOUT_VERSION) {
            layoutPrefs.edit().putInt(STORAGE_LAYOUT_VERSION_KEY, STORAGE_LAYOUT_VERSION).commit()
        }
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
                    if (ih != null) {
                        torrents[ih]?.let { current ->
                            // A delayed alert for generation N must not erase a rapid replacement N+1.
                            val replacementIsLive = synchronized(nativeLock) {
                                current.handle?.let { h ->
                                    runCatching { h.isValid && h.inSession() }.getOrDefault(false)
                                } == true
                            }
                            // Null means generation N+1 is in session.download()/awaitHandle and has
                            // intentionally published its slot before the async handle appears. An old
                            // removal alert must never erase that acquisition window.
                            if (current.handle != null && !replacementIsLive) torrents.remove(ih, current)
                        }
                    }
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
            val handle = alert.handle()
            // An eviction can overtake an already-queued save alert. The wrapper may remain `isValid`
            // briefly, but inSession() is false; writing that stale blob resurrects a cache entry whose
            // payload and metadata were just deleted.
            val stillCurrent = synchronized(nativeLock) {
                runCatching { handle.isValid && handle.inSession() }.getOrDefault(false)
            }
            if (!stillCurrent) return
            val params = alert.params()
            val ih = handle.infoHash().toHex().lowercase()
            if (torrents[ih]?.handle == null) return
            val buf = AddTorrentParams.writeResumeDataBuf(params)
            val target = resumeFileFor(ih)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, ".${target.name}.tmp")
            tmp.writeBytes(buf)
            if (!tmp.renameTo(target)) {
                target.writeBytes(buf)
                tmp.delete()
            }
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
    suspend fun addMagnet(
        magnetUri: String,
        preferredFileIndex: Int?,
        expectedSeason: Int? = null,
        expectedEpisode: Int? = null,
        acquireStreamingLease: Boolean = false,
    ): String {
        ensureStarted()
        val boosted = withTrackers(magnetUri)
        val parsedHash = parseInfoHash(magnetUri)
        val infoHash = parsedHash
            ?: runCatching { AddTorrentParams.parseMagnetUri(boosted).infoHashes.best.toHex().lowercase() }.getOrNull()
            ?: error("Unparseable magnet (no info-hash)")
        if (infoHash.length != 40) {
            error("v2-only torrents are not supported by this libtorrent4j session wrapper")
        }
        beginAcquisition(infoHash)
        try {
            val result = acquisitionLocks.getOrPut(infoHash) { Mutex() }.withLock {
                addMagnetLocked(
                    boosted,
                    infoHash,
                    preferredFileIndex,
                    expectedSeason,
                    expectedEpisode,
                )
            }
            // Transfer acquisition protection into a streaming lease before the acquisition count is
            // released. This closes the tiny but destructive add-return -> streamer-ref TOCTOU window.
            if (acquireStreamingLease) {
                synchronized(acquisitionStateLock) {
                    streamingLeaseCounts[result] = (streamingLeaseCounts[result] ?: 0) + 1
                }
            }
            return result
        } finally {
            endAcquisition(infoHash)
        }
    }

    private suspend fun addMagnetLocked(
        boosted: String,
        infoHash: String,
        preferredFileIndex: Int?,
        expectedSeason: Int?,
        expectedEpisode: Int?,
    ): String {

        // Fast path: a Details prewarm (or a prior open) already has this torrent live — re-attach
        // instead of re-resolving (a second metadata fetch on an added torrent dead-latches 60s).
        torrents[infoHash]?.let { existing ->
            liveHandle(existing)?.let { handle ->
                // Season pack: the SAME infoHash carries every episode, distinguished only by
                // fileIndex. A re-attach that skips selectFile keeps the PREVIOUS episode's file
                // selected — the HTTP server then serves E5's bytes under E6's title.
                reselectFileIfNeeded(handle, existing, preferredFileIndex, expectedSeason, expectedEpisode)
                applySequentialAndPriority(handle, existing)
                return infoHash
            }
            torrents.remove(infoHash, existing)
        }

        // Metadata cache hit (a torrent opened before this build cached its .torrent): add ONCE with
        // full info — peers are discovered a single time, straight into the download, no network
        // metadata fetch at all.
        loadCachedMetadata(infoHash)?.let { cachedInfo ->
            Log.i(TAG, "metadata cache hit for $infoHash")
            return addWithInfo(cachedInfo, preferredFileIndex, expectedSeason, expectedEpisode)
        }

        // Cache miss (first watch): add the magnet LIVE, FORCE it active so its metadata fetch runs at
        // full tilt, read metadata off the handle, then stream — keeping every discovered peer (no
        // fetchMagnet discard-and-rediscover).
        // Resolve the info-hash up front (handles v1-hex AND base32/v2 magnets) and register it in the
        // map BEFORE adding, so a metadata timeout or VM failover can always find + remove the torrent —
        // a non-40-hex magnet used to be added to the session then orphaned with no removable key.
        val active = torrents.getOrPut(infoHash) { ActiveTorrent(infoHash) }

        val handle = findHandle(infoHash)?.takeIf { it.isValid && runCatching { it.inSession() }.getOrDefault(false) }
            ?: try {
                // 3-arg live add: (magnet, saveDir, flags). flags must be non-null (libtorrent4j ORs them
                // with no null guard). AUTO_MANAGED here is immediately overridden below.
                synchronized(nativeLock) {
                    session.download(boosted, torrentSavePath(infoHash), TorrentFlags.AUTO_MANAGED)
                }
                awaitHandle(infoHash) ?: run {
                    findHandle(infoHash)?.takeIf { it.isValid }?.let { orphan ->
                        synchronized(nativeLock) { runCatching { session.remove(orphan, session_handle.delete_files) } }
                    }
                    torrents.remove(infoHash)
                    error("Failed to obtain torrent handle")
                }
            } catch (cancelled: CancellationException) {
                cleanupCancelledAdd(infoHash, active, deleteFiles = true)
                throw cancelled
            }
        active.handle = handle

        // Take manual control NOW — DON'T wait on the queue auto-manager. A freshly-added AUTO_MANAGED
        // torrent can sit queued/paused while other torrents (a Details prewarm, cached seeders) hold the
        // active-download slots, so its ut_metadata fetch never starts: the "spins 45 s on N seeders,
        // never buffers, fails over" stall. Unmanaging + resuming makes metadata fetch immediately, just
        // as the old fetchMagnet did implicitly.
        // Serialized: another stream's cache eviction can session.remove this handle concurrently
        // (this hash isn't in streamingHashes yet), and a handle call racing a remove is a native
        // use-after-free SIGSEGV (see nativeLock).
        synchronized(nativeLock) {
            runCatching {
                handle.unsetFlags(
                    TorrentFlags.AUTO_MANAGED.or_(TorrentFlags.PAUSED).or_(TorrentFlags.UPLOAD_MODE),
                )
                handle.resume()
            }.onFailure { Log.w(TAG, "force-active failed for $infoHash", it) }
        }

        val info = try {
            awaitMetadataFromHandle(handle) ?: run {
                // Cold/dead magnet: free the session slot + map entry instead of stranding a live torrent.
                runCatching { removeTorrent(infoHash, removeFiles = true) }
                error("Timed out fetching torrent metadata")
            }
        } catch (cancelled: CancellationException) {
            // Details prewarms are routinely cancelled as the user navigates. The torrent was manually
            // resumed above, so failing to remove our acquisition leaves an invisible full-speed download.
            if (active.handle === handle) runCatching { removeTorrent(infoHash, removeFiles = true) }
            throw cancelled
        }
        persistMetadata(infoHash, info, handle)
        try {
            selectFile(handle, info, active, preferredFileIndex, expectedSeason, expectedEpisode)
        } catch (t: Throwable) {
            // No playable file (RAR/disc release): tear the torrent down like the metadata-timeout
            // branch, or the force-resumed add keeps downloading the whole multi-GB archive (no file
            // priorities were ever applied) long after the player failed over to another source.
            runCatching { removeTorrent(infoHash, removeFiles = true) }
            throw t
        }
        applySequentialAndPriority(handle, active)
        synchronized(nativeLock) { runCatching { handle.resume() } }
        return infoHash
    }

    /** Add a torrent we already have full [TorrentInfo] for (metadata cache hit / re-open), select the
     *  file and start sequential streaming. One add, peers discovered once. */
    private suspend fun addWithInfo(
        info: TorrentInfo,
        preferredFileIndex: Int?,
        expectedSeason: Int?,
        expectedEpisode: Int?,
    ): String {
        val infoHash = info.infoHash().toHex().lowercase()
        torrents[infoHash]?.let { existing ->
            liveHandle(existing)?.let { handle ->
                // Same season-pack re-select as addMagnet's fast path.
                reselectFileIfNeeded(handle, existing, preferredFileIndex, expectedSeason, expectedEpisode)
                applySequentialAndPriority(handle, existing)
                return infoHash
            }
            torrents.remove(infoHash, existing)
        }
        val active = torrents.getOrPut(infoHash) { ActiveTorrent(infoHash) }
        val resumeFile = resumeFileFor(infoHash).takeIf { it.exists() }
        // 6-arg form: (info, saveDir, resumeFile, filePriorities, peers, flags). flags non-null (see
        // addMagnet). resumeFile re-checks the partial already on disk instead of re-downloading it.
        val handle = try {
            synchronized(nativeLock) {
                session.download(info, torrentSavePath(infoHash), resumeFile, null, null, TorrentFlags.AUTO_MANAGED)
            }
            awaitHandle(infoHash) ?: run {
                findHandle(infoHash)?.takeIf { it.isValid }?.let { orphan ->
                    synchronized(nativeLock) { runCatching { session.remove(orphan, session_handle.delete_files) } }
                }
                torrents.remove(infoHash)
                error("Failed to obtain torrent handle for $infoHash")
            }
        } catch (cancelled: CancellationException) {
            // This path reopens an existing payload from cached metadata/resume data. Cancel the native
            // add, but never delete the partial the user expected to resume later.
            cleanupCancelledAdd(infoHash, active, deleteFiles = false)
            throw cancelled
        }
        active.handle = handle
        try {
            selectFile(handle, info, active, preferredFileIndex, expectedSeason, expectedEpisode)
        } catch (t: Throwable) {
            // Cached metadata may be reopening gigabytes of valid partial payload (including other
            // episodes in a pack). Remove the unprioritized handle, but preserve those bytes for a
            // later correct selection/LRU eviction instead of deleting the entire hash directory.
            cleanupCancelledAdd(infoHash, active, deleteFiles = false)
            throw t
        }
        applySequentialAndPriority(handle, active)
        synchronized(nativeLock) { runCatching { handle.resume() } }
        return infoHash
    }

    /** Read previously-cached bencoded metadata for [hash] (written by an older build), or null. */
    private fun loadCachedMetadata(hash: String): TorrentInfo? {
        val cached = metadataFileFor(hash).takeIf { it.exists() } ?: return null
        val info = runCatching { TorrentInfo.bdecode(cached.readBytes()) }.getOrNull()
        // SELF-HEAL poisoned caches: builds hit by the empty-file-table bug persisted .meta files with
        // numFiles=0 — a cache hit on one re-fails the add forever. Parse-but-fileless = delete + refetch.
        if (info == null || runCatching { info.files().numFiles() }.getOrDefault(0) <= 0) {
            runCatching { cached.delete() }
            return null
        }
        return info
    }

    /** Persist a minimal .torrent immediately after the first successful metadata exchange. Without
     *  this, the separately-saved resume blob is unreachable after process death because reopening a
     *  magnet first needs the same metadata all over again. */
    private fun persistMetadata(infoHash: String, info: TorrentInfo, handle: TorrentHandle) {
        // Never persist a file-less info — a poisoned .meta re-breaks this hash on every future open.
        if (runCatching { info.files().numFiles() }.getOrDefault(0) <= 0) return
        runCatching {
            val params = AddTorrentParams().apply {
                setTorrentInfo(info)
                val announce = synchronized(nativeLock) {
                    runCatching { handle.trackers().map { it.url() }.distinct() }.getOrDefault(emptyList())
                }
                if (announce.isNotEmpty()) setTrackers(announce)
            }
            val bytes = Entry(libtorrent.write_torrent_file(params.swig())).bencode()
            val target = metadataFileFor(infoHash)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, ".${target.name}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                target.writeBytes(bytes)
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "persistMetadata failed for $infoHash", it) }
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
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + METADATA_TIMEOUT_SECONDS * 1000L
        var lastReannounce = 0L
        while (System.currentTimeMillis() < deadline) {
            if (!handle.isValid) return null
            // Serialized like every other handle read — a concurrent eviction's session.remove
            // racing this status() call is a native use-after-free (see nativeLock).
            val info = synchronized(nativeLock) {
                runCatching {
                    if (handle.status().hasMetadata()) {
                        // The plain torrentFile() view may omit v2/hybrid piece layers, making
                        // write_torrent_file fail and silently defeating cold metadata reuse — BUT
                        // torrentFileWithHashes() can itself hand back a valid-looking info whose FILE
                        // TABLE is EMPTY (numFiles=0). Trusting it made selectFile see "no video files"
                        // and throw archive/RAR for EVERY torrent of EVERY title — the "cannot find any
                        // sources for Alien Covenant" outage. Only accept an info that actually carries
                        // files; otherwise keep polling until one does.
                        listOfNotNull(handle.torrentFileWithHashes(), handle.torrentFile())
                            .firstOrNull { i -> runCatching { i.files().numFiles() > 0 }.getOrDefault(false) }
                    } else null
                }.getOrNull()
            }
            if (info != null) return info
            val now = System.currentTimeMillis()
            if (now - startedAt >= METADATA_REANNOUNCE_AFTER_MS &&
                now - lastReannounce >= METADATA_REANNOUNCE_INTERVAL_MS
            ) {
                synchronized(nativeLock) {
                    runCatching {
                        handle.forceReannounce()
                        handle.forceDHTAnnounce()
                    }
                }
                lastReannounce = now
            }
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
        synchronized(nativeLock) { session.find(Sha1Hash.parseHex(infoHash)) }

    /** A handle may remain Java-valid briefly after session.remove(). Never take that stale generation
     *  as the fast path: it cannot download or answer status and would strand the player in buffering. */
    private fun liveHandle(active: ActiveTorrent): TorrentHandle? = synchronized(nativeLock) {
        active.handle?.takeIf { handle ->
            runCatching { handle.isValid && handle.inSession() }.getOrDefault(false)
        }
    }

    private suspend fun beginAcquisition(infoHash: String) {
        while (true) {
            val claimed = synchronized(acquisitionStateLock) {
                if (infoHash in evictingHashes) {
                    false
                } else {
                    acquisitionCounts[infoHash] = (acquisitionCounts[infoHash] ?: 0) + 1
                    true
                }
            }
            if (claimed) return
            delay(ACQUISITION_EVICTION_POLL_MS)
        }
    }

    private fun endAcquisition(infoHash: String) = synchronized(acquisitionStateLock) {
        val remaining = (acquisitionCounts[infoHash] ?: 1) - 1
        if (remaining <= 0) acquisitionCounts.remove(infoHash) else acquisitionCounts[infoHash] = remaining
    }

    private fun beginEviction(infoHash: String): Boolean = synchronized(acquisitionStateLock) {
        if ((acquisitionCounts[infoHash] ?: 0) > 0 ||
            (streamingLeaseCounts[infoHash] ?: 0) > 0 ||
            !evictingHashes.add(infoHash)
        ) false else true
    }

    private fun endEviction(infoHash: String) = synchronized(acquisitionStateLock) {
        evictingHashes.remove(infoHash)
        Unit
    }

    fun isAcquiring(infoHash: String): Boolean = synchronized(acquisitionStateLock) {
        (acquisitionCounts[infoHash] ?: 0) > 0
    }

    fun releaseStreamingLease(infoHash: String) = synchronized(acquisitionStateLock) {
        val remaining = (streamingLeaseCounts[infoHash] ?: 1) - 1
        if (remaining <= 0) streamingLeaseCounts.remove(infoHash) else streamingLeaseCounts[infoHash] = remaining
    }

    /** session.download() installs its handle asynchronously. Cancellation can arrive before findHandle
     *  sees it, so poll briefly in NonCancellable and remove the eventual handle; otherwise an abandoned
     *  Details warm remains an invisible all-files download. */
    private suspend fun cleanupCancelledAdd(
        infoHash: String,
        active: ActiveTorrent,
        deleteFiles: Boolean,
    ) = withContext(NonCancellable) {
        var handle = findHandle(infoHash)
        repeat(CANCELLED_ADD_CLEANUP_POLLS) {
            if (handle != null) return@repeat
            delay(HANDLE_POLL_INTERVAL_MS)
            handle = findHandle(infoHash)
        }
        handle?.takeIf { it.isValid }?.let { orphan ->
            synchronized(nativeLock) {
                runCatching {
                    if (deleteFiles) session.remove(orphan, session_handle.delete_files)
                    else session.remove(orphan)
                }
            }
        }
        torrents.remove(infoHash, active)
        if (deleteFiles && infoHash !in legacyLayoutHashes) {
            runCatching { File(savePath, infoHash).deleteRecursively() }
        }
    }

    /**
     * Fast-path re-attach helper: if the caller asked for a DIFFERENT (valid, non-sample video) file
     * than the one currently selected — the season-pack next-episode case — re-run [selectFile] so the
     * piece range, path and priorities move to the requested episode. A null/invalid preferred index
     * keeps the existing selection untouched.
     */
    private fun reselectFileIfNeeded(
        handle: TorrentHandle,
        active: ActiveTorrent,
        preferredFileIndex: Int?,
        expectedSeason: Int?,
        expectedEpisode: Int?,
    ) {
        if (preferredFileIndex != null && preferredFileIndex == active.fileIndex &&
            (expectedSeason == null || expectedEpisode == null)
        ) return
        if (preferredFileIndex == null && (expectedSeason == null || expectedEpisode == null)) return
        val info = synchronized(nativeLock) { runCatching { handle.torrentFile() }.getOrNull() } ?: return
        val files = info.files()
        val validPreferred = preferredFileIndex?.takeIf { index ->
            index in 0 until files.numFiles() && isVideoFile(files.fileName(index)) &&
                !isSampleFile(files.fileName(index))
        }
        val episodeMatch = matchingEpisodeFile(files, expectedSeason, expectedEpisode)
        val requested = episodeMatch ?: validPreferred
        if (requested == null || requested == active.fileIndex) return
        selectFile(handle, info, active, requested, expectedSeason, expectedEpisode)
    }

    /** Choose the file to stream: explicit index if valid, else the largest video file. */
    private fun selectFile(
        handle: TorrentHandle,
        info: TorrentInfo,
        active: ActiveTorrent,
        preferredFileIndex: Int?,
        expectedSeason: Int?,
        expectedEpisode: Int?,
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
        val playableVideos = (0 until numFiles)
            .filter { isVideoFile(files.fileName(it)) && !isSampleFile(files.fileName(it)) }
        val largestVideo = playableVideos
            .maxByOrNull { files.fileSize(it) }

        val episodeMatch = matchingEpisodeFile(files, expectedSeason, expectedEpisode)
        // Clear filename evidence beats a stale-but-in-range addon index. With no index and several
        // episode files, failing is safer than silently serving the longest/random episode.
        val chosen = episodeMatch ?: prefValid ?: largestVideo?.takeIf {
            expectedSeason == null || expectedEpisode == null || playableVideos.size == 1
        }
            ?: run {
                val names = (0 until minOf(numFiles, 8)).joinToString { files.fileName(it) }
                error(
                    "No playable video file in this torrent (archive/RAR release) — " +
                        "numFiles=$numFiles s=$expectedSeason e=$expectedEpisode pref=$preferredFileIndex files=[$names]",
                )
            }

        active.fileIndex = chosen
        active.fileLength = files.fileSize(chosen)
        active.fileOffset = files.fileOffset(chosen)
        active.pieceLength = info.pieceLength()
        active.firstPiece = (active.fileOffset / active.pieceLength).toInt()
        active.lastPiece = ((active.fileOffset + active.fileLength - 1) / active.pieceLength).toInt()
        // Default the bulk-start anchor to the file head; a resume overrides it via setStreamStart().
        active.streamStartPiece = active.firstPiece

        val relPath = files.filePath(chosen)
        active.filePath = File(torrentSavePath(active.infoHash), relPath).absolutePath

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
        synchronized(nativeLock) { handle.prioritizeFiles(priorities) }
    }

    /** Recover a pack episode when the addon omitted fileIdx. Torrent filenames overwhelmingly encode
     *  SxxExx or 1x02; selecting the largest file silently chose a random/long episode. */
    private fun matchingEpisodeFile(files: FileStorage, season: Int?, episode: Int?): Int? {
        if (season == null || episode == null) return null
        val sxe = Regex("(?i)(?:^|[^a-z0-9])s0*${season}[^a-z0-9]*e0*${episode}(?:[^0-9]|$)")
        val x = Regex("(?i)(?:^|[^0-9])0*${season}x0*${episode}(?:[^0-9]|$)")
        val words = Regex(
            "(?i)(?:^|[^a-z0-9])season[^0-9]*0*${season}.*" +
                "episode[^0-9]*0*${episode}(?:[^0-9]|$)",
        )
        return (0 until files.numFiles()).firstOrNull { index ->
            val path = files.filePath(index)
            val name = files.fileName(index)
            isVideoFile(name) && !isSampleFile(name) &&
                (sxe.containsMatchIn(path) || x.containsMatchIn(path) || words.containsMatchIn(path))
        }
    }

    private fun applySequentialAndPriority(handle: TorrentHandle, active: ActiveTorrent) {
        // Force an actively-downloading, RUNNING state and take manual control, THEN prime the
        // deadlines. resume() runs BEFORE prioritizeHeadAndTail: set_piece_deadline only enters the
        // time-critical list on a RUNNING torrent, so deadlines applied while still PAUSED (the
        // Details-prewarm pause/resume path) silently didn't stick. SEQUENTIAL_DOWNLOAD stays on — it
        // gives the in-order steady-state buffer-ahead playback relies on; the staggered head/tail
        // deadlines (prioritizeHeadAndTail) ride ON TOP to pull the first pieces + the moov/cues to the
        // front of that order.
        synchronized(nativeLock) { runCatching {
            handle.unsetFlags(
                TorrentFlags.UPLOAD_MODE
                    .or_(TorrentFlags.PAUSED)
                    .or_(TorrentFlags.AUTO_MANAGED),
            )
            // Park the sequential cursor AT THE BULK-START PIECE — the selected file's first piece
            // normally, but the RESUME piece when resuming mid-file. In a season pack the wanted episode
            // sits at a big fileOffset (firstPiece is hundreds of pieces in); the torrent-wide
            // SEQUENTIAL_DOWNLOAD flag aims libtorrent's "lowest piece" cursor at piece 0, so the head
            // filled only as far as the few deadlined pieces and then stalled. On a RESUME, parking at
            // firstPiece meant the bulk fill poured into the file HEAD the user is skipping past — over a
            // minute of wasted download before it redirected. setSequentialRange(streamStartPiece, …)
            // aims the bulk in-order fill where the user will actually watch; the file header still
            // downloads via its own TOP-priority deadline band in prioritizeHeadAndTail. Fall back to the
            // global flag if unavailable.
            val startP = active.streamStartPiece.coerceIn(active.firstPiece, active.lastPiece)
            val ranged = runCatching {
                handle.setSequentialRange(startP, active.lastPiece)
            }.isSuccess
            if (!ranged) handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
            handle.resume()
        }.onFailure { Log.w(TAG, "set streaming flags failed", it) } }
        prioritizeHeadAndTail(active)
    }

    /**
     * Set up STREAMING download shaping right after the torrent is added/selected:
     *  - anchor the bulk download at a RESUME position ([fraction] of the file, 0f..1f) instead of the
     *    file head, so a resumed title buffers where the user will actually watch;
     *  - when [concentrate], set the selected file's base priority to IGNORE so libtorrent can only
     *    download the head/resume/read-ahead/moov windows (raised to TOP) plus whatever a read forces via
     *    ensureRange — a MOVING DOWNLOAD WINDOW that stops the swarm scattering across the whole file
     *    while the head is still filling ("96 seeders, 4.6 MB/s, 1% downloaded scattered, still buffering").
     *
     * Offline downloads pass concentrate=false — they want every piece, and nothing reads to advance a
     * window. Playback can never starve under concentration: ensureRange force-raises + deadlines the exact
     * pieces each read needs, independently of this base.
     */
    fun setStreamStart(infoHash: String, fraction: Float, concentrate: Boolean) {
        val active = torrents[infoHash] ?: return
        val handle = liveHandle(active) ?: return
        if (active.pieceLength <= 0 || active.fileLength <= 0) return

        if (fraction > 0.001f) {
            val startByte = (active.fileLength * fraction.coerceIn(0f, 0.98f)).toLong()
            active.streamStartPiece = ((active.fileOffset + startByte) / active.pieceLength).toInt()
                .coerceIn(active.firstPiece, active.lastPiece)
        }
        // Force advanceReadHead to re-apply from the (possibly new) anchor on the next read().
        active.readHeadPiece = -1

        if (concentrate && !active.concentrate) {
            active.concentrate = true
            // Base the whole selected file at IGNORE in ONE native call; the head/resume/tail bands below
            // (and the moving read-ahead window + ensureRange during playback) re-raise what's needed.
            val info = synchronized(nativeLock) { runCatching { handle.torrentFile() }.getOrNull() }
            val numFiles = runCatching { info?.files()?.numFiles() ?: 0 }.getOrDefault(0)
            if (info != null && numFiles > 0) {
                synchronized(nativeLock) {
                    runCatching { handle.prioritizeFiles(Array(numFiles) { Priority.IGNORE }) }
                }
            }
        }
        Log.i(
            TAG,
            "setStreamStart $infoHash frac=$fraction concentrate=$concentrate startPiece=${active.streamStartPiece} " +
                "(first=${active.firstPiece} last=${active.lastPiece})",
        )
        applySequentialAndPriority(handle, active)
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
        // Bound urgency by BYTES. Four mandatory pieces made 128 MB time-critical on a 32 MB-piece
        // old torrent, so the one piece required to start competed with three later pieces.
        val headPieces = maxOf(1, ceilDiv(HEAD_PRIORITY_BYTES, pieceLen))
        // Only ISO-BMFF containers can require an EOF moov before first frame. Other containers fetch an
        // EOF seek map on demand if the player actually asks for it; speculative EOF work starves thin heads.
        val tailFrom = startupTailFromPiece(active)
        // Let the first contiguous head piece win before the EOF band. Giving both deadline zero made a
        // 32 MB head piece and a 32 MB tail piece split a thin swarm in parallel, doubling the time until
        // the UI could show meaningful head progress without reducing total MP4 readiness work.
        val tailBase = headPieces * HEAD_DEADLINE_STEP_MS

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
            // (1) FILE-HEADER band at the file start — ExoPlayer must parse the container header (ftyp/moov
            //     or EBML/Tracks) at offset 0 before it can decode OR seek, so this is fetched even on a
            //     resume. Small (~HEAD_PRIORITY_BYTES).
            for (i in 0 until headPieces) {
                val p = active.firstPiece + i
                if (p in active.firstPiece..active.lastPiece) {
                    handle.piecePriority(p, Priority.TOP_PRIORITY)
                    handle.setPieceDeadline(p, i * HEAD_DEADLINE_STEP_MS)
                }
            }
            // (2) RESUME band — the bytes the user will actually start watching, when resuming past the
            //     header band. Deadlined right after the header so playback can begin at the resume point
            //     without first waiting on the wasted file-head fill. Skipped for a from-start play
            //     (streamStartPiece == firstPiece), where band (1) already covers it.
            var resumeBase = headPieces * HEAD_DEADLINE_STEP_MS
            if (active.streamStartPiece > active.firstPiece + headPieces - 1) {
                for (i in 0 until headPieces) {
                    val p = active.streamStartPiece + i
                    if (p in active.firstPiece..active.lastPiece) {
                        handle.piecePriority(p, Priority.TOP_PRIORITY)
                        handle.setPieceDeadline(p, resumeBase + i * HEAD_DEADLINE_STEP_MS)
                    }
                }
                resumeBase += headPieces * HEAD_DEADLINE_STEP_MS
            }
            // (3) EOF moov/cues band, after the header + resume bands.
            if (tailFrom != null) {
                var i = 0
                for (p in active.lastPiece downTo tailFrom) {
                    if (p in active.firstPiece..active.lastPiece) {
                        handle.piecePriority(p, Priority.TOP_PRIORITY)
                        handle.setPieceDeadline(p, maxOf(tailBase, resumeBase) + i * HEAD_DEADLINE_STEP_MS)
                        i++
                    }
                }
            }
            active.startupTailArmed = tailFrom != null
        }.onFailure { Log.w(TAG, "prioritizeHeadAndTail failed", it) } }
    }

    /**
     * The contiguous head is in and the EOF moov band is now the ONLY thing between the user and a
     * first frame. Stop waiting for it passively: re-deadline exactly those pieces at the FRONT of the
     * time-critical queue.
     *
     * At startup [prioritizeHeadAndTail] deliberately staggers the EOF band BEHIND the head band
     * (tailBase = headPieces x [HEAD_DEADLINE_STEP_MS]) so a 32 MB head piece and a 32 MB tail piece
     * don't split a thin swarm while the head is what the UI is showing progress against. That ordering
     * is right up until the head lands — after which those stale head deadlines are the only reason the
     * bytes that ARE blocking playback are still queued second. This re-issues the band from deadline 0
     * with a tight step, so the swarm converges on the moov immediately.
     *
     * Deliberately does NOT touch the head band's priorities or deadlines: the read-ahead the player is
     * about to need rides on them, and a completed piece's deadline is a no-op anyway. Idempotent and
     * cheap (one native round-trip over a handful of pieces) — safe to call on every poll while the
     * moov is missing.
     */
    fun promoteStartupTail(infoHash: String) {
        val active = torrents[infoHash] ?: return
        if (!active.startupTailArmed || active.pieceLength <= 0) return
        val handle = active.handle?.takeIf { it.isValid } ?: return
        val tailFrom = tailFromPiece(active)
        synchronized(nativeLock) {
            if (!active.startupTailArmed) return@synchronized
            runCatching {
                var i = 0
                for (p in active.lastPiece downTo tailFrom) {
                    if (p in active.firstPiece..active.lastPiece) {
                        handle.piecePriority(p, Priority.TOP_PRIORITY)
                        handle.setPieceDeadline(p, i * TAIL_PROMOTION_DEADLINE_STEP_MS)
                        i++
                    }
                }
            }.onFailure { Log.w(TAG, "promoteStartupTail failed", it) }
        }
    }

    /** A faststart MP4 revealed its moov in the head, so its speculative EOF work is no longer useful. */
    fun cancelStartupTail(infoHash: String) {
        val active = torrents[infoHash] ?: return
        if (!active.startupTailArmed || active.pieceLength <= 0) return
        val handle = active.handle?.takeIf { it.isValid } ?: return
        synchronized(nativeLock) {
            if (!active.startupTailArmed) return@synchronized
            runCatching {
                val startupHeadLast = active.firstPiece +
                    maxOf(1, ceilDiv(HEAD_PRIORITY_BYTES, active.pieceLength)) - 1
                for (p in active.lastPiece downTo tailFromPiece(active)) {
                    if (p in active.firstPiece..active.lastPiece && p > startupHeadLast) {
                        handle.resetPieceDeadline(p)
                        handle.piecePriority(p, Priority.DEFAULT)
                    }
                }
                active.startupTailArmed = false
            }
        }
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
        // MUST match prioritizeHeadAndTail's deadlined tail count exactly — the old "/pieceLength + 1"
        // required one EOF piece MORE than was ever deadlined, so tailAvailable rarely went true on its
        // own and READY only fired via the 15s hard-cap: a fixed 15s "Almost ready" tax on every stream.
        val tailFrom = tailFromPiece(active)
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
        contiguousHeadBytes(active, status.pieces())
    }

    private fun contiguousHeadBytes(active: ActiveTorrent, pieces: PieceIndexBitfield): Long {
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

    /** Contiguous downloaded bytes starting at an arbitrary piece — the readiness signal at the RESUME
     *  anchor (whether playback can actually START at streamStartPiece). Byte-approximate (whole pieces);
     *  the readiness gate only needs a ~2 MB threshold, so exactness doesn't matter. */
    private fun contiguousBytesFrom(active: ActiveTorrent, pieces: PieceIndexBitfield, fromPiece: Int): Long {
        val pieceCount = pieces.size()
        if (pieceCount == 0) return 0L
        var contiguous = 0
        var p = fromPiece
        while (p in active.firstPiece..active.lastPiece && p < pieceCount && pieces.getBit(p)) {
            contiguous++; p++
        }
        return (contiguous.toLong() * active.pieceLength).coerceIn(0L, active.fileLength)
    }

    /**
     * Force a fresh tracker + DHT announce to re-discover peers. Used to recover a download that
     * stalled to 0 B/s mid-stream because its current peers dropped — instead of sitting dead until
     * libtorrent's next scheduled (up to ~30 min) announce.
     */
    fun reannounce(infoHash: String) {
        val handle = torrents[infoHash]?.handle?.takeIf { it.isValid } ?: return
        synchronized(nativeLock) {
            runCatching {
                handle.forceReannounce()
                handle.forceDHTAnnounce()
            }.onFailure { Log.w(TAG, "reannounce failed for $infoHash", it) }
        }
    }

    /** True if the tail pieces (needed for mp4 moov atoms) are present. */
    fun tailAvailable(infoHash: String): Boolean = synchronized(nativeLock) {
        val active = torrents[infoHash] ?: return@synchronized false
        val handle = active.handle?.takeIf { it.isValid } ?: return@synchronized false
        if (active.pieceLength <= 0) return@synchronized false
        // MUST match prioritizeHeadAndTail's deadlined tail count exactly — the old "/pieceLength + 1"
        // required one EOF piece MORE than was ever deadlined, so tailAvailable rarely went true on its
        // own and READY only fired via the 15s hard-cap: a fixed 15s "Almost ready" tax on every stream.
        val tailFrom = tailFromPiece(active)
        val status = runCatching { handle.status(TorrentHandle.QUERY_PIECES) }.getOrNull()
            ?: return@synchronized false
        val pieces = status.pieces()
        val pieceCount = pieces.size()
        if (pieceCount == 0) return@synchronized false
        for (p in active.lastPiece downTo tailFrom) {
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

    /**
     * Downsample the selected file's piece bitfield into [buckets] fill fractions (0f..1f) spanning the
     * file start→end — the data behind the player's "chunk bar". Empty array until pieces exist.
     */
    fun pieceMap(infoHash: String, buckets: Int): FloatArray = synchronized(nativeLock) {
        if (buckets <= 0) return@synchronized FloatArray(0)
        val active = torrents[infoHash] ?: return@synchronized FloatArray(0)
        val handle = active.handle?.takeIf { it.isValid } ?: return@synchronized FloatArray(0)
        val status = runCatching { handle.status(TorrentHandle.QUERY_PIECES) }.getOrNull()
            ?: return@synchronized FloatArray(0)
        val pieces = status.pieces()
        val pieceCount = pieces.size()
        if (pieceCount == 0) return@synchronized FloatArray(0)
        val first = active.firstPiece
        val last = active.lastPiece
        val total = (last - first + 1).coerceAtLeast(1)
        FloatArray(buckets) { b ->
            val start = first + (b.toLong() * total / buckets).toInt()
            val end = first + ((b + 1).toLong() * total / buckets).toInt() - 1
            var present = 0
            var count = 0
            var p = start
            while (p <= end && p in first..last) {
                count++
                if (p < pieceCount && runCatching { pieces.getBit(p) }.getOrDefault(false)) present++
                p++
            }
            if (count > 0) present.toFloat() / count else 0f
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

    /** One QUERY_PIECES call for an arbitrary set of file-relative offsets. */
    fun availableByteOffsets(infoHash: String, byteOffsets: List<Long>): BooleanArray = synchronized(nativeLock) {
        val result = BooleanArray(byteOffsets.size)
        if (byteOffsets.isEmpty()) return@synchronized result
        val active = torrents[infoHash] ?: return@synchronized result
        val handle = active.handle?.takeIf { it.isValid } ?: return@synchronized result
        if (active.pieceLength <= 0) return@synchronized result
        val status = runCatching { handle.status(TorrentHandle.QUERY_PIECES) }.getOrNull()
            ?: return@synchronized result
        val pieces = status.pieces()
        val pieceCount = pieces.size()
        if (pieceCount == 0) return@synchronized result
        byteOffsets.forEachIndexed { i, offset ->
            if (offset >= 0L) {
                val piece = ((active.fileOffset + offset) / active.pieceLength).toInt()
                result[i] = piece in active.firstPiece..active.lastPiece &&
                    piece < pieceCount && runCatching { pieces.getBit(piece) }.getOrDefault(false)
            }
        }
        result
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

    /**
     * For an mp4/m4v/mov: is the `moov` index up FRONT (a "faststart"/web-optimized file)? Walks the
     * top-level boxes in the already-downloaded contiguous head:
     *  - `true`  = `moov` appears before any media (`mdat`/`moof`) → the index is in the head, so we can
     *    start WITHOUT the EOF tail (it streams in with the head),
     *  - `false` = media comes first → the moov is at the END → the tail genuinely IS required,
     *  - `null`  = not enough head yet to decide (caller stays conservative until it resolves).
     * Reads the on-disk file directly (offset 0 = file start); only bytes within the contiguous head.
     */
    fun mp4MoovInHead(infoHash: String, knownHeadBytes: Long? = null): Boolean? {
        val active = torrents[infoHash] ?: return null
        val path = active.filePath ?: return null
        val head = knownHeadBytes ?: contiguousHeadBytes(infoHash)
        if (head < 16L) return null
        return runCatching {
            java.io.RandomAccessFile(path, "r").use { raf ->
                var pos = 0L
                val hdr = ByteArray(8)
                while (pos + 8 <= head && pos < MP4_SCAN_LIMIT) {
                    raf.seek(pos)
                    if (raf.read(hdr, 0, 8) < 8) return@use null
                    val size32 = ((hdr[0].toInt() and 0xFF) shl 24) or ((hdr[1].toInt() and 0xFF) shl 16) or
                        ((hdr[2].toInt() and 0xFF) shl 8) or (hdr[3].toInt() and 0xFF)
                    when (String(hdr, 4, 4, Charsets.US_ASCII)) {
                        "moov" -> return@use true               // index up front → faststart, no tail wait
                        "mdat", "moof" -> return@use false      // media before index → moov at EOF
                    }
                    val boxLen: Long = when {
                        size32 == 1 -> {                        // 64-bit largesize
                            if (pos + 16 > head) return@use null
                            raf.seek(pos + 8); val lb = ByteArray(8)
                            if (raf.read(lb, 0, 8) < 8) return@use null
                            var s = 0L; for (b in lb) s = (s shl 8) or (b.toLong() and 0xFF); s
                        }
                        size32 == 0 -> return@use false         // box runs to EOF before moov
                        else -> size32.toLong()
                    }
                    if (boxLen < 8L) return@use null
                    pos += boxLen
                }
                null
            }
        }.getOrNull()
    }

    /** Length in bytes of the selected file. */
    fun fileLength(infoHash: String): Long = torrents[infoHash]?.fileLength ?: 0L

    /** Live status snapshot, or null if the torrent isn't active. */
    fun snapshot(infoHash: String): EngineStatus? = synchronized(nativeLock) {
        val active = torrents[infoHash] ?: return@synchronized null
        val handle = active.handle?.takeIf { it.isValid } ?: return@synchronized null
        val st = handle.status(TorrentHandle.QUERY_PIECES)
        val pieces = st.pieces()
        val fileTotal = active.fileLength.takeIf { it > 0 } ?: st.total()
        val downloadedFile = contiguousHeadBytes(active, pieces).coerceAtMost(fileTotal)
        val tailProgress = startupTailProgressBytes(handle, active, pieces)
        // Progress on the selected file (sequential => contiguous head is a good proxy),
        // but never report less than libtorrent's own file-aware progress.
        val byHead = if (fileTotal > 0) downloadedFile.toFloat() / fileTotal else 0f
        val progress = maxOf(byHead, st.progress()).coerceIn(0f, 1f)
        // Contiguous bytes at the RESUME anchor — the "can playback START here" signal. Equals the head
        // figure for a from-start play (streamStartPiece == firstPiece), so it changes nothing there.
        val resumeContig = if (active.streamStartPiece > active.firstPiece)
            contiguousBytesFrom(active, pieces, active.streamStartPiece) else downloadedFile
        // Hash-verifying on-disk data (cached reopen / resume): rate + seeders read 0 here but it is NOT
        // a stall. Guarded — an unexpected enum in some libtorrent4j build must never crash the snapshot.
        val checking = runCatching {
            when (st.state()) {
                org.libtorrent4j.TorrentStatus.State.CHECKING_FILES,
                org.libtorrent4j.TorrentStatus.State.CHECKING_RESUME_DATA -> true
                else -> false
            }
        }.getOrDefault(false)
        EngineStatus(
            progress = progress,
            downloadRate = st.downloadRate(),
            uploadRate = st.uploadRate(),
            seeders = st.numSeeds(),
            peers = st.numPeers(),
            downloadedBytes = (progress * fileTotal).toLong().coerceAtMost(fileTotal),
            contiguousHeadBytes = downloadedFile,
            contiguousResumeBytes = resumeContig,
            startupTailProgressBytes = tailProgress,
            totalBytes = fileTotal,
            isFinished = st.isFinished,
            isPaused = st.flags().and_(TorrentFlags.PAUSED).non_zero(),
            isChecking = checking,
        )
    }

    /** Actual completion inside the startup EOF band, including finished 16 KiB blocks in a partial
     *  piece. Whole-file progress is intentionally excluded: a swarm can have the body but no moov. */
    private fun startupTailProgressBytes(
        handle: TorrentHandle,
        active: ActiveTorrent,
        pieces: PieceIndexBitfield,
    ): Long {
        if (!active.startupTailArmed || startupTailFromPiece(active) == null || pieces.size() == 0) return 0L
        val tailFrom = tailFromPiece(active)
        var bytes = 0L
        for (piece in tailFrom..active.lastPiece) {
            if (piece < pieces.size() && pieces.getBit(piece)) bytes += active.pieceLength.toLong()
        }
        val partials = runCatching { handle.getDownloadQueue() }.getOrDefault(emptyList())
        for (partial in partials) {
            val piece = partial.pieceIndex()
            if (piece in tailFrom..active.lastPiece &&
                piece < pieces.size() && !pieces.getBit(piece)
            ) {
                bytes += partial.finished().toLong() * TORRENT_BLOCK_BYTES
            }
        }
        return bytes
    }

    fun pause(infoHash: String) {
        synchronized(nativeLock) { torrents[infoHash]?.handle?.takeIf { it.isValid }?.pause() }
        saveResume(infoHash)
    }

    fun resume(infoHash: String) {
        val active = torrents[infoHash] ?: return
        active.handle?.takeIf { it.isValid }?.let {
            synchronized(nativeLock) { runCatching { it.resume() } }
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
        synchronized(nativeLock) {
            runCatching {
                if (!handle.status().hasMetadata()) return
                resumeDir.mkdirs()
                handle.saveResumeData(TorrentHandle.SAVE_INFO_DICT)
            }.onFailure { Log.w(TAG, "saveResume failed for $infoHash", it) }
        }
    }

    /**
     * Stop a torrent. [removeFiles]=false pauses + persists resume data (kept in cache);
     * [removeFiles]=true removes it from the session and deletes its files.
     */
    fun stop(infoHash: String, removeFiles: Boolean): Boolean {
        if (!removeFiles) {
            removeTorrent(infoHash, removeFiles = false)
            return true
        }
        if (!beginEviction(infoHash)) return false
        return try {
            removeTorrent(infoHash, removeFiles = true)
            true
        } finally {
            endEviction(infoHash)
        }
    }

    /** Internal removal for the acquisition that owns this hash (metadata/select failure). */
    private fun removeTorrent(infoHash: String, removeFiles: Boolean) {
        val active = torrents[infoHash]
        val handle = active?.handle
        if (removeFiles) {
            val isolatedPayload = infoHash !in legacyLayoutHashes
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
                metadataFileFor(infoHash).delete()
                if (isolatedPayload) File(savePath, infoHash).deleteRecursively()
            }
            active?.filePath?.let { runCatching { File(it).delete() } }
            legacyLayoutHashes.remove(infoHash)
        } else {
            if (handle != null && handle.isValid) {
                // KEEP the torrent LIVE in the session (handle stays valid, map entry kept) so re-opening
                // this title — or advancing to a warmed next episode — re-attaches via addMagnet's
                // fast-path with the partial INTACT: no metadata refetch, no redownload from 0% (the
                // "exit and it starts over" + "next-episode warm does nothing" bugs were this branch
                // freeing the session slot via deferred session.remove). Just pause it and snapshot resume
                // data as a process-restart backstop. Real eviction (removeFiles=true, above) frees the
                // slot, bounded by the LRU cache budget, so paused-in-session torrents don't grow unbounded.
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

        val readahead = (readaheadBytes / active.pieceLength + 1)
        // Never strip deadlines off the tail/moov band. ExoPlayer re-reads the moov atom / mkv cues
        // (at EOF) on every seek; if a forward-walking read head reset those pieces, a later seek
        // would have to re-fetch the moov against the sequential cursor and stall again. Keep the
        // tail permanently time-critical.
        // MUST match prioritizeHeadAndTail's deadlined tail count exactly — the old "/pieceLength + 1"
        // required one EOF piece MORE than was ever deadlined, so tailAvailable rarely went true on its
        // own and READY only fired via the 15s hard-cap: a fixed 15s "Almost ready" tax on every stream.
        val tailFrom = tailFromPiece(active)
        synchronized(nativeLock) { runCatching {
            // Move the SEQUENTIAL-download cursor to FOLLOW the read head, so libtorrent's bulk fill
            // proceeds FROM the current play position forward — not from the file front. Without this the
            // cursor stayed parked at firstPiece (set once at add time): resuming/seeking to 80% deadlined
            // the immediate pieces but the sequential fill kept pulling front pieces the user is already
            // past ("fell back to torrent at 80% but downloaded from the front"). On a backward seek this
            // correctly re-includes the earlier pieces. The head/tail deadlines still ride on top.
            handle.setSequentialRange(curPiece, active.lastPiece)
            if (prev >= 0) {
                val oldEnd = minOf(active.lastPiece, prev + readahead - 1)
                val newEnd = minOf(active.lastPiece, curPiece + readahead - 1)
                for (p in prev..oldEnd) {
                    if (p in active.firstPiece..active.lastPiece && p < tailFrom && p !in curPiece..newEnd) {
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
                    deadline += READAHEAD_DEADLINE_STEP_MS
                }
            }
            active.readHeadPiece = curPiece
        }.onFailure { Log.w(TAG, "advanceReadHead failed", it) } }
    }

    private fun resumeFileFor(infoHash: String) = File(resumeDir, "$infoHash.resume")

    /** New torrents are isolated by hash so a cold eviction can delete every file in a season pack. */
    fun torrentSavePath(infoHash: String): File =
        if (infoHash in legacyLayoutHashes) savePath
        else File(savePath, infoHash).apply { mkdirs() }

    private fun tailFromPiece(active: ActiveTorrent): Int {
        val firstTailByte = (active.fileLength - TAIL_PRIORITY_BYTES).coerceAtLeast(0L)
        return ((active.fileOffset + firstTailByte) / active.pieceLength).toInt()
            .coerceIn(active.firstPiece, active.lastPiece)
    }

    private fun startupTailFromPiece(active: ActiveTorrent): Int? =
        tailFromPiece(active).takeIf {
            active.filePath?.substringAfterLast('.', "")?.lowercase() in MOOV_EXTS
        }

    companion object {
        private const val TAG = "TorrentEngine"
        private val INFO_HASH_REGEX = Regex("(?i)^[0-9a-f]{40}$")
        const val CACHE_DIR_NAME = "torrents"
        private const val STORAGE_LAYOUT_PREFS = "torrent_storage_layout"
        private const val STORAGE_LAYOUT_VERSION_KEY = "version"
        private const val STORAGE_LAYOUT_VERSION = 2

        private const val METADATA_TIMEOUT_SECONDS = 60
        private const val METADATA_REANNOUNCE_AFTER_MS = 10_000L
        private const val METADATA_REANNOUNCE_INTERVAL_MS = 15_000L
        private const val HANDLE_POLL_ATTEMPTS = 100
        private const val CANCELLED_ADD_CLEANUP_POLLS = 20
        private const val ACQUISITION_EVICTION_POLL_MS = 50L
        private const val HANDLE_POLL_INTERVAL_MS = 50L
        private const val RANGE_POLL_INTERVAL_MS = 100L
        private const val TORRENT_BLOCK_BYTES = 16L * 1024L

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

        /** Cap on how far into the head we walk mp4 boxes looking for moov/mdat (ftyp+moov sit right at
         *  the front of a faststart file, so this resolves almost immediately). */
        const val MP4_SCAN_LIMIT = 64L * 1024 * 1024

        /** Per-piece deadline STEP for the head band. Must be large: libtorrent works all time-critical
         *  pieces toward their deadlines concurrently, so near-equal deadlines (~20 ms) complete in
         *  peer-speed order. A multi-second step keeps only the earliest head piece "overdue" so the
         *  swarm converges on it first, then the next — a strict in-order contiguous head fill. */
        const val HEAD_DEADLINE_STEP_MS = 3_000

        /** Keep read-ahead ordered too. A 25ms step made an entire 24–32MB window overdue at once. */
        const val READAHEAD_DEADLINE_STEP_MS = 250

        /** Per-piece step used by [promoteStartupTail], once the EOF index is the ONLY thing blocking the
         *  first frame. Much tighter than [HEAD_DEADLINE_STEP_MS] because nothing else is competing for
         *  the time-critical slots any more, but still non-zero so the band is fetched IN ORDER rather
         *  than every piece being equally overdue (which lets them complete in peer-speed order). */
        const val TAIL_PROMOTION_DEADLINE_STEP_MS = 750

        /** Relaxed deadline (ms) for scrub-preview sample pieces — far behind the 50 ms-class head/moov
         *  deadlines, so previews only sip spare swarm capacity and never delay playback. */
        const val PREVIEW_PREFETCH_DEADLINE_MS = 4000

        /** Seed/upload cap (bytes/s) — keep a streaming client from saturating the home upstream. */
        const val UPLOAD_RATE_LIMIT = 512 * 1024
        const val LOW_POWER_UPLOAD_RATE_LIMIT = 256 * 1024

        /** Sliding look-ahead band kept hot (TOP-priority, deadlined IN ORDER) ahead of the player's read
         *  position. This is the SOLE thing that beats libtorrent's out-of-order scatter: only DEADLINED
         *  pieces are fetched strictly in sequence, so on a big swarm (hundreds of peers each holding
         *  scattered pieces) whatever falls OUTSIDE this window is grabbed rarest-first and lands all over
         *  the file — the "solid-teal chunk bar but the piece just past the playhead is missing, so it
         *  rebuffers at 5 MB/s / 300 seeders" report. A bigger ordered runway keeps the swarm converging on
         *  the bytes about to play. Must exceed the player's steady-state buffer so the read never outruns
         *  the deadlined window into un-deadlined (scatter-prone) pieces. Deadlines are just scheduling —
         *  the RAM cost is the player buffer, not this. Capable devices only. */
        const val READAHEAD_BYTES = 64 * 1024 * 1024

        /** Look-ahead on Android TV / low-RAM devices. Must stay comfortably ABOVE the player's steady-
         *  state buffer (now ~40 MB) so the read head can't outrun the prioritised window into un-deadlined
         *  pieces and rebuffer mid-stream — the exact scatter-starvation above, which a too-small 24 MB
         *  window let through on a fast swarm. */
        const val LOW_POWER_READAHEAD_BYTES = 48 * 1024 * 1024

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
            "udp://tracker.gbitt.info:80/announce",
            "udp://opentracker.io:6969/announce",
        )

        private val VIDEO_EXTS = setOf(
            "mp4", "mkv", "avi", "webm", "mov", "m4v", "flv", "ts",
            "wmv", "asf", "mpg", "mpeg", "m2ts", "mts", "vob", "ogv",
        )
        private val MOOV_EXTS = setOf("mp4", "m4v", "mov")

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
    val contiguousHeadBytes: Long,
    /** Contiguous bytes at the RESUME anchor (streamStartPiece) — "can playback START where the user
     *  will actually begin". Equals [contiguousHeadBytes] for a from-start play. */
    val contiguousResumeBytes: Long,
    val startupTailProgressBytes: Long,
    val totalBytes: Long,
    val isFinished: Boolean,
    val isPaused: Boolean,
    /** libtorrent is hash-VERIFYING already-on-disk data (a cached reopen / resume), not downloading.
     *  During this phase downloadRate and seeders read 0 — NOT a stall — so the UI must label it
     *  "checking", never "stalled". The bitfield fills as pieces verify, which looks like a head-first
     *  download but is just the recheck walking the file. */
    val isChecking: Boolean = false,
)
