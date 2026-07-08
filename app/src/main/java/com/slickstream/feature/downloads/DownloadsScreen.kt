package com.slickstream.feature.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.slickstream.core.model.Download
import com.slickstream.core.model.DownloadStatus
import com.slickstream.core.model.MediaType
import com.slickstream.ui.theme.Brand

/**
 * Offline downloads: in-progress with a live progress bar, completed with a Play (offline) button,
 * everything deletable. [onPlay] launches the player, which uses the local file (no network).
 */
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onPlay: (type: MediaType, id: Int, season: Int?, episode: Int?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()

    Column(
        modifier = modifier.fillMaxSize().background(Brand.Background),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Brand.OnSurface)
            }
            Spacer(Modifier.width(4.dp))
            Text("Downloads", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Brand.OnSurface)
        }

        if (downloads.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No downloads yet.\nTap Download on a movie or show to save it for offline.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Brand.OnSurfaceDim,
                    modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(downloads, key = { it.key }) { d ->
                    DownloadRow(
                        download = d,
                        onPlay = { onPlay(d.mediaType, d.mediaId, d.season, d.episode) },
                        onDelete = { viewModel.delete(d) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(download: Download, onPlay: () -> Unit, onDelete: () -> Unit) {
    val d = download
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Brand.Surface)
            .then(if (d.isComplete) Modifier.clickable(onClick = onPlay) else Modifier)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = d.posterUrl,
            contentDescription = d.title,
            modifier = Modifier.width(56.dp).height(84.dp).clip(RoundedCornerShape(8.dp)).background(Brand.SurfaceVariant),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(d.title, color = Brand.OnSurface, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (d.subtitle.isNotBlank()) {
                Text(d.subtitle, color = Brand.OnSurfaceDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            when (d.status) {
                DownloadStatus.COMPLETED -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.DownloadDone, null, tint = Color(0xFF22C55E), modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Downloaded${d.quality.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}${sizeSuffix(d.totalBytes)}", color = Color(0xFF22C55E), fontSize = 12.sp)
                }
                DownloadStatus.FAILED -> Text("Failed — tap delete and retry", color = Brand.Error, fontSize = 12.sp)
                DownloadStatus.QUEUED -> Text("Queued…", color = Brand.OnSurfaceDim, fontSize = 12.sp)
                else -> {
                    LinearProgressIndicator(
                        progress = { d.progress },
                        color = Brand.Cyan,
                        trackColor = Brand.SurfaceVariant,
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("Downloading · ${(d.progress * 100).toInt()}%${sizeSuffix(d.totalBytes)}", color = Brand.OnSurfaceDim, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        if (d.isComplete) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Play offline", tint = Brand.Cyan, modifier = Modifier.size(28.dp).clickable(onClick = onPlay))
            Spacer(Modifier.width(4.dp))
        } else if (d.status == DownloadStatus.DOWNLOADING) {
            CircularProgressIndicator(color = Brand.Cyan, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Brand.OnSurfaceDim, modifier = Modifier.size(20.dp))
        }
    }
}

private fun sizeSuffix(bytes: Long): String {
    if (bytes <= 0) return ""
    val gb = bytes / (1024.0 * 1024 * 1024)
    return if (gb >= 1.0) String.format(" · %.1f GB", gb) else String.format(" · %.0f MB", bytes / (1024.0 * 1024))
}
