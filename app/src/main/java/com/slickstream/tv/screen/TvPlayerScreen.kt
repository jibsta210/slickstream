package com.slickstream.tv.screen

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.slickstream.core.model.StreamSource
import com.slickstream.core.model.SubtitleTrack
import com.slickstream.feature.player.PlayerUiState
import com.slickstream.feature.player.PlayerViewModel
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

    var controlsVisible by remember { mutableStateOf(true) }
    var panelOpen by remember { mutableStateOf(false) }
    var subsPanelOpen by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }

    val rootFocus = remember { FocusRequester() }
    val playPauseFocus = remember { FocusRequester() }

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

    val anyPanelOpen = panelOpen || subsPanelOpen

    // Auto-hide the transport overlay after a few seconds of no interaction.
    LaunchedEffect(controlsVisible, anyPanelOpen) {
        if (controlsVisible && !anyPanelOpen) {
            kotlinx.coroutines.delay(5_000)
            controlsVisible = false
        }
    }

    // When the controls are hidden (and no panel is open) there is no focusable
    // overlay target, so pull focus back to the root Box. Its onPreviewKeyEvent then
    // receives any D-pad press and re-shows the transport.
    LaunchedEffect(controlsVisible, anyPanelOpen) {
        if (!controlsVisible && !anyPanelOpen) {
            rootFocus.requestFocus()
        }
    }

    BackHandler {
        when {
            subsPanelOpen -> subsPanelOpen = false
            panelOpen -> panelOpen = false
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
                if (event.type == KeyEventType.KeyDown && !controlsVisible && !anyPanelOpen) {
                    controlsVisible = true   // also restarts the auto-hide LaunchedEffect
                    true
                } else {
                    false
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
                update = { view -> view.player = player },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Buffering / error overlay (driven by the VM's PlayerUiState).
        when (val s = uiState) {
            is PlayerUiState.Buffering -> BufferingOverlay(state = s, title = title)
            is PlayerUiState.Error -> ErrorOverlay(message = s.message, onRetry = viewModel::retry, onBack = onBack)
            PlayerUiState.Playing -> Unit
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
                    if (controlsVisible && !anyPanelOpen) playPauseFocus.requestFocus()
                }
                TransportOverlay(
                    title = title,
                    isPlaying = isPlaying,
                    currentSource = currentSource,
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
                    subtitlesActive = currentSubtitle != null,
                    onOpenSubtitles = {
                        subsPanelOpen = true
                        controlsVisible = true
                        if (subtitles.isEmpty()) viewModel.refreshSubtitles()
                    },
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
                onSelect = { track ->
                    viewModel.selectSubtitle(track)
                    subsPanelOpen = false
                },
                onSearch = { viewModel.refreshSubtitles() },
                onClose = { subsPanelOpen = false },
            )
        }
    }
}

@Composable
private fun BufferingOverlay(state: PlayerUiState.Buffering, title: String) {
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
        }
    }
}

@Composable
private fun ErrorOverlay(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xE6000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.width(560.dp),
        ) {
            Text("Playback failed", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text(message, style = MaterialTheme.typography.bodyLarge, color = Brand.OnSurfaceDim)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                androidx.tv.material3.Button(onClick = onRetry) { Text("Try again") }
                androidx.tv.material3.Button(onClick = onBack) { Text("Back") }
            }
        }
    }
}

@Composable
private fun TransportOverlay(
    title: String,
    isPlaying: Boolean,
    currentSource: StreamSource?,
    playPauseFocus: FocusRequester,
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onOpenSources: () -> Unit,
    subtitlesActive: Boolean,
    onOpenSubtitles: () -> Unit,
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

        // Transport row, bottom-centre.
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 56.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportButton(Icons.Rounded.Replay10, "Rewind 10 seconds", onSeekBack)
            TransportButton(
                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (isPlaying) "Pause" else "Play",
                onPlayPause,
                large = true,
                modifier = Modifier.focusRequester(playPauseFocus),
            )
            TransportButton(Icons.Rounded.Forward10, "Forward 10 seconds", onSeekForward)
            Spacer(Modifier.width(24.dp))
            TransportButton(
                Icons.Rounded.ClosedCaption,
                "Subtitles",
                onOpenSubtitles,
                tint = if (subtitlesActive) Brand.Cyan else Color.White,
            )
            TransportButton(Icons.Rounded.Tune, "Sources & quality", onOpenSources)
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
    modifier: Modifier = Modifier,
) {
    val dim = if (large) 84.dp else 64.dp
    val shape = RoundedCornerShape(percent = 50)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0x66151520),
            focusedContainerColor = Brand.Violet,
            pressedContainerColor = Brand.VioletDim,
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
                tint = tint,
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

private fun formatBytes(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    if (gb >= 1.0) return "%.1f GB".format(gb)
    val mb = bytes / 1_000_000.0
    return "%.0f MB".format(mb)
}
