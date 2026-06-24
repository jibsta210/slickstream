package com.slickstream.feature.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slickstream.core.model.Genre
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.feature.home.HomeUiState
import com.slickstream.ui.components.ErrorRetry
import com.slickstream.ui.components.HeroBanner
import com.slickstream.ui.components.LoadingState
import com.slickstream.ui.components.MediaRow
import com.slickstream.ui.theme.Brand

/**
 * A full catalog for a single [MediaType] (the Movies and TV tabs). Mirrors the Home layout —
 * a [HeroBanner] over Trending / Popular / Top-Rated / New / genre rows — minus continue-watching.
 */
@Composable
fun CatalogScreen(
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
    val categories = remember(genres, mediaType) { categoryChips(mediaType, genres) }

    when {
        state.isLoading && state.isEmpty -> LoadingState(modifier = modifier.fillMaxSize())

        state.errorMessage != null && state.isEmpty -> ErrorRetry(
            message = state.errorMessage!!,
            onRetry = viewModel::retry,
            modifier = modifier.fillMaxSize(),
        )

        !state.isLoading && state.errorMessage == null && state.isEmpty -> CatalogEmptyState(
            onRetry = viewModel::retry,
            modifier = modifier.fillMaxSize(),
        )

        else -> CatalogContent(
            state = state,
            categories = categories,
            onMediaClick = onMediaClick,
            onPlayClick = onPlayClick,
            onCategoryClick = onCategoryClick,
            modifier = modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun CatalogContent(
    state: HomeUiState,
    categories: List<Genre>,
    onMediaClick: (MediaItem) -> Unit,
    onPlayClick: (MediaItem) -> Unit,
    onCategoryClick: (Int, String) -> Unit,
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

        if (categories.isNotEmpty()) {
            item(key = "categories") {
                CategoryChipRow(categories = categories, onCategoryClick = onCategoryClick)
            }
        }

        items(items = state.rows, key = { it.title }) { row ->
            MediaRow(title = row.title, items = row.items, onItemClick = onMediaClick)
        }

        item(key = "footer-spacer") { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun CategoryChipRow(categories: List<Genre>, onCategoryClick: (Int, String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items = categories, key = { it.id }) { genre ->
            Text(
                text = genre.name,
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = Brand.OnSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Brand.SurfaceVariant)
                    .clickable { onCategoryClick(genre.id, genre.name) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun CatalogEmptyState(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Nothing to show right now.",
                style = MaterialTheme.typography.titleMedium,
                color = Brand.OnSurfaceDim,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Brand.Violet,
                    contentColor = Color.White,
                ),
            ) {
                Text("Refresh", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** "Kids" first (TMDB Family for movies / Kids for TV), then the rest of the genre list. */
private fun categoryChips(mediaType: MediaType, genres: List<Genre>): List<Genre> {
    if (genres.isEmpty()) return emptyList()
    val kidsId = if (mediaType == MediaType.MOVIE) 10751 else 10762
    val kids = Genre(kidsId, "Kids")
    return (listOf(kids) + genres.filter { it.id != kidsId }).distinctBy { it.id }
}
