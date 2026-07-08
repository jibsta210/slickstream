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
    private val http by lazy { OkHttpClient() }

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
                val pending = dao.getAll()
                    .map { it.toDownload() }
                    .filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING }
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
                eps.forEach { ep -> download(show, seasonNumber, ep.episodeNumber, ep.name) }
            }.onFailure { Log.w(TAG, "downloadSeason failed", it) }
        }
    }

    /** Delete a download: unpin + remove its files + drop the row. */
    suspend fun delete(d: Download) = withContext(Dispatchers.IO) {
        runCatching {
            d.infoHash?.let { cache.unpin(it); cache.evict(it) }
            d.filePath?.let { p -> if (p.startsWith(downloadsDir.absolutePath)) File(p).delete() }
            dao.deleteByKey(d.mediaId, d.mediaType, d.season ?: -1, d.episode ?: -1)
        }
    }

    /** The local file path to play offline, or null if not (yet) downloaded. */
    suspend fun completedFilePath(mediaId: Int, type: MediaType, season: Int?, episode: Int?): String? =
        dao.get(mediaId, type, season ?: -1, episode ?: -1)
            ?.toDownload()?.takeIf { it.isComplete }?.filePath

    // --- queue worker --------------------------------------------------------

    private fun kick(d: Download) {
        scope.launch {
            workLock.withLock {
                // Re-read the freshest state; skip if already done or removed.
                val cur = dao.get(d.mediaId, d.mediaType, d.season ?: -1, d.episode ?: -1)?.toDownload() ?: return@withLock
                if (cur.isComplete) return@withLock
                runCatching { runDownload(cur) }.onFailure {
                    Log.w(TAG, "runDownload failed for ${cur.key}", it)
                    setStatus(cur, DownloadStatus.FAILED)
                }
            }
        }
    }

    private suspend fun runDownload(d: Download) {
        val source = d.pickedSource() ?: resolveSource(d) ?: run { setStatus(d, DownloadStatus.FAILED); return }
        // Persist the chosen source on the row so a resume doesn't re-resolve.
        val withSource = d.copy(
            quality = source.quality, sizeBytes = source.sizeBytes ?: 0L,
            infoHash = source.infoHash.takeIf { !source.isDirect }, magnetUri = source.magnetUri.takeIf { it.isNotBlank() },
            directUrl = source.directUrl, status = DownloadStatus.DOWNLOADING,
        )
        dao.upsert(DownloadEntity.from(withSource))
        if (source.isDirect) downloadDirect(withSource, source) else downloadTorrent(withSource, source)
    }

    private fun Download.pickedSource(): StreamSource? {
        // Rebuild the source from what we stored (resume path) — torrent has infoHash+magnet, direct has url.
        if (directUrl != null) {
            return StreamSource(title = title, magnetUri = "", infoHash = infoHash ?: return null, quality = quality,
                sizeBytes = sizeBytes.takeIf { it > 0 }, seeders = null, provider = "Download", directUrl = directUrl)
        }
        if (infoHash != null && !magnetUri.isNullOrBlank()) {
            return StreamSource(title = title, magnetUri = magnetUri, infoHash = infoHash, quality = quality,
                sizeBytes = sizeBytes.takeIf { it > 0 }, seeders = null, provider = "Download")
        }
        return null
    }

    private suspend fun resolveSource(d: Download): StreamSource? {
        val details: MediaDetails =
            (catalogRepository.getDetails(d.mediaId, d.mediaType) as? DataResult.Success)?.data ?: return null
        val list = (sourceRepository.resolve(details, d.season, d.episode) as? DataResult.Success)?.data ?: return null
        if (list.isEmpty()) return null
        val s = settings.current()
        val cap = s.downloadQuality.maxTier
        val capped = list.filter { QualityPreference.tierOf(it.quality) <= cap }.ifEmpty { list }
        return StreamPicker.pick(capped, cap, s.downloadSize, engine.isLowPower) ?: capped.firstOrNull()
    }

    private suspend fun downloadTorrent(d: Download, source: StreamSource) {
        var lastWrite = 0L
        torrentStreamer.start(source).collect { status ->
            val now = System.currentTimeMillis()
            if (now - lastWrite > PROGRESS_WRITE_MS || status.progress >= 0.999f) {
                lastWrite = now
                dao.updateProgress(
                    d.mediaId, d.mediaType, d.season ?: -1, d.episode ?: -1,
                    DownloadStatus.DOWNLOADING.name, status.downloadedBytes, status.totalBytes,
                    engine.filePath(source.infoHash),
                )
            }
            if (status.progress >= 0.999f && status.totalBytes > 0) {
                cache.pin(source.infoHash)
                dao.updateProgress(
                    d.mediaId, d.mediaType, d.season ?: -1, d.episode ?: -1,
                    DownloadStatus.COMPLETED.name, status.totalBytes, status.totalBytes,
                    engine.filePath(source.infoHash),
                )
                return@collect
            }
        }
    }

    private suspend fun downloadDirect(d: Download, source: StreamSource) {
        val url = source.directUrl ?: return
        val out = File(downloadsDir, "${d.key}.mp4")
        val req = Request.Builder().url(url).apply {
            source.requestHeaders.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) header(k, v) }
        }.build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw java.io.IOException("empty body")
            val total = body.contentLength().takeIf { it > 0 } ?: d.sizeBytes
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
            dao.updateProgress(d.mediaId, d.mediaType, d.season ?: -1, d.episode ?: -1,
                DownloadStatus.COMPLETED.name, downloaded, downloaded, out.absolutePath)
        }
    }

    private suspend fun setStatus(d: Download, status: DownloadStatus) =
        dao.updateProgress(d.mediaId, d.mediaType, d.season ?: -1, d.episode ?: -1, status.name, d.downloadedBytes, d.totalBytes, d.filePath)

    private companion object {
        const val TAG = "DownloadManager"
        const val PROGRESS_WRITE_MS = 1_500L
    }
}
