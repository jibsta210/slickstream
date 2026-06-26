package com.slickstream.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.slickstream.ui.theme.Brand

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
