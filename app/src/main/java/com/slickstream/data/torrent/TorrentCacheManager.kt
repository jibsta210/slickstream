package com.slickstream.data.torrent

import android.content.Context
import android.content.SharedPreferences
import android.system.Os
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

    /** Info-hashes PINNED by an offline download — NEVER evicted by the LRU budget, whatever the
     *  caller's protected set. Backed by its own prefs so it survives restarts. */
    private val pinPrefs: SharedPreferences =
        context.getSharedPreferences(PINS_PREFS_NAME, Context.MODE_PRIVATE)

    /** Pin a downloaded torrent so the cache never evicts it. */
    fun pin(hash: String) = pinPrefs.edit().putBoolean(hash, true).apply()

    /** Unpin (e.g. the user deleted the download) — it becomes normal LRU-evictable cache again. */
    fun unpin(hash: String) = pinPrefs.edit().remove(hash).apply()

    fun isPinned(hash: String): Boolean = pinPrefs.getBoolean(hash, false)

    fun pinnedHashes(): Set<String> = pinPrefs.all.keys.toSet()

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
        val fromPayloadDirs = cacheDir.listFiles()
            ?.filter { it.isDirectory && HASH_DIR.matches(it.name) }
            ?.map { it.name }
            ?: emptyList()
        // Anything we've ever touched and still have data for.
        val tracked = prefs.all.keys
        return (fromResume + fromPayloadDirs + tracked).distinct().filter { hasData(it) }
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
        // Preserve breathing room for Android, databases, artwork and libtorrent writes. `usableSpace`
        // excludes the cache's current allocation, so add it back before deriving the safe cache ceiling.
        val usableSpace = cacheDir.usableSpace
        val storageSafeMax = if (usableSpace > 0L) {
            (total + usableSpace - MIN_FREE_SPACE_BYTES).coerceAtLeast(0L)
        } else {
            maxBytes // unavailable filesystem stat: trust the configured budget instead of panic-evicting
        }
        val effectiveMax = minOf(maxBytes, storageSafeMax)
        if (total <= effectiveMax) return 0L
        var freed = 0L
        // NOTE: do NOT exclude engine.isActive() here. Torrents kept paused-in-session for fast resume
        // now have valid handles too (engine stop(removeFiles=false) no longer removes them), so an
        // isActive filter would make the WHOLE cache un-evictable -> unbounded growth/OOM. The active +
        // warmed streams are protected via [protectedHashes] (the callers pass streamingHashes + the
        // playing + warmed hash); everything else — including paused-in-session partials — is evictable.
        val pinned = pinnedHashes()
        val candidates = cachedTorrents()
            .filter { it !in protectedHashes && it !in pinned && !engine.isAcquiring(it) }
            .sortedBy { lastAccess(it) }
        for (hash in candidates) {
            if (total - freed <= effectiveMax) break
            val before = total - freed
            if (evict(hash)) {
                // A legacy season pack can own more files than its one persisted selected path. Re-read
                // physical allocation after removal so one large pack does not make us over-evict newer
                // hashes based on an underestimated per-hash size.
                val after = cacheSizeBytes()
                val actual = (before - after).coerceAtLeast(0L)
                freed += actual
                Log.i(TAG, "Evicted $hash, freed $actual bytes")
            }
        }
        return freed
    }

    /** Remove a single cached torrent's files + bookkeeping. Tears down the live session handle first
     *  (paused-in-session partials kept for fast resume must be removed from libtorrent before their
     *  files are deleted, or the session would keep a handle over deleted files). Idempotent — when the
     *  caller already removed it from the session (streamer.stop(removeFiles=true)), this is a no-op. */
    fun evict(hash: String): Boolean {
        val legacyPayloadRoot = legacyPayloadRootFor(hash)
        // Engine atomically refuses this when an add/re-attach has begun since the caller's candidate
        // snapshot. Never continue into recursive deletion in that case.
        val claimed = runCatching { engine.stop(hash, removeFiles = true) }.getOrDefault(false)
        if (!claimed) return false
        // engine.stop only deletes what the LIVE session knows; for a cold torrent from a prior run
        // the persisted path is the only route to its payload.
        mediaFileFor(hash)?.let { runCatching { it.delete() } }
        legacyPayloadRoot?.let { runCatching { it.deleteRecursively() } }
        // New cache layout isolates every torrent (including all season-pack episodes/padding) by hash.
        runCatching { File(cacheDir, hash).deleteRecursively() }
        deleteTorrentArtifacts(hash)
        prefs.edit().remove(hash).apply()
        pathPrefs.edit().remove(hash).apply()
        return true
    }

    /** Wipe the torrents cache and all LRU bookkeeping. Every known torrent is first removed from
     *  the live session (a blanket file delete under a still-valid handle leaves libtorrent claiming
     *  pieces exist — a later re-open then serves zeros from a recreated sparse file). Hashes in
     *  [protectedHashes] (currently streaming) are left completely untouched. */
    @Synchronized
    fun clearCache(protectedHashes: Set<String> = emptySet()) {
        // PINNED offline downloads survive a "Clear cache" — they're not streaming scratch space.
        val keep = protectedHashes + pinnedHashes()
        // Never blanket-delete the root: an acquisition can begin after the caller's protection
        // snapshot. evict() atomically claims each hash through the engine and simply refuses hashes
        // that became active in the meantime.
        cachedTorrents().filterNot { it in keep }.forEach { evict(it) }
    }

    private fun lastAccess(hash: String): Long = prefs.getLong(hash, 0L)

    private fun hasData(hash: String): Boolean {
        if (sizeOf(hash) > 0) return true
        val resumeDir = File(cacheDir, ".resume")
        return File(resumeDir, "$hash.resume").exists() ||
            File(cacheDir, ".meta/$hash.torrent").exists() ||
            File(cacheDir, hash).exists()
    }

    /** Bytes used by a given torrent: its media file plus its resume/torrent artifacts. */
    private fun sizeOf(hash: String): Long {
        var size = 0L
        val isolated = File(cacheDir, hash)
        if (isolated.exists()) {
            size += dirSize(isolated)
        } else {
            // Compatibility with payloads created before per-hash directories were introduced.
            val legacyRoot = legacyPayloadRootFor(hash)
            if (legacyRoot != null) size += if (legacyRoot.isDirectory) dirSize(legacyRoot) else allocatedSize(legacyRoot)
            else mediaFileFor(hash)?.let { size += allocatedSize(it) }
        }
        val resumeDir = File(cacheDir, ".resume")
        size += allocatedSize(File(resumeDir, "$hash.resume"))
        size += allocatedSize(File(cacheDir, ".meta/$hash.torrent"))
        return size
    }

    private fun deleteTorrentArtifacts(hash: String) {
        val resumeDir = File(cacheDir, ".resume")
        runCatching { File(resumeDir, "$hash.resume").delete() }
        runCatching { File(cacheDir, ".meta/$hash.torrent").delete() }
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.walkTopDown().forEach { if (it.isFile) total += allocatedSize(it) }
        return total
    }

    /** Libtorrent creates sparse files at their final logical length. File.length() therefore charges a
     *  20GB movie as 20GB after downloading one piece. st_blocks reports physical 512-byte blocks. */
    private fun allocatedSize(file: File): Long {
        if (!file.exists()) return 0L
        return runCatching { Os.stat(file.absolutePath).st_blocks * 512L }
            .getOrElse { file.length() }
            .coerceAtLeast(0L)
    }

    /** Old multi-file torrents normally live under one unique top-level release folder. Attribute that
     *  whole folder to the hash so cold LRU eviction can reclaim every episode/padding file. If another
     *  tracked hash references the same top-level path, return null rather than risk cross-deletion. */
    private fun legacyPayloadRootFor(hash: String): File? {
        val selectedPath = pathPrefs.getString(hash, null)?.let(::File) ?: return null
        val relative = runCatching { selectedPath.relativeTo(cacheDir) }.getOrNull() ?: return null
        val first = relative.path.substringBefore(File.separatorChar).takeIf {
            it.isNotBlank() && it != "." && it != hash && !it.startsWith('.')
        } ?: return null
        val candidate = File(cacheDir, first)
        val shared = pathPrefs.all.any { (otherHash, raw) ->
            if (otherHash == hash) return@any false
            val other = (raw as? String)?.let(::File) ?: return@any false
            val otherRelative = runCatching { other.relativeTo(cacheDir) }.getOrNull() ?: return@any false
            otherRelative.path.substringBefore(File.separatorChar) == first
        }
        return candidate.takeIf { !shared }
    }

    companion object {
        private const val TAG = "TorrentCacheManager"
        private const val PREFS_NAME = "torrent_cache_lru"
        private const val PATHS_PREFS_NAME = "torrent_cache_paths"
        private const val PINS_PREFS_NAME = "torrent_cache_pins"
        private val HASH_DIR = Regex("(?i)^[0-9a-f]{40}$")

        /** Default cache budget ~4 GB. */
        const val DEFAULT_MAX_BYTES = 4L * 1024L * 1024L * 1024L
        const val MIN_FREE_SPACE_BYTES = 512L * 1024L * 1024L
    }
}
