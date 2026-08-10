package com.slickstream.tv.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.slickstream.core.model.MediaItem
import com.slickstream.ui.theme.Brand

/**
 * A titled, horizontally-scrolling row of [TvPosterCard]s. The row is fully D-pad navigable via
 * [LazyRow]; focus naturally brings cards into view. Optional [progressFor] supplies a resume
 * percentage per item (used for the "Continue Watching" row).
 *
 * ## Re-entry
 * Pass the screen's [focusTicket] and this row restores focus after a Details round trip: it finds
 * the recorded tile by key, makes sure it is composed, and asks for focus until the tile itself
 * confirms it has it. The LazyRow's own [rememberLazyListState] is saveable, so the horizontal
 * offset comes back on its own — the row therefore only scrolls when the target is genuinely off
 * screen, which is what keeps "exactly where I was" exact.
 *
 * Both parameters default to null/[title], so callers that do not opt in (e.g. the details screen's
 * "More Like This") behave exactly as before.
 */
@Composable
fun TvMediaRow(
    title: String,
    items: List<MediaItem>,
    onItemClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    progressFor: ((MediaItem) -> Float?)? = null,
    focusTicket: TvFocusTicket? = null,
    rowKey: String = title,
) {
    if (items.isEmpty()) return
    val state = rememberLazyListState()

    // Matched by item KEY, never by index: Continue Watching reorders itself while you are away, and
    // an index ticket would re-focus whatever slid into that slot.
    val pendingKey = focusTicket?.pendingItemFor(rowKey).orEmpty()
    val restoreIndex = remember(pendingKey, items) {
        if (pendingKey.isEmpty()) -1 else items.indexOfFirst { tvItemKey(it) == pendingKey }
    }
    // Fallback target for a restore that cannot land (see below). Item 0 of the row we were left
    // through is always composed, so it is the one anchor guaranteed to be attachable.
    val firstAnchor = remember { TvFocusAnchor() }

    LaunchedEffect(restoreIndex) {
        // claim() is one-shot for the whole screen: without it, scrolling this row out of the
        // LazyColumn and back would re-run the restore and yank focus off wherever the user had
        // moved to.
        if (restoreIndex < 0 || focusTicket == null || !focusTicket.claim()) return@LaunchedEffect
        state.bringItemIntoComposition(restoreIndex)
        if (focusTicket.anchor.requestUntilFocused()) return@LaunchedEffect
        // NEVER settle with nothing focused. claim() is spent whether or not the restore lands, so
        // without this a miss (the tile was recycled out, the row reloaded shorter, the list changed
        // under us) leaves the screen with NO focus at all and the remote feeling dead until the user
        // guesses a direction. Landing on the row's first tile is wrong-but-recoverable; landing
        // nowhere is not. Grid screens already fall back this way (TvCategory/TvFavorites).
        firstAnchor.requestUntilFocused()
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Brand.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
        LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            // Generous vertical padding so the focused card's scale + 3dp ring + glow aren't clipped
            // by the LazyRow bounds (a cut-off focus ring reads as "unclear what you're on").
            // Horizontal is small — the screen-wide overscan inset in TvApp supplies the safe margin.
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 22.dp),
        ) {
            itemsIndexed(
                items,
                key = { _, it -> tvItemKey(it) },
                contentType = { _, _ -> if (wide) "poster-wide" else "poster" },
            ) { index, item ->
                TvPosterCard(
                    item = item,
                    onClick = onItemClick,
                    wide = wide,
                    progress = progressFor?.invoke(item),
                    focusAnchor = when {
                        index == restoreIndex -> focusTicket?.anchor
                        // Only worn while a restore is actually pending for THIS row, so a row with no
                        // ticket never carries a stray anchor.
                        index == 0 && restoreIndex >= 0 -> firstAnchor
                        else -> null
                    },
                )
            }
        }
    }
}
