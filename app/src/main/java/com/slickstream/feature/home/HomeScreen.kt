package com.slickstream.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.WatchHistoryItem
import com.slickstream.ui.components.ErrorRetry
import com.slickstream.ui.components.HeroBanner
import com.slickstream.ui.components.LoadingState
import com.slickstream.ui.components.MediaRow

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
    // Fast lookup of resume progress for the "Continue Watching" row.
    val progressByMediaId: Map<Int, Float> = state.continueWatching.associate {
        it.media.id to it.progress.percent
    }
    val historyByMediaId: Map<Int, WatchHistoryItem> =
        state.continueWatching.associateBy { it.media.id }
    val continueItems: List<MediaItem> = state.continueWatching.map { it.media }

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
                    onItemClick = { item -> historyByMediaId[item.id]?.let(onResumeClick) },
                    wide = true,
                    progressFor = { item -> progressByMediaId[item.id] },
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
