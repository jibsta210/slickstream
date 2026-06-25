package com.slickstream.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.WatchHistoryItem
import com.slickstream.ui.components.ErrorRetry
import com.slickstream.ui.components.HeroBanner
import com.slickstream.ui.components.LoadingState
import com.slickstream.ui.components.MediaRow
import com.slickstream.ui.theme.Brand

/**
 * Phone Home / Discover screen. A single scrolling column: a cinematic [HeroBanner] for the
 * featured trending title, an optional "Continue Watching" carousel backed by the local library,
 * then the catalog rows (Trending, Popular Movies, Popular TV, Top Rated, New & Upcoming).
 *
 * @param onMediaClick open the details screen for a card.
 * @param onPlayClick start playback directly (hero "Play" / resume a continue-watching item).
 */
@Composable
fun HomeScreen(
    onMediaClick: (MediaItem) -> Unit,
    onPlayClick: (MediaItem) -> Unit,
    onResumeClick: (WatchHistoryItem) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    when {
        state.isLoading && state.isEmpty -> {
            LoadingState(modifier = modifier.fillMaxSize())
        }

        state.errorMessage != null && state.isEmpty -> {
            ErrorRetry(
                message = state.errorMessage!!,
                onRetry = viewModel::refresh,
                modifier = modifier.fillMaxSize(),
            )
        }

        !state.isLoading && state.errorMessage == null && state.isEmpty -> {
            EmptyState(
                message = "Nothing to show right now.",
                onRetry = viewModel::refresh,
                modifier = modifier.fillMaxSize(),
            )
        }

        else -> {
            HomeContent(
                state = state,
                onMediaClick = onMediaClick,
                onPlayClick = onPlayClick,
                onResumeClick = onResumeClick,
                modifier = modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun HomeContent(
    state: HomeUiState,
    onMediaClick: (MediaItem) -> Unit,
    onPlayClick: (MediaItem) -> Unit,
    onResumeClick: (WatchHistoryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Fast lookup of resume progress for the "Continue Watching" row. Derived once per
    // continueWatching change (mirrors TvHomeScreen) so scrolling doesn't rebuild these maps.
    val progressByMediaId: Map<Int, Float> = remember(state.continueWatching) {
        state.continueWatching.associate { it.media.id to it.progress.percent }
    }
    val historyByMediaId: Map<Int, WatchHistoryItem> = remember(state.continueWatching) {
        state.continueWatching.associateBy { it.media.id }
    }
    val continueItems: List<MediaItem> = remember(state.continueWatching) {
        // Show the specific episode on the tile (e.g. "Game of Thrones · S1E3"), not just the show
        // name. id is unchanged so the progress + resume lookups still match.
        state.continueWatching.map { h ->
            val s = h.progress.season
            val e = h.progress.episode
            if (s != null && e != null) h.media.copy(title = "${h.media.title} · S${s}E$e") else h.media
        }
    }

    // Stable lambdas so MediaRow stays skippable across recompositions (e.g. while scrolling).
    val onContinueClick: (MediaItem) -> Unit = remember(historyByMediaId, onResumeClick) {
        { item -> historyByMediaId[item.id]?.let(onResumeClick) }
    }
    val continueProgressFor: (MediaItem) -> Float? = remember(progressByMediaId) {
        { item -> progressByMediaId[item.id] }
    }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        state.featured?.let { featured ->
            item(key = "hero") {
                HeroBanner(
                    item = featured,
                    onPlay = onPlayClick,
                    onDetails = onMediaClick,
                )
            }
        }

        if (continueItems.isNotEmpty()) {
            item(key = "continue-watching") {
                MediaRow(
                    title = "Continue Watching",
                    items = continueItems,
                    onItemClick = onContinueClick,
                    wide = true,
                    progressFor = continueProgressFor,
                )
            }
        }

        items(items = state.rows, key = { it.title }) { row ->
            MediaRow(
                title = row.title,
                items = row.items,
                onItemClick = onMediaClick,
            )
        }

        item(key = "footer-spacer") {
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun EmptyState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = message,
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
