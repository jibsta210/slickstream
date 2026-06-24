package com.slickstream.feature.favorites

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slickstream.core.model.MediaItem
import com.slickstream.ui.components.LoadingState
import com.slickstream.ui.components.PosterCard
import com.slickstream.ui.components.SectionHeader
import com.slickstream.ui.theme.Brand

/**
 * Favourites tab. A poster grid of saved titles when signed in, with friendly empty states for
 * the signed-out and the signed-in-but-empty cases.
 */
@Composable
fun FavoritesScreen(
    onMediaClick: (MediaItem) -> Unit,
    isSignedIn: Boolean,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        SectionHeader(title = "Favourites")

        when {
            !isSignedIn -> SignInEmptyState(onSignIn = onSignIn)
            state.loading -> LoadingState(modifier = Modifier.fillMaxSize())
            state.isEmpty -> NoFavoritesEmptyState()
            else -> {
                FavoritesFilterChips(
                    selected = state.filter,
                    onSelect = viewModel::setFilter,
                )
                FavoritesGrid(
                    items = state.filtered.map { it.media },
                    onMediaClick = onMediaClick,
                )
            }
        }
    }
}

@Composable
private fun FavoritesFilterChips(
    selected: FavoritesFilter,
    onSelect: (FavoritesFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FavoritesFilter.entries.forEach { filter ->
            val isSelected = filter == selected
            Text(
                text = filter.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (isSelected) Color.White else Brand.OnSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) Brand.Violet else Brand.SurfaceVariant)
                    .clickable { onSelect(filter) }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
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
private fun FavoritesGrid(
    items: List<MediaItem>,
    onMediaClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 120.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(items, key = { "${it.mediaType.name}-${it.id}" }) { item ->
            PosterCard(
                item = item,
                onClick = onMediaClick,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SignInEmptyState(
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        title = "Sign in to sync favourites",
        message = "Save the movies and shows you love and keep them in sync across all your devices.",
        modifier = modifier,
    ) {
        Button(
            onClick = onSignIn,
            colors = ButtonDefaults.buttonColors(
                containerColor = Brand.Violet,
                contentColor = Color.White,
            ),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text("Sign in", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun NoFavoritesEmptyState(modifier: Modifier = Modifier) {
    EmptyState(
        title = "No favourites yet",
        message = "Tap the heart on any title to add it here.",
        modifier = modifier,
    )
}

@Composable
private fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.FavoriteBorder,
                contentDescription = null,
                tint = Brand.Violet,
                modifier = Modifier.size(56.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Brand.OnSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = Brand.OnSurfaceDim,
                textAlign = TextAlign.Center,
            )
            if (action != null) {
                action()
            }
        }
    }
}
