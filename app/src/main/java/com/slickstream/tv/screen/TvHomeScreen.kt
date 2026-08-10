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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.tv.material3.CarouselDefaults
import androidx.tv.material3.CarouselState
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.rememberCarouselState
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Scale
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.core.model.WatchHistoryItem
import com.slickstream.feature.home.HomeUiState
import com.slickstream.feature.home.HomeViewModel
import com.slickstream.feature.home.MediaRowUi
import com.slickstream.tv.components.ConsumeOnResume
import com.slickstream.tv.components.TvFocusTicket
import com.slickstream.tv.components.TvMediaRow
import com.slickstream.tv.components.rememberTvFocusTicket
import com.slickstream.ui.components.RatingBadge
import com.slickstream.ui.theme.Brand

/** Row identities for the focus re-entry ticket — must be stable across a Details round trip. */
private const val ROW_NEW_EPISODES = "New Episodes"
private const val ROW_CONTINUE = "Continue Watching"

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
    onResume: (WatchHistoryItem) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Created HERE, above the loading/empty branch: the ticket must be read on the very first
    // composition of this destination, before ConsumeOnResume spends it. Created inside HomeContent
    // it would be lost on any return where the rows are not ready in the first frame.
    val focusTicket = rememberTvFocusTicket()
    focusTicket.ConsumeOnResume()

    when {
        state.isLoading && state.isEmpty -> TvLoading(modifier)
        state.errorMessage != null && state.isEmpty ->
            TvErrorRetry(message = state.errorMessage!!, onRetry = viewModel::refresh, modifier = modifier)
        state.isEmpty -> TvCenteredMessage("Nothing to show right now.", modifier)
        else -> HomeContent(
            state = state,
            onMediaClick = onMediaClick,
            onPlayClick = onPlayClick,
            onResume = onResume,
            focusTicket = focusTicket,
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
    onResume: (WatchHistoryItem) -> Unit,
    focusTicket: TvFocusTicket,
    modifier: Modifier = Modifier,
) {
    // Memoize derived collections so they aren't rebuilt (new List/Map allocations) on every
    // recomposition — that was breaking skipping of the rows below and driving TV jank.
    val carouselItems: List<MediaItem> = remember(state.rows, state.featured) {
        state.rows.firstOrNull { it.title == "Trending" }?.items
            ?.filter { !it.backdropUrl.isNullOrBlank() }
            // 4, not 8: hero bitmaps are the biggest images in the app, and 8 of them cycling
            // through the low-RAM box's small memory cache evicted the row posters (and each
            // other) — posters re-decoded every time you scrolled back up.
            ?.take(4)
            ?: listOfNotNull(state.featured)
    }
    // continueWatching is already RESOLVED in HomeViewModel (a finished episode points at the next aired
    // one at 0%; a finished/last episode is gone), so read progress directly — the tile, its bar, and the
    // play action all agree.
    // Keyed by (type, id), NOT bare id: TMDB movie and TV ids are separate namespaces that overlap
    // numerically, and rawHistory dedupes by (id, type) — so a movie and a show with the same id can
    // both be on the rail. An id-only map silently drops one and cross-wires resume/progress.
    val progressByMediaId: Map<Pair<MediaType, Int>, Float> = remember(state.continueWatching) {
        state.continueWatching.associate { (it.media.mediaType to it.media.id) to it.progress.percent }
    }
    val continueItems: List<MediaItem> = remember(state.continueWatching) {
        // Surface the specific episode on the tile (e.g. "Game of Thrones · S1E3") instead of just the
        // show name, so a resumed series tile tells you what's actually queued. id is unchanged, so the
        // progress + resume lookups below still match.
        state.continueWatching.map { h ->
            val s = h.progress.season
            val e = h.progress.episode
            if (s != null && e != null) h.media.copy(title = "${h.media.title} · S${s}E$e") else h.media
        }
    }
    // Resume a continue-watching tile at its SAVED episode. Tapping a TV-show tile must carry the
    // season/episode (history is one row per show), or the player resolves the show with no episode
    // and finds no sources — the "Continue Watching breaks for TV shows" bug. The list is deduped to
    // one row per show, so a media.id lookup is unambiguous.
    val historyByMediaId: Map<Pair<MediaType, Int>, WatchHistoryItem> = remember(state.continueWatching) {
        state.continueWatching.associateBy { it.media.mediaType to it.media.id }
    }
    val onContinueClick: (MediaItem) -> Unit = remember(historyByMediaId, onResume) {
        { item -> historyByMediaId[item.mediaType to item.id]?.let(onResume) }
    }

    // "New Episodes": favourites you're caught up on that just dropped a fresh episode. progress already
    // points at the new episode, so a tap resumes straight into it (same onResume plumbing).
    val newEpisodeItems: List<MediaItem> = remember(state.newEpisodes) {
        state.newEpisodes.map { h ->
            val s = h.progress.season
            val e = h.progress.episode
            if (s != null && e != null) h.media.copy(title = "${h.media.title} · New S${s}E$e") else h.media
        }
    }
    val newEpisodeById: Map<Pair<MediaType, Int>, WatchHistoryItem> = remember(state.newEpisodes) {
        state.newEpisodes.associateBy { it.media.mediaType to it.media.id }
    }
    val onNewEpisodeClick: (MediaItem) -> Unit = remember(newEpisodeById, onResume) {
        { item -> newEpisodeById[item.mediaType to item.id]?.let(onResume) }
    }

    // Every row records the tile it is opened through, so the trip into Details (or straight into
    // the player, for the two resume rows) can be undone by BACK. Wrapped here rather than inside
    // TvMediaRow so the row stays a dumb presenter and the details screen's own "More Like This"
    // row is untouched. Remembered, like every other callback on this screen, so the rows stay
    // skippable across recomposition.
    val onNewEpisodeOpen: (MediaItem) -> Unit = remember(onNewEpisodeClick, focusTicket) {
        { item -> focusTicket.record(ROW_NEW_EPISODES, item); onNewEpisodeClick(item) }
    }
    val onContinueOpen: (MediaItem) -> Unit = remember(onContinueClick, focusTicket) {
        { item -> focusTicket.record(ROW_CONTINUE, item); onContinueClick(item) }
    }

    // The hero Play button keeps a focus requester so D-pad RIGHT from the nav rail lands on it, but we
    // DON'T auto-focus it on entry any more: on app launch the nav rail owns focus (so it's clear you're
    // in the menu — see TvApp), and grabbing the hero here stole that focus and made a RIGHT press scroll
    // the carousel instead of moving through the menu.
    val heroPlayFocus = remember { FocusRequester() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(26.dp),
        // Outer top trimmed — the screen-wide overscan inset in TvApp supplies the safe margin.
        contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp),
    ) {
        if (carouselItems.isNotEmpty()) {
            item(key = "featured-carousel") {
                FeaturedCarousel(
                    items = carouselItems,
                    onPlay = onPlayClick,
                    onDetails = onMediaClick,
                    playFocus = heroPlayFocus,
                )
            }
        }

        if (newEpisodeItems.isNotEmpty()) {
            item(key = "new-episodes") {
                TvMediaRow(
                    title = ROW_NEW_EPISODES,
                    items = newEpisodeItems,
                    onItemClick = onNewEpisodeOpen,
                    wide = true,
                    focusTicket = focusTicket,
                )
            }
        }

        if (continueItems.isNotEmpty()) {
            item(key = "continue") {
                TvMediaRow(
                    title = ROW_CONTINUE,
                    items = continueItems,
                    onItemClick = onContinueOpen,
                    wide = true,
                    progressFor = { progressByMediaId[it.mediaType to it.id] },
                    focusTicket = focusTicket,
                )
            }
        }

        itemsIndexed(
            state.rows,
            key = { _, row -> row.title },
            // All catalog rows share one shape — declare it so LazyColumn reuses row nodes when
            // scrolling instead of composing each row from scratch.
            contentType = { _, _ -> "media-row" },
        ) { _, row: MediaRowUi ->
            val onOpen: (MediaItem) -> Unit = remember(row.title, onMediaClick, focusTicket) {
                { item -> focusTicket.record(row.title, item); onMediaClick(item) }
            }
            TvMediaRow(
                title = row.title,
                items = row.items,
                onItemClick = onOpen,
                focusTicket = focusTicket,
            )
        }
    }
}

/**
 * Hero Play / Details button with UNMISTAKABLE 10-foot focus feedback (matches the nav rail + dialog
 * buttons): both jump to a solid violet fill, a 3dp white ring, and scale up when focused — the plain
 * Material3 Button only changed colour faintly, so you couldn't tell it was focused.
 */
@Composable
private fun HeroActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    androidx.tv.material3.Surface(
        onClick = onClick,
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape = shape),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = if (primary) Brand.Violet else Brand.SurfaceVariant,
            focusedContainerColor = Brand.Violet,
            contentColor = Color.White,
            focusedContentColor = Color.White,
        ),
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                androidx.compose.foundation.BorderStroke(3.dp, Color.White),
                shape = shape,
            ),
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.08f),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun FeaturedCarousel(
    items: List<MediaItem>,
    onPlay: (MediaItem) -> Unit,
    onDetails: (MediaItem) -> Unit,
    playFocus: FocusRequester? = null,
    carouselState: CarouselState = rememberCarouselState(),
) {
    Carousel(
        itemCount = items.size,
        carouselState = carouselState,
        // Default 5s auto-advance decoded a fresh ~1MB hero + ran a full-width crossfade while the
        // user browses rows below — periodic jank spikes on an idle Home. 15s keeps it alive
        // without the constant churn.
        autoScrollDurationMillis = 15_000,
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp)
            .padding(horizontal = 8.dp)
            .background(Brand.Surface, RoundedCornerShape(20.dp)),
        carouselIndicator = {
            CarouselDefaults.IndicatorRow(
                itemCount = items.size,
                activeItemIndex = carouselState.activeItemIndex,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            )
        },
    ) { index ->
        val item = items[index]
        FeaturedSlide(item = item, onPlay = onPlay, onDetails = onDetails, playFocus = playFocus)
    }
}

@Composable
private fun FeaturedSlide(
    item: MediaItem,
    onPlay: (MediaItem) -> Unit,
    onDetails: (MediaItem) -> Unit,
    playFocus: FocusRequester? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brand.Surface, RoundedCornerShape(20.dp)),
    ) {
        val context = LocalContext.current
        // Remembered so the slide's recompositions don't rebuild the request, and capped at
        // 960x540 — the hero sits under a heavy scrim at 10 feet, so 720p-class decode is
        // invisible while costing ~half the bitmap memory of the old 1280x720.
        val heroRequest = remember(item.backdropUrl, item.posterUrl) {
            ImageRequest.Builder(context)
                .data(item.backdropUrl ?: item.posterUrl)
                .size(960, 540)
                .scale(Scale.FILL)
                .build()
        }
        AsyncImage(
            model = heroRequest,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // Left-to-right + bottom scrim so text sits on a readable surface. ONE draw node painting
        // both gradients (was two stacked full-slide Boxes = an extra full-area composite layer).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val horizontal = Brush.horizontalGradient(
                        0f to Color(0xF20B0B0F),
                        0.55f to Color(0x880B0B0F),
                        1f to Color.Transparent,
                        endX = size.width,
                    )
                    val vertical = Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1f to Color(0xCC0B0B0F),
                        endY = size.height,
                    )
                    onDrawBehind {
                        drawRect(horizontal)
                        drawRect(vertical)
                    }
                },
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
                HeroActionButton(
                    icon = Icons.Rounded.PlayArrow,
                    label = "Play",
                    primary = true,
                    onClick = { onPlay(item) },
                    modifier = if (playFocus != null) Modifier.focusRequester(playFocus) else Modifier,
                )
                HeroActionButton(
                    icon = Icons.Rounded.Info,
                    label = "Details",
                    primary = false,
                    onClick = { onDetails(item) },
                )
            }
        }
    }
}
