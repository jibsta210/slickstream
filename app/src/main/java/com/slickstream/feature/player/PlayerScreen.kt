@file:kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.slickstream.feature.player

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import android.view.ContextThemeWrapper
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.app.MediaRouteControllerDialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.slickstream.data.vlc.VlcPlayer
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Rect
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.slickstream.core.model.Episode
import com.slickstream.core.model.StreamSource
import com.slickstream.core.model.SubtitleTrack
import com.slickstream.data.settings.SubtitleSize
import com.slickstream.ui.theme.Brand
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Resolve the hosting Activity from a (possibly themed-wrapped) Compose Context. */
private const val AUTO_HIDE_MS = 3_500L

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val player by viewModel.currentPlayer.collectAsState()
    val isCasting by viewModel.isCasting.collectAsState()

    // Keep the device awake while playing/loading (not when paused/idle) — replaces the always-on
    // PlayerView flag and covers the libVLC fallback too. Casting plays on the TV, so don't hold the
    // phone awake then.
    KeepScreenOn(enabled = !isCasting && (uiState is PlayerUiState.Playing || uiState is PlayerUiState.Buffering))
    val sources by viewModel.sources.collectAsState()
    val currentSource by viewModel.currentSource.collectAsState()
    val title by viewModel.title.collectAsState()
    val subtitles by viewModel.subtitles.collectAsState()
    val currentSubtitle by viewModel.currentSubtitle.collectAsState()
    val audioTracks by viewModel.audioTracks.collectAsState()
    val captionPrefs by viewModel.captionPrefs.collectAsState()
    val episodes by viewModel.episodes.collectAsState()
    val currentSeasonNumber by viewModel.currentSeasonNumber.collectAsState()
    val currentEpisodeNumber by viewModel.currentEpisodeNumber.collectAsState()
    val hasNext by viewModel.hasNext.collectAsState()
    val hasPrevious by viewModel.hasPrevious.collectAsState()
    val suggestSmaller by viewModel.suggestSmaller.collectAsState()
    val thumbnailVersion by viewModel.thumbnailVersion.collectAsState()
    val rebuffering by viewModel.rebuffering.collectAsState()
    val backdropUrl by viewModel.backdropUrl.collectAsState()
    val upNext by viewModel.upNextEpisode.collectAsState()
    val endThresholds by viewModel.endThresholds.collectAsState()
    val context = LocalContext.current

    // Scrub-preview: the ms position the user is dragging the built-in time bar to (null = not
    // scrubbing). Fed by a TimeBar.OnScrubListener attached to ExoPlayer's DefaultTimeBar below.
    var scrubPreviewMs by remember { mutableStateOf<Long?>(null) }

    var showSources by remember { mutableStateOf(false) }
    var showSubtitles by remember { mutableStateOf(false) }
    var showAudio by remember { mutableStateOf(false) }
    var showEpisodes by remember { mutableStateOf(false) }
    var overlayVisible by remember { mutableStateOf(true) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val subtitleSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val audioSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val episodeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // Fit (letterbox) vs Zoom (crop-to-fill) — survives rotation so entering fullscreen keeps the
    // user's choice. Applied to PlayerView.resizeMode in the AndroidView update block.
    var zoomFill by rememberSaveable { mutableStateOf(false) }

    // "Up next" card near the end of an episode; dismissal resets whenever the episode changes.
    var nearEnd by remember { mutableStateOf(false) }
    var upNextDismissed by remember(currentSeasonNumber, currentEpisodeNumber) { mutableStateOf(false) }

    // Fullscreen + Picture-in-Picture
    var isFullscreen by rememberSaveable { mutableStateOf(false) }
    val activity = context.findActivity()
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    val isInPip = rememberIsInPipMode()
    val videoAspect by viewModel.videoAspect.collectAsState()
    val pipController = LocalPipController.current
    var videoBounds by remember { mutableStateOf<Rect?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // --- Video surface ---------------------------------------------------
        val activePlayer = player
        if (activePlayer != null) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coords ->
                        val b = coords.boundsInWindow()
                        videoBounds = Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
                    },
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        useController = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        // Make the built-in controller the single source of truth for "are controls
                        // showing". Our custom overlays (top bar, chunk/download bar, Up-next) are gated
                        // on `overlayVisible`; the PlayerView consumes taps for its own controller, so a
                        // separate Box click never fired and those overlays stopped re-appearing after the
                        // first auto-hide. Mirror the controller's visibility into `overlayVisible` and
                        // give it our auto-hide timeout so everything shows/hides together.
                        controllerShowTimeoutMs = AUTO_HIDE_MS.toInt()
                        setControllerVisibilityListener(
                            PlayerView.ControllerVisibilityListener { visibility ->
                                overlayVisible = visibility == android.view.View.VISIBLE
                            },
                        )
                        // A non-null listener makes the built-in fullscreen button visible.
                        setFullscreenButtonClickListener { enter -> isFullscreen = enter }
                        this.player = activePlayer
                        playerViewRef = this
                        // Tap into the built-in time bar's scrub events to drive the frame-preview
                        // bubble (the controls are ExoPlayer's, so we listen rather than draw our own).
                        findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)
                            ?.addListener(object : androidx.media3.ui.TimeBar.OnScrubListener {
                                override fun onScrubStart(timeBar: androidx.media3.ui.TimeBar, position: Long) {
                                    scrubPreviewMs = position
                                }
                                override fun onScrubMove(timeBar: androidx.media3.ui.TimeBar, position: Long) {
                                    scrubPreviewMs = position
                                }
                                override fun onScrubStop(timeBar: androidx.media3.ui.TimeBar, position: Long, canceled: Boolean) {
                                    scrubPreviewMs = null
                                }
                            })
                    }
                },
                update = { view ->
                    view.player = activePlayer
                    view.useController = !isInPip   // hide all controls inside the PiP window
                    view.setFullscreenButtonState(isFullscreen)
                    view.subtitleView?.applyAppearance(captionPrefs.size, captionPrefs.style)
                    // Fit (letterbox, preserve aspect) vs Zoom (crop to fill the screen). Lets the user
                    // banish the black bars on scope (2.39:1) movies. Applies to ExoPlayer AND the VLC
                    // fallback, since both render into this PlayerView's surface.
                    view.resizeMode =
                        if (zoomFill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        else AspectRatioFrameLayout.RESIZE_MODE_FIT
                    // While our unified buffering overlay is up, suppress ExoPlayer's own spinner so
                    // there's exactly ONE start-up indicator; restore it once playing so a mid-stream
                    // rebuffer still shows feedback (the overlay stays hidden then, since state==Playing).
                    view.setShowBuffering(
                        if (uiState is PlayerUiState.Buffering) PlayerView.SHOW_BUFFERING_NEVER
                        else PlayerView.SHOW_BUFFERING_WHEN_PLAYING,
                    )
                    // The libVLC fallback renders into PlayerView's own surface but never signals
                    // Media3's first-frame, so the black shutter would stay over the video — make it
                    // transparent for VLC (normal ExoPlayer keeps the black shutter).
                    view.setShutterBackgroundColor(
                        if (activePlayer is VlcPlayer) android.graphics.Color.TRANSPARENT
                        else android.graphics.Color.BLACK,
                    )
                },
            )
        }

        // Fullscreen: immersive system bars + landscape lock, always restored on leave.
        DisposableEffect(isFullscreen, activity) {
            val window = activity?.window
            val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
            if (isFullscreen) {
                overlayVisible = true // show chrome on entering fullscreen, then it auto-hides
                controller?.apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                controller?.show(WindowInsetsCompat.Type.systemBars())
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            playerViewRef?.setFullscreenButtonState(isFullscreen)
            onDispose {
                // Leaving the player must never leave the rest of the app landscape/bars-hidden.
                controller?.show(WindowInsetsCompat.Type.systemBars())
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }

        // Entering PiP drops fullscreen so we don't restore a stuck landscape/immersive state.
        LaunchedEffect(isInPip) { if (isInPip) isFullscreen = false }

        // Auto-hide is now owned by the PlayerView controller (controllerShowTimeoutMs above), which
        // mirrors its visibility back into `overlayVisible` — so the custom overlays hide/show in lockstep
        // with the built-in controls instead of on a separate timer that drifted out of sync.

        // A dismiss is temporary — bring the card back ~30s later (unless the episode changed meanwhile).
        LaunchedEffect(upNextDismissed) {
            if (upNextDismissed) {
                delay(UP_NEXT_REDISPLAY_MS)
                upNextDismissed = false
            }
        }

        // Poll position ~1/s to learn when we're near the end (movies / no-next never qualify).
        LaunchedEffect(player, uiState) {
            if (uiState !is PlayerUiState.Playing) {
                nearEnd = false
                return@LaunchedEffect
            }
            while (true) {
                val p = player
                nearEnd = p != null && p.duration > 0 &&
                    p.currentPosition.toFloat() / p.duration >= endThresholds.first
                delay(1000)
            }
        }

        // Keep the Activity's PiP params synced with playback + aspect; suppress PiP while casting.
        LaunchedEffect(uiState, isCasting, videoAspect, videoBounds) {
            pipController?.update(
                enabled = uiState is PlayerUiState.Playing && !isCasting,
                aspect = videoAspect,
                sourceHint = videoBounds,
            )
        }

        // Auto-PiP must only arm while the player is on screen. When we leave the player (back to
        // the main UI), disarm it — otherwise a later Home/Back press from a browsing screen would
        // wrongly pop the floating window.
        DisposableEffect(pipController) {
            onDispose {
                pipController?.update(enabled = false, aspect = videoAspect, sourceHint = null)
            }
        }

        // --- Top bar overlay (title + back + sources) -----------------------
        AnimatedVisibility(
            visible = (overlayVisible || uiState !is PlayerUiState.Playing) && !isInPip,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopOverlay(
                title = title,
                onBack = onBack,
                onSources = { showSources = true },
                showSourcesButton = sources.isNotEmpty(),
                onSubtitles = { showSubtitles = true },
                subtitlesActive = currentSubtitle != null,
                onAudio = { showAudio = true },
                showAudioButton = audioTracks.size > 1,
                zoomFill = zoomFill,
                onToggleZoom = { zoomFill = !zoomFill },
                // Episode navigation — only present for TV shows (episode list resolved).
                hasEpisodes = episodes.isNotEmpty(),
                hasPrevious = hasPrevious,
                hasNext = hasNext,
                onPrevious = { viewModel.previousEpisode() },
                onNext = { viewModel.nextEpisode() },
                onEpisodes = { showEpisodes = true },
                pipSupported = pipController?.isSupported == true && !isCasting,
                onPip = { pipController?.enter() },
                castAvailable = viewModel.castAvailable,
                isCasting = isCasting,
                onCast = {
                    val selector = viewModel.castMergedSelector
                    if (selector != null) {
                        val themed = ContextThemeWrapper(
                            context,
                            androidx.appcompat.R.style.Theme_AppCompat_DayNight,
                        )
                        if (viewModel.isCastConnected()) {
                            MediaRouteControllerDialog(themed).show()
                        } else {
                            MediaRouteChooserDialog(themed).apply { routeSelector = selector }.show()
                        }
                    }
                },
            )
        }

        // --- State overlays (hidden inside the PiP window) ------------------
        if (!isInPip) {
            when (val state = uiState) {
                // ONE continuous overlay from tap → first frame. The torrent head-download AND the
                // player's own prepare/buffer both live under PlayerUiState.Buffering, so we keep it up
                // until Playing (not just until the player object exists). This removes the old jarring
                // second step where the branded ETA vanished and ExoPlayer's bare spinner took over.
                is PlayerUiState.Buffering -> BufferingOverlay(
                    state = state,
                    // Title art behind the overlay — the "rollercoaster line-up", so the wait feels like
                    // the movie loading instead of a black screen.
                    backdropUrl = backdropUrl,
                    // Gentle "taking a while? try a smaller stream" nudge — only after a long, starved buffer.
                    suggestSmaller = suggestSmaller,
                    onSwitchToSmaller = viewModel::switchToSmaller,
                    onDismissSuggestSmaller = viewModel::dismissSuggestSmaller,
                    // Let the user open the source sheet (direct + torrents, badged) WHILE loading — the
                    // overlay otherwise covers the top bar's source button, stranding them on the auto-pick.
                    canSwitchSource = sources.size > 1,
                    onSwitchSource = { showSources = true },
                )
                is PlayerUiState.Error -> ErrorOverlay(
                    message = state.message,
                    onRetry = viewModel::retry,
                    onOtherStreams = if (sources.size > 1) { { showSources = true } } else null,
                )
                PlayerUiState.Playing -> Unit
            }

            if (isCasting) CastingOverlay(
                controlsVisible = overlayVisible,
                onPlayOnDevice = viewModel::stopCasting,
            )

            // Film-strip preview while dragging the seek bar: a row of frames around the scrub point
            // that scrolls as you drag (earlier frames left, later frames right), the centre frame
            // being the target. Empty slots show for parts of the timeline not yet sampled.
            val previewMs = scrubPreviewMs
            if (previewMs != null && !isCasting) {
                val dur = activePlayer?.duration?.takeIf { it > 0 } ?: 0L
                ScrubFilmstrip(
                    centerMs = previewMs,
                    durationMs = dur,
                    thumbnailAt = viewModel::thumbnailAt,
                    version = thumbnailVersion,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

            // Mid-playback stall: small "buffering ~Xs" badge over the frozen frame so it never looks
            // permanently frozen. The video (Playing) stays underneath; this is NOT the startup overlay.
            val rebuf = rebuffering
            if (rebuf != null && !isCasting && scrubPreviewMs == null) {
                RebufferBadge(rebuf, modifier = Modifier.align(Alignment.Center))
            }

            // Torrent chunk bar at the bottom, shown with the controls — watch the file fill in. Polled ~1/s.
            // Skip for DIRECT/OFFLINE sources: there's no swarm/piece map, so it only ever read
            // "Downloading…" — nonsense over a completed offline file playing from disk.
            if (uiState is PlayerUiState.Playing && !isCasting && currentSource?.isDirect != true) {
                var pieceMap by remember { mutableStateOf(FloatArray(0)) }
                var playheadFrac by remember { mutableStateOf(0f) }
                var stats by remember { mutableStateOf<StreamStats?>(null) }
                LaunchedEffect(activePlayer) {
                    while (true) {
                        pieceMap = viewModel.pieceMap(PIECE_BAR_BUCKETS)
                        stats = viewModel.streamStats()
                        val p = activePlayer
                        playheadFrac = if (p != null && p.duration > 0)
                            (p.currentPosition.toFloat() / p.duration).coerceIn(0f, 1f) else 0f
                        delay(1000)
                    }
                }
                AnimatedVisibility(
                    visible = overlayVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    PieceBarPanel(
                        map = pieceMap,
                        playheadFraction = playheadFrac,
                        stats = stats,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

            // "Up next" card near the end of an episode — tap "Play now" to jump to the next one (the
            // end-of-file auto-advance still fires if the user just lets it play out).
            val showUpNext = nearEnd && hasNext && upNext != null && !upNextDismissed &&
                uiState is PlayerUiState.Playing && !isCasting
            AnimatedVisibility(
                visible = showUpNext,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 90.dp),
            ) {
                upNext?.let { ep ->
                    PhoneUpNextCard(
                        episode = ep,
                        onPlay = { viewModel.nextEpisode() },
                        onDismiss = { upNextDismissed = true },
                    )
                }
            }
        }
    }

    if (showSources) {
        ModalBottomSheet(
            onDismissRequest = { showSources = false },
            sheetState = sheetState,
            containerColor = Brand.Surface,
            contentColor = Brand.OnSurface,
        ) {
            SourcesSheetContent(
                sources = sources,
                current = currentSource,
                onSelect = { source ->
                    viewModel.selectSource(source)
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        if (!sheetState.isVisible) showSources = false
                    }
                },
            )
        }
    }

    if (showSubtitles) {
        ModalBottomSheet(
            onDismissRequest = { showSubtitles = false },
            sheetState = subtitleSheetState,
            containerColor = Brand.Surface,
            contentColor = Brand.OnSurface,
        ) {
            SubtitlesSheetContent(
                subtitles = subtitles,
                current = currentSubtitle,
                currentSize = captionPrefs.size,
                onSizeChange = { viewModel.setSubtitleSize(it) },
                onSelect = { track ->
                    viewModel.selectSubtitle(track)
                    scope.launch { subtitleSheetState.hide() }.invokeOnCompletion {
                        if (!subtitleSheetState.isVisible) showSubtitles = false
                    }
                },
                onSearch = { viewModel.refreshSubtitles() },
            )
        }
    }

    if (showAudio) {
        ModalBottomSheet(
            onDismissRequest = { showAudio = false },
            sheetState = audioSheetState,
            containerColor = Brand.Surface,
            contentColor = Brand.OnSurface,
        ) {
            AudioSheetContent(
                tracks = audioTracks,
                onSelect = { track ->
                    viewModel.selectAudioTrack(track)
                    scope.launch { audioSheetState.hide() }.invokeOnCompletion {
                        if (!audioSheetState.isVisible) showAudio = false
                    }
                },
            )
        }
    }

    if (showEpisodes) {
        ModalBottomSheet(
            onDismissRequest = { showEpisodes = false },
            sheetState = episodeSheetState,
            containerColor = Brand.Surface,
            contentColor = Brand.OnSurface,
        ) {
            EpisodesSheetContent(
                episodes = episodes,
                currentSeason = currentSeasonNumber,
                currentEpisode = currentEpisodeNumber,
                onSelect = { ep ->
                    viewModel.playEpisode(ep.seasonNumber, ep.episodeNumber)
                    scope.launch { episodeSheetState.hide() }.invokeOnCompletion {
                        if (!episodeSheetState.isVisible) showEpisodes = false
                    }
                },
            )
        }
    }
}

/** Fraction of the runtime after which the "Up next" card appears. */

/** After dismissing the Up-next card, bring it back this long later (gentle nag, like Netflix). */
private const val UP_NEXT_REDISPLAY_MS = 30_000L

/**
 * Phone "Up next" card: the next episode's still + title with tappable Play now / Dismiss. Shown near
 * the end of a TV episode; "Play now" jumps to the next episode (the end-of-file auto-advance still
 * fires if the user just lets it play out).
 */
@Composable
private fun PhoneUpNextCard(
    episode: Episode,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.88f))
            .width(360.dp)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!episode.stillUrl.isNullOrBlank()) {
            coil.compose.AsyncImage(
                model = episode.stillUrl,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(112.dp, 63.dp).clip(RoundedCornerShape(8.dp)),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Up next", color = Brand.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(
                "S${episode.seasonNumber}E${episode.episodeNumber} · ${episode.name}",
                color = Color.White,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Play now",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Brand.Violet)
                        .clickable(onClick = onPlay)
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                )
                Text(
                    "Dismiss",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.12f))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun TopOverlay(
    title: String,
    onBack: () -> Unit,
    onSources: () -> Unit,
    showSourcesButton: Boolean,
    onSubtitles: () -> Unit,
    subtitlesActive: Boolean,
    onAudio: () -> Unit,
    showAudioButton: Boolean,
    zoomFill: Boolean,
    onToggleZoom: () -> Unit,
    hasEpisodes: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onEpisodes: () -> Unit,
    pipSupported: Boolean,
    onPip: () -> Unit,
    castAvailable: Boolean,
    isCasting: Boolean,
    onCast: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent),
                )
            )
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Brand.OnSurface,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            color = Brand.OnSurface,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Episode navigation (TV only).
        if (hasEpisodes) {
            IconButton(onClick = onPrevious, enabled = hasPrevious) {
                Icon(
                    imageVector = Icons.Filled.SkipPrevious,
                    contentDescription = "Previous episode",
                    tint = if (hasPrevious) Brand.OnSurface else Brand.OnSurface.copy(alpha = 0.35f),
                )
            }
            IconButton(onClick = onNext, enabled = hasNext) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = "Next episode",
                    tint = if (hasNext) Brand.OnSurface else Brand.OnSurface.copy(alpha = 0.35f),
                )
            }
            IconButton(onClick = onEpisodes) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                    contentDescription = "Episodes",
                    tint = Brand.OnSurface,
                )
            }
        }
        IconButton(onClick = onSubtitles) {
            Icon(
                imageVector = Icons.Filled.ClosedCaption,
                contentDescription = "Subtitles",
                tint = if (subtitlesActive) Brand.Cyan else Brand.OnSurface,
            )
        }
        if (showAudioButton) {
            IconButton(onClick = onAudio) {
                Icon(
                    imageVector = Icons.Filled.GraphicEq,
                    contentDescription = "Audio track",
                    tint = Brand.OnSurface,
                )
            }
        }
        IconButton(onClick = onToggleZoom) {
            Icon(
                imageVector = Icons.Filled.AspectRatio,
                contentDescription = if (zoomFill) "Fit to screen" else "Fill screen",
                tint = if (zoomFill) Brand.Cyan else Brand.OnSurface,
            )
        }
        if (pipSupported) {
            IconButton(onClick = onPip) {
                Icon(
                    imageVector = Icons.Filled.PictureInPictureAlt,
                    contentDescription = "Picture-in-picture",
                    tint = Brand.OnSurface,
                )
            }
        }
        if (castAvailable) {
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onCast) {
                Icon(
                    imageVector = if (isCasting) Icons.Filled.CastConnected else Icons.Filled.Cast,
                    contentDescription = "Cast to TV",
                    tint = if (isCasting) Brand.Cyan else Brand.OnSurface,
                )
            }
        }
        if (showSourcesButton) {
            Spacer(Modifier.width(8.dp))
            SourcesButton(onClick = onSources)
        }
    }
}

@Composable
private fun SourcesButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Brand.SurfaceVariant.copy(alpha = 0.85f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.HighQuality,
            contentDescription = null,
            tint = Brand.Cyan,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "Quality",
            color = Brand.OnSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun BufferingOverlay(
    state: PlayerUiState.Buffering,
    backdropUrl: String? = null,
    suggestSmaller: Boolean = false,
    onSwitchToSmaller: () -> Unit = {},
    onDismissSuggestSmaller: () -> Unit = {},
    canSwitchSource: Boolean = false,
    onSwitchSource: () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        // Title art behind the scrim — fades in when it loads so the buffering screen feels alive.
        if (backdropUrl != null) {
            androidx.compose.animation.AnimatedVisibility(
                visible = true,
                enter = fadeIn(),
                modifier = Modifier.fillMaxSize(),
            ) {
                coil.compose.AsyncImage(
                    model = backdropUrl,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // Scrim over the art so the spinner / ETA / progress bar stay legible.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.45f), Color.Black.copy(alpha = 0.78f)),
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 56.dp),
        ) {
            Text(
                text = state.label,
                color = Brand.OnSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
            // Cyan line is the live countdown ONLY. Below ~5s the unpredictable tail/prepare steps
            // dominate, so we drop the number and let the white label ("Almost ready…") carry it —
            // otherwise both lines would read "Almost ready…".
            state.etaSeconds?.takeIf { it > 5 }?.let { eta ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Starts in ~${eta}s",
                    color = Brand.Cyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )
            }
            Spacer(Modifier.height(16.dp))
            // Single slick progress bar — determinate once we have download progress, otherwise a
            // smooth indeterminate sweep while connecting / fetching metadata.
            val barModifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
            if (state.percent > 0) {
                LinearProgressIndicator(
                    progress = { state.percent / 100f },
                    color = Brand.Cyan,
                    trackColor = Brand.SurfaceVariant.copy(alpha = 0.6f),
                    modifier = barModifier,
                )
            } else {
                LinearProgressIndicator(
                    color = Brand.Cyan,
                    trackColor = Brand.SurfaceVariant.copy(alpha = 0.6f),
                    modifier = barModifier,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = bufferingStats(state),
                color = Brand.OnSurfaceDim,
                fontSize = 13.sp,
            )

            // Switch source DURING loading: the full-screen buffering overlay covers the top bar's
            // source button, so without this the user is stranded on whatever the auto-pick chose (a
            // slow torrent) with "no way to switch to a direct/HTTPS source". Opens the same styled
            // source sheet; shown only when there's an alternative to switch to.
            if (canSwitchSource) {
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Brand.Surface.copy(alpha = 0.92f))
                        .clickable(onClick = onSwitchSource)
                        .padding(horizontal = 22.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.SwapHoriz,
                        contentDescription = null,
                        tint = Brand.OnSurface,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Switch source",
                        color = Brand.OnSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    )
                }
            }

            // Gentle, dismissible "taking a while?" card — only once the VM decides a smaller,
            // well-seeded source would genuinely help. Non-blocking; the buffer keeps filling behind it.
            AnimatedVisibility(visible = suggestSmaller, enter = fadeIn(), exit = fadeOut()) {
                SmallerStreamPrompt(
                    onSwitchToSmaller = onSwitchToSmaller,
                    onDismiss = onDismissSuggestSmaller,
                )
            }
            }
        }
    }
}

/** Brand-styled, non-blocking nudge shown over the buffering bar when a smaller stream may help. */
@Composable
private fun SmallerStreamPrompt(
    onSwitchToSmaller: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(top = 24.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Brand.Surface.copy(alpha = 0.92f))
            .padding(horizontal = 24.dp, vertical = 18.dp),
    ) {
        Text(
            text = "Taking a while? Try a smaller stream",
            color = Brand.OnSurface,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Brand.Violet)
                    .clickable(onClick = onSwitchToSmaller)
                    .padding(horizontal = 22.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Smaller stream",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Dismiss",
                color = Brand.OnSurfaceDim,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

/** One concise status line: "23%  ·  5,000 seeders  ·  4.2 MB/s". */
private fun bufferingStats(state: PlayerUiState.Buffering): String = buildString {
    if (state.percent > 0) append("${state.percent}%")
    if (state.seeders > 0) {
        if (isNotEmpty()) append("  ·  ")
        append("${state.seeders} seeders")
    }
    if (state.downloadRateBytes > 0) {
        if (isNotEmpty()) append("  ·  ")
        append(formatRate(state.downloadRateBytes))
    }
    if (isEmpty()) append("Connecting…")
}

/**
 * Film-strip preview shown while the user drags the seek bar. A horizontally-scrolling ribbon of
 * frames pinned to whole-minute timeline anchors: it slides continuously as you drag (each frame's
 * translationX tracks the sub-minute offset) and the frame nearest the scrub point swells to full
 * size with a cyan border, its neighbours shrinking toward the edges — so the strip glides toward
 * the next/previous frame instead of popping. Slots whose slice isn't decoded yet show a dim
 * placeholder, keeping the ribbon's shape. Sits just above the system controls.
 */
@Composable
private fun ScrubFilmstrip(
    centerMs: Long,
    durationMs: Long,
    thumbnailAt: (Long) -> android.graphics.Bitmap?,
    version: Int,
    modifier: Modifier = Modifier,
) {
    @Suppress("UNUSED_EXPRESSION") version  // touch so newly-decoded frames recompose the strip
    val pitchPx = with(LocalDensity.current) { FILM_PITCH.toPx() }
    Column(
        modifier = modifier.padding(bottom = 86.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.width(FILM_VIEWPORT_W).height(FILM_FRAME_H).clipToBounds(),
            contentAlignment = Alignment.Center,
        ) {
            val step = FILMSTRIP_STEP_MS
            val centerK = Math.round(centerMs.toDouble() / step).toInt()
            // Draw far → near so the swollen centre frame lands on top of its shrunk neighbours.
            for (k in (centerK - FILM_SPAN..centerK + FILM_SPAN)
                    .sortedByDescending { kotlin.math.abs(it.toLong() * step - centerMs) }) {
                val posMs = k.toLong() * step
                if (durationMs > 0L && posMs !in 0L..durationMs) continue
                val distSteps = (posMs - centerMs).toFloat() / step
                val prox = (1f - kotlin.math.abs(distSteps)).coerceIn(0f, 1f)
                val scale = FILM_SIDE_SCALE + (1f - FILM_SIDE_SCALE) * prox
                FilmstripFrame(
                    thumbnail = thumbnailAt(posMs.coerceAtLeast(0L)),
                    center = kotlin.math.abs(distSteps) < 0.5f,
                    modifier = Modifier.graphicsLayer {
                        translationX = distSteps * pitchPx
                        scaleX = scale
                        scaleY = scale
                        alpha = 0.4f + 0.6f * prox
                    },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = formatTimecode(centerMs),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

private val FILM_FRAME_W = 150.dp
private val FILM_FRAME_H = 86.dp
private val FILM_VIEWPORT_W = 360.dp
private val FILM_PITCH = 122.dp
private const val FILM_SIDE_SCALE = 0.62f
private const val FILM_SPAN = 3
private const val FILMSTRIP_STEP_MS = 60_000L

@Composable
private fun FilmstripFrame(
    thumbnail: android.graphics.Bitmap?,
    center: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    val borderColor = if (center) Brand.Cyan else Color.White.copy(alpha = 0.45f)
    Box(
        modifier = modifier
            .size(FILM_FRAME_W, FILM_FRAME_H)
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.55f))
            .border(if (center) 2.dp else 1.dp, borderColor, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (thumbnail != null) {
            androidx.compose.foundation.Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(shape),
            )
        }
    }
}

/**
 * Compact "still working" badge shown over a frozen frame during a mid-playback re-buffer. A short
 * reveal delay keeps a quick in-buffer seek from flashing it. Shows a live ETA when the rate makes
 * one estimable, otherwise a climbing elapsed counter, plus the current download rate — so a stall
 * never reads as a permanent freeze.
 */
@Composable
private fun RebufferBadge(state: RebufferState, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(450); visible = true }
    var elapsed by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); elapsed++ } }
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(28.dp))
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                color = Brand.Cyan,
                strokeWidth = 2.5.dp,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = "Buffering…",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append(state.etaSeconds?.let { "about ${it}s left" } ?: "waiting ${elapsed}s")
                        if (state.downloadRateBytes > 0) {
                            append("  ·  ")
                            append(formatRate(state.downloadRateBytes))
                        }
                    },
                    color = Brand.Cyan,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

private fun formatTimecode(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@Composable
private fun ErrorOverlay(message: String, onRetry: () -> Unit, onOtherStreams: (() -> Unit)? = null) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = null,
                tint = Brand.Error,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Brand.Error.copy(alpha = 0.15f))
                    .padding(12.dp),
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = "Playback failed",
                color = Brand.OnSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                color = Brand.OnSurfaceDim,
                fontSize = 14.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(22.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Brand.Violet)
                        .clickable(onClick = onRetry)
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Retry",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    )
                }
                // A dead source is one tap from picking another — the user hit "top 2 were dead, 3rd
                // worked"; this makes that recovery obvious instead of a dead-end.
                if (onOtherStreams != null) {
                    Text(
                        text = "Other streams",
                        color = Brand.OnSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Brand.SurfaceVariant)
                            .clickable(onClick = onOtherStreams)
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CastingOverlay(controlsVisible: Boolean, onPlayOnDevice: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.CastConnected,
                contentDescription = null,
                tint = Brand.Cyan,
                modifier = Modifier.size(72.dp),
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = "Casting to your TV",
                color = Brand.OnSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Use the cast icon to switch device, or bring it back here",
                color = Brand.OnSurfaceDim,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(22.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Brand.Violet)
                    .clickable(onClick = onPlayOnDevice)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Cast,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Play on this device",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
            }
        }
        }
    }
}

@Composable
private fun AudioSheetContent(
    tracks: List<AudioTrackOption>,
    onSelect: (AudioTrackOption) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        Text(
            text = "Audio",
            color = Brand.OnSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
        )
        Text(
            text = if (tracks.size == 1) "1 track" else "${tracks.size} tracks",
            color = Brand.OnSurfaceDim,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 20.dp, bottom = 12.dp),
        )
        LazyColumn {
            items(tracks, key = { it.id }) { track ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(track) }
                        .background(if (track.selected) Brand.Violet.copy(alpha = 0.22f) else Color.Transparent)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.label,
                            color = Brand.OnSurface,
                            fontWeight = if (track.selected) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (track.language.isNotBlank() && track.language != "und") {
                            Text(
                                text = track.language.uppercase(),
                                color = Brand.OnSurfaceDim,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    if (track.selected) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = "Selected",
                            tint = Brand.Cyan,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitlesSheetContent(
    subtitles: List<SubtitleTrack>,
    current: SubtitleTrack?,
    currentSize: SubtitleSize,
    onSizeChange: (SubtitleSize) -> Unit,
    onSelect: (SubtitleTrack?) -> Unit,
    onSearch: () -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Subtitles",
                color = Brand.OnSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f),
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Brand.SurfaceVariant)
                    .clickable(onClick = onSearch)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = Brand.Cyan,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(text = "Search", color = Brand.OnSurface, fontSize = 13.sp)
            }
        }

        // Text size — persisted system-wide (DataStore), applied live to the player.
        Text(
            text = "Text size",
            color = Brand.OnSurfaceDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 6.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SubtitleSize.entries.forEach { size ->
                val selected = size == currentSize
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) Brand.Violet else Brand.SurfaceVariant)
                        .clickable { onSizeChange(size) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = size.label,
                        color = if (selected) Color.White else Brand.OnSurface,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        SubtitleRow(label = "Off", selected = current == null) { onSelect(null) }

        if (subtitles.isEmpty()) {
            Text(
                text = "No subtitles found. Tap Search to look again.",
                color = Brand.OnSurfaceDim,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            )
        } else {
            subtitles.forEach { track ->
                SubtitleRow(
                    label = track.label,
                    selected = track.id == current?.id,
                ) { onSelect(track) }
            }
        }
    }
}

@Composable
private fun SubtitleRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (selected) Brand.Violet.copy(alpha = 0.12f) else Color.Transparent)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (selected) Brand.Violet else Brand.OnSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 15.sp,
        )
    }
}

@Composable
private fun EpisodesSheetContent(
    episodes: List<Episode>,
    currentSeason: Int?,
    currentEpisode: Int?,
    onSelect: (Episode) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        Text(
            text = "Episodes",
            color = Brand.OnSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
        )
        Text(
            text = currentSeason?.let { "Season $it" } ?: "${episodes.size} episodes",
            color = Brand.OnSurfaceDim,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 20.dp, bottom = 12.dp),
        )
        LazyColumn {
            items(episodes, key = { "${it.seasonNumber}-${it.episodeNumber}" }) { episode ->
                EpisodeRow(
                    episode = episode,
                    selected = episode.seasonNumber == currentSeason &&
                        episode.episodeNumber == currentEpisode,
                    onClick = { onSelect(episode) },
                )
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (selected) Brand.Violet.copy(alpha = 0.12f) else Color.Transparent)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "S${episode.seasonNumber}E${episode.episodeNumber}",
            color = if (selected) Brand.Cyan else Brand.OnSurfaceDim,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = episode.name,
                color = if (selected) Brand.Violet else Brand.OnSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (episode.overview.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = episode.overview,
                    color = Brand.OnSurfaceDim,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SourcesSheetContent(
    sources: List<StreamSource>,
    current: StreamSource?,
    onSelect: (StreamSource) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        Text(
            text = "Sources & Quality",
            color = Brand.OnSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
        )
        Text(
            text = "${sources.size} available",
            color = Brand.OnSurfaceDim,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 20.dp, bottom = 12.dp),
        )
        // Anchor the currently-playing source at the top so it's obvious which torrent you're on.
        val ordered = remember(sources, current) {
            val cur = current?.let { c -> sources.firstOrNull { it.infoHash == c.infoHash && it.fileIndex == c.fileIndex } }
            if (cur == null) sources else listOf(cur) + sources.filter { it !== cur }
        }
        LazyColumn {
            items(ordered, key = { it.infoHash + (it.fileIndex ?: 0) }) { source ->
                SourceRow(
                    source = source,
                    selected = source.infoHash == current?.infoHash && source.fileIndex == current?.fileIndex,
                    onClick = { onSelect(source) },
                )
            }
        }
    }
}

@Composable
private fun SourceRow(
    source: StreamSource,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (selected) Brand.Violet.copy(alpha = 0.22f) else Color.Transparent)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QualityChip(quality = source.quality, highlighted = selected)
        if (source.isDirect) {
            Spacer(Modifier.width(6.dp))
            com.slickstream.ui.components.DirectBadge()
        }
        if (source.isCam) {
            Spacer(Modifier.width(6.dp))
            com.slickstream.ui.components.CamBadge()
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = source.title,
                color = Brand.OnSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected) {
                    Text(
                        text = "Now playing",
                        color = Brand.Cyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                if (source.isDirect) {
                    // A direct source has no swarm — show what it IS (instant, no torrent) instead of a
                    // misleading "0 seeders" that made HTTPS sources look like dead torrents.
                    Text(
                        text = "Instant • no torrent",
                        color = Color(0xFF22C55E),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if ((source.sizeBytes ?: 0) > 0) {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = formatSize(source.sizeBytes),
                            color = Brand.OnSurfaceDim,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = source.provider,
                        color = Brand.OnSurfaceDim,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.People,
                        contentDescription = null,
                        tint = Brand.OnSurfaceDim,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "${source.seeders ?: 0}",
                        color = Brand.OnSurfaceDim,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = formatSize(source.sizeBytes),
                        color = Brand.OnSurfaceDim,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = source.provider,
                        color = Brand.OnSurfaceDim,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (selected) {
            Spacer(Modifier.width(12.dp))
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "Now playing",
                tint = Brand.Cyan,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** Player-local quality chip (kept self-contained so the module compiles standalone). */
@Composable
private fun QualityChip(quality: String, highlighted: Boolean) {
    val accent = when (quality.uppercase()) {
        "4K", "2160P" -> Brand.Cyan
        "1080P" -> Brand.Violet
        "720P" -> Brand.Star
        else -> Brand.OnSurfaceDim
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(accent.copy(alpha = if (highlighted) 0.30f else 0.18f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = quality,
            color = accent,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
        )
    }
}

// --- formatting helpers -----------------------------------------------------

private fun formatRate(bytesPerSec: Int): String {
    if (bytesPerSec <= 0) return "0 MB/s"
    val mbps = bytesPerSec / (1024.0 * 1024.0)
    return if (mbps >= 1.0) String.format("%.1f MB/s", mbps)
    else String.format("%.0f KB/s", bytesPerSec / 1024.0)
}

private fun formatSize(bytes: Long?): String {
    if (bytes == null || bytes <= 0) return "—"
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) String.format("%.2f GB", gb)
    else String.format("%.0f MB", bytes / (1024.0 * 1024.0))
}
