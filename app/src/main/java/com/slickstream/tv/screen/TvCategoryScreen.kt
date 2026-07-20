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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.slickstream.tv.components.TvSearchField
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
    val query by viewModel.query.collectAsStateWithLifecycle()
    val visibleItems by viewModel.visibleItems.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()

    BackHandler { onBack() }

    // This screen hides the nav rail, so without an explicit target the first D-pad press is
    // swallowed. Land focus on the first poster as soon as the grid has items.
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
            // 10-foot SAFE AREA. TvApp deliberately applies ZERO shell inset (so backdrops/video can
            // bleed to the physical edge), so every full-screen route must supply its own margin —
            // without it the title sat ON the top edge and the first poster on the left edge, and TV
            // overscan cropped them. ~5% of 1920x1080 is the broadcast-standard safe area.
            .padding(start = 48.dp, end = 48.dp, top = 27.dp, bottom = 12.dp),
    ) {
        Text(
            text = "$genreName  ·  ${if (mediaType == MediaType.MOVIE) "Movies" else "TV"}",
            style = MaterialTheme.typography.headlineMedium,
            color = Brand.OnSurface,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        // In-grid search across the titles loaded so far. Only shown once there is something to
        // filter, so the initial loading/error/empty states stay clean.
        if (!state.isEmpty) {
            TvSearchField(
                value = query,
                onValueChange = viewModel::setQuery,
                onSubmit = {},
                modifier = Modifier.padding(bottom = 20.dp),
            )
        }

        when {
            state.isLoading && state.isEmpty -> TvLoading(Modifier.fillMaxSize())
            state.error != null && state.isEmpty ->
                TvErrorRetry(message = state.error!!, onRetry = viewModel::retry, modifier = Modifier.fillMaxSize())
            state.isEmpty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing here yet.",
                    style = MaterialTheme.typography.titleMedium,
                    color = Brand.OnSurfaceDim,
                )
            }
            visibleItems.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No matches for \"${query.trim()}\"",
                    style = MaterialTheme.typography.titleMedium,
                    color = Brand.OnSurfaceDim,
                )
            }
            // Size the cards FROM THE VIEWPORT so a whole card (poster + title + year) always fits and
            // ~2 rows are visible. A fixed 150dp minimum made each poster ~285dp tall on a 960x540dp
            // TV — one row plus a sliced-off second, with the titles cut off ("bleeds over the edges").
            else -> androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val hGap = 16.dp
                val vGap = 18.dp
                val textBlock = 46.dp          // title + year rendered UNDER the poster
                val rowHeight = (maxHeight - vGap) / TARGET_ROWS
                val posterHeight = (rowHeight - textBlock).coerceAtLeast(80.dp)
                // 2:3 poster -> width, clamped so it stays legible at 10 feet but never overflows.
                val targetCardWidth = (posterHeight * (2f / 3f)).coerceIn(90.dp, 165.dp)
                // Ceil so each cell ends up <= target (Adaptive would round the other way and overflow).
                val columns = kotlin.math.ceil(maxWidth / (targetCardWidth + hGap)).toInt().coerceAtLeast(3)

                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(hGap),
                    verticalArrangement = Arrangement.spacedBy(vGap),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(visibleItems, key = { _, it -> "${it.mediaType.name}-${it.id}" }) { index, item ->
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

/** How many poster rows the category/search grids aim to fit in the viewport at once. */
private const val TARGET_ROWS = 2
