package com.slickstream.tv.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.slickstream.feature.sports.SportCategory
import com.slickstream.feature.sports.SportEvent
import com.slickstream.feature.sports.SportStream
import com.slickstream.feature.sports.SportsViewModel
import com.slickstream.ui.theme.Brand
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Android TV Live Sports tab. A focusable category chip row over a column of event cards; choosing
 * an event opens a side-panel feed picker, then jumps into the shared HLS live player.
 */
@Composable
fun TvSportsScreen(
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SportsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheet by viewModel.sheet.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 28.dp),
        ) {
            Text(
                text = "Live Sports",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Brand.OnSurface,
                modifier = Modifier.padding(bottom = 18.dp),
            )

            when {
                !state.enabled -> TvCenteredMessage("Live sports isn't configured in this build.")
                state.loadingCategories && state.categories.isEmpty() -> TvLoading(Modifier.fillMaxSize())
                state.error != null && state.categories.isEmpty() ->
                    TvErrorRetry(message = state.error!!, onRetry = viewModel::retry, modifier = Modifier.fillMaxSize())
                else -> {
                    TvSportsChips(state.categories, state.selectedId, viewModel::select)
                    Spacer(Modifier.height(18.dp))
                    TvEventList(state.events, state.loadingEvents, state.error, viewModel::openStreams)
                }
            }
        }

        AnimatedVisibility(
            visible = sheet != null,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            sheet?.let { s ->
                TvStreamPanel(
                    sheet = s,
                    onSelect = { stream -> viewModel.prepareToPlay(s, stream); onOpenPlayer() },
                    onClose = viewModel::dismissStreams,
                )
            }
        }
    }
}

@Composable
private fun TvSportsChips(categories: List<SportCategory>, selectedId: String?, onSelect: (String) -> Unit) {
    // Auto-load the focused tab, but DEBOUNCED: settle on a chip for 250ms before switching, so
    // scrubbing the D-pad across the row doesn't fire a cancelling network load + grid wipe per
    // chip (the #1 cause of the choppiness). A click still switches instantly.
    var focusedId by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    LaunchedEffect(focusedId) {
        val id = focusedId ?: return@LaunchedEffect
        kotlinx.coroutines.delay(250)
        if (id == focusedId && id != selectedId) onSelect(id)
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(categories, key = { it.id }) { c ->
            val shape = RoundedCornerShape(50)
            val selected = c.id == selectedId
            Surface(
                onClick = { onSelect(c.id) },
                shape = ClickableSurfaceDefaults.shape(shape = shape),
                colors = ClickableSurfaceDefaults.colors(
                    // selected != focused: active tab = subtle fill + violet text; FOCUSED tab = solid
                    // violet pill + white ring, so you can always tell what you're on vs what's active.
                    containerColor = if (selected) Brand.SurfaceVariant else Brand.Surface,
                    focusedContainerColor = Brand.Violet,
                    contentColor = if (selected) Brand.Violet else Brand.OnSurface,
                    focusedContentColor = Color.White,
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(androidx.compose.foundation.BorderStroke(3.dp, Color.White), shape = shape),
                ),
                scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.06f),
                modifier = Modifier.onFocusChanged { if (it.isFocused) focusedId = c.id },
            ) {
                Text(
                    text = c.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun TvEventList(events: List<SportEvent>, loading: Boolean, error: String?, onClick: (SportEvent) -> Unit) {
    when {
        loading && events.isEmpty() -> TvLoading(Modifier.fillMaxSize())
        events.isEmpty() -> TvCenteredMessage(error ?: "No events scheduled here right now.")
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 48.dp, end = 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            gridItems(events, key = { it.id }) { event -> TvEventCard(event, onClick) }
        }
    }
}

@Composable
private fun TvEventCard(event: SportEvent, onClick: (SportEvent) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        onClick = { onClick(event) },
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
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.04f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(width = 96.dp, height = 54.dp).background(Brand.SurfaceVariant, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (event.posterUrl != null) {
                    AsyncImage(model = event.posterUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(event.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                if (event.isLive) {
                    LiveBadge()
                } else {
                    Text(
                        text = tvStartLabel(event),
                        style = MaterialTheme.typography.labelLarge,
                        color = Brand.OnSurfaceDim,
                    )
                }
            }
        }
    }
}

/** A small red "● LIVE" pill so live games stand out in any category. */
@Composable
private fun LiveBadge() {
    Row(
        modifier = Modifier
            .background(Brand.Error, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("● LIVE", style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TvStreamPanel(
    sheet: SportsViewModel.StreamSheet,
    onSelect: (SportStream) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(enabled = true) { onClose() }
    val firstFocus = remember { FocusRequester() }
    // Re-focus when the content settles (loading -> list, or -> error button) so the remote always
    // has a target — fixes "the retry/back buttons don't auto-focus".
    LaunchedEffect(sheet.loading, sheet.streams.size, sheet.error) {
        runCatching { firstFocus.requestFocus() }
    }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(440.dp)
            .background(Color(0xF2101019))
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(sheet.event.title, style = MaterialTheme.typography.titleLarge, color = Brand.OnSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        when {
            sheet.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(color = Brand.Violet, strokeWidth = 3.dp, modifier = Modifier.size(30.dp))
            }
            sheet.error != null && sheet.streams.isEmpty() -> {
                Text(sheet.error, style = MaterialTheme.typography.bodyLarge, color = Brand.OnSurfaceDim)
                Spacer(Modifier.height(8.dp))
                androidx.tv.material3.Button(
                    onClick = onClose,
                    modifier = Modifier.focusRequester(firstFocus),
                ) { Text("Close") }
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(sheet.streams, key = { _, s -> s.id }) { index, stream ->
                    val shape = RoundedCornerShape(12.dp)
                    Surface(
                        onClick = { onSelect(stream) },
                        shape = ClickableSurfaceDefaults.shape(shape = shape),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Brand.Surface,
                            focusedContainerColor = Brand.Violet,
                            contentColor = Brand.OnSurface,
                            focusedContentColor = Color.White,
                        ),
                        border = ClickableSurfaceDefaults.border(
                            focusedBorder = Border(androidx.compose.foundation.BorderStroke(3.dp, Brand.Violet), shape = shape),
                        ),
                        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.03f),
                        modifier = if (index == 0) Modifier.fillMaxWidth().focusRequester(firstFocus) else Modifier.fillMaxWidth(),
                    ) {
                        Text(stream.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(14.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TvCenteredMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = Brand.OnSurfaceDim)
    }
}

private val tvTimeFormat = SimpleDateFormat("EEE h:mm a", Locale.getDefault())

private fun tvStartLabel(event: SportEvent): String = when {
    event.isLive -> "● LIVE"
    event.startEpochMs > 0 -> tvTimeFormat.format(Date(event.startEpochMs))
    else -> "Scheduled"
}
