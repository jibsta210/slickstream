package com.slickstream.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slickstream.ui.theme.Brand
import kotlin.math.roundToInt

/**
 * A torrent-style "chunk bar": a thin strip spanning the whole file (left = start, right = end) where
 * each cell shows how much of that slice is downloaded. Lets the viewer see the download filling in —
 * the contiguous head/read-ahead in front of the playhead, scattered moov/cues at the tail, etc.
 *
 * @param map per-bucket fill 0f..1f from [PlayerViewModel.pieceMap]; empty renders a flat track.
 * @param playheadFraction current playback position as 0f..1f of the file (a marker line).
 */
@Composable
fun PieceBar(
    map: FloatArray,
    playheadFraction: Float,
    modifier: Modifier = Modifier,
    barHeight: Dp = 6.dp,
    downloaded: Color = Brand.Cyan,
    empty: Color = Color.White.copy(alpha = 0.16f),
    playhead: Color = Brand.Violet,
) {
    Canvas(modifier.fillMaxWidth().height(barHeight)) {
        // Flat track underneath (also what shows before any pieces exist).
        drawRect(empty, topLeft = Offset.Zero, size = size)

        val n = map.size
        if (n > 0) {
            val cellW = size.width / n
            for (i in 0 until n) {
                val fill = map[i].coerceIn(0f, 1f)
                if (fill > 0f) {
                    drawRect(
                        color = downloaded.copy(alpha = 0.30f + 0.70f * fill),
                        topLeft = Offset(i * cellW, 0f),
                        size = Size(cellW + 0.5f, size.height),
                    )
                }
            }
        }

        // Playhead marker.
        val px = playheadFraction.coerceIn(0f, 1f) * size.width
        val w = 2.dp.toPx()
        drawRect(playhead, topLeft = Offset((px - w / 2f).coerceIn(0f, size.width - w), 0f), size = Size(w, size.height))
    }
}

/** Bucket count for the chunk bar — fine enough to read the fill pattern, cheap to recompute. */
const val PIECE_BAR_BUCKETS = 110

/**
 * The chunk bar plus a one-line info row: a health dot, live seeders/peers, download speed, and a
 * warning ("low buffer" / "stalled") when the download is at risk of out-running playback.
 */
@Composable
fun PieceBarPanel(
    map: FloatArray,
    playheadFraction: Float,
    stats: StreamStats?,
    modifier: Modifier = Modifier,
) {
    val health = healthOf(map, playheadFraction, stats)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(
                Modifier.size(8.dp).clip(CircleShape).background(health.color),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = buildString {
                    if (stats != null) {
                        append("${stats.seeders} seeders")
                        if (stats.peers > stats.seeders) append(" · ${stats.peers} peers")
                        append(" · ${formatRate(stats.downloadRateBytes)}")
                        if (stats.progress >= 0.999f) append(" · ✓ fully downloaded")
                        else append(" · ${(stats.progress * 100).roundToInt()}% downloaded")
                        // Once the current file is in, surface the next-episode precache so the user can
                        // see it warming (instead of the bar just reading a static 100%).
                        if (stats.precaching) append(" · caching next episode")
                    } else {
                        append("Downloading…")
                    }
                    health.warning?.let { append(" · "); append(it) }
                },
                color = Color.White.copy(alpha = 0.85f),
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
            )
        }
        PieceBar(map, playheadFraction)
    }
}

private class BarHealth(val color: Color, val warning: String?)

/**
 * Green = a comfortable downloaded lead ahead of the playhead. Amber = the lead is thin (will buffer
 * soon if the rate dips). Red = stalled (0 B/s and not complete).
 */
private fun healthOf(map: FloatArray, playheadFraction: Float, stats: StreamStats?): BarHealth {
    if (stats == null || map.isEmpty()) return BarHealth(Brand.Cyan, null)
    if (stats.downloadRateBytes == 0 && stats.progress < 0.99f) return BarHealth(Brand.Error, "stalled")
    val ph = (playheadFraction * map.size).toInt().coerceIn(0, map.size - 1)
    var lead = 0
    var i = ph
    while (i < map.size && map[i] >= 0.85f) { lead++; i++ }
    val leadFraction = lead.toFloat() / map.size
    return when {
        leadFraction < 0.02f -> BarHealth(Brand.Star, "low buffer")
        else -> BarHealth(Brand.Cyan, null)
    }
}

private fun formatRate(bytesPerSec: Int): String = when {
    bytesPerSec <= 0 -> "0 KB/s"
    bytesPerSec >= 1024 * 1024 -> "%.1f MB/s".format(bytesPerSec / (1024f * 1024f))
    else -> "${bytesPerSec / 1024} KB/s"
}
