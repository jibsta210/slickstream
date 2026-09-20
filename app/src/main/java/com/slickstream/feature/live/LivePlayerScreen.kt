package com.slickstream.feature.live

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.slickstream.feature.sports.SportCategory
import com.slickstream.feature.sports.SportEvent
import com.slickstream.ui.theme.Brand

/**
 * Live-sports player — now MULTIVIEW. One game is the classic full-screen player; two is
 * picture-in-picture; three or four is a 2x2 grid. Each tile is an independent, self-healing
 * [LiveSession]; this file is only the layout, the D-pad focus, and the deliberately-obvious way to
 * add another game.
 *
 * The interaction model is built for a remote across a room: focus a tile (bright ring), press OK for
 * a small styled menu — Watch full / Listen / Switch feed / Remove. An empty grid cell is itself the
 * "＋ Add game" button, so there is no mode to discover. Shared with the phone, where the same
 * focusable/clickable surfaces work as taps.
 */
@OptIn(UnstableApi::class)
@Composable
fun LivePlayerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LivePlayerViewModel = hiltViewModel(),
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val layout by viewModel.layout.collectAsStateWithLifecycle()
    val expandedId by viewModel.expandedId.collectAsStateWithLifecycle()
    val audibleId by viewModel.audibleId.collectAsStateWithLifecycle()
    val canAdd by viewModel.canAdd.collectAsStateWithLifecycle()
    val picker by viewModel.picker.collectAsStateWithLifecycle()

    // The whole screen means to keep playing — hold the TV awake regardless of any single tile's phase.
    com.slickstream.feature.player.KeepScreenOn(enabled = sessions.isNotEmpty())

    // Per-tile menu (null = none open). Holds the session id the menu acts on.
    var menuForId by remember { mutableStateOf<Int?>(null) }
    // Per-tile feed switcher (null = none open).
    var feedSwitchForId by remember { mutableStateOf<Int?>(null) }

    BackHandler {
        when {
            picker != null -> viewModel.closePicker()
            feedSwitchForId != null -> feedSwitchForId = null
            menuForId != null -> menuForId = null
            expandedId != null -> viewModel.collapse()
            else -> onBack()
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        when {
            sessions.isEmpty() -> EmptyMultiview(onAdd = viewModel::openPicker)
            expandedId != null -> {
                val s = sessions.firstOrNull { it.id == expandedId }
                if (s != null) {
                    LiveTile(
                        session = s,
                        audible = s.id == audibleId,
                        cornerLabel = false,
                        onOpenMenu = { menuForId = s.id },
                        modifier = Modifier.fillMaxSize(),
                    )
                    ExpandedChrome(title = s.title, onCollapse = viewModel::collapse)
                }
            }
            layout == LiveMultiView.Layout.SINGLE -> {
                val s = sessions.first()
                LiveTile(
                    session = s,
                    audible = s.id == audibleId,
                    cornerLabel = false,
                    onOpenMenu = { menuForId = s.id },
                    modifier = Modifier.fillMaxSize(),
                )
                SingleChrome(
                    onBack = onBack,
                    onAdd = viewModel::openPicker.takeIf { canAdd },
                    onSwitchFeed = { feedSwitchForId = s.id }.takeIf { s.feeds.size > 1 },
                )
            }
            layout == LiveMultiView.Layout.PIP -> PipLayout(
                sessions = sessions,
                audibleId = audibleId,
                onOpenMenu = { menuForId = it },
                onAdd = viewModel::openPicker.takeIf { canAdd },
                onBack = onBack,
            )
            else -> GridLayout(
                sessions = sessions,
                audibleId = audibleId,
                canAdd = canAdd,
                onOpenMenu = { menuForId = it },
                onAdd = viewModel::openPicker,
            )
        }

        // Per-tile action menu.
        menuForId?.let { id ->
            val s = sessions.firstOrNull { it.id == id }
            if (s == null) menuForId = null
            else TileMenu(
                title = s.title,
                expanded = expandedId == id,
                audible = audibleId == id,
                canSwitchFeed = s.feeds.size > 1,
                onWatchFull = { viewModel.expand(id); menuForId = null },
                onExitFull = { viewModel.collapse(); menuForId = null },
                onListen = { viewModel.setAudible(id); menuForId = null },
                onSwitchFeed = { menuForId = null; feedSwitchForId = id },
                onRemove = { viewModel.removeSession(id); menuForId = null },
                onDismiss = { menuForId = null },
            )
        }

        // Per-tile feed switcher.
        feedSwitchForId?.let { id ->
            val s = sessions.firstOrNull { it.id == id }
            if (s == null) feedSwitchForId = null
            else {
                val currentIndex by s.currentIndex.collectAsStateWithLifecycle()
                FeedSwitchPanel(
                    feeds = s.feeds,
                    currentIndex = currentIndex,
                    onSelect = { viewModel.switchFeed(id, it); feedSwitchForId = null },
                    onClose = { feedSwitchForId = null },
                )
            }
        }

        // The add-game browser.
        AnimatedVisibility(
            visible = picker != null,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            picker?.let { p ->
                AddGamePicker(
                    picker = p,
                    onSelectCategory = viewModel::pickerSelectCategory,
                    onSelectEvent = viewModel::addFromEvent,
                    onBackToCategories = viewModel::pickerBackToCategories,
                    onClose = viewModel::closePicker,
                )
            }
        }
    }
}

// --- Layouts -------------------------------------------------------------------------------------

@OptIn(UnstableApi::class)
@Composable
private fun PipLayout(
    sessions: List<LiveSession>,
    audibleId: Int?,
    onOpenMenu: (Int) -> Unit,
    onAdd: (() -> Unit)?,
    onBack: () -> Unit,
) {
    val primary = sessions[0]
    val secondary = sessions[1]
    Box(Modifier.fillMaxSize()) {
        LiveTile(primary, primary.id == audibleId, cornerLabel = false, onOpenMenu = { onOpenMenu(primary.id) }, modifier = Modifier.fillMaxSize())
        // Corner overlay — a real focusable tile, so OK on it opens its menu just like a grid cell.
        LiveTile(
            session = secondary,
            audible = secondary.id == audibleId,
            cornerLabel = true,
            onOpenMenu = { onOpenMenu(secondary.id) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
                .fillMaxWidth(0.34f)
                .aspectRatio(16f / 9f),
        )
        SingleChrome(onBack = onBack, onAdd = onAdd, onSwitchFeed = null)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun GridLayout(
    sessions: List<LiveSession>,
    audibleId: Int?,
    canAdd: Boolean,
    onOpenMenu: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    // Up to four cells, row-major. Real tiles first; the next empty cell is the "＋ Add game" button.
    val cells = LiveMultiView.MAX_SLOTS
    Column(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (row in 0 until 2) {
            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (col in 0 until 2) {
                    val position = row * 2 + col
                    if (position >= cells) break
                    val cellMod = Modifier.weight(1f).fillMaxHeight()
                    val s = sessions.getOrNull(position)
                    when {
                        s != null -> LiveTile(
                            session = s,
                            audible = s.id == audibleId,
                            cornerLabel = true,
                            onOpenMenu = { onOpenMenu(s.id) },
                            modifier = cellMod,
                        )
                        // The FIRST empty cell is the add button; further empties stay blank.
                        position == sessions.size && canAdd -> AddGameCell(onAdd = onAdd, modifier = cellMod)
                        else -> Box(cellMod)
                    }
                }
            }
        }
    }
}

// --- A single tile -------------------------------------------------------------------------------

@OptIn(UnstableApi::class)
@Composable
private fun LiveTile(
    session: LiveSession,
    audible: Boolean,
    cornerLabel: Boolean,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by session.uiState.collectAsStateWithLifecycle()
    val player by session.player.collectAsStateWithLifecycle()
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }

    // Same resume-time surface repair the VOD/single-live players use, per tile.
    com.slickstream.feature.player.RebindVideoSurfaceOnResume(player, playerViewRef)

    val shape = RoundedCornerShape(if (cornerLabel) 12.dp else 0.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color.Black)
            // A bright ring is the whole "which tile am I on" signal on a 10-foot screen.
            .border(
                width = if (focused) 4.dp else if (cornerLabel) 1.dp else 0.dp,
                color = if (focused) Brand.Violet else Color(0x33FFFFFF),
                shape = shape,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onOpenMenu)
            .focusable(interactionSource = interaction),
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

        // Per-tile status, compact so it never covers the game in a small cell.
        when (val s = state) {
            LiveSession.UiState.Buffering ->
                TileCenter { CircularProgressIndicator(color = Brand.Violet, strokeWidth = 3.dp, modifier = Modifier.size(34.dp)) }
            is LiveSession.UiState.Recovering ->
                TilePill(s.message, Modifier.align(Alignment.TopCenter))
            is LiveSession.UiState.Error ->
                TileCenter {
                    Text("Feed failed", color = Color.White, style = MaterialTheme.typography.titleSmall)
                    Text("OK for options", color = Brand.OnSurfaceDim, style = MaterialTheme.typography.bodySmall)
                }
            LiveSession.UiState.NoStream ->
                TileCenter { Text("No feed", color = Brand.OnSurfaceDim, style = MaterialTheme.typography.titleSmall) }
            LiveSession.UiState.Playing -> Unit
        }

        // Label + audio indicator, only in multiview cells (full-screen doesn't need them).
        if (cornerLabel) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x99000000))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (audible) Icon(Icons.Rounded.VolumeUp, contentDescription = "Playing audio", tint = Brand.Violet, modifier = Modifier.size(15.dp))
                Text(
                    session.title,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(if (audible) 150.dp else 170.dp),
                )
            }
        }
    }
}

// --- Add-game affordances ------------------------------------------------------------------------

@Composable
private fun AddGameCell(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (focused) Brand.Violet.copy(alpha = 0.18f) else Brand.Surface)
            .border(
                width = if (focused) 4.dp else 2.dp,
                color = if (focused) Brand.Violet else Color(0x33FFFFFF),
                shape = shape,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onAdd)
            .focusable(interactionSource = interaction),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = if (focused) Color.White else Brand.OnSurface, modifier = Modifier.size(44.dp))
            Text("Add game", color = if (focused) Color.White else Brand.OnSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun EmptyMultiview(onAdd: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.width(420.dp),
        ) {
            Text("Multiview", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text(
                "Add up to four live games and watch them side by side.",
                style = MaterialTheme.typography.bodyMedium,
                color = Brand.OnSurfaceDim,
                textAlign = TextAlign.Center,
            )
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                repeat(12) { kotlinx.coroutines.delay(40); if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect }
            }
            PillButton("＋  Add a game", onClick = onAdd, modifier = Modifier.focusRequester(focus))
        }
    }
}

// --- Chrome --------------------------------------------------------------------------------------

@Composable
private fun androidx.compose.foundation.layout.BoxScope.SingleChrome(
    onBack: () -> Unit,
    onAdd: (() -> Unit)?,
    onSwitchFeed: (() -> Unit)?,
) {
    // Always visible, small, in the corners. Auto-hiding chrome would need a re-wake key that competes
    // with the focusable tile underneath; on a full-screen game these three chips are unobtrusive and
    // always reachable, which matters more here than hiding them.
    Row(
        Modifier.align(Alignment.TopStart).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
    }
    Row(
        Modifier.align(Alignment.TopEnd).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onSwitchFeed != null) ChromeChip(Icons.Rounded.GridView, "Switch stream", onSwitchFeed)
        if (onAdd != null) ChromeChip(Icons.Rounded.Add, "Add game", onAdd)
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.ExpandedChrome(title: String, onCollapse: () -> Unit) {
    Row(
        Modifier.align(Alignment.TopStart).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChromeChip(Icons.Rounded.GridView, "Back to grid", onCollapse)
        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(360.dp))
    }
}

// --- Per-tile menu -------------------------------------------------------------------------------

@Composable
private fun androidx.compose.foundation.layout.BoxScope.TileMenu(
    title: String,
    expanded: Boolean,
    audible: Boolean,
    canSwitchFeed: Boolean,
    onWatchFull: () -> Unit,
    onExitFull: () -> Unit,
    onListen: () -> Unit,
    onSwitchFeed: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(enabled = true) { onDismiss() }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(12) { kotlinx.coroutines.delay(40); if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect }
    }
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.width(460.dp).clip(RoundedCornerShape(22.dp)).background(Brand.Surface).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Brand.OnSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            if (expanded) {
                MenuRow("Back to grid", onExitFull, Modifier.focusRequester(firstFocus))
            } else {
                MenuRow("Watch full screen", onWatchFull, Modifier.focusRequester(firstFocus))
            }
            if (!audible) MenuRow("Listen to this game", onListen)
            if (canSwitchFeed) MenuRow("Switch feed", onSwitchFeed)
            MenuRow("Remove", onRemove, destructive = true)
        }
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, destructive: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    val accent = if (destructive) Brand.Error else Brand.Violet
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) accent else Brand.SurfaceVariant)
            .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(
            label,
            color = if (focused) Color.White else if (destructive) Brand.Error else Brand.OnSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

// --- Feed switch panel (per tile) ----------------------------------------------------------------

@Composable
private fun androidx.compose.foundation.layout.BoxScope.FeedSwitchPanel(
    feeds: List<LivePlaybackHolder.Feed>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(enabled = true) { onClose() }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(12) { kotlinx.coroutines.delay(40); if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect }
    }
    Column(
        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(440.dp).background(Color(0xF2101019)).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Streams", style = MaterialTheme.typography.titleLarge, color = Brand.OnSurface)
        Text("Pick another feed for this game.", style = MaterialTheme.typography.bodyMedium, color = Brand.OnSurfaceDim)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
            itemsIndexed(feeds) { i, feed ->
                SelectableRow(
                    label = feed.label.ifBlank { "Stream ${i + 1}" },
                    selected = i == currentIndex,
                    focusRequester = if (i == 0) firstFocus else null,
                    onClick = { onSelect(i) },
                )
            }
        }
    }
}

// --- Add-game picker (leagues -> games) ----------------------------------------------------------

@Composable
private fun AddGamePicker(
    picker: LivePlayerViewModel.Picker,
    onSelectCategory: (String, String) -> Unit,
    onSelectEvent: (SportEvent) -> Unit,
    onBackToCategories: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(enabled = true) {
        if (picker.step == LivePlayerViewModel.PickerStep.EVENTS) onBackToCategories() else onClose()
    }
    Column(
        modifier = Modifier.fillMaxHeight().width(500.dp).background(Color(0xF2101019)).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (picker.step == LivePlayerViewModel.PickerStep.CATEGORIES) "Add a game" else picker.selectedCategoryName,
            style = MaterialTheme.typography.titleLarge,
            color = Brand.OnSurface,
        )
        Text(
            if (picker.step == LivePlayerViewModel.PickerStep.CATEGORIES) "Pick a league." else "Pick a game to add.",
            style = MaterialTheme.typography.bodyMedium,
            color = Brand.OnSurfaceDim,
        )
        when {
            picker.adding -> PanelCenter { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(color = Brand.Violet, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
                Text("Adding…", color = Brand.OnSurfaceDim, style = MaterialTheme.typography.bodyMedium)
            } }
            picker.loading -> PanelCenter { CircularProgressIndicator(color = Brand.Violet, strokeWidth = 3.dp, modifier = Modifier.size(34.dp)) }
            picker.error != null -> Text(picker.error, color = Brand.OnSurfaceDim, style = MaterialTheme.typography.bodyLarge)
            picker.step == LivePlayerViewModel.PickerStep.CATEGORIES ->
                CategoryList(picker.categories, onSelectCategory)
            else -> EventList(picker.events, onSelectEvent)
        }
    }
}

@Composable
private fun CategoryList(categories: List<SportCategory>, onSelect: (String, String) -> Unit) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(categories.isNotEmpty()) {
        if (categories.isNotEmpty()) repeat(12) { kotlinx.coroutines.delay(40); if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        itemsIndexed(categories, key = { _, c -> c.id }) { i, c ->
            SelectableRow(
                label = c.name,
                selected = false,
                focusRequester = if (i == 0) firstFocus else null,
                onClick = { onSelect(c.id, c.name) },
            )
        }
    }
}

@Composable
private fun EventList(events: List<SportEvent>, onSelect: (SportEvent) -> Unit) {
    if (events.isEmpty()) {
        PanelCenter { Text("No games here right now.", color = Brand.OnSurfaceDim, style = MaterialTheme.typography.bodyLarge) }
        return
    }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(events.isNotEmpty()) {
        if (events.isNotEmpty()) repeat(12) { kotlinx.coroutines.delay(40); if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        itemsIndexed(events, key = { _, e -> e.id }) { i, e ->
            SelectableRow(
                label = e.title + if (e.isLive) "  ● LIVE" else "",
                selected = false,
                focusRequester = if (i == 0) firstFocus else null,
                onClick = { onSelect(e) },
            )
        }
    }
}

// --- Small shared pieces -------------------------------------------------------------------------

@Composable
private fun SelectableRow(label: String, selected: Boolean, focusRequester: FocusRequester?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    val bg = when { focused -> Brand.Violet; selected -> Color(0xFF2A2A38); else -> Brand.SurfaceVariant }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clip(shape)
            .background(bg)
            .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) Text("●  ", color = if (focused) Color.White else Brand.Cyan, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.titleMedium, color = if (focused) Color.White else Brand.OnSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PillButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(50)
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "pill")
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(if (focused) Brand.Violet else Brand.Surface)
            .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else Brand.OnSurfaceDim.copy(alpha = 0.35f), shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 28.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (focused) Color.White else Brand.OnSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ChromeChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (focused) Brand.Violet else Color(0x66000000))
            .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun RoundIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (focused) Brand.Violet else Color(0x66000000))
            .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = desc, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun TileCenter(content: ColumnScopeContent) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) { content() }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.TilePill(message: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(top = 10.dp).clip(RoundedCornerShape(50)).background(Color(0xCC000000)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(color = Brand.Violet, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
        Text(message, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun PanelCenter(content: ColumnScopeContent) {
    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

private typealias ColumnScopeContent = @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
