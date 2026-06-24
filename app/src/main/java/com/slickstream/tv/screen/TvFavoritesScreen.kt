package com.slickstream.tv.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.slickstream.core.model.MediaItem
import com.slickstream.feature.favorites.FavoritesFilter
import com.slickstream.feature.favorites.FavoritesViewModel
import com.slickstream.tv.components.TvPosterCard
import com.slickstream.ui.theme.Brand

/**
 * Android TV Favorites — a focusable poster grid of saved titles, backed by [FavoritesViewModel]
 * (the same Room-backed library the phone uses). Friendly empty state when nothing is saved yet.
 */
@Composable
fun TvFavoritesScreen(
    onMediaClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val firstFocus = remember { FocusRequester() }
    var didFocus by remember { mutableStateOf(false) }
    LaunchedEffect(state.isEmpty) {
        if (!state.isEmpty && !didFocus) {
            didFocus = true
            repeat(12) {
                kotlinx.coroutines.delay(40)
                if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Brand.Background)
            // Outer pad trimmed — the screen-wide overscan inset in TvApp supplies the safe margin.
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = "Favorites",
            style = MaterialTheme.typography.headlineMedium,
            color = Brand.OnSurface,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        when {
            state.loading -> TvLoading(Modifier.fillMaxSize())
            state.isEmpty -> EmptyFavorites()
            else -> {
                TvFavoritesChips(
                    selected = state.filter,
                    onSelect = viewModel::setFilter,
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(
                        state.filtered.map { it.media },
                        key = { _, it -> "${it.mediaType.name}-${it.id}" },
                    ) { index, item ->
                        TvPosterCard(
                            item = item,
                            onClick = onMediaClick,
                            fillCell = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Focusable Movies / TV / All chip row over the grid. Uses tv-material3 [Surface] so D-pad focus
 * and Enter clicks are handled; selected != focused (active chip = subtle fill + violet text, the
 * focused chip = solid violet pill + white ring). Does NOT grab focus on entry — default focus
 * still lands on the grid's first poster.
 */
@Composable
private fun TvFavoritesChips(
    selected: FavoritesFilter,
    onSelect: (FavoritesFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier.padding(bottom = 4.dp),
    ) {
        items(FavoritesFilter.entries, key = { it.name }) { filter ->
            val shape = RoundedCornerShape(50)
            val isSelected = filter == selected
            Surface(
                onClick = { onSelect(filter) },
                shape = ClickableSurfaceDefaults.shape(shape = shape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (isSelected) Brand.SurfaceVariant else Brand.Surface,
                    focusedContainerColor = Brand.Violet,
                    contentColor = if (isSelected) Brand.Violet else Brand.OnSurface,
                    focusedContentColor = Color.White,
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = shape),
                ),
                scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.06f),
            ) {
                // Standard TV pill: fixed 48.dp height + 22.dp horizontal pad, titleSmall.
                Box(
                    modifier = Modifier.height(48.dp).padding(horizontal = 22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = filter.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

private val FavoritesFilter.label: String
    get() = when (this) {
        FavoritesFilter.ALL -> "All"
        FavoritesFilter.MOVIES -> "Movies"
        FavoritesFilter.TV -> "TV"
    }

@Composable
private fun EmptyFavorites() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(40.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.FavoriteBorder,
                contentDescription = null,
                tint = Brand.Violet,
                modifier = Modifier.size(64.dp),
            )
            Text(
                text = "No favourites yet",
                style = MaterialTheme.typography.titleLarge,
                color = Brand.OnSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Open any title and press Favourite to keep it here.",
                style = MaterialTheme.typography.bodyLarge,
                color = Brand.OnSurfaceDim,
                textAlign = TextAlign.Center,
            )
        }
    }
}
