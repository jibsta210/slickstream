package com.slickstream.tv.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.slickstream.core.model.MediaItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one stable identity of a tile across a navigation round trip: type + id, exactly the key the
 * rows and grids already give their lazy items. Everything in this file matches by this string
 * rather than by list index, because the list a screen comes BACK to is not always the list it
 * left — Continue Watching reshuffles itself, a re-run query returns a different page — and an
 * index ticket then silently re-focuses whatever slid into that slot.
 */
fun tvItemKey(item: MediaItem): String = "${item.mediaType.name}-${item.id}"

/**
 * A focus target that REPORTS BACK when focus actually lands on it.
 *
 * Why this type exists at all: [FocusRequester.requestFocus] returns Unit, and the only thing it
 * ever throws on is "no attached node is using this requester". The idiom this codebase grew —
 * `if (runCatching { r.requestFocus() }.isSuccess) …` — therefore tests "a node exists", NOT "focus
 * moved". Every retry loop built on it could report success and stop while nothing on the screen
 * was focused, which is precisely how a Back out of Details used to settle with a dead D-pad.
 *
 * [landed] is written by the target itself from `onFocusChanged`, so it cannot lie.
 */
@Stable
class TvFocusAnchor {
    val requester = FocusRequester()
    private var landedState by mutableStateOf(false)

    /** True once focus has genuinely arrived at the node wearing this anchor. */
    val landed: Boolean get() = landedState

    internal fun markLanded() {
        landedState = true
    }

    /**
     * Ask for focus until the target CONFIRMS it has it. Delay-first, because the target is composed
     * in the same frame as the caller and an immediate request throws "not initialized".
     *
     * @return true only if focus really landed — callers may trust this to decide on a fallback.
     */
    suspend fun requestUntilFocused(attempts: Int = 15, stepMs: Long = 40): Boolean {
        repeat(attempts) {
            delay(stepMs)
            if (landed) return true
            runCatching { requester.requestFocus() }
            if (landed) return true
        }
        return landed
    }
}

/**
 * Route [anchor] at this node. Put it on the composable that actually TAKES focus (for
 * [TvPosterCard] that is the inner tv-material3 `Surface`, not the outer column), so the
 * `onFocusChanged` report below is about the real focus target.
 */
fun Modifier.tvFocusAnchor(anchor: TvFocusAnchor?): Modifier =
    if (anchor == null) {
        this
    } else {
        this
            .focusRequester(anchor.requester)
            .onFocusChanged { if (it.isFocused || it.hasFocus) anchor.markLanded() }
    }

/**
 * "Which tile did I leave through?" — the re-entry ticket that carries focus across a Details (or
 * player) round trip.
 *
 * It has to be [rememberSaveable] because nothing else survives the trip: navigation-compose
 * disposes the browse destination once the transition into Details finishes, every FocusTargetNode
 * is then rebuilt unfocused, and `Modifier.focusRestorer()` only remembers a focus group's last
 * child within a LIVE composition. Lazy scroll offsets survive (they are saveable too) — focus does
 * not, which is the whole of the "Back does not put me back on the poster I opened" bug.
 *
 * One ticket per screen, so exactly ONE row/grid can claim focus on return (see [claim]).
 */
@Stable
class TvFocusTicket internal constructor(
    private val pending: String,
    private val write: (String) -> Unit,
) {
    val anchor = TvFocusAnchor()
    private var claimed by mutableStateOf(false)

    /** Row this screen was left through; "" for single-surface screens (a grid). */
    val pendingRow: String = pending.substringBeforeLast('#', "")

    /** [tvItemKey] of the tile this screen was left through, or "" on a fresh arrival. */
    val pendingItem: String = pending.substringAfterLast('#', "")

    /** True when this composition is a Back OUT of Details rather than an arrival from the rail. */
    val isReturning: Boolean get() = pendingItem.isNotEmpty()

    /** The pending tile key, but only for the surface that owns it. */
    fun pendingItemFor(row: String): String = if (row == pendingRow) pendingItem else ""

    /** Record the tile being opened. Call this on the way OUT, before navigating. */
    fun record(row: String, item: MediaItem) {
        write("$row#${tvItemKey(item)}")
    }

    fun clear() {
        write("")
    }

    /**
     * Take the one-shot right to move focus. Without this a row that scrolls out of the LazyColumn
     * and back would re-run its restore effect and yank focus off whatever the user had moved to.
     */
    fun claim(): Boolean {
        if (claimed) return false
        claimed = true
        return true
    }
}

/**
 * Create the screen's re-entry ticket. Call it ABOVE any loading/empty branch — if the ticket is
 * only created once content exists, [ConsumeOnResume] can fire first and the ticket is lost.
 */
@Composable
fun rememberTvFocusTicket(): TvFocusTicket {
    var slot by rememberSaveable { mutableStateOf("") }
    // Read ONCE, at composition, before any click in this instance can overwrite it: this is what
    // the PREVIOUS instance of this screen wrote on its way into Details.
    val pending = remember { slot }
    return remember { TvFocusTicket(pending) { slot = it } }
}

/**
 * Spend the ticket, so the ticket is worth exactly one Back.
 *
 * ON_RESUME rather than `LaunchedEffect(Unit)`, matching the policy [TvSearchScreen] already
 * proved: pressing BACK inside the nav transition can return us before the NavHost has torn the
 * screen down, and a ticket left set there would make the NEXT arrival (which can only be from the
 * nav rail) look like a Details return — and steal focus off the rail into the content.
 */
@Composable
fun TvFocusTicket.ConsumeOnResume() {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { clear() }
}

/**
 * Make sure item [index] is actually COMPOSED, then leave the row where the user had it.
 *
 * Order matters and so does the "already visible" test. A lazy container only attaches a focus
 * requester to a composed cell, so a restore that never scrolls simply fails when the target is off
 * screen. But scrolling unconditionally would throw away the row's restored horizontal offset —
 * the second half of "put me back exactly where I was" — so scroll ONLY when the cell is missing.
 */
suspend fun LazyListState.bringItemIntoComposition(index: Int) {
    if (index < 0) return
    // First layout has not happened yet in the frame this is called, so "is it visible" has no
    // answer to give. Bounded, because an empty list never lays anything out.
    withTimeoutOrNull(FIRST_LAYOUT_TIMEOUT_MS) {
        snapshotFlow { layoutInfo.visibleItemsInfo }.first { it.isNotEmpty() }
    }
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) {
        runCatching { scrollToItem(index) }
    }
}

/**
 * Grid twin of [bringItemIntoComposition]. Scrolling by ITEM index is also what immunises the
 * search grid against its off-by-one row: the outgoing screen re-measures 212dp wider the moment
 * the nav rail is dropped for Details, so it saves a first-visible-item from a layout with MORE
 * columns than the one we return to, and the restored window lands a row off. An item index means
 * the same tile at any column count.
 */
suspend fun LazyGridState.bringItemIntoComposition(index: Int) {
    if (index < 0) return
    withTimeoutOrNull(FIRST_LAYOUT_TIMEOUT_MS) {
        snapshotFlow { layoutInfo.visibleItemsInfo }.first { it.isNotEmpty() }
    }
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) {
        runCatching { scrollToItem(index) }
    }
}

private const val FIRST_LAYOUT_TIMEOUT_MS = 1_000L
