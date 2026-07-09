package com.slickstream.tv.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.slickstream.core.model.Download
import com.slickstream.core.model.DownloadStatus
import com.slickstream.core.model.MediaType
import com.slickstream.feature.downloads.DownloadsViewModel
import com.slickstream.ui.theme.Brand

/**
 * Android TV Downloads. Reuses [DownloadsViewModel]. A focusable list: a completed item plays offline
 * (file://, no network); a Delete surface removes it. In-progress items show a live bar.
 */
@Composable
fun TvDownloadsScreen(
    onPlay: (MediaType, Int, Int?, Int?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(downloads.isEmpty()) {
        if (downloads.isNotEmpty()) repeat(12) {
            kotlinx.coroutines.delay(40)
            if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Brand.Background)
            .padding(start = 56.dp, end = 56.dp, top = 36.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(
            text = "Downloads",
            style = MaterialTheme.typography.headlineMedium,
            color = Brand.OnSurface,
        )

        if (downloads.isEmpty()) {
            Text(
                text = "No downloads yet. Open a movie or show and choose Download to save it for offline.",
                style = MaterialTheme.typography.titleMedium,
                color = Brand.OnSurfaceDim,
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.width(880.dp),
            ) {
                items(downloads, key = { it.key }) { d ->
                    TvDownloadRow(
                        download = d,
                        focusRequester = if (d.key == downloads.first().key) firstFocus else null,
                        onPlay = { onPlay(d.mediaType, d.mediaId, d.season, d.episode) },
                        onDelete = { viewModel.delete(d) },
                        onRetry = { viewModel.retry(d) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TvDownloadRow(
    download: Download,
    focusRequester: FocusRequester?,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
) {
    val d = download
    val failed = d.status == DownloadStatus.FAILED
    val shape = RoundedCornerShape(16.dp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(
            onClick = { if (d.isComplete) onPlay() else if (failed) onRetry() },
            shape = ClickableSurfaceDefaults.shape(shape = shape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Brand.Surface,
                focusedContainerColor = Brand.Violet,
                contentColor = Brand.OnSurface,
                focusedContentColor = Color.White,
            ),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(3.dp, Brand.Violet), shape = shape),
            ),
            scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.02f),
            modifier = Modifier
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .weight(1f)
                .height(108.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = d.posterUrl,
                    contentDescription = d.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.width(58.dp).height(84.dp).clip(RoundedCornerShape(8.dp)).background(Brand.SurfaceVariant),
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(d.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (d.subtitle.isNotBlank()) {
                        Text(d.subtitle, style = MaterialTheme.typography.bodyMedium, color = Brand.OnSurfaceDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(8.dp))
                    when (d.status) {
                        DownloadStatus.COMPLETED -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.DownloadDone, null, tint = Color(0xFF22C55E), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Downloaded${d.quality.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF22C55E))
                        }
                        DownloadStatus.FAILED -> Text("Failed — select to retry", style = MaterialTheme.typography.bodyMedium, color = Brand.Error)
                        DownloadStatus.QUEUED -> Text("Queued…", style = MaterialTheme.typography.bodyMedium, color = Brand.OnSurfaceDim)
                        else -> {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Brand.SurfaceVariant),
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxWidth(d.progress.coerceIn(0f, 1f)).fillMaxSize().background(Brand.Cyan),
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("Downloading · ${(d.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = Brand.OnSurfaceDim)
                        }
                    }
                }
                if (d.isComplete) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Play offline", tint = Brand.Cyan, modifier = Modifier.size(30.dp))
                }
            }
        }

        Surface(
            onClick = onDelete,
            shape = ClickableSurfaceDefaults.shape(shape = shape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Brand.Surface,
                focusedContainerColor = Brand.Error,
                contentColor = Brand.OnSurfaceDim,
                focusedContentColor = Color.White,
            ),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(3.dp, Brand.Error), shape = shape),
            ),
            scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.05f),
            modifier = Modifier.size(108.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Delete, contentDescription = "Delete", modifier = Modifier.size(28.dp))
            }
        }
    }
}
