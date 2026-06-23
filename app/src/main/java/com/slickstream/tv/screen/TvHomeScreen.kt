package com.slickstream.tv.screen

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.tv.material3.Button
import androidx.tv.material3.Carousel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Scale
import com.slickstream.core.model.MediaItem
import com.slickstream.feature.home.HomeUiState
import com.slickstream.feature.home.HomeViewModel
import com.slickstream.feature.home.MediaRowUi
import com.slickstream.tv.components.TvMediaRow
import com.slickstream.ui.components.RatingBadge
import com.slickstream.ui.theme.Brand

/**
 * Android TV Home / Browse. A cinematic featured [Carousel] across the top (big backdrop with
 * Play + Details), followed by D-pad-navigable category rows. Reuses [HomeViewModel] from
 * `feature.home` — the exact same data the phone Home screen uses.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvHomeScreen(
    onMediaClick: (MediaItem) -> Unit,
    onPlayClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    when {
        state.isLoading && state.isEmpty -> TvLoading(modifier)
        state.errorMessage != null && state.isEmpty ->
            TvErrorRetry(message = state.errorMessage!!, onRetry = viewModel::refresh, modifier = modifier)
        else -> HomeContent(
            state = state,
            onMediaClick = onMediaClick,
            onPlayClick = onPlayClick,
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomeContent(
    state: HomeUiState,
    onMediaClick: (MediaItem) -> Unit,
    onPlayClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Memoize derived collections so they aren't rebuilt (new List/Map allocations) on every
    // recomposition — that was breaking skipping of the rows below and driving TV jank.
    val carouselItems: List<MediaItem> = remember(state.rows, state.featured) {
        state.rows.firstOrNull { it.title == "Trending" }?.items
            ?.filter { !it.backdropUrl.isNullOrBlank() }
            ?.take(8)
            ?: listOfNotNull(state.featured)
    }
    val progressByMediaId: Map<Int, Float> = remember(state.continueWatching) {
        state.continueWatching.associate { it.media.id to it.progress.percent }
    }
    val continueItems: List<MediaItem> = remember(state.continueWatching) {
        state.continueWatching.map { it.media }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(26.dp),
        contentPadding = PaddingValues(top = 24.dp, bottom = 48.dp),
    ) {
        if (carouselItems.isNotEmpty()) {
            item(key = "featured-carousel") {
                FeaturedCarousel(
                    items = carouselItems,
                    onPlay = onPlayClick,
                    onDetails = onMediaClick,
                )
            }
        }

        if (continueItems.isNotEmpty()) {
            item(key = "continue") {
                TvMediaRow(
                    title = "Continue Watching",
                    items = continueItems,
                    onItemClick = onPlayClick,
                    wide = true,
                    progressFor = { progressByMediaId[it.id] },
                )
            }
        }

        itemsIndexed(state.rows, key = { _, row -> row.title }) { _, row: MediaRowUi ->
            TvMediaRow(
                title = row.title,
                items = row.items,
                onItemClick = onMediaClick,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun FeaturedCarousel(
    items: List<MediaItem>,
    onPlay: (MediaItem) -> Unit,
    onDetails: (MediaItem) -> Unit,
) {
    Carousel(
        itemCount = items.size,
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp)
            .padding(horizontal = 48.dp)
            .background(Brand.Surface, RoundedCornerShape(20.dp)),
        carouselIndicator = { /* indicators drawn inline below the slide content */ },
    ) { index ->
        val item = items[index]
        FeaturedSlide(item = item, onPlay = onPlay, onDetails = onDetails)
    }
}

@Composable
private fun FeaturedSlide(
    item: MediaItem,
    onPlay: (MediaItem) -> Unit,
    onDetails: (MediaItem) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brand.Surface, RoundedCornerShape(20.dp)),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(item.backdropUrl ?: item.posterUrl)
                .size(1280, 720) // hard decode ceiling so a 4K panel doesn't decode at 2x
                .scale(Scale.FILL)
                .build(),
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // Left-to-right + bottom scrim so text sits on a readable surface.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color(0xF20B0B0F),
                        0.55f to Color(0x880B0B0F),
                        1f to Color.Transparent,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1f to Color(0xCC0B0B0F),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.62f)
                .align(Alignment.CenterStart)
                .padding(start = 44.dp, end = 24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.voteAverage > 0.0) {
                    RatingBadge(vote = item.voteAverage)
                    Spacer(Modifier.width(12.dp))
                }
                item.year?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = Brand.OnSurfaceDim)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = item.overview,
                style = MaterialTheme.typography.bodyMedium,
                color = Brand.OnSurface.copy(alpha = 0.92f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Button(onClick = { onPlay(item) }) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Play", style = MaterialTheme.typography.labelLarge)
                }
                Button(onClick = { onDetails(item) }) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Details", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
