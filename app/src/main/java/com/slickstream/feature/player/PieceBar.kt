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
import com.slickstream.core.common.formatRate
import com.slickstream.core.model.StartupBlocker
import com.slickstream.ui.theme.Brand
import kotlin.math.roundToInt

/**
 * A torrent-style "chunk bar": a thin strip spanning the whole file (left = start, right = end) where
 * each cell is coloured by how much of that slice is on disk. DISCRETE colours, not brightness — a cell
 * is teal ONLY when it is 100% present, so a partially-downloaded slice (a HOLE that can stall playback)
 * shows as an obvious red/purple cell instead of masquerading as "solid teal" the way a faint-alpha
 * gradient did. Makes a scatter gap right at the playhead visible at a glance:
 *   blank = nothing yet · red = <25% · purple = partial (25–99%) · teal = fully downloaded.
 *
 * @param map per-bucket fill 0f..1f from [PlayerViewModel.pieceMap]; empty renders a flat track.
 * @param playheadFraction current playback position as 0f..1f of the file (a white marker line).
 */
@Composable
fun PieceBar(
    map: FloatArray,
    playheadFraction: Float,
    modifier: Modifier = Modifier,
    barHeight: Dp = 6.dp,
    done: Color = Brand.Cyan,           // teal — cell 100% present, safe to play through
    partial: Color = Brand.Violet,      // purple — 25–99% present (a hole lives in this slice)
    low: Color = Brand.Error,           // red — <25% present, barely started
    empty: Color = Color.White.copy(alpha = 0.16f),
    playhead: Color = Color.White,      // white line — visible over ANY cell colour
) {
    Canvas(modifier.fillMaxWidth().height(barHeight)) {
        // Flat track underneath (also what shows for a "nothing yet" cell).
        drawRect(empty, topLeft = Offset.Zero, size = size)

        val n = map.size
        if (n > 0) {
            val cellW = size.width / n
            for (i in 0 until n) {
                val fill = map[i].coerceIn(0f, 1f)
                // Discrete bands so partial ≠ full at a glance. "Done" requires ~every piece in the slice
                // (>=0.999) — a single missing piece is enough to stall, so it must NOT read as teal.
                val color = when {
                    fill >= 0.999f -> done
                    fill >= 0.25f -> partial
                    fill > 0.02f -> low
                    else -> null            // leave the empty track showing
                }
                if (color != null) {
                    drawRect(
                        color = color,
                        topLeft = Offset(i * cellW, 0f),
                        size = Size(cellW + 0.5f, size.height),
                    )
                }
            }
        }

        // Playhead marker — white so it stands out against teal/purple/red cells alike.
        val px = playheadFraction.coerceIn(0f, 1f) * size.width
        val w = 2.dp.toPx()
        drawRect(playhead, topLeft = Offset((px - w / 2f).coerceIn(0f, size.width - w), 0f), size = Size(w, size.height))
    }
}

/** Bucket count for the chunk bar — fine enough to read the fill pattern, cheap to recompute. */
const val PIECE_BAR_BUCKETS = 110

/**
 * When the chunk bar belongs on screen. Pure so the rule can be tested without a composition.
 *
 * The bar exists for ONE job: telling a real stall from a false one. Two ways that job was being
 * failed, both of them "the bar shows nothing":
 *
 *  - [stalled] (a MID-PLAYBACK rebuffer) used to be missing from this rule, so the bar rode with the
 *    transport only. The player's uiState stays Playing through a rebuffer and the transport auto-hides
 *    after 5 s, so the picture froze, the "Buffering… · 5.0 MB/s" badge came up, and the one surface
 *    that could show WHERE those megabytes were landing was not drawn at all. That gap only became the
 *    common case once the exact-moov gate and the mkv preference cut startup to a second or two: the
 *    wait moved out of the startup overlay (where the bar is forced up) into mid-playback.
 *
 *  - [torrentBacked] false is a DIRECT (Real-Debrid / file-server / offline) stream. There is no swarm
 *    and no piece map behind it, so the panel could only ever render the word "Downloading…" over a
 *    flat empty track — a bar that reads as "nothing has downloaded" for a stream that is playing fine.
 *    A diagnostic that reports an empty file for a healthy stream is worse than no diagnostic.
 */
fun shouldShowChunkBar(
    torrentBacked: Boolean,
    starting: Boolean,
    stalled: Boolean,
    controlsShown: Boolean,
): Boolean = torrentBacked && (starting || stalled || controlsShown)

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
                        // Cached reopen / resume: libtorrent is hash-verifying on-disk data. Rate + seeders
                        // read 0 here (no network), so DON'T show "0 seeders · 0 KB/s · stalled" — that's the
                        // false alarm that read as a dead swarm. Show the real activity instead.
                        if (stats.isChecking) {
                            append("Checking cached data… ${(stats.progress * 100).roundToInt()}%")
                        } else {
                            append("${stats.seeders} seeders")
                            if (stats.peers > stats.seeders) append(" · ${stats.peers} peers")
                            // The PAYLOAD rate — bytes that actually become file. The wire rate that
                            // used to be printed here also counts protocol overhead, hash-failed
                            // re-fetches and duplicate blocks the swarm discards, which is why
                            // "7.7 MB/s" and "60% of 1.9 GB after 3 minutes" could both be true and
                            // still look like a contradiction. Now speed x time ~= delta-progress,
                            // to within the waste figure printed below.
                            append(" · ${formatRate(stats.payloadRateBytes)}")
                            if (stats.progress >= 0.999f) append(" · ✓ fully downloaded")
                            else append(" · ${(stats.progress * 100).roundToInt()}% downloaded")
                            // Real bandwidth this bounded link paid for and threw away. Shown only when
                            // it is big enough to matter, because it is not cosmetic: those bytes come
                            // out of the read-ahead window and are a direct cause of mid-stream
                            // rebuffering. Better on the diagnostic line than hidden inside the speed.
                            stats.wastedPercent?.let { append(" · $it% wasted") }
                            // Once the current file is in, surface the next-episode precache so the user can
                            // see it warming (instead of the bar just reading a static 100%). Two states,
                            // not one: "is it warming" and "is it warm" are different questions, and the
                            // second is the one that predicts whether "Up next" starts instantly.
                            if (stats.nextEpisodeReady) append(" · next episode ready")
                            else if (stats.precaching) append(" · caching next episode")
                            health.warning?.let { append(" · "); append(it) }
                        }
                    } else {
                        append("Downloading…")
                    }
                },
                color = Color.White.copy(alpha = 0.85f),
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
            )
        }
        PieceBar(map, playheadFraction)
        // Legend — makes the discrete colours self-explanatory while diagnosing a stall.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendDot(Brand.Error, "<25%")
            LegendDot(Brand.Violet, "partial")
            LegendDot(Brand.Cyan, "done")
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, color = Color.White.copy(alpha = 0.70f), fontSize = 10.sp)
    }
}

private class BarHealth(val color: Color, val warning: String?)

/** A downloaded run ahead of the playhead worth at least this many seconds of video is comfortable.
 *  Measured in SECONDS, not in fraction-of-file: the lead that protects playback is time, and a
 *  fraction-of-file threshold silently scales with the release size — the same 4% is 4 minutes of a
 *  2 h movie but 400 MB of download, which is why a genuinely healthy head read as "low buffer". */
private const val HEALTHY_LEAD_SECONDS = 30.0

/** Fallback only, for the window before the media duration is known (nothing has prepared yet). */
private const val HEALTHY_LEAD_FRACTION = 0.02f

/**
 * Green = a comfortable downloaded lead ahead of the playhead. Amber = the lead is thin (will buffer
 * soon if the rate dips). Red = stalled (0 B/s and not complete).
 *
 * While STARTING UP this defers entirely to the engine's readiness gate ([StreamStats.startupBlocker]).
 * The bar and the gate must never describe the file differently: the reported bug was three or four
 * solid teal cells at the head — a head the gate had already accepted — sitting under the words "low
 * buffer", because this function was inferring a verdict from the fill pattern instead of asking.
 *
 * Every rate test below reads [StreamStats.downloadRateBytes], the WIRE rate — on purpose, and unlike
 * the line above the bar, which prints the payload rate. "Stalled" is a claim about the LINK: a swarm
 * that is moving bytes we end up discarding is being wasteful, not dead, and must not be labelled with
 * the red dot that means "this source is finished, switch away".
 */
private fun healthOf(map: FloatArray, playheadFraction: Float, stats: StreamStats?): BarHealth {
    if (stats == null || map.isEmpty()) return BarHealth(Brand.Cyan, null)
    // Verifying cached data (resume) — rate 0 is expected, NOT a stall. Neutral, no warning.
    if (stats.isChecking) return BarHealth(Brand.Cyan, null)
    // Whole file on disk: nothing left to be short of, whatever the rate reads.
    if (stats.progress >= 0.999f) return BarHealth(Brand.Cyan, null)

    // STARTUP — the gate owns the verdict, and names the one thing it is missing.
    when (stats.startupBlocker) {
        StartupBlocker.MOOV_INDEX ->
            // The head IS buffered (that is why the bar looks teal). Say what is actually outstanding.
            return BarHealth(Brand.Star, "fetching MP4 index")
        StartupBlocker.CONTAINER_HEADER, StartupBlocker.PLAYHEAD -> {
            if (stats.downloadRateBytes == 0 && stats.progress <= 0.01f) {
                return BarHealth(Brand.Star, "connecting…")
            }
            if (stats.downloadRateBytes == 0) return BarHealth(Brand.Error, "stalled")
            return BarHealth(
                Brand.Star,
                if (stats.startupBlocker == StartupBlocker.PLAYHEAD) "buffering at resume point"
                else "buffering start",
            )
        }
        StartupBlocker.NONE -> Unit   // gate is open — fall through to the playback lead check
    }

    // PLAYBACK — the downloaded LEAD ahead of the playhead is what protects the stream, so judge on it
    // FIRST. Under the concentrated moving window the download rate legitimately drops to 0 once the
    // window ahead is full (nothing left to fetch until you watch further), so a big lead with 0 B/s is
    // HEALTHY, not "stalled". Only a THIN lead makes the rate matter.
    val ph = (playheadFraction * map.size).toInt().coerceIn(0, map.size - 1)
    var lead = 0
    var i = ph
    while (i < map.size && map[i] >= 0.85f) { lead++; i++ }
    val leadFraction = lead.toFloat() / map.size
    val comfortable = if (stats.durationMs > 0L) {
        // A cell is (duration / cells) of video, so the lead converts straight to seconds.
        leadFraction * (stats.durationMs / 1000.0) >= HEALTHY_LEAD_SECONDS
    } else {
        leadFraction >= HEALTHY_LEAD_FRACTION
    }
    if (comfortable) return BarHealth(Brand.Cyan, null)
    // Thin lead — now the rate tells us whether we're recovering or stuck.
    if (stats.downloadRateBytes == 0 && stats.progress <= 0.01f) return BarHealth(Brand.Star, "connecting…")
    if (stats.downloadRateBytes == 0) return BarHealth(Brand.Error, "stalled")
    return BarHealth(Brand.Star, "low buffer")
}

