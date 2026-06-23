package com.slickstream.feature.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.feature.home.HomeUiState
import com.slickstream.ui.components.ErrorRetry
import com.slickstream.ui.components.HeroBanner
import com.slickstream.ui.components.LoadingState
import com.slickstream.ui.components.MediaRow

/**
 * A full catalog for a single [MediaType] (the Movies and TV tabs). Mirrors the Home layout —
 * a [HeroBanner] over Trending / Popular / Top-Rated / New / genre rows — minus continue-watching.
 */
@Composable
fun CatalogScreen(
    mediaType: MediaType,
    onMediaClick: (MediaItem) -> Unit,
    onPlayClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CatalogViewModel = hiltViewModel(),
) {
    LaunchedEffect(mediaType) { viewModel.load(mediaType) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    when {
        state.isLoading && state.isEmpty -> LoadingState(modifier = modifier.fillMaxSize())

        state.errorMessage != null && state.isEmpty -> ErrorRetry(
            message = state.errorMessage!!,
            onRetry = viewModel::retry,
            modifier = modifier.fillMaxSize(),
        )

        else -> CatalogContent(
            state = state,
            onMediaClick = onMediaClick,
            onPlayClick = onPlayClick,
            modifier = modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun CatalogContent(
    state: HomeUiState,
    onMediaClick: (MediaItem) -> Unit,
    onPlayClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        state.featured?.let { featured ->
            item(key = "hero") {
                HeroBanner(item = featured, onPlay = onPlayClick, onDetails = onMediaClick)
            }
        }

        items(items = state.rows, key = { it.title }) { row ->
            MediaRow(title = row.title, items = row.items, onItemClick = onMediaClick)
        }

        item(key = "footer-spacer") { Spacer(Modifier.height(8.dp)) }
    }
}
