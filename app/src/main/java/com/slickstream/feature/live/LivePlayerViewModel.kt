package com.slickstream.feature.live

import android.content.Context
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
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
import javax.inject.Inject

/**
 * A lightweight HLS player for live sports — completely separate from the torrent
 * [com.slickstream.feature.player.PlayerViewModel]. Reads the selected feed from
 * [LivePlaybackHolder] and plays it via ExoPlayer with the host's required request headers
 * (Referer / User-Agent) injected on every playlist + segment request.
 */
@OptIn(UnstableApi::class)
@HiltViewModel
class LivePlayerViewModel @Inject constructor(
    @ApplicationContext appContext: Context,
    private val holder: LivePlaybackHolder,
) : ViewModel() {

    sealed interface UiState {
        data object Buffering : UiState
        data object Playing : UiState
        data class Error(val message: String) : UiState
        data object NoStream : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Buffering)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val title: String = holder.current?.title ?: "Live"

    val player: ExoPlayer? = holder.current?.let { sel ->
        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(sel.headers)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(sel.headers["User-Agent"])
        ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(sel.url))
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
    }

    init {
        if (holder.current == null) _uiState.value = UiState.NoStream
    }

    fun retry() {
        val p = player ?: return
        _uiState.value = UiState.Buffering
        p.prepare()
        p.playWhenReady = true
    }

    override fun onCleared() {
        player?.release()
        super.onCleared()
    }
}
