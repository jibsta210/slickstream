package com.slickstream.tv.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.slickstream.ui.theme.Brand
import kotlinx.coroutines.flow.Flow

/**
 * The persistent TV search console: voice orb + text tile + clear, ALWAYS on screen.
 *
 * It exists because the old search screen gated its mic/keyboard hero behind `!hasResults` and
 * swapped the whole thing OUT of the composition the moment results arrived. Two bugs fell out of
 * that: there was no way to start a second search without leaving the tab and coming back, and the
 * node that had D-pad focus (the mic you had just pressed) was destroyed mid-search, so the screen
 * settled with NOTHING focused and the remote appeared dead.
 *
 * The fix is structural: this bar is the SAME `Row` with the SAME two focusable children in both
 * states — only the measured sizes change ([expanded] hero vs docked). A collapse can therefore
 * never destroy the node the user is standing on, and [micFocusRequester] can never fail to
 * resolve.
 *
 * Sizes swap instantly rather than animating: the orb is 168dp -> 56dp, and animating that would
 * recompose the orb for the whole duration of the tween — the one place in this screen that would
 * recompose on an animation, against the TV perf pass (draw-phase animations only).
 */
@Composable
fun TvSearchBar(
    query: String,
    expanded: Boolean,
    listening: Boolean,
    rmsDbFlow: Flow<Float>,
    micFocusRequester: FocusRequester,
    onMicClick: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    status: String? = null,
    statusColor: Color = Brand.OnSurfaceDim,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (expanded) 24.dp else 14.dp),
        // Hero: a centred cluster at 86% width (the caller supplies the Column alignment).
        // Docked: full width, so the tile can hold a long spoken query without ellipsing.
        modifier = modifier.fillMaxWidth(if (expanded) HERO_WIDTH_FRACTION else 1f),
    ) {
        MicOrb(
            size = if (expanded) HERO_ORB else DOCKED_ORB,
            listening = listening,
            rmsDbFlow = rmsDbFlow,
            focusRequester = micFocusRequester,
            onClick = onMicClick,
        )
        SearchTile(
            value = query,
            height = if (expanded) 64.dp else 52.dp,
            listening = listening,
            onValueChange = onQueryChange,
            onSubmit = onSubmit,
            modifier = Modifier.weight(1f),
        )
        // Only offered when there is something to clear — an always-present dead button is one more
        // D-pad stop between the tile and the rest of the row.
        if (query.isNotBlank()) {
            BarIconButton(
                icon = Icons.Rounded.Close,
                contentDescription = "Clear search and start over",
                size = if (expanded) 56.dp else 48.dp,
                onClick = onClear,
            )
        }
        // Docked, the hero's transcript line is gone, so a second VOICE search would otherwise give
        // no feedback at all. This carries "Listening…" / the recognizer error / the result count.
        if (status != null) {
            Text(
                text = status,
                style = MaterialTheme.typography.labelLarge,
                color = statusColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 170.dp),
            )
        }
    }
}

/**
 * The voice hero, moved out of TvSearchScreen so it can live in both bar states. Identical to the
 * old private `MicTile` apart from the [size] parameter.
 */
@Composable
private fun MicOrb(
    size: Dp,
    listening: Boolean,
    rmsDbFlow: Flow<Float>,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    // Pulse the ring with mic loudness while listening. Loudness is collected HERE so its ticks
    // recompose only this tile, and the animated scale is applied in graphicsLayer's lambda —
    // a draw-phase read, so the pulse animation itself never recomposes anything.
    val rmsDb by rmsDbFlow.collectAsStateWithLifecycle(initialValue = 0f)
    val pulse = animateFloatAsState(
        targetValue = if (listening) 1f + (rmsDb.coerceIn(0f, 12f) / 12f) * 0.18f else 1f,
        label = "mic-pulse",
    )

    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (listening) Brand.Violet else Brand.Surface,
            focusedContainerColor = Brand.Violet,
            pressedContainerColor = Brand.VioletDim,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(3.dp, Brand.Cyan),
                shape = CircleShape,
            ),
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.08f),
        modifier = Modifier
            .focusRequester(focusRequester)
            .size(size)
            .graphicsLayer {
                val s = if (listening) pulse.value else 1f
                scaleX = s
                scaleY = s
            },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Rounded.Mic,
                contentDescription = "Voice search",
                tint = if (listening || focused) Brand.OnSurface else Brand.Violet,
                modifier = Modifier.size(size * 0.42f),
            )
        }
    }
}

/**
 * Text entry as a TWO-TARGET tile: a focusable [Surface] that only DISPLAYS the query, plus a live
 * `BasicTextField` that is swapped in when you press OK on it.
 *
 * A bare always-live text field cannot be used on TV. It grabs the platform IME the instant it is
 * focused, so merely passing THROUGH it — pressing RIGHT from the orb on the way to Clear, or UP
 * out of the results grid — threw the full-screen leanback keyboard over the whole app. It also
 * eats LEFT/RIGHT for caret movement, which walls focus in between the orb and Clear and blocks
 * LEFT back to the nav rail.
 *
 * Gating the field behind an explicit OK press fixes both: horizontal navigation belongs to the
 * tile, editing belongs to the keyboard. The tile itself is never removed from the composition, so
 * the hero -> docked morph keeps its focus node identity.
 */
@Composable
private fun SearchTile(
    value: String,
    height: Dp,
    listening: Boolean,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    // Kept as State (not delegated): the focus ring reads it in the DRAW phase and the label in its
    // own tiny composable, so a D-pad focus change doesn't recompose the tile (the TvPosterCard
    // idiom).
    val focusedState = interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(50)
    val keyboard = LocalSoftwareKeyboardController.current

    var editing by remember { mutableStateOf(false) }
    // Bumped on every OK press, even when [editing] is already true. Without it, a stray
    // programmatic focus move (a reset fired while the field was live) would leave editing stuck on
    // with nothing focused, and pressing OK again would be a no-op — a wedge with no way out.
    var editEpoch by remember { mutableStateOf(0) }
    val tileFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }

    /**
     * Leave edit mode WITHOUT dropping focus on the floor: take focus back to the tile FIRST, then
     * remove the field. Doing it the other way round destroys the focused node and the framework
     * has nowhere to fall back to.
     */
    fun stopEditing() {
        runCatching { tileFocus.requestFocus() }
        editing = false
        keyboard?.hide()
    }

    // Delay-first retry, the house idiom (see TvFavoritesScreen / TvCategoryScreen): the field is
    // composed in the same frame the flag flips, so an immediate requestFocus() would throw
    // "FocusRequester is not initialized".
    LaunchedEffect(editing, editEpoch) {
        if (!editing) return@LaunchedEffect
        repeat(12) {
            kotlinx.coroutines.delay(40)
            if (runCatching { fieldFocus.requestFocus() }.isSuccess) {
                keyboard?.show()
                return@LaunchedEffect
            }
        }
        // The field never made it on screen — fall back to the display tile rather than sit in a
        // fake edit mode where the remote does nothing.
        editing = false
    }

    Surface(
        onClick = { editing = true; editEpoch++ },
        interactionSource = interaction,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Brand.Surface,
            focusedContainerColor = Brand.SurfaceVariant,
            pressedContainerColor = Brand.SurfaceVariant,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Brand.Violet), shape = shape),
        ),
        // Deliberately NO focus scale. This tile is full-bleed in the docked bar: growing it 6%
        // would shove the Clear button and the status label sideways and nudge the grid below on
        // every focus change. The 3dp violet border + the white ring below carry the focus state.
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f),
        modifier = modifier
            .focusRequester(tileFocus)
            .height(height),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Focus ring drawn in the draw phase (state read inside the lambda), so focus
                // changes invalidate only this node's draw — no recomposition, no overlay Box.
                .drawWithContent {
                    drawContent()
                    if (focusedState.value) {
                        val stroke = 3.dp.toPx()
                        drawRoundRect(
                            color = Color.White,
                            topLeft = Offset(stroke / 2f, stroke / 2f),
                            size = Size(size.width - stroke, size.height - stroke),
                            cornerRadius = CornerRadius(size.height / 2f),
                            style = Stroke(width = stroke),
                        )
                    }
                }
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = Brand.OnSurfaceDim,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    if (editing) {
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            singleLine = true,
                            // SHARE the tile's interaction source. The field is a nested focus target
                            // inside the tv-material3 Surface, so once it takes focus the Surface goes
                            // ActiveParent — and Modifier.focusable only emits FocusInteraction.Focus for
                            // Active/Captured. Without this the shared source emitted Unfocus the instant
                            // typing began: the white ring stopped drawing, the violet focus border was
                            // dropped and the container reverted to the unfocused colour, leaving NOTHING
                            // on screen looking focused while the user typed.
                            interactionSource = interaction,
                            cursorBrush = SolidColor(Brand.Cyan),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Brand.OnSurface),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { stopEditing(); onSubmit() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(fieldFocus)
                                .onPreviewKeyEvent { e ->
                                    when (e.key) {
                                        // OK/ENTER: consume BOTH the DOWN and the UP, but only ACT on
                                        // the UP. androidx.tv.material3's Surface fires its onClick on
                                        // ACTION_UP and does NOT check that it saw the matching DOWN. So
                                        // acting on the DOWN — which synchronously moves focus back to
                                        // the tile — let the orphan UP land on the now-focused tile and
                                        // instantly re-run its onClick (`editing = true`), dropping the
                                        // user straight back into the field. OK looked permanently
                                        // wedged: there was no way out of text entry.
                                        Key.DirectionCenter, Key.Enter -> {
                                            if (e.type == KeyEventType.KeyUp) stopEditing()
                                            true
                                        }
                                        // OTHER ESCAPE HATCHES. While the platform IME is up it owns the
                                        // key stream, so these only fire once the user has dismissed
                                        // it (BACK closes the leanback IME without ever reaching the
                                        // app, leaving the field focused but un-editable). Any of
                                        // them hands focus back to the tile, from where normal D-pad
                                        // navigation works again. These are not click keys, so a
                                        // trailing UP can't be mistaken for a Surface click.
                                        Key.Back, Key.DirectionUp, Key.DirectionDown -> {
                                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                            stopEditing()
                                            true
                                        }
                                        // LEFT/RIGHT fall through on purpose: with the field live
                                        // they are the only way to move the caret, and the tile
                                        // already owns horizontal navigation when it is not live.
                                        else -> false
                                    }
                                },
                        )
                    } else {
                        TileLabel(value = value, listening = listening, focused = focusedState)
                    }
                }
            }
        }
    }
}

/**
 * Own composable so the focus-driven / listening-driven label swap recomposes ONLY this Text and
 * not the whole tile.
 */
@Composable
private fun TileLabel(value: String, listening: Boolean, focused: State<Boolean>) {
    val placeholder = when {
        listening -> "Listening…"
        focused.value -> "Press OK to type"
        else -> "Search movies & shows"
    }
    Text(
        text = value.ifBlank { placeholder },
        style = MaterialTheme.typography.bodyLarge,
        color = when {
            value.isNotBlank() -> Brand.OnSurface
            listening -> Brand.Cyan
            else -> Brand.OnSurfaceDim
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Round icon button for the bar (Clear). Focus feedback matches TvNavItem: violet fill + white ring. */
@Composable
private fun BarIconButton(
    icon: ImageVector,
    contentDescription: String,
    size: Dp,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Brand.Surface,
            focusedContainerColor = Brand.Violet,
            pressedContainerColor = Brand.VioletDim,
            contentColor = Brand.OnSurfaceDim,
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = CircleShape),
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.06f),
        modifier = Modifier.size(size),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(size * 0.44f),
            )
        }
    }
}

/** Hero cluster width, as a fraction of the content column. */
private const val HERO_WIDTH_FRACTION = 0.86f
private val HERO_ORB = 168.dp
private val DOCKED_ORB = 56.dp
