package com.slickstream.feature.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.ui.components.ErrorRetry
import com.slickstream.ui.components.LoadingState
import com.slickstream.ui.components.PosterCard
import com.slickstream.ui.theme.Brand

/**
 * Full-screen poster grid for a single genre (e.g. "Kids", "Action"), reached by tapping a
 * category chip on the Movies / TV tab. Pages in more titles as the user scrolls.
 */
@Composable
fun CategoryScreen(
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

    // Trigger pagination a little before the very end so it feels seamless.
    val shouldPage by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.items.size - 6
        }
    }
    LaunchedEffect(shouldPage, state.items.size, state.endReached) {
        if (shouldPage && state.items.isNotEmpty()) viewModel.loadMore()
    }

    androidx.compose.foundation.layout.Column(modifier = modifier.fillMaxSize()) {
        CategoryTopBar(title = genreName, mediaType = mediaType, onBack = onBack)

        // Filters the loaded titles client-side; paging still walks the full genre list.
        if (!state.isEmpty) {
            CategorySearchField(
                query = query,
                onQueryChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
            )
        }

        when {
            state.isLoading && state.isEmpty -> LoadingState(modifier = Modifier.fillMaxSize())

            state.error != null && state.isEmpty -> ErrorRetry(
                message = state.error!!,
                onRetry = viewModel::retry,
                modifier = Modifier.fillMaxSize(),
            )

            visibleItems.isEmpty() && query.isNotBlank() -> Box(
                Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No matches for \"${query.trim()}\"",
                    style = MaterialTheme.typography.titleMedium,
                    color = Brand.OnSurfaceDim,
                )
            }

            else -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 120.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(visibleItems, key = { "${it.mediaType.name}-${it.id}" }) { item ->
                    PosterCard(item = item, onClick = onMediaClick, modifier = Modifier.fillMaxWidth())
                }
                // Only paginate the unfiltered list; while searching, the spinner would be misleading.
                if (state.isPaging && query.isBlank()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Brand.Violet, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategorySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        placeholder = {
            Text("Search this category", color = Brand.OnSurfaceDim, style = MaterialTheme.typography.bodyLarge)
        },
        leadingIcon = {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = Brand.OnSurfaceDim)
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Clear search", tint = Brand.OnSurfaceDim)
                }
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Brand.OnSurface,
            unfocusedTextColor = Brand.OnSurface,
            cursorColor = Brand.Cyan,
            focusedBorderColor = Brand.Violet,
            unfocusedBorderColor = Brand.SurfaceVariant,
            focusedContainerColor = Brand.Surface,
            unfocusedContainerColor = Brand.Surface,
        ),
    )
}

@Composable
private fun CategoryTopBar(title: String, mediaType: MediaType, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Brand.OnSurface)
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Brand.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "  ·  ${if (mediaType == MediaType.MOVIE) "Movies" else "TV"}",
            style = MaterialTheme.typography.titleMedium,
            color = Brand.OnSurfaceDim,
            maxLines = 1,
        )
    }
}
