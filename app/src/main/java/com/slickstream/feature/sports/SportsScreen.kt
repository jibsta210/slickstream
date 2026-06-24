package com.slickstream.feature.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.slickstream.ui.components.ErrorRetry
import com.slickstream.ui.components.LoadingState
import com.slickstream.ui.theme.Brand
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phone Live Sports tab: a category chip rail (sport / league + "Live now") over a list of events.
 * Tapping an event opens a feed picker; choosing a feed jumps into the HLS live player.
 */
@Composable
fun SportsScreen(
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SportsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheet by viewModel.sheet.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = "Live Sports",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Brand.OnSurface,
            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 12.dp),
        )

        when {
            !state.enabled -> CenteredMessage("Live sports isn't configured in this build.")

            state.loadingCategories && state.categories.isEmpty() -> LoadingState(Modifier.fillMaxSize())

            state.error != null && state.events.isEmpty() && state.categories.isEmpty() ->
                ErrorRetry(message = state.error!!, onRetry = viewModel::retry, modifier = Modifier.fillMaxSize())

            else -> {
                CategoryChips(
                    categories = state.categories,
                    selectedId = state.selectedId,
                    onSelect = viewModel::select,
                )
                EventList(
                    events = state.events,
                    loading = state.loadingEvents,
                    error = state.error,
                    onClick = viewModel::openStreams,
                )
            }
        }
    }

    sheet?.let { s ->
        StreamPicker(
            sheet = s,
            onSelect = { stream -> viewModel.prepareToPlay(s, stream); onOpenPlayer() },
            onDismiss = viewModel::dismissStreams,
        )
    }
}

@Composable
private fun CategoryChips(
    categories: List<SportCategory>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(bottom = 8.dp),
    ) {
        items(categories, key = { it.id }) { c ->
            val selected = c.id == selectedId
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .height(38.dp) // uniform height: logoed == text-only chips
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) Brand.Violet else Brand.SurfaceVariant)
                    .clickable { onSelect(c.id) }
                    .padding(horizontal = 16.dp),
            ) {
                if (c.logoUrl != null) {
                    AsyncImage(
                        model = c.logoUrl,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = c.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) Color.White else Brand.OnSurface,
                )
            }
        }
    }
}

@Composable
private fun EventList(
    events: List<SportEvent>,
    loading: Boolean,
    error: String?,
    onClick: (SportEvent) -> Unit,
) {
    when {
        loading && events.isEmpty() -> LoadingState(Modifier.fillMaxSize())
        events.isEmpty() -> CenteredMessage(error ?: "No events scheduled here right now.")
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(events, key = { it.id }) { event -> EventCard(event, onClick) }
        }
    }
}

@Composable
private fun EventCard(event: SportEvent, onClick: (SportEvent) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Brand.Surface)
            .clickable { onClick(event) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(8.dp)).background(Brand.SurfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (event.posterUrl != null) {
                AsyncImage(
                    model = event.posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.titleMedium,
                color = Brand.OnSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = startLabel(event),
                style = MaterialTheme.typography.bodySmall,
                color = if (event.isLive) Brand.Error else Brand.OnSurfaceDim,
                fontWeight = if (event.isLive) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StreamPicker(
    sheet: SportsViewModel.StreamSheet,
    onSelect: (SportStream) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Brand.Surface) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            Text(sheet.event.title, style = MaterialTheme.typography.titleLarge, color = Brand.OnSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            when {
                sheet.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Brand.Violet, strokeWidth = 3.dp, modifier = Modifier.size(30.dp))
                }
                sheet.error != null && sheet.streams.isEmpty() ->
                    Text(sheet.error, style = MaterialTheme.typography.bodyLarge, color = Brand.OnSurfaceDim)
                else -> sheet.streams.forEach { stream ->
                    Text(
                        text = stream.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = Brand.OnSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(stream) }
                            .padding(vertical = 14.dp, horizontal = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = Brand.OnSurfaceDim)
    }
}

private val timeFormat = SimpleDateFormat("EEE h:mm a", Locale.getDefault())

private fun startLabel(event: SportEvent): String = when {
    event.isLive -> "● LIVE"
    event.startEpochMs > 0 -> timeFormat.format(Date(event.startEpochMs))
    else -> "Scheduled"
}
