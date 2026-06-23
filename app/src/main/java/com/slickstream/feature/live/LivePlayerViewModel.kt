package com.slickstream.feature.live

import android.content.Context
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A lightweight HLS player for live sports — separate from the torrent
 * [com.slickstream.feature.player.PlayerViewModel]. Reads the selected feed from
 * [LivePlaybackHolder] and plays it via ExoPlayer with the host's required request headers.
 *
 * If the feed is an obfuscated embed (streamed.pk, `needsResolution`), it's first run through
 * [WebViewStreamResolver] to capture the real `.m3u8`; FanCode direct feeds play immediately.
 * Because resolution is async, [player] is a StateFlow set once the stream is ready.
 */
@OptIn(UnstableApi::class)
@HiltViewModel
class LivePlayerViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val holder: LivePlaybackHolder,
    private val resolver: WebViewStreamResolver,
) : ViewModel() {

    sealed interface UiState {
        data object Buffering : UiState
        data object Playing : UiState
        data class Error(val message: String) : UiState
        data object NoStream : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Buffering)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    val title: String = holder.current?.title ?: "Live"

    init {
        val sel = holder.current
        if (sel == null) {
            _uiState.value = UiState.NoStream
        } else {
            viewModelScope.launch {
                val playUrl = if (sel.needsResolution) {
                    // Resolve the embed -> m3u8 invisibly (shows the buffering overlay meanwhile).
                    resolver.resolve(
                        embedUrl = sel.url,
                        referer = sel.headers["Referer"] ?: "https://streamed.pk/",
                        userAgent = sel.headers["User-Agent"] ?: DEFAULT_UA,
                    )
                } else {
                    sel.url
                }
                if (playUrl.isNullOrBlank()) {
                    _uiState.value = UiState.Error("Couldn't find a playable stream for this feed. Try another source.")
                } else {
                    buildPlayer(playUrl, sel.headers)
                }
            }
        }
    }

    private fun buildPlayer(url: String, headers: Map<String, String>) {
        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(headers["User-Agent"] ?: DEFAULT_UA)
        val exo = ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(url))
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_READY -> _uiState.value = UiState.Playing
                            Player.STATE_BUFFERING ->
                                if (_uiState.value !is UiState.Playing) _uiState.value = UiState.Buffering
                            else -> Unit
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        _uiState.value = UiState.Error("This feed didn't load. Try another source.")
                    }
                })
                prepare()
                playWhenReady = true
            }
        _player.value = exo
    }

    fun retry() {
        val p = _player.value ?: return
        _uiState.value = UiState.Buffering
        p.prepare()
        p.playWhenReady = true
    }

    override fun onCleared() {
        _player.value?.release()
        super.onCleared()
    }

    private companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"
    }
}
