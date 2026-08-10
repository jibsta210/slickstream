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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import com.slickstream.tv.components.ConsumeOnResume
import com.slickstream.tv.components.TvFocusAnchor
import com.slickstream.tv.components.TvPosterCard
import com.slickstream.tv.components.bringItemIntoComposition
import com.slickstream.tv.components.rememberTvFocusTicket
import com.slickstream.tv.components.tvItemKey
import com.slickstream.ui.theme.Brand

/** Row identity for the (single-surface) favourites grid in the re-entry ticket. */
private const val GRID = ""

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
    val gridState = rememberLazyGridState()
    val items = remember(state.filtered) { state.filtered.map { it.media } }

    // Back out of a title's Details restores the tile you opened; a fresh arrival still lands on the
    // first poster. Matched by key and confirmed by the tile itself — the old
    // `runCatching { requestFocus() }.isSuccess` test only proved a node was attached, so this loop
    // could report success while nothing at all was focused.
    val focusTicket = rememberTvFocusTicket()
    focusTicket.ConsumeOnResume()
    val firstAnchor = remember { TvFocusAnchor() }
    var didFocus by remember { mutableStateOf(false) }
    LaunchedEffect(state.isEmpty) {
        if (state.isEmpty || didFocus) return@LaunchedEffect
        didFocus = true
        val index = items.indexOfFirst {
            focusTicket.isReturning && tvItemKey(it) == focusTicket.pendingItem
        }
        if (index >= 0) {
            gridState.bringItemIntoComposition(index)
            if (focusTicket.anchor.requestUntilFocused()) return@LaunchedEffect
        }
        firstAnchor.requestUntilFocused()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Brand.Background)
            // Outer pad trimmed — the screen-wide overscan inset in TvApp supplies the safe margin.
            .padding(start = 48.dp, end = 48.dp, top = 27.dp, bottom = 12.dp),
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
                // Same viewport-derived sizing as the category/search grids — a fixed 150dp minimum
                // made favourites huge on a 540dp-tall TV (one clipped row).
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                    val hGap = 16.dp
                    val vGap = 18.dp
                    val textBlock = 46.dp
                    val rowHeight = (maxHeight - vGap) / 2
                    val posterHeight = (rowHeight - textBlock).coerceAtLeast(80.dp)
                    val targetCardWidth = (posterHeight * (2f / 3f)).coerceIn(90.dp, 165.dp)
                    val columns = kotlin.math.ceil(maxWidth / (targetCardWidth + hGap)).toInt().coerceAtLeast(3)

                    LazyVerticalGrid(
                        // Hoisted so the restore effect can scroll the tile we want back into
                        // composition — a focus requester on an uncomposed cell is attached to
                        // nothing and the request simply throws.
                        state = gridState,
                        columns = GridCells.Fixed(columns),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(hGap),
                        verticalArrangement = Arrangement.spacedBy(vGap),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(items, key = { _, it -> tvItemKey(it) }) { index, item ->
                            TvPosterCard(
                                item = item,
                                onClick = {
                                    focusTicket.record(GRID, it)
                                    onMediaClick(it)
                                },
                                fillCell = true,
                                focusAnchor = when {
                                    tvItemKey(item) == focusTicket.pendingItem -> focusTicket.anchor
                                    index == 0 -> firstAnchor
                                    else -> null
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
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
