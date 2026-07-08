package com.slickstream.feature.details

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.slickstream.core.model.CastMember
import com.slickstream.core.model.Episode
import com.slickstream.core.model.Genre
import com.slickstream.core.model.isDownloadable
import com.slickstream.core.model.MediaDetails
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.core.model.Season
import com.slickstream.ui.components.ErrorRetry
import com.slickstream.ui.components.LoadingState
import com.slickstream.ui.components.MediaRow
import com.slickstream.ui.components.RatingBadge
import com.slickstream.ui.components.SectionHeader
import com.slickstream.ui.theme.Brand

/** Signature the navigation host wires up to launch the player. */
typealias OnPlay = (mediaType: MediaType, mediaId: Int, season: Int?, episode: Int?) -> Unit

@Composable
fun DetailsScreen(
    onPlay: OnPlay,
    onBack: () -> Unit,
    onMediaClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isFavorite by viewModel.isFavorite.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val seasonDownloads by viewModel.seasonDownloads.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize().background(Brand.Background)) {
        when {
            state.details != null -> DetailsContent(
                details = state.details!!,
                state = state,
                isFavorite = isFavorite,
                downloadState = downloadState,
                seasonDownloads = seasonDownloads,
                onDownloadMovie = viewModel::downloadMovie,
                onDownloadSeason = viewModel::downloadSeason,
                onDownloadEpisode = { ep -> viewModel.downloadEpisode(ep.seasonNumber, ep.episodeNumber, ep.name) },
                onPlay = onPlay,
                onToggleFavorite = viewModel::toggleFavorite,
                onSelectSeason = viewModel::selectSeason,
                onMediaClick = onMediaClick,
                onMarkEpisodeWatched = viewModel::markWatched,
                onMarkEpisodeUnwatched = viewModel::markUnwatched,
                onMarkMovieWatched = viewModel::markMovieWatched,
                onMarkMovieUnwatched = viewModel::markMovieUnwatched,
            )

            state.isLoading -> LoadingState(Modifier.fillMaxSize())

            state.errorMessage != null -> ErrorRetry(
                message = state.errorMessage!!,
                onRetry = viewModel::load,
                modifier = Modifier.fillMaxSize(),
            )

            else -> ErrorRetry(
                message = "Couldn't load details.",
                onRetry = viewModel::load,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Floating back button — always visible above the backdrop.
        BackButton(
            onBack = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
        )
    }
}

@Composable
private fun DetailsContent(
    details: MediaDetails,
    state: DetailsUiState,
    isFavorite: Boolean,
    downloadState: com.slickstream.core.model.Download?,
    seasonDownloads: Map<Int, com.slickstream.core.model.Download>,
    onDownloadMovie: () -> Unit,
    onDownloadSeason: () -> Unit,
    onDownloadEpisode: (Episode) -> Unit,
    onPlay: OnPlay,
    onToggleFavorite: () -> Unit,
    onSelectSeason: (Int) -> Unit,
    onMediaClick: (MediaItem) -> Unit,
    onMarkEpisodeWatched: (season: Int, episode: Int) -> Unit,
    onMarkEpisodeUnwatched: (season: Int, episode: Int) -> Unit,
    onMarkMovieWatched: () -> Unit,
    onMarkMovieUnwatched: () -> Unit,
) {
    val item = details.item

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        // --- Collapsing backdrop header with gradient scrim ---
        item("backdrop") {
            BackdropHeader(details = details)
        }

        // --- Title block + meta row ---
        item("meta") {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Brand.OnSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!details.tagline.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = details.tagline!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Brand.Cyan,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(10.dp))
                MetaRow(details = details, onlyCamAvailable = state.onlyCamAvailable)
            }
        }

        // --- Actions: Play + favourite heart (+ Mark watched for movies) ---
        item("actions") {
            val isTv = item.mediaType == MediaType.TV
            val firstSeason = state.selectedSeasonNumber ?: state.seasons.firstOrNull()?.seasonNumber
            val rt = state.resumeTarget
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlayButton(
                        // Resume the in-progress episode / next after the last finished (from the VM),
                        // not always S1E1.
                        label = rt?.label ?: "Play",
                        onClick = {
                            if (isTv) {
                                onPlay(item.mediaType, item.id, rt?.season ?: firstSeason ?: 1, rt?.episode ?: 1)
                            } else {
                                onPlay(item.mediaType, item.id, null, null)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    FavoriteHeart(
                        isFavorite = isFavorite,
                        onToggle = onToggleFavorite,
                    )
                    // Movies only — TV shows get a labeled "Download season" button in the Episodes
                    // header (next to the season it acts on) plus per-episode buttons on each row. The
                    // bare icon here gave zero feedback for shows and read as broken.
                    if (!isTv) {
                        Spacer(Modifier.width(8.dp))
                        DownloadAction(
                            state = downloadState,
                            isTv = false,
                            onClick = onDownloadMovie,
                        )
                    }
                }
                // Movies: an explicit Mark watched / unwatched toggle next to Play/Favourite.
                if (!isTv) {
                    Spacer(Modifier.height(12.dp))
                    MarkWatchedButton(
                        isWatched = state.isMovieWatched,
                        onToggle = {
                            if (state.isMovieWatched) onMarkMovieUnwatched() else onMarkMovieWatched()
                        },
                    )
                }
            }
        }

        // --- Overview ---
        if (item.overview.isNotBlank()) {
            item("overview") {
                Text(
                    text = item.overview,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Brand.OnSurfaceDim,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        // --- Genre chips ---
        if (details.genres.isNotEmpty()) {
            item("genres") {
                GenreChips(
                    genres = details.genres,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }

        // --- Cast row ---
        if (details.cast.isNotEmpty()) {
            item("cast-header") {
                SectionHeader(
                    title = "Cast",
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
            item("cast-row") {
                CastRow(cast = details.cast)
            }
        }

        // --- Seasons + episodes (TV only) ---
        if (item.mediaType == MediaType.TV && state.seasons.isNotEmpty()) {
            item("seasons-header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionHeader(title = "Episodes", modifier = Modifier.weight(1f))
                    SeasonDownloadButton(
                        episodes = state.episodes,
                        downloads = seasonDownloads,
                        onClick = onDownloadSeason,
                    )
                }
            }
            // Only show the season picker when there's an actual choice — a single-season show (a
            // limited series / miniseries) rendered one pointless chip labelled with the season's name.
            if (state.seasons.size > 1) {
                item("season-selector") {
                    SeasonSelector(
                        seasons = state.seasons,
                        selected = state.selectedSeasonNumber,
                        onSelect = onSelectSeason,
                    )
                }
            }

            when {
                state.isLoadingEpisodes -> item("episodes-loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                    ) { LoadingState(Modifier.fillMaxSize()) }
                }

                state.episodesError != null -> item("episodes-error") {
                    ErrorRetry(
                        message = state.episodesError,
                        onRetry = { state.selectedSeasonNumber?.let(onSelectSeason) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                    )
                }

                else -> items(
                    items = state.episodes,
                    key = { "ep-${it.seasonNumber}-${it.episodeNumber}" },
                ) { episode ->
                    val progress = state.episodeProgress[episode.episodeNumber] ?: 0f
                    EpisodeRow(
                        episode = episode,
                        progress = progress,
                        download = seasonDownloads[episode.episodeNumber],
                        onDownload = { onDownloadEpisode(episode) },
                        onPlay = {
                            onPlay(
                                item.mediaType,
                                item.id,
                                episode.seasonNumber,
                                episode.episodeNumber,
                            )
                        },
                        onToggleWatched = {
                            if (progress >= 0.92f) {
                                onMarkEpisodeUnwatched(episode.seasonNumber, episode.episodeNumber)
                            } else {
                                onMarkEpisodeWatched(episode.seasonNumber, episode.episodeNumber)
                            }
                        },
                    )
                }
            }
        }

        // --- More like this ---
        if (state.similar.isNotEmpty()) {
            item("similar") {
                MediaRow(
                    title = "More like this",
                    items = state.similar,
                    onItemClick = onMediaClick,
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
        }
    }
}

@Composable
private fun BackdropHeader(details: MediaDetails) {
    val item = details.item
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(item.backdropUrl ?: item.posterUrl)
                .size(1080, 675)
                .crossfade(true)
                .build(),
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .background(Brand.SurfaceVariant),
        )
        // Bottom-to-top gradient scrim so the title block reads cleanly over the image.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.55f to Brand.Background.copy(alpha = 0.35f),
                            1.0f to Brand.Background,
                        ),
                    ),
                ),
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DownloadAction(
    state: com.slickstream.core.model.Download?,
    isTv: Boolean,
    onClick: () -> Unit,
) {
    val status = state?.status
    val icon = if (status == com.slickstream.core.model.DownloadStatus.COMPLETED) Icons.Rounded.DownloadDone else Icons.Rounded.Download
    val tint = when (status) {
        com.slickstream.core.model.DownloadStatus.COMPLETED -> Color(0xFF22C55E)
        com.slickstream.core.model.DownloadStatus.DOWNLOADING, com.slickstream.core.model.DownloadStatus.QUEUED -> Brand.Cyan
        else -> Brand.OnSurface
    }
    val enabled = status == null || status == com.slickstream.core.model.DownloadStatus.FAILED
    androidx.compose.material3.IconButton(onClick = onClick, enabled = enabled) {
        Box(contentAlignment = Alignment.Center) {
            if (status == com.slickstream.core.model.DownloadStatus.DOWNLOADING) {
                androidx.compose.material3.CircularProgressIndicator(
                    progress = { state?.progress ?: 0f },
                    color = Brand.Cyan,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(34.dp),
                )
            }
            Icon(imageVector = icon, contentDescription = if (isTv) "Download season" else "Download", tint = tint)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MetaRow(details: MediaDetails, onlyCamAvailable: Boolean = false) {
    val item = details.item
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (onlyCamAvailable) com.slickstream.ui.components.CamBadge()
        RatingBadge(vote = item.voteAverage)
        item.year?.let { MetaText(it) }
        runtimeLabel(details)?.let { MetaText(it) }
        if (item.mediaType == MediaType.TV) {
            details.numberOfSeasons?.let { count ->
                MetaText(if (count == 1) "1 Season" else "$count Seasons")
            }
        }
        details.status?.takeIf { it.isNotBlank() }?.let { MetaText(it) }
    }
}

private fun runtimeLabel(details: MediaDetails): String? {
    val minutes = details.runtimeMinutes ?: return null
    if (minutes <= 0) return null
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

@Composable
private fun MetaText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = Brand.OnSurfaceDim,
    )
}

@Composable
private fun PlayButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Brand.Violet,
            contentColor = Color.White,
        ),
    ) {
        Icon(
            imageVector = Icons.Rounded.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun FavoriteHeart(
    isFavorite: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        onClick = onToggle,
        shape = CircleShape,
        color = Brand.SurfaceVariant,
        modifier = Modifier.size(50.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = if (isFavorite) "Remove from favourites" else "Add to favourites",
                tint = if (isFavorite) Brand.Error else Brand.OnSurface,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun GenreChips(
    genres: List<Genre>,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(genres, key = { it.id }) { genre ->
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(genre.name) },
                shape = RoundedCornerShape(10.dp),
                colors = AssistChipDefaults.assistChipColors(
                    disabledContainerColor = Brand.SurfaceVariant,
                    disabledLabelColor = Brand.OnSurface,
                ),
                border = null,
            )
        }
    }
}

@Composable
private fun CastRow(cast: List<CastMember>) {
    val context = LocalContext.current
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(cast, key = { it.id }) { member ->
            Column(
                modifier = Modifier.width(76.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(member.profileUrl)
                        .size(160, 160)
                        .crossfade(true)
                        .build(),
                    contentDescription = member.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Brand.SurfaceVariant),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = member.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Brand.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (member.character.isNotBlank()) {
                    Text(
                        text = member.character,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Brand.OnSurfaceDim,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SeasonSelector(
    seasons: List<Season>,
    selected: Int?,
    onSelect: (Int) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(seasons, key = { it.seasonNumber }) { season ->
            val isSelected = season.seasonNumber == selected
            Surface(
                onClick = { onSelect(season.seasonNumber) },
                shape = RoundedCornerShape(10.dp),
                color = if (isSelected) Brand.Violet else Brand.SurfaceVariant,
                contentColor = if (isSelected) Color.White else Brand.OnSurface,
            ) {
                Text(
                    text = season.name.ifBlank { "Season ${season.seasonNumber}" },
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private val EPISODE_MONTHS =
    arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "2024-11-28" -> "Nov 28, 2024". Null/blank/odd -> null. */
private fun formatEpisodeAirDate(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val parts = raw.split("-")
    if (parts.size != 3) return raw
    val month = parts[1].toIntOrNull() ?: return raw
    val day = parts[2].toIntOrNull() ?: return raw
    val mon = EPISODE_MONTHS.getOrNull(month - 1) ?: return raw
    return "$mon $day, ${parts[0]}"
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    progress: Float,
    download: com.slickstream.core.model.Download?,
    onDownload: () -> Unit,
    onPlay: () -> Unit,
    onToggleWatched: () -> Unit,
) {
    val context = LocalContext.current
    // Mirror PlaybackProgress.isFinished (>= 0.92f) so a fully-watched episode reads as watched.
    val isWatched = progress >= 0.92f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(episode.stillUrl)
                    .size(360, 202)
                    .crossfade(true)
                    .build(),
                contentDescription = episode.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(132.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brand.SurfaceVariant),
            )
            // Play affordance overlaid on the still.
            Box(
                modifier = Modifier
                    .matchPlayOverlaySize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    onClick = onPlay,
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.45f),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Play episode ${episode.episodeNumber}",
                        tint = Color.White,
                        modifier = Modifier.padding(6.dp).size(22.dp),
                    )
                }
            }
            // Watched check overlay in the top-end corner of the still.
            if (isWatched) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = Brand.Cyan,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(20.dp),
                )
            }
            // Thin resume/progress bar pinned to the bottom of the still. No bar at 0.
            if (progress > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .matchProgressBarWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp))
                        .background(Color.Black.copy(alpha = 0.33f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .fillMaxSize()
                            .background(Brand.Violet),
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${episode.episodeNumber}. ${episode.name}",
                style = MaterialTheme.typography.titleMedium,
                color = Brand.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            run {
                val date = formatEpisodeAirDate(episode.airDate)
                val mins = episode.runtimeMinutes?.takeIf { it > 0 }?.let { "${it}m" }
                val meta = listOfNotNull(date, mins).joinToString("  ·  ")
                if (meta.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Brand.OnSurfaceDim,
                    )
                }
            }
            if (episode.overview.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = episode.overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Brand.OnSurfaceDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Per-episode download — idle arrow / progress ring / green done. Gated on the SAME predicate
        // downloadSeason enqueues with (aired OR unknown date), so no downloadable episode lacks a button.
        if (episode.isDownloadable()) {
            Spacer(Modifier.width(8.dp))
            EpisodeDownloadIcon(
                download = download,
                episodeNumber = episode.episodeNumber,
                onClick = onDownload,
            )
        }
        // Mark watched / unwatched toggle — filled check when watched, outline when not.
        Spacer(Modifier.width(8.dp))
        Surface(
            onClick = onToggleWatched,
            shape = CircleShape,
            color = Color.Transparent,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isWatched) Icons.Rounded.CheckCircle else Icons.Outlined.CheckCircle,
                    contentDescription = if (isWatched) {
                        "Mark episode ${episode.episodeNumber} unwatched"
                    } else {
                        "Mark episode ${episode.episodeNumber} watched"
                    },
                    tint = if (isWatched) Brand.Cyan else Brand.OnSurfaceDim,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/** Per-episode download state button: idle ↓, queued/downloading progress ring, done green ✓,
 *  failed red retry. Tap starts (or retries after FAILED); no-op while active or done. */
@Composable
private fun EpisodeDownloadIcon(
    download: com.slickstream.core.model.Download?,
    episodeNumber: Int,
    onClick: () -> Unit,
) {
    val status = download?.status
    val active = status == com.slickstream.core.model.DownloadStatus.QUEUED ||
        status == com.slickstream.core.model.DownloadStatus.DOWNLOADING
    val done = status == com.slickstream.core.model.DownloadStatus.COMPLETED
    val failed = status == com.slickstream.core.model.DownloadStatus.FAILED
    Surface(
        onClick = { if (!active && !done) onClick() },
        shape = CircleShape,
        color = Color.Transparent,
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (status == com.slickstream.core.model.DownloadStatus.DOWNLOADING) {
                androidx.compose.material3.CircularProgressIndicator(
                    progress = { download?.progress ?: 0f },
                    color = Brand.Cyan,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(30.dp),
                )
            } else if (status == com.slickstream.core.model.DownloadStatus.QUEUED) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = Brand.Cyan.copy(alpha = 0.5f),
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(30.dp),
                )
            }
            Icon(
                imageVector = if (done) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                contentDescription = when {
                    done -> "Episode $episodeNumber downloaded"
                    active -> "Downloading episode $episodeNumber"
                    failed -> "Retry download of episode $episodeNumber"
                    else -> "Download episode $episodeNumber"
                },
                tint = when {
                    done -> Color(0xFF22C55E)
                    failed -> Brand.Error
                    active -> Brand.Cyan
                    else -> Brand.OnSurfaceDim
                },
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** "Download season" pill in the Episodes header — shows live aggregate state so a tap visibly DOES
 *  something ("Downloading 2/8", then "Downloaded"). Acts on the selected season's AIRED episodes. */
@Composable
private fun SeasonDownloadButton(
    episodes: List<Episode>,
    downloads: Map<Int, com.slickstream.core.model.Download>,
    onClick: () -> Unit,
) {
    // Same predicate downloadSeason enqueues with — the counter must cover exactly what a tap creates.
    val aired = episodes.filter { it.isDownloadable() }
    if (aired.isEmpty()) return
    val done = aired.count { downloads[it.episodeNumber]?.isComplete == true }
    val active = aired.any {
        val s = downloads[it.episodeNumber]?.status
        s == com.slickstream.core.model.DownloadStatus.QUEUED || s == com.slickstream.core.model.DownloadStatus.DOWNLOADING
    }
    val allDone = done == aired.size
    val label = when {
        allDone -> "Downloaded"
        active -> "Downloading $done/${aired.size}"
        done > 0 -> "Download rest"
        else -> "Download season"
    }
    Surface(
        onClick = { if (!active && !allDone) onClick() },
        shape = RoundedCornerShape(50),
        color = if (allDone) Brand.Surface else Brand.SurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (active) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = Brand.Cyan,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp),
                )
            } else {
                Icon(
                    imageVector = if (allDone) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                    contentDescription = null,
                    tint = if (allDone) Color(0xFF22C55E) else Brand.OnSurface,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    allDone -> Color(0xFF22C55E)
                    active -> Brand.Cyan
                    else -> Brand.OnSurface
                },
            )
        }
    }
}

/** Matches the still width so the progress bar spans exactly the thumbnail. */
private fun Modifier.matchProgressBarWidth(): Modifier = this.width(132.dp)

/** Movie-only "Mark watched" / "Mark unwatched" pill, sitting under the Play/Favourite row. */
@Composable
private fun MarkWatchedButton(
    isWatched: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(14.dp),
        color = Brand.SurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isWatched) Icons.Rounded.CheckCircle else Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = if (isWatched) Brand.Cyan else Brand.OnSurface,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (isWatched) "Watched" else "Mark watched",
                style = MaterialTheme.typography.labelLarge,
                color = Brand.OnSurface,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Matches the size of the episode still so the play overlay covers exactly it. */
private fun Modifier.matchPlayOverlaySize(): Modifier =
    this
        .width(132.dp)
        .aspectRatio(16f / 9f)

@Composable
private fun BackButton(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onBack,
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.45f),
        modifier = modifier.size(42.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
