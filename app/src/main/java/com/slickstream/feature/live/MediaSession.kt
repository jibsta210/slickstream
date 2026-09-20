package com.slickstream.feature.live

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.slickstream.core.common.DeviceProfile
import com.slickstream.core.diagnostics.Diagnostics
import com.slickstream.core.model.DataResult
import com.slickstream.core.model.PlaybackProgress
import com.slickstream.core.model.StreamSource
import com.slickstream.core.model.StreamState
import com.slickstream.core.repository.CatalogRepository
import com.slickstream.core.repository.LibraryRepository
import com.slickstream.core.repository.SourceRepository
import com.slickstream.core.repository.TorrentStreamer
import com.slickstream.data.settings.SettingsRepository
import com.slickstream.data.source.StreamPicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A MOVIE or EPISODE as a multiview tile — a deliberately small player, not the 2,600-line one.
 *
 * It takes the same road to a playable URL the main player does (details -> sources -> pick ->
 * direct URL or the torrent engine's local stream), then plays it with ExoPlayer at the resume
 * point. What it deliberately does NOT carry: subtitles, the audio picker, thumbnails, episode
 * hopping, casting, the libVLC fallback, the piece bar. A tile is a quarter of the screen next to
 * a game; if you want all of that, expand it — or watch it in the real player.
 *
 * It DOES keep the two things that make it honest as a second screen: it saves progress, so the
 * film shows up in Continue Watching where you left it; and it fails over across sources, so one
 * dead torrent does not leave a black cell.
 */
@OptIn(UnstableApi::class)
class MediaSession(
    override val id: Int,
    val seed: LivePlaybackHolder.MediaSeed,
    private val appContext: Context,
    private val catalogRepository: CatalogRepository,
    private val sourceRepository: SourceRepository,
    private val torrentStreamer: TorrentStreamer,
    private val libraryRepository: LibraryRepository,
    private val settingsRepository: SettingsRepository,
    private val deviceProfile: DeviceProfile,
    private val diagnostics: Diagnostics,
    private val audioCode: () -> String,
    private val scope: CoroutineScope,
) : TileSession {

    override val title: String = LiveMultiView.mediaLabel(seed.item.title, seed.season, seed.episode)

    private val _uiState = MutableStateFlow<TileUiState>(TileUiState.Buffering)
    override val uiState: StateFlow<TileUiState> = _uiState.asStateFlow()

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    override val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    private var mainJob: Job? = null
    private var streamJob: Job? = null
    private var progressJob: Job? = null
    private var watchdogJob: Job? = null

    private var remaining: MutableList<StreamSource> = mutableListOf()
    private var current: StreamSource? = null
    private var attempts = 0
    private var hasPlayed = false
    private var generation = 0

    /** Where playback starts. Updated from the player so a retry resumes where it broke, not at the seed. */
    private var startPositionMs: Long = seed.positionMs

    private var audible = false
    private var videoEnabled = true
    private var videoCap: LiveMultiView.VideoCap? = null

    fun start() {
        val gen = ++generation
        mainJob?.cancel()
        _uiState.value = TileUiState.Buffering
        mainJob = scope.launch {
            runCatching {
                val details = when (val r = catalogRepository.getDetails(seed.item.id, seed.item.mediaType)) {
                    is DataResult.Success -> r.data
                    is DataResult.Error -> { fail(r.message); return@launch }
                }
                val list = when (val r = sourceRepository.resolve(details, seed.season, seed.episode)) {
                    is DataResult.Success -> r.data
                    is DataResult.Error -> { fail(r.message); return@launch }
                }
                if (gen != generation) return@launch
                if (list.isEmpty()) { fail("No sources found for this title."); return@launch }
                remaining = list.toMutableList()
                attempts = 0
                playNext(gen)
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                if (gen == generation) fail("Couldn't start this title.")
            }
        }
    }

    /** Same pick order as the main player: best DIRECT stream first, else the healthiest torrent. */
    private suspend fun pickFrom(list: List<StreamSource>): StreamSource? {
        val settings = settingsRepository.current()
        val prefTier = minOf(settings.wifiQuality.maxTier, deviceProfile.maxDisplayTier)
        StreamPicker.pickDirect(list, prefTier, deviceProfile.isLowPower)?.let { return it }
        return StreamPicker.pick(list, prefTier, settings.streamSize, deviceProfile.isLowPower) ?: list.firstOrNull()
    }

    private suspend fun playNext(gen: Int) {
        if (attempts >= MAX_SOURCE_ATTEMPTS || remaining.isEmpty()) {
            fail(if (hasPlayed) "This title stopped playing and no other source worked." else "Couldn't start any source for this title.")
            return
        }
        val source = pickFrom(remaining) ?: return fail("No playable source.")
        remaining.remove(source)
        attempts++
        current = source
        diagnostics.breadcrumb("mv.media id=$id attempt=$attempts direct=${source.isDirect} q=${source.quality}")
        tearDownPlayer()
        _uiState.value = TileUiState.Buffering
        armStartupWatchdog(gen)

        if (source.isDirect) {
            buildPlayer(source.directUrl!!, source.requestHeaders, gen)
            return
        }
        // Torrent: anchor the swarm at the resume point, and wait for the engine's readiness gate.
        val fraction = if (seed.durationMs > 0) (startPositionMs.toFloat() / seed.durationMs).coerceIn(0f, 0.95f) else 0f
        streamJob?.cancel()
        streamJob = scope.launch {
            torrentStreamer.start(source, fraction).collect { st ->
                if (gen != generation) return@collect
                val url = st.streamUrl
                if (url != null && _player.value == null) buildPlayer(url, emptyMap(), gen)
                if (st.state == StreamState.ERROR && _player.value == null) {
                    diagnostics.breadcrumb("mv.media id=$id torrent error: ${st.errorMessage}")
                    playNext(gen)
                }
            }
        }
    }

    private fun buildPlayer(url: String, headers: Map<String, String>, gen: Int) {
        if (gen != generation) return
        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
        val renderers = DefaultRenderersFactory(appContext)
            .setEnableDecoderFallback(true)
            .setAllowedVideoJoiningTimeMs(0L)
        val exo = ExoPlayer.Builder(appContext, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
        val item = MediaItem.Builder().setUri(url).apply {
            val path = url.substringBefore('?').lowercase()
            when {
                path.endsWith(".m3u8") -> setMimeType(MimeTypes.APPLICATION_M3U8)
                path.endsWith(".mp4") -> setMimeType(MimeTypes.VIDEO_MP4)
            }
        }.build()
        exo.setMediaItem(item, startPositionMs.coerceAtLeast(0L))
        exo.volume = if (audible) 1f else 0f
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        hasPlayed = true
                        _uiState.value = TileUiState.Playing
                    }
                    Player.STATE_BUFFERING ->
                        _uiState.value = if (hasPlayed) TileUiState.Recovering("Buffering…") else TileUiState.Buffering
                    Player.STATE_ENDED -> {
                        saveProgressNow(completed = true)
                        _uiState.value = TileUiState.Error("Finished.")
                    }
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                diagnostics.breadcrumb("mv.media id=$id player error code=${error.errorCode}")
                // Resume where it broke rather than back at the seed, then try another source.
                _player.value?.let { p -> if (p.currentPosition > 0) startPositionMs = p.currentPosition }
                scope.launch { playNext(gen) }
            }
        })
        applyTrackParams(exo)
        exo.prepare()
        exo.playWhenReady = true
        _player.value = exo
        startProgressTicker()
    }

    /** If nothing has played within the budget, this source is dead or undecodable — move on. */
    private fun armStartupWatchdog(gen: Int) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(STARTUP_BUDGET_MS)
            if (gen != generation || hasPlayed) return@launch
            diagnostics.breadcrumb("mv.media id=$id startup budget exhausted -> next source")
            playNext(gen)
        }
    }

    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                delay(PROGRESS_TICK_MS)
                saveProgressNow(completed = false)
            }
        }
    }

    /** Continue Watching stays truthful even when the film was watched in a corner. */
    private fun saveProgressNow(completed: Boolean) {
        val p = _player.value ?: return
        val position = runCatching { p.currentPosition }.getOrDefault(0L)
        val duration = runCatching { p.duration }.getOrDefault(0L).takeIf { it > 0 } ?: seed.durationMs
        if (position <= 0L && !completed) return
        val progress = PlaybackProgress(
            mediaId = seed.item.id,
            mediaType = seed.item.mediaType,
            season = seed.season,
            episode = seed.episode,
            positionMs = if (completed && duration > 0) duration else position,
            durationMs = duration,
            updatedAt = System.currentTimeMillis(),
            infoHash = current?.takeIf { !it.isDirect }?.infoHash,
        )
        scope.launch { runCatching { libraryRepository.saveProgress(seed.item, progress) } }
    }

    private fun applyTrackParams(exo: ExoPlayer) {
        runCatching {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage(audioCode())
                .apply {
                    val cap = videoCap
                    if (cap != null) setMaxVideoSize(cap.maxWidth, cap.maxHeight) else clearVideoSizeConstraints()
                    setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !videoEnabled)
                }
                .build()
        }
    }

    private fun fail(message: String) {
        _uiState.value = TileUiState.Error(message)
    }

    private fun tearDownPlayer() {
        progressJob?.cancel()
        _player.value?.let { p ->
            runCatching { if (p.currentPosition > 0) startPositionMs = p.currentPosition }
            runCatching { p.release() }
        }
        _player.value = null
    }

    override fun setAudible(value: Boolean) {
        if (audible == value) return
        audible = value
        _player.value?.let { runCatching { it.volume = if (value) 1f else 0f } }
    }

    override fun setVideoEnabled(value: Boolean) {
        if (videoEnabled == value) return
        videoEnabled = value
        _player.value?.let { applyTrackParams(it) }
    }

    override fun setVideoCap(cap: LiveMultiView.VideoCap?) {
        if (videoCap == cap) return
        videoCap = cap
        _player.value?.let { applyTrackParams(it) }
    }

    override fun retry() {
        _player.value?.let { p -> runCatching { if (p.currentPosition > 0) startPositionMs = p.currentPosition } }
        streamJob?.cancel()
        watchdogJob?.cancel()
        tearDownPlayer()
        start()
    }

    override fun release() {
        generation++
        saveProgressNow(completed = false)
        mainJob?.cancel()
        streamJob?.cancel()
        watchdogJob?.cancel()
        progressJob?.cancel()
        tearDownPlayer()
        // Keep the partial download in cache (fast to come back to); just stop feeding a dead tile.
        current?.takeIf { !it.isDirect }?.let { src ->
            scope.launch { runCatching { torrentStreamer.stop(src.infoHash, removeFiles = false) } }
        }
    }

    private companion object {
        const val MAX_SOURCE_ATTEMPTS = 3
        const val STARTUP_BUDGET_MS = 90_000L
        const val PROGRESS_TICK_MS = 10_000L
    }
}
