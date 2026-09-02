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
import org.videolan.libvlc.interfaces.IMedia

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

    /**
     * Comma-separated `--audio-language` list (e.g. "en,eng,english"), applied as a MEDIA option.
     *
     * Without this libVLC has no language preference at all: its es_out picks the audio ES with the
     * highest priority, which means the container's DEFAULT/FORCED disposition and otherwise the FIRST
     * audio ES in demux order. On a Chinese-sourced `MULTI` WEB-DL the Mandarin track is routinely both
     * — which is exactly how "Mutiny" played in Chinese on this backend while the ExoPlayer path had a
     * preference the VLC path never saw. Set before [handleSetMediaItems]; empty = no preference.
     */
    private var audioLanguagePreference: String = ""

    fun setPreferredAudioLanguages(commaSeparated: String) {
        audioLanguagePreference = commaSeparated.trim()
    }

    /**
     * Fired on the main thread whenever libVLC's elementary-stream set changes (ESAdded / ESSelected /
     * ESDeleted). The owning ViewModel re-enumerates [audioTracks] here — libVLC does NOT know its
     * track list at prepare() time, only once the demuxer has opened the stream, so a one-shot read
     * after play() finds nothing.
     */
    var onAudioTracksChanged: (() -> Unit)? = null

    /** Fired (on the main thread, once per vout creation) when libVLC reports a LIVE video output —
     *  the authoritative "frames are rendering" signal. The VM's no-first-frame watchdog keys on this:
     *  the Media3 onVideoSizeChanged proxy depends on track DIMENSIONS being parsed at Vout time, which
     *  can lag or miss on a late (setVideoTrackEnabled-created) vout — and then the watchdog popped the
     *  source panel over a video that was playing fine. */
    var onFirstFrame: (() -> Unit)? = null

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
        // ES events carry their type on the event object, which libVLC may recycle — read it HERE,
        // on libVLC's thread, like every other field above.
        val esType = if (type == MediaPlayer.Event.ESAdded ||
            type == MediaPlayer.Event.ESDeleted ||
            type == MediaPlayer.Event.ESSelected
        ) {
            runCatching { event.esChangedType }.getOrDefault(IMedia.Track.Type.Unknown)
        } else {
            IMedia.Track.Type.Unknown
        }
        mainHandler.post { onVlcEventMain(type, buffering, timeMs, lengthMs, voutCount, esType) }
    }

    private fun onVlcEventMain(
        type: Int,
        buffering: Float,
        timeMs: Long,
        lengthMs: Long,
        voutCount: Int,
        esType: Int,
    ) {
        when (type) {
            // The ONLY moment libVLC knows what audio streams the file has. Previously these three
            // fell into the `else -> return` branch below and were dropped, which is why the VLC
            // backend never populated an audio-track list and the picker button never appeared.
            MediaPlayer.Event.ESAdded,
            MediaPlayer.Event.ESDeleted,
            MediaPlayer.Event.ESSelected,
            -> {
                if (esType == IMedia.Track.Type.Audio) onAudioTracksChanged?.invoke()
                return   // no Media3 state changed — don't invalidate
            }

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
                    onFirstFrame?.invoke()   // video output is LIVE — frames are rendering
                    if (playbackStateField == Player.STATE_BUFFERING) {
                        playbackStateField = Player.STATE_READY
                    }
                    // Frames are rendering NOW — lift the black anti-"blue box" backing. It's a View
                    // background painted OVER the SurfaceView's punched hole, and it was only cleared in
                    // the OnNewVideoLayout callback — which does NOT re-fire when the vout is created
                    // LATE via setVideoTrackEnabled (the mid-stream Exo->VLC rescue). Result: the video
                    // played invisibly under a black view ("every video black, frames flash on
                    // back-press"). Vout(count>0) is the authoritative "video output live" event, so
                    // clear it here too and re-assert the window size so the picture fills the surface.
                    hasRenderedFrame = true
                    currentSurfaceView?.let { sv ->
                        sv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        if (sv.width > 0 && sv.height > 0) applyWindowSize(sv.width, sv.height)
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
        // A NEW media item has painted nothing yet, so the anti-"blue box" black backing is earned
        // again for this source (see [hasRenderedFrame] / [doAttach]).
        hasRenderedFrame = false

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
            // Preferred SPOKEN language. libVLC 3.x matches each comma-separated entry against the
            // track's language string, so we pass every form a container writes ("en,eng,english").
            // This only helps TAGGED tracks — an untagged MULTI still needs the explicit pick the
            // ViewModel applies from AudioTrackChoice once ESAdded lands — but it is free and it fixes
            // the common case before the first frame, with no audible track switch.
            if (audioLanguagePreference.isNotEmpty()) {
                media.addOption(":audio-language=$audioLanguagePreference")
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

    // ---------------------------------------------------------------------
    // Audio tracks
    // ---------------------------------------------------------------------

    /** One libVLC audio elementary stream, flattened for the ViewModel's picker. */
    data class VlcAudioTrack(
        /** libVLC ES id — the value [selectAudioTrack] takes. NOT an index. */
        val id: Int,
        /** libVLC's display name, e.g. "Track 1 - [English]". */
        val name: String?,
        /** ISO language tag straight from the container ("eng", "zh", null). */
        val language: String?,
        /** libVLC codec string ("a52", "mp4a", "dts"). */
        val codec: String?,
        val channels: Int,
        val selected: Boolean,
    )

    /**
     * Enumerate the stream's audio tracks.
     *
     * Uses the LEGACY libVLC 3.x API, which is what `libvlc-all:3.6.5` actually ships — verified with
     * `javap` on the AAR's classes.jar: `getAudioTracksCount()`, `getAudioTracks(): TrackDescription[]`
     * (fields `int id`, `String name`), `getAudioTrack(): int`, `setAudioTrack(int): boolean`. The
     * newer `getTracks(type)` / `selectTrack()` 4.x API is NOT present in this build; writing against
     * it compiles nowhere.
     *
     * `TrackDescription` only carries id + display name, so the real language/codec/channel metadata
     * comes from the Media's own track table ([IMedia.AudioTrack]) matched by ES id. Returns an empty
     * list until libVLC has opened the stream (before the first ESAdded event there is nothing to
     * report) — which is why the ViewModel re-reads this on [onAudioTracksChanged].
     */
    fun audioTracks(): List<VlcAudioTrack> = runCatching {
        // libVLC prepends a synthetic "Disable" entry with id -1; it isn't a track.
        val descriptions = mediaPlayer.audioTracks?.filter { it.id >= 0 } ?: return emptyList()
        if (descriptions.isEmpty()) return emptyList()
        val currentId = runCatching { mediaPlayer.audioTrack }.getOrDefault(-1)
        val meta = audioTrackMetadata()
        descriptions.mapIndexed { index, d ->
            // Match by ES id (both APIs report libvlc's i_id); fall back to position if a demuxer
            // ever disagrees, so we degrade to "no language shown" rather than to a wrong language.
            val m = meta.firstOrNull { it.id == d.id } ?: meta.getOrNull(index)
            VlcAudioTrack(
                id = d.id,
                name = d.name,
                language = m?.language,
                codec = m?.codec ?: m?.originalCodec,
                channels = m?.channels ?: 0,
                selected = d.id == currentId,
            )
        }
    }.getOrElse {
        Log.w(TAG, "audioTracks() failed", it)
        emptyList()
    }

    /** Force a specific audio ES. Returns false if libVLC rejected it (e.g. the stream closed). */
    fun selectAudioTrack(id: Int): Boolean =
        runCatching { mediaPlayer.setAudioTrack(id) }.getOrElse {
            Log.w(TAG, "setAudioTrack($id) failed", it)
            false
        }

    /**
     * The Media's parsed track table. `MediaPlayer.getMedia()` hands back a RETAINED reference, so it
     * must be released or the native Media leaks for the life of the process (one per source switch).
     */
    private fun audioTrackMetadata(): List<IMedia.AudioTrack> {
        val media = runCatching { mediaPlayer.media }.getOrNull() ?: return emptyList()
        return try {
            (0 until media.trackCount).mapNotNull { media.getTrack(it) as? IMedia.AudioTrack }
        } catch (t: Throwable) {
            Log.w(TAG, "audioTrackMetadata() failed", t)
            emptyList()
        } finally {
            runCatching { media.release() }
        }
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
        // Drop the ViewModel's callbacks BEFORE releasing: a late ES event would otherwise call back
        // into a VM that has already moved on to another player and republish a dead track list.
        onAudioTracksChanged = null
        onFirstFrame = null
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
        // Same VIEW re-handed. Do NOT short-circuit on view identity: the same SurfaceView can come
        // back carrying a BRAND NEW android.view.Surface (screensaver/background return), and a View
        // check cannot see that — it reports "already attached" and leaves libVLC's vout pointing at a
        // dead surface, which is exactly audio-over-black. Re-attach whenever the surface is live;
        // doAttach is idempotent and [attachedSurface] tracks the Surface, not the view.
        if (currentSurfaceView === surfaceView) {
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
        override fun surfaceCreated(holder: SurfaceHolder) {
            // Second, independent signal for the same re-attach. Recovery used to ride on
            // surfaceChanged ALONE, and libVLC itself can't help: AWindow's own SurfaceHolder.Callback
            // has a surfaceChanged that is a compiled no-op, and its surfaceDestroyed REMOVES that
            // callback (AWindow.onSurfaceDestroyed -> detachViews -> SurfaceHelper.release), so after a
            // destroy libVLC hears nothing at all. If doAttach fails here it gets a second chance below.
            val sv = currentSurfaceView ?: return
            if (sv.width > 0 && sv.height > 0) doAttach(sv, sv.width, sv.height)
        }
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            // Re-attach whenever the vout isn't bound to THIS exact surface. A mid-stream failover
            // remounts PlayerView with a FRESH SurfaceView; a stale voutAttached==true latch (from the
            // previous surface) used to make this a no-op resize, so the new surface never got a vout —
            // AUDIO played over a BLACK screen. The identity check forces a real re-attach.
            //
            // The check is on the android.view.Surface, not on the SurfaceView: a screensaver/background
            // round trip hands back the SAME VIEW with a NEW SURFACE, and a view-level check calls that
            // "already attached" — the same black-with-audio outcome by a different route.
            val sv = currentSurfaceView
            if (voutAttached && attachedView === sv && attachedSurface === holder.surface) {
                applyWindowSize(width, height)
            } else {
                sv?.let { doAttach(it, width, height) }
            }
        }
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            voutAttached = false
            attachedView = null
            attachedSurface = null
            // The anti-BLUE-BOX black backing is scoped to the SURFACE, not to the media item: a brand
            // new surface has painted nothing, so it shows libVLC's uninitialised buffer (blue) until a
            // frame lands. hasRenderedFrame is otherwise reset only per media item, so on a
            // pause -> screensaver -> resume with the SAME item it would still read true and the backing
            // would be skipped — reinstating the v1.4.53 blue screen on API < 34 boxes, where this
            // destroy/create pair really does fire. Earn the backing again.
            hasRenderedFrame = false
            // Disable the video track while there's no surface (mirrors the enable in doAttach) so the
            // next attach's setVideoTrackEnabled(true) is a real transition that rebuilds the vout.
            runCatching { mediaPlayer.setVideoTrackEnabled(false) }
            runCatching { mediaPlayer.vlcVout.detachViews() }
        }
    }

    /** True once VLC's vout is attached to the current surface (so surfaceChanged can just resize). */
    private var voutAttached = false
    /** The exact SurfaceView the vout is currently attached to — so a remounted (different-identity)
     *  surface forces a real re-attach even if [voutAttached] is stale-true. */
    private var attachedView: SurfaceView? = null
    /** The exact android.view.Surface the vout is attached to. Tracked SEPARATELY from [attachedView]
     *  because the same view can be handed back with a new Surface behind it (screensaver return, PiP,
     *  any window recreate) — view identity says "unchanged" while the vout is in fact pointing at a
     *  dead buffer producer: video black, audio fine. */
    private var attachedSurface: android.view.Surface? = null

    /** True once libVLC has actually PAINTED a frame for the current media item (Vout / new-video-layout).
     *  Gates the opaque black backing in [doAttach]: it is anti-"blue box" protection that is only
     *  earned BEFORE the first frame. Re-painting it on a LATER re-attach hid a picture that was
     *  rendering perfectly well — the screensaver-return black screen on the libVLC backend, where a
     *  PAUSED libVLC decodes nothing, so no Vout event ever arrives to lift the backing again. */
    private var hasRenderedFrame = false

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
                hasRenderedFrame = true
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
            // libVLC creates its video output at play() time. On the mid-stream Exo->VLC hand-off,
            // ensureVlcPlayer play()s SYNCHRONOUSLY and the PlayerView rebind (-> this attach) lands a
            // Compose frame LATER — so the session started with NO vout, and late attachViews alone
            // never creates one: audio plays over a permanently BLACK surface. Re-asserting the video
            // track after attach forces libVLC to (re)create the vout against the now-attached views —
            // the same pattern VLC-android's own VideoPlayerActivity uses. No-op when playback hasn't
            // started yet (the cold-start ordering), so the happy path is untouched.
            //
            // DESELECT FIRST *if something is still selected*. `setVideoTrackEnabled(true)` bails out
            // internally unless the current video track is already -1, so on a re-attach where nothing
            // deselected it (a background return with NO surfaceDestroyed — the Android 14+
            // FOLLOWS_ATTACHMENT case) the "true" call is a NO-OP and the vout is never rebuilt: audio
            // over black. Forcing -1 first makes it a real transition. Guarded on the current track so
            // the cold-start ordering (no media open yet, track already -1) is bit-for-bit unchanged.
            if (runCatching { mediaPlayer.videoTrack }.getOrDefault(-1) != -1) {
                runCatching { mediaPlayer.setVideoTrackEnabled(false) }
            }
            runCatching { mediaPlayer.setVideoTrackEnabled(true) }
            // ...and VERIFY, because libVLC can refuse silently: MediaPlayer.setVideoTrack() returns
            // false without acting while AWindow still reports surfaces "waiting" rather than ready,
            // and setVideoTrackEnabled discards that boolean. A single posted retry runs after AWindow
            // has settled, so one unlucky refusal can't strand the session in audio-only black.
            mainHandler.post {
                if (currentSurfaceView === surfaceView &&
                    runCatching { mediaPlayer.videoTrack }.getOrDefault(0) == -1
                ) {
                    runCatching { mediaPlayer.setVideoTrackEnabled(true) }
                }
            }
            voutAttached = true
            attachedView = surfaceView
            attachedSurface = surfaceView.holder?.surface
            // Black out the SurfaceView buffer until VLC paints its first frame — otherwise the
            // uninitialised surface buffer shows through as the Android-TV "blue box" while buffering
            // (a slow source that never decodes a frame left the whole screen blue). Cleared to
            // transparent in [videoLayoutListener] once real video dimensions arrive (decode has begun).
            //
            // ONLY before the first frame of this media item. This is a View background painted OVER
            // the SurfaceView's punched hole, so re-painting it on a LATER re-attach hides video that
            // is rendering fine — and the only things that lift it (Vout / new-video-layout) cannot
            // fire while libVLC is PAUSED, because a paused libVLC decodes no picture. That is exactly
            // "pause, screensaver, come back: audio plays, screen black" on the libVLC backend.
            surfaceView.setBackgroundColor(
                if (hasRenderedFrame) android.graphics.Color.TRANSPARENT
                else android.graphics.Color.BLACK
            )
        }.onFailure {
            voutAttached = false
            Log.e(TAG, "doAttach failed", it)
        }
    }

    fun detachSurface() {
        // Hand the view back CLEAN. PlayerView reuses the same SurfaceView across a player swap, so a
        // VlcPlayer released while its black backing was painted left the next player — including a
        // fresh ExoPlayer — behind a permanently opaque view.
        currentSurfaceView?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        currentSurfaceView?.holder?.removeCallback(surfaceCallback)
        currentSurfaceView = null
        voutAttached = false
        attachedView = null
        attachedSurface = null
        runCatching { mediaPlayer.setVideoTrackEnabled(false) }
        runCatching { mediaPlayer.vlcVout.detachViews() }
    }

    private companion object {
        const val TAG = "VlcPlayer"
    }
}
