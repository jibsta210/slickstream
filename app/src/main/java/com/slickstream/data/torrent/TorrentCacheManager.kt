package com.slickstream.data.torrent

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.slickstream.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LRU manager over the on-disk torrents cache directory.
 *
 * Tracks a last-access timestamp per info-hash and enforces a maximum total cache size
 * (default ~4 GB). When the budget is exceeded, the least-recently-used torrents are evicted
 * until the cache is back under budget. The currently-streaming torrent is never evicted.
 */
@Singleton
class TorrentCacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: TorrentEngine,
    private val settingsRepository: SettingsRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Live cache budget, driven by the user's Settings > Storage picker. Seeded with the default. */
    @Volatile
    private var configuredMaxBytes: Long = DEFAULT_MAX_BYTES

    init {
        scope.launch {
            settingsRepository.settings.collectLatest { configuredMaxBytes = it.maxCacheSize.bytes }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** infoHash -> absolute path of its selected media file. The engine only knows paths for torrents
     *  added THIS process; without persisting them, evicting a cold cached torrent (after a restart)
     *  could delete only its bookkeeping and orphan the multi-GB payload on disk forever. */
    private val pathPrefs: SharedPreferences =
        context.getSharedPreferences(PATHS_PREFS_NAME, Context.MODE_PRIVATE)

    private val cacheDir: File get() = engine.savePath

    /** Record that an info-hash was just used (resets its LRU position). */
    fun touch(infoHash: String) {
        prefs.edit().putLong(infoHash, System.currentTimeMillis()).apply()
        // Snapshot the media-file path while the engine still knows it, for post-restart eviction.
        engine.filePath(infoHash)?.let { pathPrefs.edit().putString(infoHash, it).apply() }
    }

    /** The media file for [hash]: the live engine's path, else the persisted one from a prior run. */
    private fun mediaFileFor(hash: String): File? =
        (engine.filePath(hash) ?: pathPrefs.getString(hash, null))?.let { File(it) }

    /** Info-hashes that currently have a directory/resume entry on disk. */
    fun cachedTorrents(): List<String> {
        val resumeDir = File(cacheDir, ".resume")
        val fromResume = resumeDir.listFiles()
            ?.filter { it.name.endsWith(".resume") || it.name.endsWith(".torrent") }
            ?.map { it.name.substringBeforeLast('.') }
            ?: emptyList()
        // Anything we've ever touched and still have data for.
        val tracked = prefs.all.keys
        return (fromResume + tracked).distinct().filter { hasData(it) }
    }

    /** Total bytes occupied by the torrents cache dir (excludes nothing — whole subtree). */
    fun cacheSizeBytes(): Long = dirSize(cacheDir)

    /** Enforce the size budget, protecting a SET of info-hashes (the playing + prefetched ones).
     *  Evicts LRU-first until the cache is under [maxBytes]. Returns the number of bytes freed.
     *  (The old single-hash overload also filtered out engine.isActive() torrents — but paused-in-
     *  session partials all have valid handles, so it could never evict anything. Deleted.) */
    @Synchronized
    fun enforceBudget(protectedHashes: Set<String>, maxBytes: Long = configuredMaxBytes): Long {
        var total = cacheSizeBytes()
        if (total <= maxBytes) return 0L
        var freed = 0L
        // NOTE: do NOT exclude engine.isActive() here. Torrents kept paused-in-session for fast resume
        // now have valid handles too (engine stop(removeFiles=false) no longer removes them), so an
        // isActive filter would make the WHOLE cache un-evictable -> unbounded growth/OOM. The active +
        // warmed streams are protected via [protectedHashes] (the callers pass streamingHashes + the
        // playing + warmed hash); everything else — including paused-in-session partials — is evictable.
        val candidates = cachedTorrents()
            .filter { it !in protectedHashes }
            .sortedBy { lastAccess(it) }
        for (hash in candidates) {
            if (total - freed <= maxBytes) break
            val size = sizeOf(hash)
            evict(hash)
            freed += size
            Log.i(TAG, "Evicted $hash, freed $size bytes")
        }
        return freed
    }

    /** Remove a single cached torrent's files + bookkeeping. Tears down the live session handle first
     *  (paused-in-session partials kept for fast resume must be removed from libtorrent before their
     *  files are deleted, or the session would keep a handle over deleted files). Idempotent — when the
     *  caller already removed it from the session (streamer.stop(removeFiles=true)), this is a no-op. */
    fun evict(hash: String) {
        runCatching { engine.stop(hash, removeFiles = true) }
        // engine.stop only deletes what the LIVE session knows; for a cold torrent from a prior run
        // the persisted path is the only route to its payload.
        mediaFileFor(hash)?.let { runCatching { it.delete() } }
        deleteTorrentArtifacts(hash)
        prefs.edit().remove(hash).apply()
        pathPrefs.edit().remove(hash).apply()
    }

    /** Wipe the torrents cache and all LRU bookkeeping. Every known torrent is first removed from
     *  the live session (a blanket file delete under a still-valid handle leaves libtorrent claiming
     *  pieces exist — a later re-open then serves zeros from a recreated sparse file). Hashes in
     *  [protectedHashes] (currently streaming) are left completely untouched. */
    @Synchronized
    fun clearCache(protectedHashes: Set<String> = emptySet()) {
        val known = cachedTorrents()
        known.filterNot { it in protectedHashes }.forEach { runCatching { engine.stop(it, removeFiles = true) } }
        if (protectedHashes.isEmpty()) {
            cacheDir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
            File(cacheDir, ".resume").mkdirs()
            prefs.edit().clear().apply()
            pathPrefs.edit().clear().apply()
        } else {
            // Something is streaming right now — evict everything else individually and keep the
            // stream's files + bookkeeping intact.
            known.filterNot { it in protectedHashes }.forEach { evict(it) }
        }
    }

    private fun lastAccess(hash: String): Long = prefs.getLong(hash, 0L)

    private fun hasData(hash: String): Boolean {
        if (sizeOf(hash) > 0) return true
        val resumeDir = File(cacheDir, ".resume")
        return File(resumeDir, "$hash.resume").exists() || File(resumeDir, "$hash.torrent").exists()
    }

    /** Bytes used by a given torrent: its media file plus its resume/torrent artifacts. */
    private fun sizeOf(hash: String): Long {
        var size = 0L
        mediaFileFor(hash)?.let { size += it.length() }
        val resumeDir = File(cacheDir, ".resume")
        size += File(resumeDir, "$hash.resume").length()
        size += File(resumeDir, "$hash.torrent").length()
        return size
    }

    private fun deleteTorrentArtifacts(hash: String) {
        val resumeDir = File(cacheDir, ".resume")
        runCatching { File(resumeDir, "$hash.resume").delete() }
        runCatching { File(resumeDir, "$hash.torrent").delete() }
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }

    companion object {
        private const val TAG = "TorrentCacheManager"
        private const val PREFS_NAME = "torrent_cache_lru"
        private const val PATHS_PREFS_NAME = "torrent_cache_paths"

        /** Default cache budget ~4 GB. */
        const val DEFAULT_MAX_BYTES = 4L * 1024L * 1024L * 1024L
    }
}
