package com.slickstream.feature.live

import androidx.media3.common.Player
import kotlinx.coroutines.flow.StateFlow

/**
 * What every multiview tile reports, whatever it is playing. Shared by the live-sports
 * [LiveSession] and the movie/episode [MediaSession] so the layout never has to know which is which.
 */
sealed interface TileUiState {
    data object Buffering : TileUiState
    data object Playing : TileUiState

    /** Playing-but-broken and being repaired: a small badge over the last frame, not an overlay. */
    data class Recovering(val message: String) : TileUiState
    data class Error(val message: String) : TileUiState
    data object NoStream : TileUiState
}

/**
 * One multiview tile. A live game and a movie are entirely different players underneath — an HLS
 * feed with a stall watchdog versus a torrent-backed VOD with a resume point — but the coordinator
 * only ever needs this much: a picture, a state, and the three resource knobs the decoder budget is
 * enforced through.
 */
interface TileSession {
    val id: Int
    val title: String
    val uiState: StateFlow<TileUiState>
    val player: StateFlow<Player?>

    /** Only one tile is heard at a time. */
    fun setAudible(value: Boolean)

    /** Off-screen tiles hand their hardware decoder back (video track disabled, session kept). */
    fun setVideoEnabled(value: Boolean)

    /** Decode no larger than the tile is drawn. */
    fun setVideoCap(cap: LiveMultiView.VideoCap?)

    fun retry()
    fun release()
}
