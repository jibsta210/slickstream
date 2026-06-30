package com.slickstream.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slickstream.ui.theme.Brand

/**
 * A compact pill showing a TMDB vote average with a star glyph.
 * Renders e.g. "★ 8.4". Intended to overlay poster/backdrop art, so it carries
 * its own translucent dark background for legibility.
 */
@Composable
fun RatingBadge(
    vote: Double,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(
                color = Color(0xCC000000),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = null,
            tint = Brand.Star,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = formatVote(vote),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
}

private fun formatVote(vote: Double): String {
    // One decimal place, no trailing ".0" noise for whole numbers.
    val rounded = (vote * 10).toInt() / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
}

/**
 * A small chip used to convey a stream quality / tag, e.g. "1080p", "4K", "WEB-DL".
 * Subtle violet-tinted surface with an outline accent.
 */
@Composable
fun QualityChip(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = Brand.Cyan,
        modifier = modifier
            .background(
                color = Brand.SurfaceVariant,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** A red "CAM" warning pill — a cinema-rip (CAM/TS/TELESYNC), i.e. terrible quality. Shown next to the
 *  quality chip on a source, and on the details screen when the best available release is a cam. */
@Composable
fun CamBadge(modifier: Modifier = Modifier) {
    Text(
        text = "CAM",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = modifier
            .background(color = Brand.Error, shape = RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
