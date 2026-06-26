package com.slickstream.tv.screen

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.slickstream.data.vlc.VlcPlayer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.slickstream.core.model.Episode
import com.slickstream.core.model.StreamSource
import com.slickstream.core.model.SubtitleTrack
import com.slickstream.data.settings.SubtitleSize
import com.slickstream.feature.player.PlayerUiState
import com.slickstream.feature.player.PlayerViewModel
import com.slickstream.feature.player.applyAppearance
import com.slickstream.ui.components.QualityChip
import com.slickstream.ui.theme.Brand

/**
 * Android TV player. Reuses [PlayerViewModel] (which resolves sources, drives the libtorrent
 * stream and owns the [androidx.media3.exoplayer.ExoPlayer]) and renders the very same ExoPlayer
 * instance via a Media3 [PlayerView]. A D-pad transport overlay (play/pause + ±10s) and a
 * focusable sources side-panel (quality switching) sit on top. Back press dismisses the panel /
 * overlay first, then leaves the screen.
 */
@OptIn(UnstableApi::class)
@Composable
fun TvPlayerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val currentSource by viewModel.currentSource.collectAsStateWithLifecycle()
    val title by viewModel.title.collectAsStateWithLifecycle()
    val subtitles by viewModel.subtitles.collectAsStateWithLifecycle()
    val currentSubtitle by viewModel.currentSubtitle.collectAsStateWithLifecycle()
    val captionPrefs by viewModel.captionPrefs.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val currentSeasonNumber by viewModel.currentSeasonNumber.collectAsStateWithLifecycle()
    val currentEpisodeNumber by viewModel.currentEpisodeNumber.collectAsStateWithLifecycle()
    val hasNext by viewModel.hasNext.collectAsStateWithLifecycle()
    val hasPrevious by viewModel.hasPrevious.collectAsStateWithLifecycle()
    val suggestSmaller by viewModel.suggestSmaller.collectAsStateWithLifecycle()
    val thumbnailVersion by viewModel.thumbnailVersion.collectAsStateWithLifecycle()
    val rebuffering by viewModel.rebuffering.collectAsStateWithLifecycle()
    val backdropUrl by viewModel.backdropUrl.collectAsStateWithLifecycle()
    val upNext by viewModel.upNextEpisode.collectAsStateWithLifecycle()

    var controlsVisible by remember { mutableStateOf(true) }
    var panelOpen by remember { mutableStateOf(false) }
    var subsPanelOpen by remember { mutableStateOf(false) }
    var episodesPanelOpen by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }
    var scrubbing by remember { mutableStateOf(false) }
    // Fit (letterbox) vs Zoom (crop-to-fill) — toggled from the transport row.
    var zoomFill by remember { mutableStateOf(false) }
    // "Up next" card near the end of an episode; dismissal resets whenever the episode changes.
    var nearEnd by remember { mutableStateOf(false) }
    var upNextDismissed by remember(currentSeasonNumber, currentEpisodeNumber) { mutableStateOf(false) }

    val rootFocus = remember { FocusRequester() }
    val playPauseFocus = remember { FocusRequester() }
    val scrubFocus = remember { FocusRequester() }
    val upNextFocus = remember { FocusRequester() }

    // Track play state for the toggle icon.
    DisposableEffect(player) {
        val p = player
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        }
        p?.addListener(listener)
        p?.let { isPlaying = it.isPlaying }
        onDispose { p?.removeListener(listener) }
    }

    val anyPanelOpen = panelOpen || subsPanelOpen || episodesPanelOpen

    // The "Up next" card shows when we're near the end of a TV episode that has a next one, the user
    // hasn't dismissed it, and the transport isn't up (the card owns focus when it's visible).
    val showUpNext = nearEnd && hasNext && upNext != null && !upNextDismissed &&
        !controlsVisible && uiState is PlayerUiState.Playing

    // Poll position ~1/s to learn when we've crossed UP_NEXT_PCT. Movies / no-next never qualify.
    LaunchedEffect(player, uiState) {
        if (uiState !is PlayerUiState.Playing) {
            nearEnd = false
            return@LaunchedEffect
        }
        while (true) {
            val p = player
            nearEnd = p != null && p.duration > 0 &&
                p.currentPosition.toFloat() / p.duration >= UP_NEXT_PCT
            kotlinx.coroutines.delay(1000)
        }
    }
    // Land focus on the card's "Play now" the moment it appears so it's immediately D-pad operable.
    // Retry past the slide-in animation — a single requestFocus can fire before the button is attached.
    LaunchedEffect(showUpNext) {
        if (showUpNext) {
            repeat(12) {
                kotlinx.coroutines.delay(50)
                if (runCatching { upNextFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }
    }

    // Auto-hide the transport overlay after a few seconds of no interaction (never while a panel is
    // open or a scrub is in progress).
    LaunchedEffect(controlsVisible, anyPanelOpen, scrubbing) {
        if (controlsVisible && !anyPanelOpen && !scrubbing) {
            kotlinx.coroutines.delay(5_000)
            controlsVisible = false
        }
    }

    // When the controls are hidden (and no panel is open) there is no focusable
    // overlay target, so pull focus back to the root Box. Its onPreviewKeyEvent then
    // receives any D-pad press and re-shows the transport.
    LaunchedEffect(controlsVisible, anyPanelOpen, showUpNext) {
        if (!controlsVisible && !anyPanelOpen && !showUpNext) {
            rootFocus.requestFocus()
        }
    }

    BackHandler {
        when {
            episodesPanelOpen -> episodesPanelOpen = false
            subsPanelOpen -> subsPanelOpen = false
            panelOpen -> panelOpen = false
            // Back on the Up-next card just hides it (keep watching the tail of the episode).
            showUpNext -> upNextDismissed = true
            // While buffering/fetching or errored there's no transport to dismiss — leave at once.
            uiState !is PlayerUiState.Playing -> onBack()
            controlsVisible -> controlsVisible = false
            else -> onBack()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val p = player
                // Honour the remote's dedicated media keys (play/pause, FF, RW, stop) — with our
                // custom transport + useController=false, nothing mapped them before, so only
                // D-pad/Enter worked.
                when (event.key) {
                    Key.MediaPlay -> { p?.let { it.playWhenReady = true }; controlsVisible = true; true }
                    Key.MediaPause -> { p?.let { it.playWhenReady = false }; controlsVisible = true; true }
                    Key.MediaPlayPause -> { p?.let { it.playWhenReady = !it.isPlaying }; controlsVisible = true; true }
                    Key.MediaFastForward -> { p?.let { it.seekTo(it.currentPosition + 10_000) }; controlsVisible = true; true }
                    Key.MediaRewind -> { p?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) }; controlsVisible = true; true }
                    Key.MediaStop -> { onBack(); true }
                    Key.Back -> false   // never swallow Back, or buffering/error states would trap the user
                    else -> {
                        // Any other D-pad press just re-shows the transport (while playing) — UNLESS the
                        // Up-next card is up, in which case the press belongs to the card's buttons.
                        if (!controlsVisible && !anyPanelOpen && !showUpNext && uiState is PlayerUiState.Playing) {
                            controlsVisible = true
                            true
                        } else {
                            false
                        }
                    }
                }
            },
    ) {
        // The video surface — same ExoPlayer the VM created.
        if (player != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false // we draw our own 10-foot transport controls
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { view ->
                    view.player = player
                    view.subtitleView?.applyAppearance(captionPrefs.size, captionPrefs.style)
                    // Fit (letterbox) vs Zoom (crop to fill) — applies to ExoPlayer and the VLC
                    // fallback alike, both render into this PlayerView surface.
                    view.resizeMode =
                        if (zoomFill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        else AspectRatioFrameLayout.RESIZE_MODE_FIT
                    // libVLC renders into PlayerView's surface but never signals Media3's first-frame,
                    // so make the black shutter transparent for the VLC fallback (else it hides video).
                    view.setShutterBackgroundColor(
                        if (player is VlcPlayer) android.graphics.Color.TRANSPARENT
                        else android.graphics.Color.BLACK,
                    )
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Buffering / error overlay (driven by the VM's PlayerUiState).
        when (val s = uiState) {
            is PlayerUiState.Buffering -> BufferingOverlay(
                state = s,
                title = title,
                onBack = onBack,
                backdropUrl = backdropUrl,
                canSwitchSource = sources.size > 1,
                onSwitchSource = { panelOpen = true },
                // Gentle "taking a while? try a smaller stream" nudge — only after a long, starved buffer.
                suggestSmaller = suggestSmaller,
                onSwitchToSmaller = { viewModel.switchToSmaller() },
                onDismissSuggestSmaller = { viewModel.dismissSuggestSmaller() },
            )
            is PlayerUiState.Error -> ErrorOverlay(
                message = s.message,
                onRetry = viewModel::retry,
                onBack = onBack,
                canSwitchSource = sources.size > 1,
                onSwitchSource = { panelOpen = true },
            )
            PlayerUiState.Playing -> Unit
        }

        // Mid-playback stall: small "buffering ~Xs" badge over the (still-Playing) frozen frame, shown
        // independently of the transport so it's visible even when the controls are hidden.
        rebuffering?.let { rebuf ->
            if (uiState is PlayerUiState.Playing) {
                TvRebufferBadge(
                    state = rebuf,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 40.dp),
                )
            }
        }

        // Any D-pad press while playing should surface the controls.
        // (We rely on the focusable transport buttons; tapping center re-shows them.)
        if (uiState is PlayerUiState.Playing) {
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                // Whenever the overlay (re)appears, move focus onto the play/pause button so
                // it is immediately D-pad controllable.
                // Don't yank focus back to play/pause while a side-panel (sources/subtitles) is
                // open — that's what kept the CC menu un-navigable.
                LaunchedEffect(controlsVisible, anyPanelOpen) {
                    if (controlsVisible && !anyPanelOpen) runCatching { scrubFocus.requestFocus() }
                }
                TransportOverlay(
                    title = title,
                    isPlaying = isPlaying,
                    currentSource = currentSource,
                    player = player,
                    scrubFocus = scrubFocus,
                    onScrubbingChange = { scrubbing = it },
                    playPauseFocus = playPauseFocus,
                    onPlayPause = {
                        player?.let { it.playWhenReady = !it.isPlaying }
                        controlsVisible = true
                    },
                    onSeekBack = {
                        player?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) }
                        controlsVisible = true
                    },
                    onSeekForward = {
                        player?.let { it.seekTo(it.currentPosition + 10_000) }
                        controlsVisible = true
                    },
                    onOpenSources = { panelOpen = true; controlsVisible = true },
                    zoomFill = zoomFill,
                    onToggleZoom = { zoomFill = !zoomFill; controlsVisible = true },
                    thumbnailAt = viewModel::thumbnailAt,
                    thumbnailVersion = thumbnailVersion,
                    subtitlesActive = currentSubtitle != null,
                    onOpenSubtitles = {
                        subsPanelOpen = true
                        controlsVisible = true
                        if (subtitles.isEmpty()) viewModel.refreshSubtitles()
                    },
                    // Episode navigation — only present for TV shows (episode list resolved).
                    hasEpisodes = episodes.isNotEmpty(),
                    hasPrevious = hasPrevious,
                    hasNext = hasNext,
                    onPrevious = { viewModel.previousEpisode(); controlsVisible = true },
                    onNext = { viewModel.nextEpisode(); controlsVisible = true },
                    onOpenEpisodes = { episodesPanelOpen = true; controlsVisible = true },
                )
            }
        }

        // Torrent chunk bar pinned to the very bottom edge, shown with the transport. Lets the viewer
        // watch the file fill in (downloaded slices vs the playhead). Polled ~1/s.
        if (uiState is PlayerUiState.Playing) {
            var pieceMap by remember { mutableStateOf(FloatArray(0)) }
            var playheadFrac by remember { mutableStateOf(0f) }
            var stats by remember { mutableStateOf<com.slickstream.feature.player.StreamStats?>(null) }
            LaunchedEffect(player) {
                while (true) {
                    pieceMap = viewModel.pieceMap(com.slickstream.feature.player.PIECE_BAR_BUCKETS)
                    stats = viewModel.streamStats()
                    val p = player
                    playheadFrac = if (p != null && p.duration > 0)
                        (p.currentPosition.toFloat() / p.duration).coerceIn(0f, 1f) else 0f
                    kotlinx.coroutines.delay(1000)
                }
            }
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                com.slickstream.feature.player.PieceBarPanel(
                    map = pieceMap,
                    playheadFraction = playheadFrac,
                    stats = stats,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }

        // "Up next" card — slides in near the end of an episode with the next one's art + title.
        AnimatedVisibility(
            visible = showUpNext,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 32.dp, bottom = 32.dp),
        ) {
            upNext?.let { ep ->
                TvUpNextCard(
                    episode = ep,
                    playFocus = upNextFocus,
                    onPlay = { viewModel.nextEpisode() },
                    onDismiss = { upNextDismissed = true },
                )
            }
        }

        // Focusable sources side-panel for quality switching.
        AnimatedVisibility(
            visible = panelOpen,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            SourcesPanel(
                sources = sources,
                current = currentSource,
                onSelect = { src ->
                    viewModel.selectSource(src)
                    panelOpen = false
                },
                onClose = { panelOpen = false },
            )
        }

        // Focusable subtitles side-panel (search + pick a track, or turn off).
        AnimatedVisibility(
            visible = subsPanelOpen,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            SubtitlesPanel(
                subtitles = subtitles,
                current = currentSubtitle,
                currentSize = captionPrefs.size,
                onSizeChange = { viewModel.setSubtitleSize(it) },
                onSelect = { track ->
                    viewModel.selectSubtitle(track)
                    subsPanelOpen = false
                },
                onSearch = { viewModel.refreshSubtitles() },
                onClose = { subsPanelOpen = false },
            )
        }

        // Focusable episodes side-panel (TV shows): pick any episode to play it.
        AnimatedVisibility(
            visible = episodesPanelOpen,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            EpisodesPanel(
                episodes = episodes,
                currentSeason = currentSeasonNumber,
                currentEpisode = currentEpisodeNumber,
                onSelect = { ep ->
                    viewModel.playEpisode(ep.seasonNumber, ep.episodeNumber)
                    episodesPanelOpen = false
                },
                onClose = { episodesPanelOpen = false },
            )
        }
    }
}

@Composable
private fun BufferingOverlay(
    state: PlayerUiState.Buffering,
    title: String,
    onBack: () -> Unit,
    backdropUrl: String? = null,
    canSwitchSource: Boolean = false,
    onSwitchSource: () -> Unit = {},
    suggestSmaller: Boolean = false,
    onSwitchToSmaller: () -> Unit = {},
    onDismissSuggestSmaller: () -> Unit = {},
) {
    val backFocus = remember { FocusRequester() }
    val smallerFocus = remember { FocusRequester() }
    // Land focus on the smaller-stream action the moment it appears so it's D-pad operable; otherwise
    // fall back to the Back button.
    LaunchedEffect(suggestSmaller) {
        val target = if (suggestSmaller) smallerFocus else backFocus
        repeat(12) {
            kotlinx.coroutines.delay(40)
            if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        // Title art behind the scrim — the "rollercoaster line-up": the wait looks like the movie
        // loading, not a black screen. Dissolves into the real first frame once playback starts.
        if (backdropUrl != null) {
            AsyncImage(
                model = backdropUrl,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xCC000000)),
            contentAlignment = Alignment.Center,
        ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                color = Brand.Violet,
                trackColor = Brand.SurfaceVariant,
                strokeWidth = 4.dp,
                modifier = Modifier.size(56.dp),
            )
            Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(state.label, style = MaterialTheme.typography.bodyLarge, color = Brand.OnSurfaceDim)
            // Cyan line is the live countdown ONLY. Below ~5s the tail/prepare steps dominate, so we
            // drop the number and let the label ("Almost ready…") carry it — no duplicated text.
            state.etaSeconds?.takeIf { it > 5 }?.let { eta ->
                Text(
                    text = "Starts in ~${eta}s",
                    style = MaterialTheme.typography.titleMedium,
                    color = Brand.Cyan,
                    fontWeight = FontWeight.Bold,
                )
            }
            val detail = buildString {
                if (state.percent > 0) append("${state.percent}%")
                if (state.seeders > 0) {
                    if (isNotEmpty()) append("  ·  ")
                    append("${state.seeders} seeders")
                }
                if (state.downloadRateBytes > 0) {
                    if (isNotEmpty()) append("  ·  ")
                    append("${state.downloadRateBytes / 1024} KB/s")
                }
            }
            if (detail.isNotBlank()) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = Brand.OnSurfaceDim)
            }

            // Gentle, dismissible "taking a while?" card — only once the VM decides a smaller,
            // well-seeded source would genuinely help. Non-blocking; the buffer keeps filling behind it.
            AnimatedVisibility(visible = suggestSmaller, enter = fadeIn(), exit = fadeOut()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Brand.Surface.copy(alpha = 0.92f))
                        .padding(horizontal = 28.dp, vertical = 20.dp),
                ) {
                    Text(
                        "Taking a while? Try a smaller stream",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        ErrorButton("Smaller stream", onClick = onSwitchToSmaller, focusRequester = smallerFocus)
                        ErrorButton("Dismiss", onClick = onDismissSuggestSmaller)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            // Focusable actions so the user is never stranded on the fetch/buffer screen — and can
            // switch to a healthier torrent without waiting for playback to start (parity with phone).
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ErrorButton("Back", onClick = onBack, focusRequester = backFocus)
                if (canSwitchSource) {
                    ErrorButton("Switch source", onClick = onSwitchSource)
                }
            }
        }
        }
    }
}

@Composable
private fun ErrorOverlay(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    canSwitchSource: Boolean = false,
    onSwitchSource: () -> Unit = {},
) {
    val retryFocus = remember { FocusRequester() }
    // Retry past layout so focus reliably lands on "Try again" — a single requestFocus() fired
    // before the buttons existed, which is why nothing was highlighted.
    LaunchedEffect(Unit) {
        repeat(12) {
            kotlinx.coroutines.delay(40)
            if (runCatching { retryFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xE6000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.width(620.dp),
        ) {
            Text("Playback failed", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text(message, style = MaterialTheme.typography.bodyLarge, color = Brand.OnSurfaceDim)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ErrorButton("Try again", onClick = onRetry, focusRequester = retryFocus)
                if (canSwitchSource) ErrorButton("Other streams", onClick = onSwitchSource)
                ErrorButton("Back", onClick = onBack)
            }
        }
    }
}

/** Fraction of the runtime after which the "Up next" card appears (the user's "~90% played"). */
private const val UP_NEXT_PCT = 0.90f

/**
 * "Up next" card: the next episode's still + title with a focusable "Play now" / "Dismiss". Shown near
 * the end of a TV episode; "Play now" jumps straight to the next episode (the natural end-of-file
 * auto-advance still fires if the user just lets it ride).
 */
@Composable
private fun TvUpNextCard(
    episode: Episode,
    playFocus: FocusRequester,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.88f))
            .width(440.dp)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!episode.stillUrl.isNullOrBlank()) {
            AsyncImage(
                model = episode.stillUrl,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(150.dp, 84.dp).clip(RoundedCornerShape(8.dp)),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Up next",
                style = MaterialTheme.typography.labelMedium,
                color = Brand.Cyan,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "S${episode.seasonNumber}E${episode.episodeNumber} · ${episode.name}",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ErrorButton("Play now", onClick = onPlay, focusRequester = playFocus)
                ErrorButton("Dismiss", onClick = onDismiss)
            }
        }
    }
}

/** Error/overlay action button with an unmistakable focus highlight (violet fill + white ring). */
@Composable
private fun ErrorButton(
    label: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Brand.Surface,
            focusedContainerColor = Brand.Violet,
            contentColor = Brand.OnSurface,
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(androidx.compose.foundation.BorderStroke(3.dp, Color.White), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.06f),
        modifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
    }
}

@Composable
private fun TransportOverlay(
    title: String,
    isPlaying: Boolean,
    currentSource: StreamSource?,
    player: androidx.media3.common.Player?,
    scrubFocus: FocusRequester,
    onScrubbingChange: (Boolean) -> Unit,
    playPauseFocus: FocusRequester,
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onOpenSources: () -> Unit,
    zoomFill: Boolean,
    onToggleZoom: () -> Unit,
    thumbnailAt: (Long) -> android.graphics.Bitmap?,
    thumbnailVersion: Int,
    subtitlesActive: Boolean,
    onOpenSubtitles: () -> Unit,
    hasEpisodes: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenEpisodes: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color(0x66000000),
                    0.55f to Color.Transparent,
                    1f to Color(0xCC000000),
                ),
            ),
    ) {
        // Title + active quality, top-left.
        Column(modifier = Modifier.align(Alignment.TopStart).padding(48.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            currentSource?.let {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    QualityChip(text = it.quality)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = it.provider,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Brand.OnSurfaceDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Scrub bar + transport row, bottom-centre.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 48.dp, end = 48.dp, bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ScrubBar(
                player = player,
                focusRequester = scrubFocus,
                downFocus = playPauseFocus,
                onScrubbingChange = onScrubbingChange,
                onTogglePlayPause = onPlayPause,
                thumbnailAt = thumbnailAt,
                thumbnailVersion = thumbnailVersion,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Previous episode (TV only) — disabled at the very first episode.
                if (hasEpisodes) {
                    TransportButton(
                        Icons.Rounded.SkipPrevious,
                        "Previous episode",
                        onPrevious,
                        enabled = hasPrevious,
                    )
                }
                TransportButton(Icons.Rounded.Replay10, "Rewind 10 seconds", onSeekBack)
                TransportButton(
                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (isPlaying) "Pause" else "Play",
                    onPlayPause,
                    large = true,
                    modifier = Modifier.focusRequester(playPauseFocus),
                )
                TransportButton(Icons.Rounded.Forward10, "Forward 10 seconds", onSeekForward)
                // Next episode (TV only) — disabled at the season/series finale.
                if (hasEpisodes) {
                    TransportButton(
                        Icons.Rounded.SkipNext,
                        "Next episode",
                        onNext,
                        enabled = hasNext,
                    )
                }
                Spacer(Modifier.width(24.dp))
                if (hasEpisodes) {
                    TransportButton(Icons.Rounded.Subscriptions, "Episodes", onOpenEpisodes)
                }
                TransportButton(
                    Icons.Rounded.ClosedCaption,
                    "Subtitles",
                    onOpenSubtitles,
                    tint = if (subtitlesActive) Brand.Cyan else Color.White,
                )
                TransportButton(
                    Icons.Rounded.AspectRatio,
                    if (zoomFill) "Fit to screen" else "Fill screen",
                    onToggleZoom,
                    tint = if (zoomFill) Brand.Cyan else Color.White,
                )
                TransportButton(Icons.Rounded.Tune, "Sources & quality", onOpenSources)
            }
        }
    }
}

/**
 * Focusable D-pad scrub bar with a Netflix-style hold-to-accelerate transport. Press & HOLD ◀/▶: for
 * the first ~500 ms nothing moves (the film-strip + target time appear), then it scrubs at 1×,
 * DOUBLING every 500 ms held (1× 2× 4× 8× …). RELEASE and it KEEPS gliding at that speed (coasting);
 * the next key — OK, the other direction, anything — commits the seek and resumes playback. The live
 * speed shows as ▶ ▶▶ ▶▶▶ … A wide film-strip (~6 frames edge-to-edge) previews around the target.
 * DOWN moves focus to the transport buttons.
 */
@Composable
private fun ScrubBar(
    player: androidx.media3.common.Player?,
    focusRequester: FocusRequester,
    downFocus: FocusRequester,
    onScrubbingChange: (Boolean) -> Unit,
    onTogglePlayPause: () -> Unit,
    thumbnailAt: (Long) -> android.graphics.Bitmap?,
    thumbnailVersion: Int,
    modifier: Modifier = Modifier,
) {
    var scrubbing by remember { mutableStateOf(false) }
    var scrubTargetMs by remember { mutableStateOf(0L) }
    var dir by remember { mutableStateOf(0) }            // +1 forward, -1 back, 0 idle
    var held by remember { mutableStateOf(false) }       // the direction key is currently held down
    var holdStartMs by remember { mutableStateOf(0L) }   // uptime when the current hold began
    var speedLevel by remember { mutableStateOf(-1) }    // -1 = grace (no move yet); else 2^level ×
    var positionMs by remember { mutableStateOf(0L) }
    var bufferedMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }

    // Live position poll while NOT scrubbing.
    LaunchedEffect(player) {
        while (true) {
            val p = player
            if (p != null && !scrubbing) {
                val pos = p.currentPosition.coerceAtLeast(0L)
                val buf = p.bufferedPosition.coerceAtLeast(0L)
                val dur = p.duration.takeIf { it > 0 } ?: 0L
                if (pos != positionMs) positionMs = pos
                if (buf != bufferedMs) bufferedMs = buf
                if (dur != durationMs) durationMs = dur
            }
            kotlinx.coroutines.delay(500)
        }
    }

    // Drive loop: while scrubbing, ramp the speed (grace → 1× → doubling, only while held) and glide
    // the target by dir × 2^level video-ms per real-ms each tick. On release the level just stops
    // changing, so it coasts at whatever speed it reached until a key commits.
    LaunchedEffect(scrubbing) {
        if (!scrubbing) return@LaunchedEffect
        var last = android.os.SystemClock.elapsedRealtime()
        while (true) {
            kotlinx.coroutines.delay(SCRUB_TICK_MS)
            val now = android.os.SystemClock.elapsedRealtime()
            val dt = now - last
            last = now
            if (held) {
                val heldFor = now - holdStartMs
                speedLevel = if (heldFor < SCRUB_GRACE_MS) -1
                else ((heldFor - SCRUB_GRACE_MS) / SCRUB_DOUBLE_MS).toInt().coerceIn(0, SCRUB_MAX_LEVEL)
            }
            val durMax = durationMs.takeIf { it > 0 } ?: continue
            if (speedLevel >= 0 && dir != 0) {
                scrubTargetMs = (scrubTargetMs + dir.toLong() * (1L shl speedLevel) * dt).coerceIn(0L, durMax)
            }
        }
    }

    fun startScrub(d: Int) {
        scrubbing = true
        onScrubbingChange(true)
        scrubTargetMs = positionMs
        dir = d
        held = true
        holdStartMs = android.os.SystemClock.elapsedRealtime()
        speedLevel = -1
    }
    fun commit() {
        player?.seekTo(scrubTargetMs)
        scrubbing = false; held = false; dir = 0; speedLevel = -1
        onScrubbingChange(false)
    }

    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (scrubbing) {
            @Suppress("UNUSED_EXPRESSION") thumbnailVersion
            TvFilmstrip(centerMs = scrubTargetMs, durationMs = durationMs, thumbnailAt = thumbnailAt)
            Row(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (speedLevel >= 0) {
                    val chevrons = (if (dir < 0) "◀" else "▶").repeat((speedLevel + 1).coerceAtMost(6))
                    Text(chevrons, color = Brand.Cyan, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("${1 shl speedLevel}×", color = Brand.Cyan, style = MaterialTheme.typography.labelLarge)
                }
                Text(
                    text = fmtTime(scrubTargetMs),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (focused || scrubbing) 12.dp else 6.dp)
                .clip(RoundedCornerShape(50))
                .background(Brand.SurfaceVariant)
                .focusRequester(focusRequester)
                // DOWN from the scrub bar must always land on the centre Play/Pause button, not whatever
                // happens to be geometrically nearest (it was jumping to the right-most transport button).
                .focusProperties { down = downFocus }
                .focusable(interactionSource = interaction)
                .onKeyEvent { event ->
                    val isLeft = event.key == Key.DirectionLeft
                    val isRight = event.key == Key.DirectionRight
                    val isCenter = event.key == Key.DirectionCenter || event.key == Key.Enter
                    when {
                        (isLeft || isRight) && event.type == KeyEventType.KeyDown -> {
                            val d = if (isRight) 1 else -1
                            when {
                                !scrubbing -> startScrub(d)
                                held && dir == d -> Unit  // auto-repeat of the held key — drive loop owns timing
                                else -> commit()           // a fresh press while coasting (any dir) -> stop + seek
                            }
                            true
                        }
                        (isLeft || isRight) && event.type == KeyEventType.KeyUp -> {
                            // Release of the active direction -> coast at the speed reached.
                            if (scrubbing && dir == (if (isRight) 1 else -1)) held = false
                            true
                        }
                        isCenter && event.type == KeyEventType.KeyDown -> {
                            if (scrubbing) commit() else onTogglePlayPause()
                            true
                        }
                        // Any other key while scrubbing (Up/Down/Back/…) stops the coast and commits.
                        event.type == KeyEventType.KeyDown && scrubbing -> {
                            commit()
                            true
                        }
                        else -> false
                    }
                },
        ) {
            val dur = durationMs.takeIf { it > 0 } ?: 1L
            val bufFrac = (bufferedMs.toFloat() / dur).coerceIn(0f, 1f)
            val progFrac = ((if (scrubbing) scrubTargetMs else positionMs).toFloat() / dur).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth(bufFrac).fillMaxHeight().background(Color.White.copy(alpha = 0.28f)))
            Box(Modifier.fillMaxWidth(progFrac).fillMaxHeight().clip(RoundedCornerShape(50)).background(Brand.Violet))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(fmtTime(if (scrubbing) scrubTargetMs else positionMs), style = MaterialTheme.typography.labelMedium, color = Color.White)
            Spacer(Modifier.weight(1f))
            Text(fmtTime(durationMs), style = MaterialTheme.typography.labelMedium, color = Brand.OnSurfaceDim)
        }
    }
}

private fun fmtTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

// Scrub transport tuning (hold-to-accelerate).
private const val SCRUB_TICK_MS = 33L      // drive-loop cadence (~30 fps)
private const val SCRUB_GRACE_MS = 500L    // hold this long before any movement (just shows the strip)
private const val SCRUB_DOUBLE_MS = 500L   // every further 500 ms held, double the speed
private const val SCRUB_MAX_LEVEL = 7      // cap at 2^7 = 128×

// Film-strip tuning: how many frames fit across the full width, how far to draw beyond the edges (so
// the ribbon stays filled as it glides), the minute spacing of the frame grid, and the side-frame
// scale (subtle, so all ~6 read as a row rather than one big frame + tiny neighbours).
private const val TV_FILM_VISIBLE = 6
private const val TV_FILM_SPAN = 4
private const val TV_FILMSTRIP_STEP_MS = 60_000L
private const val TV_FILM_SIDE_SCALE = 0.82f
private val TV_FILM_STRIP_H = 168.dp

/**
 * Wide film-strip: ~[TV_FILM_VISIBLE] frames edge-to-edge around [centerMs] on a whole-minute grid,
 * the centre frame full-size + cyan border, neighbours slightly smaller and fading toward the edges.
 * The ribbon glides as the target moves (eased animation on the centre). Off-timeline slots dropped.
 */
@Composable
private fun TvFilmstrip(
    centerMs: Long,
    durationMs: Long,
    thumbnailAt: (Long) -> android.graphics.Bitmap?,
) {
    val dur = durationMs.takeIf { it > 0 } ?: 0L
    val animatedCenter by animateFloatAsState(
        targetValue = centerMs.toFloat(),
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "filmstrip-slide",
    )
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().height(TV_FILM_STRIP_H).clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        val pitchPx = with(LocalDensity.current) { maxWidth.toPx() } / TV_FILM_VISIBLE
        val frameW = with(LocalDensity.current) { (pitchPx * 0.94f).toDp() }
        val frameH = frameW * 9f / 16f
        val step = TV_FILMSTRIP_STEP_MS
        val centerK = Math.round(animatedCenter / step).toInt()
        // Draw far → near so the swollen centre frame lands on top of its shrunk neighbours.
        for (k in (centerK - TV_FILM_SPAN..centerK + TV_FILM_SPAN)
                .sortedByDescending { kotlin.math.abs(it.toLong() * step - animatedCenter.toLong()) }) {
            val posMs = k.toLong() * step
            if (dur > 0L && posMs !in 0L..dur) continue
            val distSteps = (posMs - animatedCenter) / step
            val near = (1f - kotlin.math.abs(distSteps)).coerceIn(0f, 1f)
            val scale = TV_FILM_SIDE_SCALE + (1f - TV_FILM_SIDE_SCALE) * near
            val edgeFade = (1f - kotlin.math.abs(distSteps) / (TV_FILM_SPAN + 1)).coerceIn(0.35f, 1f)
            TvFilmstripFrame(
                thumbnail = thumbnailAt(posMs.coerceAtLeast(0L)),
                center = kotlin.math.abs(distSteps) < 0.5f,
                width = frameW,
                height = frameH,
                modifier = Modifier.graphicsLayer {
                    translationX = distSteps * pitchPx
                    scaleX = scale
                    scaleY = scale
                    alpha = edgeFade
                },
            )
        }
    }
}

@Composable
private fun TvFilmstripFrame(
    thumbnail: android.graphics.Bitmap?,
    center: Boolean,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    val borderColor = if (center) Brand.Cyan else Color.White.copy(alpha = 0.40f)
    Box(
        modifier = modifier
            .size(width, height)
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.55f))
            .border(if (center) 3.dp else 1.dp, borderColor, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(shape),
            )
        }
    }
}

/**
 * 10-foot version of the mid-playback re-buffer badge. Same idea as the phone's: a short reveal
 * delay so a quick seek doesn't flash it, a live ETA when estimable (else a climbing elapsed
 * counter) and the current rate — so a stall never reads as a permanent freeze.
 */
@Composable
private fun TvRebufferBadge(state: com.slickstream.feature.player.RebufferState, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(450); visible = true }
    var elapsed by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1_000); elapsed++ } }
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(40.dp))
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 26.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                color = Brand.Cyan,
                strokeWidth = 3.dp,
                modifier = Modifier.size(30.dp),
            )
            Spacer(Modifier.width(18.dp))
            Column {
                Text(
                    text = "Buffering…",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = buildString {
                        append(state.etaSeconds?.let { "about ${it}s left" } ?: "waiting ${elapsed}s")
                        if (state.downloadRateBytes > 0) {
                            append("  ·  ")
                            val kb = state.downloadRateBytes / 1024
                            append(if (kb >= 1024) "%.1f MB/s".format(kb / 1024f) else "$kb KB/s")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Brand.Cyan,
                )
            }
        }
    }
}

@Composable
private fun TransportButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    large: Boolean = false,
    tint: Color = Color.White,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val dim = if (large) 84.dp else 64.dp
    val shape = RoundedCornerShape(percent = 50)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0x66151520),
            focusedContainerColor = Brand.Violet,
            pressedContainerColor = Brand.VioletDim,
            disabledContainerColor = Color(0x33151520),
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(3.dp, Color.White),
                shape = shape,
            ),
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.12f),
        modifier = modifier.size(dim),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) tint else tint.copy(alpha = 0.35f),
                modifier = Modifier.size(if (large) 44.dp else 32.dp),
            )
        }
    }
}

@Composable
private fun SourcesPanel(
    sources: List<StreamSource>,
    current: StreamSource?,
    onSelect: (StreamSource) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(enabled = true) { onClose() }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(420.dp)
            .background(Color(0xF2101019))
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Sources",
            style = MaterialTheme.typography.titleLarge,
            color = Brand.OnSurface,
        )
        Text(
            text = "Pick a quality. Higher quality needs more seeders.",
            style = MaterialTheme.typography.bodyMedium,
            color = Brand.OnSurfaceDim,
        )
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
        ) {
            itemsIndexed(sources, key = { _, s -> s.infoHash + (s.fileIndex ?: 0) }) { index, source ->
                SourceRow(
                    source = source,
                    selected = source.infoHash == current?.infoHash,
                    onClick = { onSelect(source) },
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
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
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Brand.SurfaceVariant else Brand.Surface,
            focusedContainerColor = Brand.Violet,
            contentColor = Brand.OnSurface,
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(3.dp, Brand.Violet),
                shape = shape,
            ),
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.03f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                QualityChip(text = source.quality)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = source.provider,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = source.title,
                style = MaterialTheme.typography.bodyMedium,
                color = Brand.OnSurfaceDim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = buildString {
                source.seeders?.let { append("$it seeders") }
                source.sizeBytes?.let {
                    if (isNotEmpty()) append("  ·  ")
                    append(formatBytes(it))
                }
            }
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.labelMedium, color = Brand.Cyan)
            }
        }
    }
}

@Composable
private fun SubtitlesPanel(
    subtitles: List<SubtitleTrack>,
    current: SubtitleTrack?,
    currentSize: SubtitleSize,
    onSizeChange: (SubtitleSize) -> Unit,
    onSelect: (SubtitleTrack?) -> Unit,
    onSearch: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(enabled = true) { onClose() }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(420.dp)
            .background(Color(0xF2101019))
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Subtitles",
            style = MaterialTheme.typography.titleLarge,
            color = Brand.OnSurface,
        )
        Text(
            text = "Pick a language, or turn subtitles off.",
            style = MaterialTheme.typography.bodyMedium,
            color = Brand.OnSurfaceDim,
        )
        // Text size — persisted system-wide, applied live. D-pad left/right between sizes.
        Text("Text size", style = MaterialTheme.typography.labelLarge, color = Brand.OnSurfaceDim)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SubtitleSize.entries.forEach { size ->
                TvSizeChip(label = size.label, selected = size == currentSize) { onSizeChange(size) }
            }
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
        ) {
            item {
                TvSubtitleRow(
                    label = "Off",
                    selected = current == null,
                    modifier = Modifier.focusRequester(firstFocus),
                ) { onSelect(null) }
            }
            item { TvSubtitleRow(label = "Search again", selected = false, accent = true) { onSearch() } }
            if (subtitles.isEmpty()) {
                item {
                    Text(
                        text = "No subtitles found yet. Tap \"Search again\" to look.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Brand.OnSurfaceDim,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
                items(subtitles, key = { it.id }) { track ->
                    TvSubtitleRow(
                        label = track.label,
                        selected = track.id == current?.id,
                    ) { onSelect(track) }
                }
            }
        }
    }
}

@Composable
private fun TvSizeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Brand.Violet else Brand.Surface,
            focusedContainerColor = Brand.Violet,
            contentColor = if (selected) Color.White else Brand.OnSurface,
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(androidx.compose.foundation.BorderStroke(3.dp, Color.White), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.06f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun TvSubtitleRow(
    label: String,
    selected: Boolean,
    accent: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Brand.SurfaceVariant else Brand.Surface,
            focusedContainerColor = Brand.Violet,
            contentColor = if (accent) Brand.Cyan else Brand.OnSurface,
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(3.dp, Brand.Violet),
                shape = shape,
            ),
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.03f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (selected) {
                Spacer(Modifier.width(10.dp))
                Text("●", color = Brand.Cyan, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun EpisodesPanel(
    episodes: List<Episode>,
    currentSeason: Int?,
    currentEpisode: Int?,
    onSelect: (Episode) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(enabled = true) { onClose() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val currentFocus = remember { FocusRequester() }
    // Land focus on the currently-playing episode (and scroll it into view) when the panel opens.
    val currentIndex = remember(episodes, currentSeason, currentEpisode) {
        episodes.indexOfFirst {
            it.seasonNumber == currentSeason && it.episodeNumber == currentEpisode
        }.takeIf { it >= 0 } ?: 0
    }
    LaunchedEffect(Unit) {
        runCatching { listState.scrollToItem(currentIndex) }
        runCatching { currentFocus.requestFocus() }
    }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(460.dp)
            .background(Color(0xF2101019))
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Episodes",
            style = MaterialTheme.typography.titleLarge,
            color = Brand.OnSurface,
        )
        Text(
            text = currentSeason?.let { "Season $it" } ?: "Pick an episode to play.",
            style = MaterialTheme.typography.bodyMedium,
            color = Brand.OnSurfaceDim,
        )
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
        ) {
            itemsIndexed(
                episodes,
                key = { _, ep -> "${ep.seasonNumber}-${ep.episodeNumber}" },
            ) { index, episode ->
                EpisodeRow(
                    episode = episode,
                    selected = episode.seasonNumber == currentSeason &&
                        episode.episodeNumber == currentEpisode,
                    onClick = { onSelect(episode) },
                    modifier = if (index == currentIndex) Modifier.focusRequester(currentFocus) else Modifier,
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
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Brand.SurfaceVariant else Brand.Surface,
            focusedContainerColor = Brand.Violet,
            contentColor = Brand.OnSurface,
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(3.dp, Brand.Violet),
                shape = shape,
            ),
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.03f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "S${episode.seasonNumber}E${episode.episodeNumber}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) Brand.Cyan else Brand.OnSurfaceDim,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = episode.name,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selected) {
                    Spacer(Modifier.width(8.dp))
                    Text("●", color = Brand.Cyan, style = MaterialTheme.typography.labelLarge)
                }
            }
            if (episode.overview.isNotBlank()) {
                Text(
                    text = episode.overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Brand.OnSurfaceDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    if (gb >= 1.0) return "%.1f GB".format(gb)
    val mb = bytes / 1_000_000.0
    return "%.0f MB".format(mb)
}
