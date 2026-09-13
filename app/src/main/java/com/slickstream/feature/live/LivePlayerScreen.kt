package com.slickstream.feature.live

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.slickstream.ui.theme.Brand

/**
 * Full-screen HLS player for live sports. Chrome (back + "Switch stream") auto-hides after a few
 * seconds while playing and reappears on any D-pad press (TV) or tap (phone). The Switch-stream
 * panel lets the user change to another of the event's feeds WITHOUT leaving the player.
 */
@OptIn(UnstableApi::class)
@Composable
fun LivePlayerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LivePlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    val currentIndex by viewModel.currentIndex.collectAsStateWithLifecycle()
    val feeds = viewModel.feeds

    var controlsVisible by remember { mutableStateOf(true) }
    var panelOpen by remember { mutableStateOf(false) }
    // Recovering counts as "playing" for every CHROME decision. The last decoded frame is still on
    // screen and the retry engine is repairing the feed underneath it, so the chrome must behave
    // exactly as it did a second earlier — auto-hidden, switch-stream chip available, screen awake.
    // Treating a 2-second self-healing hiccup as "not playing" would pop the back arrow and the chip
    // back up every few minutes, which is the flicker the user was already annoyed by.
    val playing = state is LivePlayerViewModel.UiState.Playing ||
        state is LivePlayerViewModel.UiState.Recovering
    // The live PlayerView, so the resume-time video-surface repair can reach its SurfaceView.
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }

    // Keep the TV awake while a live stream is playing/loading (this is a SEPARATE player from the
    // torrent one, so it needs its own wake lock — the screensaver was kicking in during sports).
    com.slickstream.feature.player.KeepScreenOn(
        enabled = playing || state is LivePlayerViewModel.UiState.Buffering,
    )

    // Same resume-time video-surface repair as the VOD players: a feed left paused/idle long enough
    // for the screensaver came back as audio over a black picture.
    com.slickstream.feature.player.RebindVideoSurfaceOnResume(player, playerViewRef)

    val rootFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }

    BackHandler {
        when {
            panelOpen -> panelOpen = false
            playing && controlsVisible -> controlsVisible = false
            else -> onBack()
        }
    }

    // Auto-hide the chrome while actively playing.
    LaunchedEffect(controlsVisible, panelOpen, playing) {
        if (controlsVisible && !panelOpen && playing) {
            kotlinx.coroutines.delay(4_000)
            controlsVisible = false
        }
    }
    // Is a full-screen overlay (Error / NoStream) currently up? While one is, IT owns the cursor.
    val overlayUp = state is LivePlayerViewModel.UiState.Error ||
        state == LivePlayerViewModel.UiState.NoStream

    // Keep a focus target: the back button when chrome is up, else the root (so any key re-shows it).
    // EXCEPT while an overlay is up. This effect used to fire on the overlay too and drag focus off the
    // big Retry button onto the tiny corner back arrow — the one control whose focus treatment is the
    // faint tint the overlay was rebuilt to get away from. Two writers of focus is a race, not a design.
    LaunchedEffect(controlsVisible, panelOpen, playing, overlayUp) {
        if (overlayUp) return@LaunchedEffect
        runCatching {
            when {
                panelOpen -> Unit
                controlsVisible || !playing -> backFocus.requestFocus()
                else -> rootFocus.requestFocus()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { e ->
                // The "any key wakes the chrome" shortcut must NOT run while an overlay is up. A feed
                // that freezes mid-game does so with the chrome ALREADY auto-hidden (4s), so this
                // consumed the very first press on the focused Retry button — the press did nothing, and
                // flipping controlsVisible then pulled focus onto the corner arrow. Pressing OK on an
                // obviously-highlighted button has to actually press it.
                if (e.type == KeyEventType.KeyDown && e.key != Key.Back &&
                    !controlsVisible && !panelOpen && !overlayUp
                ) {
                    controlsVisible = true
                    true
                } else {
                    false
                }
            }
            .pointerInput(Unit) { detectTapGestures { controlsVisible = !controlsVisible } },
    ) {
        if (player != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        isFocusable = false
                        isFocusableInTouchMode = false
                        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        playerViewRef = this
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize(),
            )
        }

        when (val s = state) {
            LivePlayerViewModel.UiState.Buffering -> CenterOverlay {
                CircularProgressIndicator(color = Brand.Violet, strokeWidth = 4.dp, modifier = Modifier.size(52.dp))
                Text(viewModel.title, style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text("Connecting to live feed…", style = MaterialTheme.typography.bodyMedium, color = Brand.OnSurfaceDim)
            }
            is LivePlayerViewModel.UiState.Error -> CenterOverlay {
                Text("Couldn't play this feed", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(s.message, style = MaterialTheme.typography.bodyMedium, color = Brand.OnSurfaceDim, textAlign = TextAlign.Center)
                // Land the cursor on Retry, so there is always a defined starting point. Without this
                // the first D-pad press just "wakes up" some arbitrary button and the press is wasted.
                val retryFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    repeat(12) {
                        kotlinx.coroutines.delay(40)
                        if (runCatching { retryFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OverlayActionButton("Retry", onClick = viewModel::retry, modifier = Modifier.focusRequester(retryFocus))
                    if (feeds.size > 1) OverlayActionButton("Other streams", onClick = { panelOpen = true })
                    OverlayActionButton("Back", onClick = onBack)
                }
            }
            LivePlayerViewModel.UiState.NoStream -> CenterOverlay {
                Text("No stream selected", style = MaterialTheme.typography.titleLarge, color = Color.White)
                val backOnlyFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    repeat(12) {
                        kotlinx.coroutines.delay(40)
                        if (runCatching { backOnlyFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                    }
                }
                OverlayActionButton("Back", onClick = onBack, modifier = Modifier.focusRequester(backOnlyFocus))
            }
            // Non-blocking, and deliberately NOT a CenterOverlay: the frozen frame stays visible and
            // the user can still open the stream switcher. The whole point of the badge is to stop the
            // UI lying that it is Playing while the picture is stuck — without escalating a hiccup the
            // engine is about to fix into the 3-button dead end.
            is LivePlayerViewModel.UiState.Recovering ->
                ReconnectingBadge(s.message, Modifier.align(Alignment.TopCenter))
            LivePlayerViewModel.UiState.Playing -> Unit
        }

        // Chrome: back (always while not playing; auto-hides while playing) + switch-stream.
        AnimatedVisibility(
            visible = controlsVisible || !playing,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.padding(16.dp).focusRequester(backFocus).clip(CircleShape).background(Color(0x66000000)),
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
        }

        AnimatedVisibility(
            visible = controlsVisible && playing && feeds.size > 1,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Surface(
                onClick = { panelOpen = true },
                shape = RoundedCornerShape(50),
                color = Color(0x66000000),
                contentColor = Color.White,
                modifier = Modifier.padding(16.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("  Switch stream", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        // Switch-stream side panel.
        AnimatedVisibility(
            visible = panelOpen,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            StreamsPanel(
                feeds = feeds,
                currentIndex = currentIndex,
                onSelect = { viewModel.switchTo(it); panelOpen = false; controlsVisible = true },
                onClose = { panelOpen = false },
            )
        }
    }
}

@Composable
private fun StreamsPanel(
    feeds: List<LivePlaybackHolder.Feed>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(enabled = true) { onClose() }
    val firstFocus = remember { FocusRequester() }
    // Retry past the slide-in animation so the D-pad actually lands IN the panel — the focus request
    // was firing before the first row was laid out, leaving the panel visible but unreachable.
    LaunchedEffect(Unit) {
        repeat(12) {
            kotlinx.coroutines.delay(40)
            if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(440.dp)
            .background(Color(0xF2101019))
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Streams", style = MaterialTheme.typography.titleLarge, color = Brand.OnSurface)
        Text("Pick another feed for this event.", style = MaterialTheme.typography.bodyMedium, color = Brand.OnSurfaceDim)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
            itemsIndexed(feeds) { i, feed ->
                StreamRow(
                    label = feed.label.ifBlank { "Stream ${i + 1}" },
                    selected = i == currentIndex,
                    focusRequester = if (i == 0) firstFocus else null,
                    onClick = { onSelect(i) },
                )
            }
        }
    }
}

/** A D-pad-focusable feed row (focusable + clickable so Center/Enter selects it; explicit focus highlight). */
@Composable
private fun StreamRow(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    // High-contrast: focused = bright violet fill + white ring; selected(not focused) = lighter
    // surface + cyan ring + dot; idle = a clearly-lighter-than-panel surface so every row is legible.
    val bg = when {
        focused -> Brand.Violet
        selected -> Color(0xFF2A2A38)
        else -> Brand.SurfaceVariant
    }
    val ringColor = when {
        focused -> Color.White
        selected -> Brand.Cyan
        else -> Color.Transparent
    }
    val ringWidth = when {
        focused -> 3.dp
        selected -> 2.dp
        else -> 0.dp
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clip(shape)
            .background(bg)
            .border(androidx.compose.foundation.BorderStroke(ringWidth, ringColor), shape)
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) Text("●  ", color = if (focused) Color.White else Brand.Cyan, style = MaterialTheme.typography.titleMedium)
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = if (focused) Color.White else Brand.OnSurface,
        )
    }
}

/**
 * A full-screen-overlay action button with UNMISTAKABLE focus.
 *
 * The overlay used plain Material3 [Button]s, whose focus treatment is a faint container tint — on a
 * TV, across the room, over a dark backdrop, the focused and unfocused buttons were nearly identical
 * and you could not tell which one Enter would press. Every other TV surface in this app states focus
 * three ways at once, so this matches: a solid violet fill, a 3.dp white ring, and a size bump.
 * Redundant on purpose — any ONE of the three is enough to read at a glance.
 *
 * Built on foundation APIs rather than androidx.tv.material3 because this screen is shared with the
 * phone, where the same button has to behave as an ordinary touch target.
 */
@Composable
private fun OverlayActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(50)
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (focused) 1.08f else 1f,
        label = "overlayButtonScale",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(if (focused) Brand.Violet else Brand.Surface)
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) Color.White else Brand.OnSurfaceDim.copy(alpha = 0.35f),
                shape = shape,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 30.dp, vertical = 14.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
            color = if (focused) Color.White else Brand.OnSurface,
        )
    }
}

/**
 * "Reconnecting…" over a frozen picture — the honest version of what the live player used to do,
 * which was to keep claiming `Playing` while nothing moved.
 *
 * A pill, not an overlay, on purpose: the automatic ladder in [LivePlayerViewModel] fixes the great
 * majority of these within a couple of seconds, and blacking out the game to announce that would be
 * worse than the freeze. The 3-button Error overlay stays reserved for a genuinely exhausted feed.
 */
@Composable
private fun ReconnectingBadge(message: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color(0xCC000000),
        contentColor = Color.White,
        modifier = modifier.padding(top = 24.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(color = Brand.Violet, strokeWidth = 2.5.dp, modifier = Modifier.size(18.dp))
            Text(message, style = MaterialTheme.typography.labelLarge, color = Color.White)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.CenterOverlay(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xCC000000)).align(Alignment.Center),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.width(560.dp),
        ) { content() }
    }
}
