package com.slickstream.data.download

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.slickstream.core.model.DataResult
import com.slickstream.core.model.Download
import com.slickstream.core.model.DownloadStatus
import com.slickstream.core.model.MediaDetails
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.core.model.StreamSource
import com.slickstream.core.model.isDownloadable
import com.slickstream.core.model.StreamState
import com.slickstream.core.repository.CatalogRepository
import com.slickstream.core.repository.ProfileRepository
import com.slickstream.core.repository.SourceRepository
import com.slickstream.core.repository.TorrentStreamer
import com.slickstream.data.settings.SettingsRepository
import com.slickstream.data.settings.QualityPreference
import com.slickstream.data.source.StreamPicker
import com.slickstream.data.local.dao.DownloadDao
import com.slickstream.data.local.entity.DownloadEntity
import com.slickstream.data.torrent.TorrentCacheManager
import com.slickstream.data.torrent.TorrentEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates OFFLINE downloads (movies / episodes / seasons). One at a time, queued.
 *
 * Each item resolves to a source at the user's DOWNLOAD quality+size (separate from streaming), then:
 *  - a TORRENT source is driven to a FULL download via [TorrentStreamer] and PINNED so the LRU cache
 *    never evicts it — the finished file plays offline via file:// with no swarm;
 *  - a DIRECT/RD source is HTTP-downloaded to the downloads dir.
 * Progress is written to [DownloadDao]; the Downloads UI observes it. A finished download stores a
 * local [Download.filePath] that the player uses to play with the network off.
 */
@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DownloadDao,
    private val sourceRepository: SourceRepository,
    private val catalogRepository: CatalogRepository,
    private val torrentStreamer: TorrentStreamer,
    private val engine: TorrentEngine,
    private val cache: TorrentCacheManager,
    private val settings: SettingsRepository,
    private val profiles: ProfileRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workLock = Mutex()
    // Explicit read timeout so a stalled/slow-drip direct host can't jam the one-at-a-time queue: a
    // gap longer than this throws, the failover loop advances, and workLock is released.
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /** key -> its running/queued worker Job, so delete() can actually STOP an in-flight download
     *  (cancel → the streamer flow's awaitClose pauses the torrent) instead of letting it keep eating
     *  bandwidth and re-pinning the hash we just evicted. */
    private val activeJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /** Bring up the foreground service so downloads survive the app being backgrounded / screen off.
     *  Safe to call repeatedly; the service self-stops once the queue drains. Called from every entry
     *  point that enqueues work (user tap or launch resume) — all foreground-initiated, so Android 12+
     *  background-start limits don't bite. */
    private fun ensureService() {
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }.onFailure { Log.w(TAG, "startForegroundService failed", it) }
    }

    private val downloadsDir: File by lazy {
        (context.getExternalFilesDir("downloads") ?: File(context.filesDir, "downloads")).apply { mkdirs() }
    }

    /** Live list of downloads for the Downloads screen (newest first). */
    val downloads: Flow<List<Download>> = dao.observeAll().map { rows -> rows.map { it.toDownload() } }

    /** Live status of one item — for the details-screen Download button. */
    fun observe(mediaId: Int, type: MediaType, season: Int?, episode: Int?): Flow<Download?> =
        dao.observe(mediaId, type, season ?: -1, episode ?: -1).map { it?.toDownload() }

    /** Resume any in-flight downloads left QUEUED/DOWNLOADING by a previous run (called at launch). */
    fun resumeInterrupted() {
        scope.launch {
            runCatching {
                val all = dao.getAll().map { it.toDownload() }
                // JUNK SWEEP: builds before the anti-fake floors could store a 2 MB fake as COMPLETED
                // ("Downloaded" that plays seconds of junk). Purge the junk artifacts and RE-QUEUE — the
                // user asked for this title, and with the floors + failover it now lands a real source.
                //
                // Gate on the FIXED ABSOLUTE_MIN_BYTES (10 MB), NOT the runtime-aware minBytesFor: that
                // makes a TMDB call which FAILS offline and then returns the higher static CEILING, so on
                // a plane the sweep would flag a legit short download (a 20 MB Bluey episode) as junk and
                // DELETE it — unrecoverable with no network. Every real completed file is ≥ its
                // download-time floor ≥ ABSOLUTE_MIN_BYTES, so <10 MB can only be pre-fix junk. No network.
                val junk = all.filter { it.isComplete && it.totalBytes in 1 until ABSOLUTE_MIN_BYTES }
                val junkKeys = junk.map { it.key }.toSet()
                val keepHashes = all.filter { it.isComplete && it.key !in junkKeys }.mapNotNull { it.infoHash }.toSet()
                junk.forEach { j ->
                    Log.w(TAG, "junk sweep: re-queueing ${j.key} (${j.totalBytes} B)")
                    // Same season-pack guard as delete(): never evict a hash a good sibling still uses.
                    j.infoHash?.takeIf { it !in keepHashes }?.let { runCatching { cache.unpin(it); cache.evict(it) } }
                    j.filePath?.let { p -> if (p.startsWith(downloadsDir.absolutePath)) runCatching { File(p).delete() } }
                    dao.upsert(DownloadEntity.from(j.copy(
                        status = DownloadStatus.QUEUED, downloadedBytes = 0L, totalBytes = 0L,
                        filePath = null, infoHash = null, magnetUri = null, directUrl = null,
                    )))
                }
                val pending = all.filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING } + junk
                if (pending.isNotEmpty()) {
                    ensureService()
                    pending.forEach { kick(it) }
                }
            }.onFailure { Log.w(TAG, "resumeInterrupted failed", it) }
        }
    }

    /** Queue a movie or a single episode. Returns immediately; work runs on the queue. */
    fun download(item: MediaItem, season: Int? = null, episode: Int? = null, episodeLabel: String? = null) {
        scope.launch {
            runCatching {
                // Idempotent: a repeat tap (or "Download season" re-run over already-saved episodes) must
                // NOT wipe an in-flight/finished row back to QUEUED. Only (re)start when absent or FAILED.
                val existing = dao.get(item.id, item.mediaType, season ?: -1, episode ?: -1)?.toDownload()
                if (existing != null && existing.status != DownloadStatus.FAILED) {
                    if (existing.status == DownloadStatus.QUEUED) { ensureService(); kick(existing) }
                    return@launch
                }
                val profileId = profiles.currentProfileId()
                val subtitle = when {
                    season != null && episode != null ->
                        "S$season · E$episode" + (episodeLabel?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                    else -> ""
                }
                val row = DownloadEntity(
                    mediaId = item.id, mediaType = item.mediaType, season = season ?: -1, episode = episode ?: -1,
                    title = item.title, subtitle = subtitle, posterUrl = item.posterUrl, backdropUrl = item.backdropUrl,
                    overview = item.overview, quality = "", sizeBytes = 0L, infoHash = null, magnetUri = null,
                    directUrl = null, filePath = null, status = DownloadStatus.QUEUED.name,
                    downloadedBytes = 0L, totalBytes = 0L, addedAt = System.currentTimeMillis(), profileId = profileId,
                )
                dao.upsert(row)
                ensureService()
                kick(row.toDownload())
            }.onFailure { Log.w(TAG, "download($item) failed", it) }
        }
    }

    /** Queue every AIRED episode of a season. */
    fun downloadSeason(show: MediaItem, seasonNumber: Int) {
        scope.launch {
            runCatching {
                val eps = (catalogRepository.getEpisodes(show.id, seasonNumber) as? DataResult.Success)?.data ?: return@launch
                // Skip episodes with a FUTURE air date — they have no sources yet and would just pile up
                // as FAILED rows. Unknown (null) air dates are kept (some shows omit them). Same
                // predicate the download UI gates/aggregates use — keep them in sync.
                eps.filter { it.isDownloadable() }
                    .forEach { ep -> download(show, seasonNumber, ep.episodeNumber, ep.name) }
            }.onFailure { Log.w(TAG, "downloadSeason failed", it) }
        }
    }

    /** Retry a FAILED (or any) download in place: reset the row to QUEUED, clear the dead source, and
     *  re-enqueue — no need to delete and re-navigate to the title. */
    fun retry(d: Download) {
        scope.launch {
            runCatching {
                val reset = d.copy(
                    status = DownloadStatus.QUEUED, downloadedBytes = 0L, totalBytes = 0L,
                    filePath = null, infoHash = null, magnetUri = null, directUrl = null,
                )
                dao.upsert(DownloadEntity.from(reset))
                ensureService()
                kick(reset)
            }.onFailure { Log.w(TAG, "retry(${d.key}) failed", it) }
        }
    }

    /** Delete a download: STOP it if running, then unpin + remove its files + drop the row. */
    suspend fun delete(d: Download) = withContext(Dispatchers.IO) {
        runCatching {
            // Cancel first so the worker stops writing progress / re-pinning as we tear the row down.
            activeJobs.remove(d.key)?.cancel()
            // Drop the row BEFORE deciding on eviction so the still-referenced check below sees the
            // remaining rows only.
            dao.deleteByKey(d.mediaId, d.mediaType, d.season ?: -1, d.episode ?: -1)
            d.infoHash?.let { hash ->
                // SEASON-PACK GUARD: every episode downloaded from one pack shares this infoHash, and
                // evict() deletes ALL of the torrent's files. Only unpin+evict when no other download
                // row still references the hash — otherwise deleting E02 would nuke E01's finished file.
                val stillReferenced = dao.getAll().any { it.infoHash == hash }
                if (!stillReferenced) { cache.unpin(hash); cache.evict(hash) }
            }
            d.filePath?.let { p -> if (p.startsWith(downloadsDir.absolutePath)) File(p).delete() }
        }
    }

    /** The local file path to play offline, or null if not (yet) downloaded. */
    suspend fun completedFilePath(mediaId: Int, type: MediaType, season: Int?, episode: Int?): String? =
        dao.get(mediaId, type, season ?: -1, episode ?: -1)
            ?.toDownload()?.takeIf { it.isComplete }?.filePath

    /** The full completed download row (title/poster/path), or null. Lets the player render + play a
     *  downloaded title with NO network — the row carries everything, so no TMDB fetch is needed. */
    suspend fun completedDownload(mediaId: Int, type: MediaType, season: Int?, episode: Int?): Download? =
        dao.get(mediaId, type, season ?: -1, episode ?: -1)?.toDownload()?.takeIf { it.isComplete }

    // --- queue worker --------------------------------------------------------

    private fun kick(d: Download) {
        // One worker per key, ATOMICALLY. A plain containsKey→put is check-then-act: a double-tap (or
        // a tap racing launch-resume) could slip two workers through, and the second put would
        // overwrite the first job so delete() cancelled the wrong one. LAZY start = the loser of
        // putIfAbsent never runs at all.
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            workLock.withLock {
                // Re-read the freshest state; skip if already done or removed.
                val cur = dao.get(d.mediaId, d.mediaType, d.season ?: -1, d.episode ?: -1)?.toDownload() ?: return@withLock
                if (cur.isComplete) return@withLock
                runCatching { runDownload(cur) }.onFailure {
                    // A cancelled worker (delete()/shutdown) must propagate, not be recorded as FAILED
                    // on a row that's being torn down.
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    Log.w(TAG, "runDownload failed for ${cur.key}", it)
                    setStatus(cur, DownloadStatus.FAILED)
                }
            }
        }
        if (activeJobs.putIfAbsent(d.key, job) != null) {
            job.cancel()   // never started (LAZY) — a worker for this key already exists
            return
        }
        job.invokeOnCompletion { activeJobs.remove(d.key, job) }
        job.start()
    }

    private suspend fun runDownload(d: Download) {
        // FAILOVER: new releases are riddled with fake/decoy sources (a 2 MB "movie" that downloads to
        // an honest 100%). One junk source must advance to the next-best candidate, not strand the row
        // as FAILED — and NEVER be presented as Downloaded. Each attempt persists its source + resets
        // progress so the UI never shows a stale size from a junk try. User cancellation (delete())
        // rethrows and stops the loop; only real failures fail over.
        val minBytes = minBytesFor(d)
        val tried = mutableSetOf<String>()
        suspend fun attempt(source: StreamSource): Boolean {
            tried += source.attemptKey()
            val withSource = d.copy(
                quality = source.quality, sizeBytes = source.sizeBytes ?: 0L,
                infoHash = source.infoHash.takeIf { !source.isDirect }, magnetUri = source.magnetUri.takeIf { it.isNotBlank() },
                directUrl = source.directUrl, status = DownloadStatus.DOWNLOADING,
                downloadedBytes = 0L, totalBytes = 0L, filePath = null,
            )
            dao.upsert(DownloadEntity.from(withSource))
            return try {
                if (source.isDirect) downloadDirect(withSource, source, minBytes) else downloadTorrent(withSource, source, minBytes)
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "source failed for ${d.key} (${source.provider} ${source.quality} ${source.sizeBytes ?: "?"}B): ${e.message}")
                false
            }
        }
        // Resume path first (the persisted source), then fresh ranked candidates.
        d.pickedSource()?.let { if (attempt(it)) return }
        for (source in resolveCandidates(d, minBytes)) {
            if (source.attemptKey() in tried) continue
            if (attempt(source)) return
        }
        setStatus(d.copy(downloadedBytes = 0L, totalBytes = 0L), DownloadStatus.FAILED)
    }

    /** Identity of an attempt for the failover tried-set: URL for directs, infoHash for torrents. */
    private fun StreamSource.attemptKey(): String = directUrl ?: infoHash

    private fun Download.pickedSource(): StreamSource? {
        // Rebuild the source from what we stored (resume path) — torrent has infoHash+magnet, direct has url.
        if (directUrl != null) {
            // infoHash is null for persisted directs (only torrents store it) and unused on the direct
            // path — attemptKey() and the player key directs off the URL. Don't let it block the resume.
            return StreamSource(title = title, magnetUri = "", infoHash = infoHash ?: "", quality = quality,
                sizeBytes = sizeBytes.takeIf { it > 0 }, seeders = null, provider = "Download", directUrl = directUrl)
        }
        if (infoHash != null && !magnetUri.isNullOrBlank()) {
            return StreamSource(title = title, magnetUri = magnetUri, infoHash = infoHash, quality = quality,
                sizeBytes = sizeBytes.takeIf { it > 0 }, seeders = null, provider = "Download")
        }
        return null
    }

    /** Resolve once, then rank up to [MAX_SOURCE_ATTEMPTS] candidates by repeatedly asking the SAME
     *  picker the player trusts (pick best → remove → pick next), so failover order == picker order.
     *  The [minBytesFor] floor makes SMALLEST mean "smallest REAL release": a 2 MB fake no longer wins
     *  the min-size sort just because it's tiny. */
    private suspend fun resolveCandidates(d: Download, minBytes: Long): List<StreamSource> {
        val details: MediaDetails =
            (catalogRepository.getDetails(d.mediaId, d.mediaType) as? DataResult.Success)?.data ?: return emptyList()
        val list = (sourceRepository.resolve(details, d.season, d.episode) as? DataResult.Success)?.data ?: return emptyList()
        if (list.isEmpty()) return emptyList()
        val s = settings.current()
        val cap = s.downloadQuality.maxTier
        val capped = list.filter { QualityPreference.tierOf(it.quality) <= cap }.ifEmpty { list }
        val pool = capped.toMutableList()
        val ranked = mutableListOf<StreamSource>()
        while (ranked.size < MAX_SOURCE_ATTEMPTS && pool.isNotEmpty()) {
            val next = StreamPicker.pick(pool, cap, s.downloadSize, engine.isLowPower, minBytes) ?: break
            ranked += next
            if (!pool.remove(next)) break   // defensive: picker returned something not in the pool
        }
        return ranked
    }

    /** Anti-fake floor for this download, RUNTIME-AWARE so legit short-form content is never rejected.
     *  ~1 MB/min is far below any real encode (480p x265 runs 2–4 MB/min), so a 7-minute kids episode
     *  (Bluey/Peppa, ~20 MB real) or a Pixar short passes, while the 2 MB fake class never does (10 MB
     *  absolute floor). Unknown runtime falls back to conservative static ceilings (150 MB movie /
     *  40 MB standard episode); known runtime is capped at those same ceilings so a 3-hour epic doesn't
     *  demand more. */
    private suspend fun minBytesFor(d: Download): Long {
        val isEpisode = (d.episode ?: -1) >= 0
        val ceiling = if (isEpisode) EPISODE_MIN_BYTES else MOVIE_MIN_BYTES
        // Bounded so a hung TMDB request can't pin workLock (this runs inside the one-at-a-time queue).
        // On timeout/failure the runtime is unknown → the static ceiling, same as the offline case.
        val runtimeMin: Int? = kotlinx.coroutines.withTimeoutOrNull(TMDB_LOOKUP_TIMEOUT_MS) {
            runCatching {
                if (isEpisode) {
                    (catalogRepository.getEpisodes(d.mediaId, d.season ?: -1) as? DataResult.Success)
                        ?.data?.firstOrNull { it.episodeNumber == d.episode }?.runtimeMinutes
                } else {
                    (catalogRepository.getDetails(d.mediaId, d.mediaType) as? DataResult.Success)
                        ?.data?.runtimeMinutes
                }
            }.getOrNull()
        }
        return if (runtimeMin != null && runtimeMin > 0) {
            (runtimeMin * PER_MINUTE_MIN_BYTES).coerceIn(ABSOLUTE_MIN_BYTES, ceiling)
        } else ceiling
    }

    private suspend fun downloadTorrent(d: Download, source: StreamSource, minBytes: Long) {
        var lastWrite = 0L
        var lastBytes = -1L
        var lastAdvanceAt = android.os.SystemClock.elapsedRealtime()
        // first { done } CANCELS the (hot, never-completing) streamer flow the instant the file is fully
        // downloaded — releasing workLock so the next queued item (e.g. the rest of a season) can run.
        // A plain collect{ return@collect } would loop forever and jam the queue. A terminal ERROR
        // throws out to the failover loop, which tries the next source.
        val finalStatus = try {
            torrentStreamer.start(source)
                .onEach { status ->
                    if (status.state == StreamState.ERROR) {
                        throw java.io.IOException(status.errorMessage ?: "torrent error")
                    }
                    // FAKE-TORRENT GUARD: totalBytes is the SELECTED file's real size (or the advertised
                    // size pre-metadata). Fake torrents ship a ~2 MB decoy .mkv that dodges the "sample"
                    // name check, downloads to an honest 100%, and looked Downloaded. A movie can't be
                    // 2 MB — abort the moment the size is known instead of saving junk.
                    if (status.totalBytes in 1 until minBytes) {
                        throw java.io.IOException("selected file too small to be real (${status.totalBytes} B < $minBytes B)")
                    }
                    // STALL GUARD: many fakes resolve metadata then deliver nothing (fabricated seeder
                    // counts, dead swarm). Without an escape, first{} would hang forever, holding
                    // workLock and jamming every queued download behind this one.
                    val nowElapsed = android.os.SystemClock.elapsedRealtime()
                    if (status.downloadedBytes > lastBytes) {
                        lastBytes = status.downloadedBytes; lastAdvanceAt = nowElapsed
                    } else if (nowElapsed - lastAdvanceAt > STALL_TIMEOUT_MS) {
                        throw java.io.IOException("no progress in ${STALL_TIMEOUT_MS / 60_000} min — dead swarm")
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastWrite > PROGRESS_WRITE_MS) {
                        lastWrite = now
                        dao.updateProgress(
                            d.mediaId, d.mediaType, d.season ?: -1, d.episode ?: -1,
                            DownloadStatus.DOWNLOADING.name, status.downloadedBytes, status.totalBytes,
                            engine.filePath(source.infoHash),
                        )
                    }
                }
                .first { it.progress >= 0.999f && it.totalBytes > 0 }
                // Belt-and-braces: never mark a sub-floor file COMPLETED. Inside the try so the same
                // guarded cleanup below applies.
                .also { fin ->
                    if (fin.totalBytes < minBytes) {
                        throw java.io.IOException("downloaded file too small to be real (${fin.totalBytes} B < $minBytes B)")
                    }
                }
        } catch (e: Exception) {
            // Rejected/failed torrent: drop its data so junk never lingers (or gets resumed) in the
            // cache — but NEVER evict a PINNED hash. This failing attempt hasn't pinned (pin happens
            // only after validation below), so an existing pin belongs to a COMPLETED sibling episode
            // sharing this season-pack torrent: evicting would delete ALL the pack's files, including
            // the sibling's finished episode. Skip on cancellation too — delete() does its own cleanup.
            if (e !is kotlinx.coroutines.CancellationException && !cache.isPinned(source.infoHash)) {
                runCatching { cache.evict(source.infoHash) }
            }
            throw e
        }
        // DURABLE RELOCATION: the torrent's file lives in the app CACHE dir, which the OS can reclaim
        // under storage pressure — so "downloaded for the plane" could silently vanish. Copy it into the
        // persistent downloads dir (same place direct downloads land) and point the row THERE, so offline
        // playback reads a file the system won't delete. A short/failed copy is treated as an attempt
        // failure (failover tries the next source) rather than a bogus COMPLETED.
        val src = engine.filePath(source.infoHash)?.let { File(it) }
            ?: throw java.io.IOException("engine reported no file for completed torrent")
        val ext = src.name.substringAfterLast('.', "mkv")
        val dest = File(downloadsDir, "${d.key}.$ext")
        runCatching { if (dest.exists()) dest.delete(); src.copyTo(dest, overwrite = true) }
            .onFailure { runCatching { dest.delete() }; throw java.io.IOException("failed to persist download: ${it.message}") }
        if (dest.length() < minBytes || dest.length() < src.length()) {
            runCatching { dest.delete() }
            throw java.io.IOException("persisted copy incomplete (${dest.length()} of ${src.length()} B)")
        }
        // We now own a durable copy, so the cache torrent no longer needs protection — unpin and let the
        // LRU reclaim it normally. Do NOT evict: a season-pack sibling may still be downloading from the
        // same torrent, and evict() deletes ALL the torrent's files.
        runCatching { cache.unpin(source.infoHash) }
        dao.updateProgress(
            d.mediaId, d.mediaType, d.season ?: -1, d.episode ?: -1,
            DownloadStatus.COMPLETED.name, finalStatus.totalBytes, finalStatus.totalBytes,
            dest.absolutePath,
        )
    }

    private suspend fun downloadDirect(d: Download, source: StreamSource, minBytes: Long) {
        val url = source.directUrl ?: return
        // Keep the real container extension so ExoPlayer infers the right extractor for offline play
        // (a downloaded .mkv saved as .mp4 could mis-sniff). Fall back to mp4 when the URL has none.
        val ext = url.substringBefore('?').substringAfterLast('.', "")
            .lowercase().takeIf { it in setOf("mp4", "mkv", "webm", "mov", "m4v", "avi", "ts") } ?: "mp4"
        val out = File(downloadsDir, "${d.key}.$ext")
        val req = Request.Builder().url(url).apply {
            source.requestHeaders.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) header(k, v) }
        }.build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw java.io.IOException("empty body")
                // DEAD/PLACEHOLDER GUARD: expired RD links and "configure this addon" placeholders come
                // back HTTP 200 with an HTML/JSON body — that used to be saved as a 2 MB ".mp4" and
                // shown as Downloaded. Reject non-media bodies and implausibly small advertised sizes
                // BEFORE wasting the transfer.
                val ct = body.contentType()?.toString()?.lowercase() ?: ""
                if (ct.startsWith("text/") || ct.contains("json") || ct.contains("xml") || ct.contains("html")) {
                    throw java.io.IOException("non-media response: $ct")
                }
                val declared = body.contentLength()
                if (declared in 1 until minBytes) {
                    throw java.io.IOException("response too small to be real ($declared B < $minBytes B)")
                }
                val total = declared.takeIf { it > 0 } ?: d.sizeBytes
                var downloaded = 0L
                var lastWrite = 0L
                body.byteStream().use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            output.write(buf, 0, n); downloaded += n
                            val now = System.currentTimeMillis()
                            if (now - lastWrite > PROGRESS_WRITE_MS) {
                                lastWrite = now
                                dao.updateProgress(d.mediaId, d.mediaType, d.season ?: -1, d.episode ?: -1,
                                    DownloadStatus.DOWNLOADING.name, downloaded, total, out.absolutePath)
                            }
                        }
                    }
                }
                // ACTUAL-size floor: catches sources that lie in their metadata (advertise 2 GB, deliver
                // 2 MB) and chunked responses with no Content-Length. Never present junk as Downloaded.
                if (downloaded < minBytes) {
                    throw java.io.IOException("downloaded file too small to be real ($downloaded B < $minBytes B)")
                }
                dao.updateProgress(d.mediaId, d.mediaType, d.season ?: -1, d.episode ?: -1,
                    DownloadStatus.COMPLETED.name, downloaded, downloaded, out.absolutePath)
            }
        } catch (e: Exception) {
            // Any failure (including the guards above): remove the junk/partial file so a rejected
            // download can't masquerade in the downloads dir, then let the failover loop try the next.
            runCatching { out.delete() }
            throw e
        }
    }

    private suspend fun setStatus(d: Download, status: DownloadStatus) =
        dao.updateProgress(d.mediaId, d.mediaType, d.season ?: -1, d.episode ?: -1, status.name, d.downloadedBytes, d.totalBytes, d.filePath)

    private companion object {
        const val TAG = "DownloadManager"
        const val PROGRESS_WRITE_MS = 1_500L

        /** Anti-fake floor ceilings (used directly when runtime is unknown): a real FEATURE is never
         *  under ~300 MB even at 480p; a real STANDARD (40+ min) episode never under ~60 MB. Well below
         *  those so no legitimate release is rejected, well above the 2–150 MB fake/sample/error-page
         *  class. Short-form content gets a runtime-scaled floor instead — see [minBytesFor]. */
        const val MOVIE_MIN_BYTES = 150L * 1024 * 1024   // 150 MB
        const val EPISODE_MIN_BYTES = 40L * 1024 * 1024  // 40 MB
        const val PER_MINUTE_MIN_BYTES = 1L * 1024 * 1024 // 1 MB/min — under any real encode's bitrate
        const val ABSOLUTE_MIN_BYTES = 10L * 1024 * 1024  // nothing real is under 10 MB

        /** How many distinct sources to try before marking a download FAILED. New releases often lead
         *  with fakes — a handful of alternates usually contains a real encode. */
        const val MAX_SOURCE_ATTEMPTS = 4

        /** No bytes for this long = dead/fake swarm → fail the attempt so the failover loop advances
         *  (and releases workLock for the rest of the queue) instead of hanging forever. */
        const val STALL_TIMEOUT_MS = 3L * 60 * 1000

        /** Cap on the runtime lookup so a hung TMDB call can't pin the download queue's workLock. */
        const val TMDB_LOOKUP_TIMEOUT_MS = 8_000L
    }
}
