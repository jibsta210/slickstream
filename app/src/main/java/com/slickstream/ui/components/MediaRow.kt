package com.slickstream.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.slickstream.core.model.MediaItem

/**
 * A titled, horizontally-scrolling carousel of catalog items. Defaults to portrait
 * [PosterCard]s; pass [wide] = true for landscape [BackdropCard]s (e.g. "Continue Watching").
 *
 * @param progressFor optional resume-progress lookup (0f..1f) per item; null hides the bar.
 */
@Composable
fun MediaRow(
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
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionHeader(title = title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(
                items = items,
                key = { "${it.mediaType.name}-${it.id}" },
            ) { item ->
                val progress = progressFor?.invoke(item)
                if (wide) {
                    BackdropCard(
                        item = item,
                        onClick = onItemClick,
                        progress = progress,
                    )
                } else {
                    PosterCard(
                        item = item,
                        onClick = onItemClick,
                        progress = progress,
                    )
                }
            }
        }
    }
}
