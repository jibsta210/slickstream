package com.slickstream.data.vlc

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.VideoSize
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/**
 * A libVLC-backed [androidx.media3.common.Player].
 *
 * Wraps an [org.videolan.libvlc.MediaPlayer] and exposes it through Media3's
 * [SimpleBasePlayer] surface so the existing `PlayerView`, the custom TV
 * transport controls, and the progress-save logic all drive it unchanged. This
 * lets SlickStream play codecs ExoPlayer can't (XviD/DivX/AVI/WMV/VC-1…), the
 * same way desktop VLC does.
 *
 * All player state lives in plain mutable fields; each libVLC event (delivered
 * on the main looper by libVLC's event handler) mutates a field and then calls
 * [invalidateState], which makes SimpleBasePlayer re-pull [getState] and emit
 * the correct Media3 listener callbacks.
 *
 * The shared [org.videolan.libvlc.LibVLC] is owned by [VlcEngine]; this class
 * only owns the `MediaPlayer`/`Media` it creates.
 */
class VlcPlayer(
    private val engine: VlcEngine,
    private val context: Context,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val mediaPlayer = MediaPlayer(engine.libVlc)

    /** HTTP headers a direct source's host requires (Referer/User-Agent). Set before the media item;
     *  empty for torrents (served by the local HTTP server, which needs no headers). */
    private var requestHeaders: Map<String, String> = emptyMap()

    /** Supply the host's required request headers before [handleSetMediaItems] builds the [Media]. */
    fun setRequestHeaders(headers: Map<String, String>) {
        requestHeaders = headers
    }

    // --- Mutable player state, all read back in getState() ---
    private var playWhenReadyField: Boolean = false
    private var mediaItemField: MediaItem? = null
    private var positionMs: Long = 0L
    private var durationMs: Long = C.TIME_UNSET
    private var playbackStateField: @Player.State Int = Player.STATE_IDLE
    private var videoSize: VideoSize = VideoSize.UNKNOWN
    private var isLoadingField: Boolean = false
    private var playerErrorField: PlaybackException? = null

    /** Position (ms) to apply once playback actually starts, or -1 if none. */
    private var pendingSeekMs: Long = -1L

    /** SimpleBasePlayer.getState()/invalidateState() must run on the application (main) looper. */
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        mediaPlayer.setEventListener(::onVlcEvent)
    }

    // ---------------------------------------------------------------------
    // libVLC event handling
    // ---------------------------------------------------------------------

    /**
     * libVLC delivers events on its OWN background thread, but SimpleBasePlayer requires all state
     * mutation + [invalidateState] on the application looper. So read the (possibly-recycled) event's
     * primitives here, then marshal them to [onVlcEventMain] on the main thread. Without this, the
     * Media3 state never updates and the controls sit at 00:00/paused while VLC is actually playing.
     */
    private fun onVlcEvent(event: MediaPlayer.Event) {
        val type = event.type
        val buffering = runCatching { event.buffering }.getOrDefault(0f)
        val timeMs = runCatching { event.timeChanged }.getOrDefault(0L)
        val lengthMs = runCatching { event.lengthChanged }.getOrDefault(0L)
        val voutCount = runCatching { event.voutCount }.getOrDefault(0)
        mainHandler.post { onVlcEventMain(type, buffering, timeMs, lengthMs, voutCount) }
    }

    private fun onVlcEventMain(type: Int, buffering: Float, timeMs: Long, lengthMs: Long, voutCount: Int) {
        when (type) {
            MediaPlayer.Event.Opening -> {
                playbackStateField = Player.STATE_BUFFERING
                isLoadingField = true
            }

            MediaPlayer.Event.Buffering -> {
                if (buffering < 100f) {
                    playbackStateField = Player.STATE_BUFFERING
                    isLoadingField = true
                } else {
                    if (playbackStateField == Player.STATE_BUFFERING) {
                        playbackStateField = Player.STATE_READY
                    }
                    isLoadingField = false
                }
            }

            MediaPlayer.Event.Playing -> {
                playbackStateField = Player.STATE_READY
                isLoadingField = false
                applyPendingSeek()
            }

            MediaPlayer.Event.Paused -> {
                if (playbackStateField == Player.STATE_BUFFERING) {
                    playbackStateField = Player.STATE_READY
                }
            }

            MediaPlayer.Event.EndReached -> {
                playbackStateField = Player.STATE_ENDED
                isLoadingField = false
            }

            MediaPlayer.Event.EncounteredError -> {
                Log.e(TAG, "libVLC EncounteredError for ${mediaItemField?.mediaId}")
                isLoadingField = false
                // Surface a real Media3 error (state must be IDLE when a playerError is set) so the
                // owning listener's onPlayerError fires and can fail over. Swallowing it here left the
                // player wedged in BUFFERING/READY forever with nothing watching for it.
                playbackStateField = Player.STATE_IDLE
                playerErrorField = PlaybackException(
                    "libVLC playback error",
                    null,
                    PlaybackException.ERROR_CODE_UNSPECIFIED,
                )
                runCatching { mediaPlayer.stop() }
            }

            MediaPlayer.Event.TimeChanged -> {
                positionMs = timeMs
            }

            MediaPlayer.Event.LengthChanged -> {
                durationMs = if (lengthMs > 0L) lengthMs else C.TIME_UNSET
            }

            MediaPlayer.Event.Vout -> {
                if (voutCount > 0) {
                    if (playbackStateField == Player.STATE_BUFFERING) {
                        playbackStateField = Player.STATE_READY
                    }
                    updateVideoSize()
                }
            }

            else -> return // ignore events we don't care about; no invalidate
        }
        invalidateState()
    }

    private fun updateVideoSize() {
        runCatching {
            val track = mediaPlayer.currentVideoTrack
            if (track != null && track.width > 0 && track.height > 0) {
                // Report the sample/pixel aspect ratio (SAR) too, so PlayerView letterbox-fits
                // non-square-pixel sources (PAL/anamorphic DVD rips etc.) correctly instead of
                // rendering them stretched. ExoPlayer derives PAR from codec metadata; libVLC
                // exposes it as sarNum/sarDen on the video track. Square pixels → 1.0 (a no-op).
                val par = if (track.sarNum > 0 && track.sarDen > 0) {
                    track.sarNum.toFloat() / track.sarDen.toFloat()
                } else {
                    1f
                }
                videoSize = VideoSize(track.width, track.height, par)
            }
        }
    }

    private fun applyPendingSeek() {
        if (pendingSeekMs >= 0L) {
            val target = pendingSeekMs
            pendingSeekMs = -1L
            runCatching { mediaPlayer.setTime(target.coerceAtLeast(0L)) }
            positionMs = target
        }
    }

    // ---------------------------------------------------------------------
    // SimpleBasePlayer state
    // ---------------------------------------------------------------------

    override fun getState(): State {
        val builder = State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
                    .addAll(
                        Player.COMMAND_PLAY_PAUSE,
                        Player.COMMAND_PREPARE,
                        Player.COMMAND_STOP,
                        Player.COMMAND_RELEASE,
                        Player.COMMAND_SET_MEDIA_ITEM,
                        Player.COMMAND_CHANGE_MEDIA_ITEMS,
                        Player.COMMAND_SEEK_BACK,
                        Player.COMMAND_SEEK_FORWARD,
                        Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_GET_TIMELINE,
                        Player.COMMAND_GET_METADATA,
                        Player.COMMAND_SET_VIDEO_SURFACE,
                    )
                    .build()
            )
            .setPlaybackState(playbackStateField)
            .setPlayerError(playerErrorField)
            .setPlayWhenReady(
                playWhenReadyField,
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            )
            .setContentPositionMs(positionMs)
            .setVideoSize(videoSize)
            .setIsLoading(isLoadingField)

        val item = mediaItemField
        if (item != null) {
            val uid = item.mediaId.ifEmpty { "vlc" }
            builder
                .setPlaylist(
                    listOf(
                        MediaItemData.Builder(uid)
                            .setMediaItem(item)
                            .setDurationUs(if (durationMs > 0L) durationMs * 1000L else C.TIME_UNSET)
                            .setIsSeekable(true)
                            .build()
                    )
                )
                .setCurrentMediaItemIndex(0)
        }

        return builder.build()
    }

    // ---------------------------------------------------------------------
    // Command handlers
    // ---------------------------------------------------------------------

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        val item = mediaItems.firstOrNull()
        mediaItemField = item

        if (item != null) {
            val uriString = item.localConfiguration!!.uri.toString()
            val media = Media(engine.libVlc, Uri.parse(uriString))
            media.setHWDecoderEnabled(true, false)
            // Apply the direct host's required headers. libVLC exposes only user-agent + referrer (no
            // per-arbitrary-header option), which covers the two virtually every CDN validates. Origin
            // has no libVLC option, so map it onto referrer too. No-op when headers are empty (torrents).
            (requestHeaders["User-Agent"] ?: requestHeaders["user-agent"])?.let {
                media.addOption(":http-user-agent=$it")
            }
            (requestHeaders["Referer"] ?: requestHeaders["Origin"])?.let {
                media.addOption(":http-referrer=$it")
            }
            mediaPlayer.media = media
            media.release()

            pendingSeekMs = if (startPositionMs > 0L) startPositionMs else -1L
            positionMs = startPositionMs.coerceAtLeast(0L)
        } else {
            pendingSeekMs = -1L
            positionMs = 0L
        }

        durationMs = C.TIME_UNSET
        videoSize = VideoSize.UNKNOWN
        playerErrorField = null   // new media -> the player is reusable after a prior error
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        playerErrorField = null
        if (mediaItemField != null) {
            playbackStateField = Player.STATE_BUFFERING
            isLoadingField = true
        }
        // Actual play() is deferred to handleSetPlayWhenReady(true).
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        playWhenReadyField = playWhenReady
        if (playWhenReady) {
            mediaPlayer.play()
        } else {
            mediaPlayer.pause()
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        val target = positionMs.coerceAtLeast(0L)
        mediaPlayer.setTime(target)
        this.positionMs = target
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        mediaPlayer.stop()
        playbackStateField = Player.STATE_IDLE
        isLoadingField = false
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    /**
     * Media3 video-surface SPI: PlayerView (and the TV PlayerView) call `setVideoSurfaceView(...)`
     * when the player is bound, which routes here. We attach libVLC's video output to that SurfaceView
     * so VLC renders straight into the existing player surface — no separate video view needed.
     */
    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        (videoOutput as? SurfaceView)?.let { attachSurface(it) }
        return Futures.immediateVoidFuture()
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        detachSurface()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        detachSurface()
        runCatching { mediaPlayer.release() }
        // Do NOT release the shared LibVLC here — VlcEngine owns it.
        return Futures.immediateVoidFuture()
    }

    // ---------------------------------------------------------------------
    // Surface helpers (called by the UI layer once it's wired in)
    // ---------------------------------------------------------------------

    private var currentSurfaceView: SurfaceView? = null

    /**
     * PlayerView hands us its SurfaceView at bind time, BEFORE Compose has laid it out (0x0, not
     * attached) — attaching VLC to that dead surface renders nothing. So register a SurfaceHolder
     * callback and (re)attach VLC's vout in surfaceChanged, once the surface is actually created and
     * sized. Also attach immediately if the surface is already valid (re-bind case).
     */
    fun attachSurface(surfaceView: SurfaceView) {
        if (currentSurfaceView === surfaceView) {
            // Same surface re-handed: attach now if it's already live.
            if (surfaceView.holder.surface?.isValid == true && surfaceView.width > 0) {
                doAttach(surfaceView, surfaceView.width, surfaceView.height)
            }
            return
        }
        currentSurfaceView?.holder?.removeCallback(surfaceCallback)
        currentSurfaceView = surfaceView
        surfaceView.holder.addCallback(surfaceCallback)
        if (surfaceView.holder.surface?.isValid == true && surfaceView.width > 0) {
            doAttach(surfaceView, surfaceView.width, surfaceView.height)
        }
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {}
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            // Re-attach whenever the vout isn't bound to THIS exact surface. A mid-stream failover
            // remounts PlayerView with a FRESH SurfaceView; a stale voutAttached==true latch (from the
            // previous surface) used to make this a no-op resize, so the new surface never got a vout —
            // AUDIO played over a BLACK screen. The identity check forces a real re-attach.
            val sv = currentSurfaceView
            if (voutAttached && attachedSurface === sv) applyWindowSize(width, height)
            else sv?.let { doAttach(it, width, height) }
        }
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            voutAttached = false
            attachedSurface = null
            runCatching { mediaPlayer.vlcVout.detachViews() }
        }
    }

    /** True once VLC's vout is attached to the current surface (so surfaceChanged can just resize). */
    private var voutAttached = false
    /** The exact SurfaceView the vout is currently attached to — so a remounted (different-identity)
     *  surface forces a real re-attach even if [voutAttached] is stale-true. */
    private var attachedSurface: SurfaceView? = null

    /**
     * libVLC reports the decoded video layout here. We re-assert the FULL surface as the window so VLC
     * scales the picture to fill it — without this, libVLC draws the frame 1:1 in a corner and the rest
     * of the surface shows through as an uninitialised buffer (the Android-TV "blue box"). Also the most
     * reliable place to learn the real video size (incl. pixel aspect) to publish up to Media3.
     */
    private val videoLayoutListener =
        org.videolan.libvlc.interfaces.IVLCVout.OnNewVideoLayoutListener { _, width, height, _, _, sarNum, sarDen ->
            currentSurfaceView?.let { sv ->
                if (sv.width > 0 && sv.height > 0) applyWindowSize(sv.width, sv.height)
            }
            if (width > 0 && height > 0) {
                // A real frame is decoding now — drop the black backing so VLC's video shows (and any
                // letterbox region isn't forced black over the picture).
                currentSurfaceView?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                val par = if (sarNum > 0 && sarDen > 0) sarNum.toFloat() / sarDen.toFloat() else 1f
                videoSize = VideoSize(width, height, par)
                mainHandler.post { invalidateState() }
            }
        }

    private fun applyWindowSize(width: Int, height: Int) {
        runCatching { mediaPlayer.vlcVout.setWindowSize(width.coerceAtLeast(1), height.coerceAtLeast(1)) }
    }

    private fun doAttach(surfaceView: SurfaceView, width: Int, height: Int) {
        runCatching {
            val vout = mediaPlayer.vlcVout
            vout.detachViews()
            vout.setVideoView(surfaceView)
            // Order matters: attachViews FIRST, THEN setWindowSize. libVLC ignores a window size set
            // before its vout window exists, which left the picture unscaled in a corner with the blue
            // surface showing around it. The layout listener re-asserts the size once VLC reports the
            // real video dimensions.
            vout.attachViews(videoLayoutListener)
            vout.setWindowSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
            voutAttached = true
            attachedSurface = surfaceView
            // Black out the SurfaceView buffer until VLC paints its first frame — otherwise the
            // uninitialised surface buffer shows through as the Android-TV "blue box" while buffering
            // (a slow source that never decodes a frame left the whole screen blue). Cleared to
            // transparent in [videoLayoutListener] once real video dimensions arrive (decode has begun).
            surfaceView.setBackgroundColor(android.graphics.Color.BLACK)
        }.onFailure {
            voutAttached = false
            Log.e(TAG, "doAttach failed", it)
        }
    }

    fun detachSurface() {
        currentSurfaceView?.holder?.removeCallback(surfaceCallback)
        currentSurfaceView = null
        voutAttached = false
        attachedSurface = null
        runCatching { mediaPlayer.vlcVout.detachViews() }
    }

    private companion object {
        const val TAG = "VlcPlayer"
    }
}
