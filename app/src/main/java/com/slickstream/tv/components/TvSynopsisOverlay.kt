package com.slickstream.tv.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.slickstream.ui.theme.Brand
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-text reader for a long synopsis on TV — a scrim + centred card over whatever screen opened it.
 *
 * THE BUG IT FIXES: on the common 960x540dp TV viewport the details hero can only afford 2 lines of
 * overview (see the vertical-budget comments in TvDetailsScreen) and an episode card only 2 more, so a
 * description simply stopped mid-sentence with no way whatsoever to read the rest.
 *
 * WHY THIS IS NOT A [androidx.compose.ui.window.Dialog] (unlike [TvConfirmDialog]): a Dialog is a second
 * window with its OWN focus tree. TvConfirmDialog gets away with it because both of its buttons dismiss
 * the whole screen, so nothing has to be re-focused afterwards. Here we must hand focus back to the exact
 * pill that opened the reader, and Dialog dismissal on TV routinely lands focus nowhere at all — the
 * "dead remote" bug. Rendering as a plain sibling Box keeps ONE focus tree, which is what every other TV
 * panel in this app does (SourcesPanel / SubtitlesPanel / AudioPanel are plain composables inside the
 * player's root Box, not Dialogs).
 *
 * WHY A CENTRED CARD AND NOT A SIDE PANEL: the 420dp side panel is a LIST idiom (short rows). Long-form
 * prose at 10 feet wants ~50-60 characters per line; a ~690dp card with a 140dp poster leaves a ~470dp
 * text column ≈ 52 chars at TV bodyLarge (16/24sp). A 420dp panel gives ~40 and turns a 450-character
 * overview into a scroll marathon. The card also keeps the cinematic backdrop visible behind the scrim.
 *
 * D-PAD: exactly two focus targets — the scrolling body and the Close pill. UP/DOWN scroll the body;
 * DOWN is only swallowed while there is somewhere left to scroll, so at the bottom it falls through to
 * Close. LEFT/RIGHT are consumed on both nodes: nothing behind the scrim (the episode LazyRow, the hero
 * buttons) may take focus while the reader is up, or the user ends up driving an invisible screen.
 * OK and BACK both close. Read the key handler below before adding a THIRD control to this card.
 *
 * @param meta optional cyan sub-heading ("2024  ·  148 min  ·  Sci-Fi · Drama", or an episode air date).
 * @param cardWidth derived by the caller from ITS BoxWithConstraints — the TV viewport is small and
 *   every other size on these screens is viewport-derived rather than hardcoded.
 * @param cardMaxHeight likewise; it is what makes the body scroll instead of the card growing.
 * @param imageWide the artwork is a 16:9 episode still rather than a 2:3 poster.
 */
@Composable
fun TvSynopsisOverlay(
    title: String,
    meta: String?,
    body: String,
    imageUrl: String?,
    cardWidth: Dp,
    cardMaxHeight: Dp,
    onClose: () -> Unit,
    imageWide: Boolean = false,
) {
    // Newest registered callback wins, so this fires before the nav host's popBackStack(): the first
    // Back closes the reader, a second Back leaves the details screen.
    BackHandler(enabled = true) { onClose() }

    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val bodyFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    val bodyInteraction = remember { MutableInteractionSource() }
    // Kept as State (not delegated): the focus outline + scroll rail read it in the DRAW phase, so a
    // D-pad focus change repaints this node without recomposing the card (the TvPosterCard rule).
    val bodyFocused = bodyInteraction.collectIsFocusedAsState()

    // A single requestFocus() before the node attaches is silently dropped — retry past layout. Same
    // idiom as HeroBlock's landing-focus loop and TvStreamPanel's.
    LaunchedEffect(Unit) {
        repeat(20) {
            delay(40)
            if (runCatching { bodyFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    val imgW = if (imageWide) 240.dp else 140.dp
    val imgH = if (imageWide) 135.dp else 210.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            colors = SurfaceDefaults.colors(containerColor = Brand.Surface),
            modifier = Modifier
                .width(cardWidth)
                // Load-bearing: the body below uses weight(1f, fill = false), which only bounds (and
                // therefore only scrolls) when the Column has a bounded max height. Drop this and the
                // card grows off the bottom of the screen instead of scrolling.
                .heightIn(max = cardMaxHeight),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 26.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (imageUrl != null) {
                    val context = LocalContext.current
                    // Hard decode ceiling, as everywhere else — a 4K panel must not decode the full-res
                    // TMDB bitmap for a 140dp thumbnail.
                    val request = remember(imageUrl, imageWide) {
                        ImageRequest.Builder(context)
                            .data(imageUrl)
                            .size(if (imageWide) 640 else 300, if (imageWide) 360 else 450)
                            .build()
                    }
                    AsyncImage(
                        model = request,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(imgW)
                            .height(imgH)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Brand.SurfaceVariant),
                    )
                }

                Column(
                    // weight, not fillMaxWidth: the text column takes whatever the fixed-width artwork
                    // leaves, instead of claiming the whole row and pushing itself off the card.
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    meta?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelLarge,
                            color = Brand.Cyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Brand.Violet.copy(alpha = 0.6f)),
                    )

                    Box(
                        modifier = Modifier
                            // fill = false so a SHORT synopsis renders a short card instead of a
                            // mostly-empty full-height one.
                            .weight(1f, fill = false)
                            .fillMaxWidth()
                            .focusRequester(bodyFocus)
                            // DOWN out of the body goes to Close and nowhere else. Geometric search
                            // would probably find it anyway, but "probably" is how TV focus bugs start.
                            .focusProperties { down = closeFocus }
                            .focusable(interactionSource = bodyInteraction)
                            // Compose Foundation 1.7.6 does NOT scroll a verticalScroll container on
                            // arrow keys (arrows are consumed by focus traversal), so the scrolling is
                            // driven by hand here. Do not "simplify" this away — the panel looks frozen.
                            //
                            // NOTE FOR FUTURE EDITS: LEFT/RIGHT are swallowed unconditionally so nothing
                            // behind the scrim can steal focus. If a THIRD control is ever added beside
                            // Close, that consumption has to be relaxed or the new control is unreachable.
                            .onKeyEvent { event ->
                                val isDown = event.key == Key.DirectionDown
                                val isUp = event.key == Key.DirectionUp
                                val isSideways =
                                    event.key == Key.DirectionLeft || event.key == Key.DirectionRight
                                val isCenter =
                                    event.key == Key.DirectionCenter || event.key == Key.Enter
                                // A page is most of a viewport, not a fixed dp step: on a tall card the
                                // reader would otherwise crawl three lines at a time.
                                val page = (scroll.viewportSize * 0.8f).coerceAtLeast(120f)
                                when {
                                    // Only swallowed while there IS somewhere to scroll; at the bottom
                                    // it falls through to focus traversal -> Close.
                                    isDown && scroll.canScrollForward -> {
                                        if (event.type == KeyEventType.KeyDown) {
                                            scope.launch { scroll.animateScrollBy(page) }
                                        }
                                        true
                                    }
                                    // UP is always consumed: there is nothing above the body inside the
                                    // card, and letting it escape hands focus to the hero behind the scrim.
                                    isUp -> {
                                        if (event.type == KeyEventType.KeyDown && scroll.canScrollBackward) {
                                            scope.launch { scroll.animateScrollBy(-page) }
                                        }
                                        true
                                    }
                                    isSideways -> true
                                    isCenter -> {
                                        if (event.type == KeyEventType.KeyDown) onClose()
                                        true
                                    }
                                    else -> false
                                }
                            }
                            // Focus outline + scroll rail, both DRAW phase only (state read inside the
                            // lambda) so neither focus nor scrolling recomposes the card.
                            .drawWithContent {
                                drawContent()
                                val focused = bodyFocused.value
                                if (focused) {
                                    val stroke = 2.dp.toPx()
                                    drawRoundRect(
                                        color = Brand.Violet,
                                        topLeft = Offset(-6.dp.toPx(), -4.dp.toPx()),
                                        size = Size(
                                            size.width + 12.dp.toPx(),
                                            size.height + 8.dp.toPx(),
                                        ),
                                        cornerRadius = CornerRadius(10.dp.toPx()),
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
                                    )
                                }
                                val max = scroll.maxValue
                                if (max > 0 && max != Int.MAX_VALUE) {
                                    val railW = 4.dp.toPx()
                                    val x = size.width - railW
                                    val viewport = size.height
                                    // Thumb length is the fraction of the whole text on screen; its
                                    // travel is the scroll fraction. Classic proportional scrollbar.
                                    val thumbH = (viewport * (viewport / (viewport + max)))
                                        .coerceAtLeast(24.dp.toPx())
                                        .coerceAtMost(viewport)
                                    val t = (scroll.value.toFloat() / max).coerceIn(0f, 1f)
                                    drawRoundRect(
                                        color = Brand.Violet.copy(alpha = 0.18f),
                                        topLeft = Offset(x, 0f),
                                        size = Size(railW, viewport),
                                        cornerRadius = CornerRadius(railW / 2f),
                                    )
                                    drawRoundRect(
                                        color = if (focused) Brand.Violet else Brand.VioletDim,
                                        topLeft = Offset(x, (viewport - thumbH) * t),
                                        size = Size(railW, thumbH),
                                        cornerRadius = CornerRadius(railW / 2f),
                                    )
                                }
                            },
                    ) {
                        Text(
                            text = body,
                            // 28sp leading (vs the theme's 24sp) — long-form prose at 10 feet needs the
                            // extra air to stay trackable line to line.
                            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp),
                            color = Brand.OnSurface,
                            modifier = Modifier
                                .verticalScroll(scroll)
                                // Keep the glyphs clear of the scroll rail.
                                .padding(end = 16.dp),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Only claim the arrows do something when they actually do. maxValue changes on
                        // LAYOUT, not on every scroll tick, so reading it here is not a per-frame
                        // recomposition. The Int.MAX_VALUE guard matters: ScrollState starts at that
                        // sentinel and only gets its real value once the column is MEASURED, and
                        // composition runs first — so a bare `> 0` shows the hint for one frame on every
                        // open (even for a short synopsis that can't scroll), then drops it, visibly
                        // reflowing the footer. Same guard the scroll rail above already uses.
                        if (scroll.maxValue > 0 && scroll.maxValue != Int.MAX_VALUE) {
                            Text(
                                text = "▲ ▼  Scroll",
                                style = MaterialTheme.typography.labelMedium,
                                color = Brand.Cyan,
                                maxLines = 1,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        CloseChip(
                            onClose = onClose,
                            closeFocus = closeFocus,
                            bodyFocus = bodyFocus,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The reader's only other focus target. UP returns to the body; DOWN/LEFT/RIGHT are consumed so focus
 * cannot leak to the screen sitting behind the scrim. OK is handled by the Surface itself.
 */
@Composable
private fun CloseChip(
    onClose: () -> Unit,
    closeFocus: FocusRequester,
    bodyFocus: FocusRequester,
) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClose,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Brand.SurfaceVariant,
            focusedContainerColor = Brand.Violet,
            contentColor = Brand.OnSurface,
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(3.dp, Color.White),
                shape = shape,
            ),
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.06f),
        modifier = Modifier
            .focusRequester(closeFocus)
            .focusProperties { up = bodyFocus }
            .onKeyEvent { event ->
                when (event.key) {
                    Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> true
                    else -> false
                }
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Close",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}
