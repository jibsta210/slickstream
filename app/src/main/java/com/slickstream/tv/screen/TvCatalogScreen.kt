package com.slickstream.tv.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.feature.catalog.CatalogViewModel
import com.slickstream.feature.home.HomeUiState
import com.slickstream.feature.home.MediaRowUi
import com.slickstream.tv.components.TvMediaRow

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
    modifier: Modifier = Modifier,
    viewModel: CatalogViewModel = hiltViewModel(),
) {
    LaunchedEffect(mediaType) { viewModel.load(mediaType) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    when {
        state.isLoading && state.isEmpty -> TvLoading(modifier)
        state.errorMessage != null && state.isEmpty ->
            TvErrorRetry(message = state.errorMessage!!, onRetry = viewModel::retry, modifier = modifier)
        else -> CatalogContent(state, onMediaClick, onPlayClick, modifier)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CatalogContent(
    state: HomeUiState,
    onMediaClick: (MediaItem) -> Unit,
    onPlayClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val carouselItems: List<MediaItem> = remember(state.rows, state.featured) {
        state.rows.firstOrNull { it.title.startsWith("Trending") }?.items
            ?.filter { !it.backdropUrl.isNullOrBlank() }
            ?.take(8)
            ?: listOfNotNull(state.featured)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(26.dp),
        contentPadding = PaddingValues(top = 24.dp, bottom = 48.dp),
    ) {
        if (carouselItems.isNotEmpty()) {
            item(key = "featured-carousel") {
                FeaturedCarousel(items = carouselItems, onPlay = onPlayClick, onDetails = onMediaClick)
            }
        }
        itemsIndexed(state.rows, key = { _, row -> row.title }) { _, row: MediaRowUi ->
            TvMediaRow(title = row.title, items = row.items, onItemClick = onMediaClick)
        }
    }
}
