package com.slickstream.tv.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.slickstream.core.model.Genre
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.feature.catalog.CatalogViewModel
import com.slickstream.feature.home.HomeUiState
import com.slickstream.feature.home.MediaRowUi
import com.slickstream.tv.components.TvMediaRow
import com.slickstream.ui.theme.Brand

/**
 * Android TV Movies / TV catalog. Mirrors [TvHomeScreen] (featured carousel + category rows),
 * scoped to a single [MediaType], minus Continue-Watching. Reuses the phone [CatalogViewModel].
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvCatalogScreen(
    mediaType: MediaType,
    onMediaClick: (MediaItem) -> Unit,
    onPlayClick: (MediaItem) -> Unit,
    onCategoryClick: (Int, String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CatalogViewModel = hiltViewModel(),
) {
    LaunchedEffect(mediaType) { viewModel.load(mediaType) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val genres by viewModel.genres.collectAsStateWithLifecycle()
    val categories = remember(genres, mediaType) { tvCategoryChips(mediaType, genres) }

    when {
        state.isLoading && state.isEmpty -> TvLoading(modifier)
        state.errorMessage != null && state.isEmpty ->
            TvErrorRetry(message = state.errorMessage!!, onRetry = viewModel::retry, modifier = modifier)
        state.isEmpty -> TvCenteredMessage("Nothing to show right now.", modifier)
        else -> CatalogContent(state, categories, onMediaClick, onPlayClick, onCategoryClick, modifier)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CatalogContent(
    state: HomeUiState,
    categories: List<Genre>,
    onMediaClick: (MediaItem) -> Unit,
    onPlayClick: (MediaItem) -> Unit,
    onCategoryClick: (Int, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val carouselItems: List<MediaItem> = remember(state.rows, state.featured) {
        state.rows.firstOrNull { it.title.startsWith("Trending") }?.items
            ?.filter { !it.backdropUrl.isNullOrBlank() }
            ?.take(8)
            ?: listOfNotNull(state.featured)
    }

    // The hero Play button keeps a focus requester (so RIGHT from the nav rail lands on it) but we do
    // NOT auto-grab focus: the old LaunchedEffect re-fired whenever the rows reloaded (carouselItems
    // changed), yanking focus back to the hero while the user was browsing further down. Focus arrives
    // naturally from the nav rail.
    val heroPlayFocus = remember { FocusRequester() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(26.dp),
        // Outer top trimmed — the screen-wide overscan inset in TvApp supplies the safe margin.
        contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp),
    ) {
        if (carouselItems.isNotEmpty()) {
            item(key = "featured-carousel") {
                FeaturedCarousel(items = carouselItems, onPlay = onPlayClick, onDetails = onMediaClick, playFocus = heroPlayFocus)
            }
        }
        if (categories.isNotEmpty()) {
            item(key = "categories") {
                TvCategoryChipRow(categories = categories, onCategoryClick = onCategoryClick)
            }
        }
        itemsIndexed(state.rows, key = { _, row -> row.title }) { _, row: MediaRowUi ->
            TvMediaRow(title = row.title, items = row.items, onItemClick = onMediaClick)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvCategoryChipRow(categories: List<Genre>, onCategoryClick: (Int, String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(items = categories, key = { it.id }) { genre ->
            val shape = RoundedCornerShape(50)
            Surface(
                onClick = { onCategoryClick(genre.id, genre.name) },
                shape = ClickableSurfaceDefaults.shape(shape = shape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Brand.SurfaceVariant,
                    focusedContainerColor = Brand.Violet,
                    contentColor = Brand.OnSurface,
                    focusedContentColor = Color.White,
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(
                        border = androidx.compose.foundation.BorderStroke(3.dp, Color.White),
                        shape = shape,
                    ),
                ),
                scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.06f),
            ) {
                // Standard TV pill: fixed 48.dp height + 22.dp horizontal pad, titleSmall.
                Box(
                    modifier = Modifier.height(48.dp).padding(horizontal = 22.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = genre.name,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }
    }
}

/** "Kids" first (TMDB Family for movies / Kids for TV), then the rest of the genre list. */
private fun tvCategoryChips(mediaType: MediaType, genres: List<Genre>): List<Genre> {
    if (genres.isEmpty()) return emptyList()
    val kidsId = if (mediaType == MediaType.MOVIE) 10751 else 10762
    return (listOf(Genre(kidsId, "Kids")) + genres.filter { it.id != kidsId }).distinctBy { it.id }
}
