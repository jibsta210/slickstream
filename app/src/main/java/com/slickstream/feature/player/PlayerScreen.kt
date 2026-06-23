@file:kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.slickstream.feature.player

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.media3.ui.PlayerView
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
import com.slickstream.core.model.StreamSource
import com.slickstream.core.model.SubtitleTrack
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
    val sources by viewModel.sources.collectAsState()
    val currentSource by viewModel.currentSource.collectAsState()
    val title by viewModel.title.collectAsState()
    val subtitles by viewModel.subtitles.collectAsState()
    val currentSubtitle by viewModel.currentSubtitle.collectAsState()
    val captionPrefs by viewModel.captionPrefs.collectAsState()
    val context = LocalContext.current

    var showSources by remember { mutableStateOf(false) }
    var showSubtitles by remember { mutableStateOf(false) }
    var overlayVisible by remember { mutableStateOf(true) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val subtitleSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

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
            .background(Color.Black)
            .clickable { overlayVisible = !overlayVisible },
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
                        keepScreenOn = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        // A non-null listener makes the built-in fullscreen button visible.
                        setFullscreenButtonClickListener { enter -> isFullscreen = enter }
                        this.player = activePlayer
                        playerViewRef = this
                    }
                },
                update = { view ->
                    view.player = activePlayer
                    view.useController = !isInPip   // hide all controls inside the PiP window
                    view.setFullscreenButtonState(isFullscreen)
                    view.subtitleView?.applyAppearance(captionPrefs.size, captionPrefs.style)
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

        // Auto-hide the custom chrome while actively playing (re-keys on every tap/show to restart
        // the countdown; never hides during buffering/error, in PiP, or while casting).
        LaunchedEffect(overlayVisible, uiState, isInPip, isCasting) {
            if (overlayVisible && uiState is PlayerUiState.Playing && !isInPip && !isCasting) {
                delay(AUTO_HIDE_MS)
                overlayVisible = false
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
                // Only show our buffering bar before the player exists (torrent head-download).
                is PlayerUiState.Buffering -> if (player == null) BufferingOverlay(state)
                is PlayerUiState.Error -> ErrorOverlay(
                    message = state.message,
                    onRetry = viewModel::retry,
                )
                PlayerUiState.Playing -> Unit
            }

            if (isCasting) CastingOverlay(
                controlsVisible = overlayVisible,
                onPlayOnDevice = viewModel::stopCasting,
            )
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
}

@Composable
private fun TopOverlay(
    title: String,
    onBack: () -> Unit,
    onSources: () -> Unit,
    showSourcesButton: Boolean,
    onSubtitles: () -> Unit,
    subtitlesActive: Boolean,
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
        IconButton(onClick = onSubtitles) {
            Icon(
                imageVector = Icons.Filled.ClosedCaption,
                contentDescription = "Subtitles",
                tint = if (subtitlesActive) Brand.Cyan else Brand.OnSurface,
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
private fun BufferingOverlay(state: PlayerUiState.Buffering) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.7f)),
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

@Composable
private fun ErrorOverlay(message: String, onRetry: () -> Unit) {
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
private fun SubtitlesSheetContent(
    subtitles: List<SubtitleTrack>,
    current: SubtitleTrack?,
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
        LazyColumn {
            items(sources, key = { it.infoHash + (it.fileIndex ?: 0) }) { source ->
                SourceRow(
                    source = source,
                    selected = source.infoHash == current?.infoHash,
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
            .background(if (selected) Brand.Violet.copy(alpha = 0.12f) else Color.Transparent)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QualityChip(quality = source.quality, highlighted = selected)
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
