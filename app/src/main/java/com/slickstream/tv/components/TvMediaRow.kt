package com.slickstream.tv.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.slickstream.core.model.MediaItem
import com.slickstream.ui.theme.Brand

/**
 * A titled, horizontally-scrolling row of [TvPosterCard]s. The row is fully D-pad navigable via
 * [LazyRow]; focus naturally brings cards into view. Optional [progressFor] supplies a resume
 * percentage per item (used for the "Continue Watching" row).
 */
@Composable
fun TvMediaRow(
    title: String,
    items: List<MediaItem>,
    onItemClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    progressFor: ((MediaItem) -> Float?)? = null,
) {
    if (items.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Brand.OnSurface,
            modifier = Modifier.padding(start = 48.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
        ) {
            items(
                items,
                key = { "${it.mediaType.name}-${it.id}" },
                contentType = { if (wide) "poster-wide" else "poster" },
            ) { item ->
                TvPosterCard(
                    item = item,
                    onClick = onItemClick,
                    wide = wide,
                    progress = progressFor?.invoke(item),
                )
            }
        }
    }
}
