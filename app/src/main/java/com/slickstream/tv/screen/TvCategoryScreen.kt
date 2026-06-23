package com.slickstream.tv.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.feature.catalog.CategoryViewModel
import com.slickstream.tv.components.TvPosterCard
import com.slickstream.ui.theme.Brand

/**
 * Android TV full-screen grid for one genre (Kids, Action, …), reached from the category chips on
 * the Movies / TV rails. D-pad grid of [TvPosterCard]s, paging as focus nears the end.
 */
@Composable
fun TvCategoryScreen(
    mediaType: MediaType,
    genreId: Int,
    genreName: String,
    onMediaClick: (MediaItem) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CategoryViewModel = hiltViewModel(),
) {
    LaunchedEffect(mediaType, genreId) { viewModel.init(mediaType, genreId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()

    BackHandler { onBack() }

    val shouldPage by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.items.size - 10
        }
    }
    LaunchedEffect(shouldPage, state.items.size, state.endReached) {
        if (shouldPage && state.items.isNotEmpty()) viewModel.loadMore()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 48.dp, end = 48.dp, top = 28.dp),
    ) {
        Text(
            text = "$genreName  ·  ${if (mediaType == MediaType.MOVIE) "Movies" else "TV"}",
            style = MaterialTheme.typography.headlineMedium,
            color = Brand.OnSurface,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 20.dp),
        )

        when {
            state.isLoading && state.isEmpty -> TvLoading(Modifier.fillMaxSize())
            state.error != null && state.isEmpty ->
                TvErrorRetry(message = state.error!!, onRetry = viewModel::retry, modifier = Modifier.fillMaxSize())
            state.isEmpty -> Box(Modifier.fillMaxSize()) {
                Text(
                    "Nothing here yet.",
                    style = MaterialTheme.typography.titleMedium,
                    color = Brand.OnSurfaceDim,
                )
            }
            else -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 150.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.items, key = { "${it.mediaType.name}-${it.id}" }) { item ->
                    TvPosterCard(item = item, onClick = onMediaClick, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
