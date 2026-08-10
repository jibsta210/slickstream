package com.slickstream.tv.screen

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.slickstream.core.model.MediaItem
import com.slickstream.feature.search.SearchViewModel
import com.slickstream.tv.components.ConsumeOnResume
import com.slickstream.tv.components.TvFocusTicket
import com.slickstream.tv.components.TvPosterCard
import com.slickstream.tv.components.TvSearchBar
import com.slickstream.tv.components.bringItemIntoComposition
import com.slickstream.tv.components.rememberTvFocusTicket
import com.slickstream.tv.components.tvItemKey
import com.slickstream.ui.theme.Brand
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Where focus belongs on this screen. Modelled as a target rather than a bare [FocusRequester] so
 * the restore path can do real work (scroll the tile into composition, confirm the landing) before
 * deciding whether the mic-orb fallback is warranted.
 */
private enum class SearchFocus { Grid, Mic }

/** Row identity for the (single-surface) results grid in the re-entry ticket. */
private const val GRID = ""

/**
 * Android TV Search — VOICE FIRST, with a search console that DOCKS instead of disappearing.
 *
 * [TvSearchBar] (mic orb + text tile + clear) is composed in every state; when results arrive it
 * shrinks to a 56dp bar above the grid instead of being swapped out. Before this the hero was
 * gated behind `!hasResults`, which left the user stranded: once results were on screen there was
 * no mic and no way to type, so the only "reset" was switching to another tab and back.
 *
 * Re-entry policy (see `focusTicket`): coming BACK from a title's details keeps the results and
 * re-focuses the poster you opened; arriving from the nav rail starts a fresh search. All
 * recognition/search logic is reused from [SearchViewModel].
 */
@Composable
fun TvSearchScreen(
    onMediaClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    // --- Re-entry policy ----------------------------------------------------------------------
    // Which grid cell was opened last — and, because it is ONLY ever set when this screen was left
    // through a poster, the ticket that says "this composition is a Back out of Details". Its two
    // exit paths look identical to the composable (it is disposed either way), but they must not
    // behave the same:
    //   • Back from Details  -> keep the results, land focus on the poster you opened. Nuking a
    //     voice search because the user glanced at a synopsis would be worse than the bug we are
    //     fixing — nobody wants to re-say "the one with the spinning top".
    //   • Nav rail -> Search -> start over. Pressing the menu item is an explicit "I want to search
    //     something new", and landing on last night's grid is the actual complaint.
    // Saveable, not remember: navigateTopLevel() pops this entry with saveState = true, so the
    // ViewModelStore (and with it the old query + results) survives while every plain remember in
    // here is thrown away. The ticket records the tile by KEY, not by grid index — see below for
    // why an index cannot be trusted across this particular round trip.
    val focusTicket = rememberTvFocusTicket()

    // The reset runs in a remember initializer ON PURPOSE — do not "tidy" this into a
    // LaunchedEffect. clear() sets the ViewModel's StateFlow synchronously, so running it here,
    // above the collectAsStateWithLifecycle below, means the very FIRST composition already reads
    // the empty state. From a LaunchedEffect the first frame would still paint the previous
    // search's grid (and latch the bar into its docked form off stale data).
    val freshSearch = remember {
        val fromRail = !focusTicket.isReturning
        if (fromRail) viewModel.clear()
        fromRail
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Strip the high-frequency rmsDb BEFORE collecting at screen scope: raw voiceState ticks at
    // mic-callback rate while listening, and each tick recomposed the ENTIRE screen (hero, grid,
    // transcript) — a sustained storm exactly while SpeechRecognizer is eating CPU. The mic orb
    // collects the loudness by itself, confining that churn to one small tile.
    val voice by remember {
        viewModel.voiceState.map { it.copy(rmsDb = 0f) }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = viewModel.voiceState.value.copy(rmsDb = 0f))
    val rmsFlow = remember { viewModel.voiceState.map { it.rmsDb } }

    // VoiceSearchManager is a @Singleton and SearchViewModel.onCleared() does NOT fire on a tab
    // switch (the entry's ViewModelStore is saved, not cleared), so a live "Listening…" session —
    // and a stale "No match, try again" — used to follow the user off the screen and still be there
    // on return.
    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopVoice()
            viewModel.acknowledgeVoice()
        }
    }

    // Consume the drill-down ticket so the NEXT arrival (which can only be from the rail) resets.
    // On ON_RESUME rather than LaunchedEffect(Unit) to cover the case where this composable is
    // never disposed at all — pressing BACK inside the nav transition can bring us back before
    // NavHost has torn the screen down, and a ticket left set there would make the following tab
    // switch look like a Details return and restore the stale grid. Effects run after composition,
    // so `returnIndex` above has always been captured before this can fire.
    focusTicket.ConsumeOnResume()

    val micFocus = remember { FocusRequester() }
    val gridState = rememberLazyGridState()

    // --- Focus ----------------------------------------------------------------------------------
    // SINGLE FOCUS AUTHORITY. Every focus move on this screen assigns [focusTarget]; this is the
    // only place requestFocus() is called, and it must stay that way or the "nothing is focused"
    // bug grows straight back. Assigning instead of calling also DEFERS the request past the
    // recomposition that removes the node the user is standing on (pressing Clear deletes the
    // Clear button itself) — an inline requestFocus() wins the race and is then undone when the
    // removal is applied.
    var focusTarget by remember {
        // The results check matters after process death: the saved key comes back but the
        // ViewModel does not, so without it we would burn 480ms of retries on a cell that will
        // never exist before falling back.
        val returning = !freshSearch && state.results.isNotEmpty()
        mutableStateOf<SearchFocus?>(if (returning) SearchFocus.Grid else SearchFocus.Mic)
    }
    LaunchedEffect(focusTarget) {
        when (focusTarget) {
            null -> return@LaunchedEffect

            SearchFocus.Grid -> {
                // THE OFF-BY-ONE FIX, in two parts.
                //
                // (1) Find the tile by KEY. The grid's saved scroll position cannot be trusted here:
                //     TvApp drops the 212dp nav rail the instant we navigate to Details, but this
                //     screen stays composed for the whole exit transition, so it re-measures WIDER,
                //     derives MORE columns, and saves a first-visible-item from a layout we never
                //     come back to.
                // (2) Scroll to it BY ITEM INDEX before asking for focus. An item index means the
                //     same tile at any column count, and it guarantees the cell is composed — which
                //     is the only state in which its focus requester is attached at all. Previously
                //     the request threw "not initialized" for all 12 retries, we fell back to the
                //     mic orb, and one DOWN press from the orb entered the grid at its first VISIBLE
                //     row — always exactly one row above the row the user came from, because
                //     focus-driven scrolling leaves your row as the LAST visible one.
                val index = state.results.indexOfFirst { tvItemKey(it) == focusTicket.pendingItem }
                if (index >= 0) {
                    gridState.bringItemIntoComposition(index)
                    // requestUntilFocused is answered by the TILE, not by the requester: a bare
                    // requestFocus() returns Unit and only throws when no node is attached, so the
                    // old `runCatching { … }.isSuccess` test could report success while nothing on
                    // the screen was focused, and then skip the fallback below.
                    if (focusTicket.anchor.requestUntilFocused()) {
                        focusTarget = null
                        return@LaunchedEffect
                    }
                }
                // Only now is the tile genuinely gone (the profile switched and the query re-ran
                // under new rules). Never settle with nothing focused.
                focusTarget = SearchFocus.Mic
            }

            SearchFocus.Mic -> {
                // Terminal fallback: the orb exists in BOTH bar states, so there is nothing further
                // to try and nothing for a landing report to change.
                repeat(12) {
                    kotlinx.coroutines.delay(40)
                    if (runCatching { micFocus.requestFocus() }.isSuccess) {
                        focusTarget = null
                        return@LaunchedEffect
                    }
                }
                focusTarget = null
            }
        }
    }

    // --- Docked / hero ---------------------------------------------------------------------------
    // LATCHED, not derived from results.isEmpty(). Driving the dock straight off the result list
    // makes the whole console grow and shrink on every backspace once a query is up. It docks on the
    // first search and stays docked until an explicit reset (Clear, Back, or a rail re-entry).
    var collapsed by remember { mutableStateOf(!freshSearch && state.results.isNotEmpty()) }
    val searching = state.results.isNotEmpty() || state.isLoading
    LaunchedEffect(searching) { if (searching) collapsed = true }

    // Rewind the (screen-scoped, therefore long-lived) grid whenever a NEW search starts, not just on
    // an explicit reset — searching again from the docked bar while scrolled deep into the previous
    // results would otherwise render the new list from the old offset.
    LaunchedEffect(state.isLoading) {
        if (state.isLoading) runCatching { gridState.scrollToItem(0) }
    }

    val resetScope = rememberCoroutineScope()

    fun resetToHero() {
        // Stop the recognizer FIRST. Without this, a BACK/Clear pressed while the mic is still live
        // is silently undone ~1s later: the in-flight final transcript lands in the ViewModel, kicks
        // off a fresh search, `searching` flips true and the LaunchedEffect above re-docks the bar —
        // the hero the user just asked for snaps back to a result grid on its own.
        viewModel.stopVoice()
        viewModel.acknowledgeVoice()
        viewModel.clear()
        collapsed = false
        focusTicket.clear()
        focusTarget = SearchFocus.Mic
        // The grid state is hoisted to screen scope (so a Details return can restore focus), which
        // means it OUTLIVES the results it was scrolled against. Clearing without rewinding leaves
        // the next search rendering from a stale index — opening mid-list, or pinned near the end if
        // the new list is shorter. That is precisely the "semi screwed up results page" this change
        // exists to kill, so rewind on every path that starts a fresh search.
        resetScope.launch { runCatching { gridState.scrollToItem(0) } }
    }

    // BACK inside a result list starts over instead of leaving the tab — the reset the user was
    // faking by switching tabs and coming back. Disabled in the hero, so a second BACK still pops to
    // Home exactly as before. (Two-press Back on a docked Search is a deliberate behaviour change.)
    BackHandler(enabled = collapsed) { resetToHero() }

    // RECORD_AUDIO permission launcher; on grant we immediately start listening.
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.startVoice()
    }

    // What the tile shows. The ViewModel mirrors partial transcripts into the query
    // (SearchViewModel.observeVoiceTranscripts), but only once the FIRST partial lands — until then
    // the tile would still be showing the previous search's text while the user is already talking.
    val barText = if (voice.isListening && voice.partialTranscript.isNotBlank()) {
        voice.partialTranscript
    } else {
        state.query
    }
    val statusText: String? = when {
        voice.isListening -> "Listening…"
        voice.error != null -> voice.error
        state.isLoading -> "Searching…"
        state.results.isNotEmpty() -> "${state.results.size} results"
        else -> null
    }
    val statusColor = when {
        voice.isListening -> Brand.Cyan
        voice.error != null -> Brand.Error
        else -> Brand.OnSurfaceDim
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // No background here — TvApp's shell already fills Brand.Background; repeating it was
            // a second full-screen fill pass per frame on a fill-rate-bound GPU.
            // Outer pad trimmed — the screen-wide overscan inset in TvApp supplies the safe margin.
            .padding(start = 48.dp, end = 48.dp, top = 27.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (collapsed) 12.dp else 18.dp),
    ) {
        // Docked, the bar IS the header (magnifier + the live query), and dropping the headline buys
        // the grid back most of what the bar costs it: 540 - 27 - 56 - 12 - 12 = 433dp of grid, so
        // the viewport maths below still yields two full rows.
        if (!collapsed) {
            Text(
                text = "Search",
                style = MaterialTheme.typography.headlineMedium,
                color = Brand.OnSurface,
            )
        }

        TvSearchBar(
            query = barText,
            expanded = !collapsed,
            listening = voice.isListening,
            rmsDbFlow = rmsFlow,
            micFocusRequester = micFocus,
            onMicClick = {
                // Ask for the mic permission first if needed, else start directly.
                viewModel.acknowledgeVoice()
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onQueryChange = viewModel::setQuery,
            onSubmit = viewModel::submit,
            onClear = { resetToHero() },
            // In the hero the transcript line below already says all this; docked it is the only
            // feedback a voice search gets.
            status = if (collapsed) statusText else null,
            statusColor = statusColor,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        // Live transcript / prompt under the mic (hero only).
        if (!collapsed) {
            val transcript = when {
                voice.isListening && barText.isNotBlank() -> "\"$barText\""
                voice.isListening -> "Listening…"
                voice.error != null -> voice.error!!
                state.query.isNotBlank() -> "\"${state.query}\""
                else -> "Press the mic and say a title, actor, or genre"
            }
            Text(
                text = transcript,
                style = MaterialTheme.typography.titleMedium,
                color = if (voice.error != null && !voice.isListening) Brand.Error else Brand.OnSurfaceDim,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // --- Results ----------------------------------------------------------
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                state.isLoading -> TvLoading()
                state.errorMessage != null -> TvErrorRetry(
                    message = state.errorMessage!!,
                    onRetry = viewModel::submit,
                )
                state.isEmpty -> CenteredHint(
                    "No results for \"${state.query}\".\nPress BACK, or the ✕ in the bar, to start over.",
                )
                state.results.isEmpty() -> CenteredHint("Search results will appear here.")
                else -> ResultsGrid(
                    results = state.results,
                    gridState = gridState,
                    upFocus = micFocus,
                    focusTicket = focusTicket,
                    onOpen = onMediaClick,
                )
            }
        }
    }
}

@Composable
private fun ResultsGrid(
    results: List<MediaItem>,
    gridState: LazyGridState,
    upFocus: FocusRequester,
    focusTicket: TvFocusTicket,
    onOpen: (MediaItem) -> Unit,
) {
    // Size cards FROM THE VIEWPORT so a whole card (poster + title + year) fits and ~2 rows show —
    // a fixed 150dp minimum overflowed a 960x540dp TV and cut the titles off.
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val hGap = 16.dp
        // These numbers pay for the SECOND title line (titleMaxLines = 2 below). A search cell is
        // only ~95dp wide, so one 14sp line ellipsized after ~13 characters; two 13sp lines is
        // roughly 2.2x the title before the "…". The reserve has to grow with the text or the
        // second grid row is what gets sliced instead:
        //   textBlock = 8dp pad + 2 x 18dp bodySmall line + 16dp year = 60dp, + 4dp slack.
        // vGap and the bottom inset shrink to pay for it (8dp still clears the bleed of the focused
        // card's 1.06 scale on the last row), and the row budget now SUBTRACTS the grid's own
        // content padding, which the old formula quietly ignored. That last part is what keeps the
        // extra line safe on panels other than the 960x540dp reference: an over-generous rowHeight
        // asks for wider cards, and a wider card is a TALLER card, which is exactly how two rows
        // stop fitting. Honest arithmetic flips to one more column instead of overflowing.
        // On the reference TV (652dp x 433dp of grid) the result is unchanged where it matters:
        // still 6 columns of 95dp, poster still 143dp — only the text token shrinks.
        val vGap = 12.dp
        val textBlock = 64.dp
        val padTop = 4.dp
        val padBottom = 8.dp
        val rowHeight = (maxHeight - vGap - padTop - padBottom) / 2
        val posterHeight = (rowHeight - textBlock).coerceAtLeast(80.dp)
        val targetCardWidth = (posterHeight * (2f / 3f)).coerceIn(90.dp, 165.dp)
        val columns = kotlin.math.ceil(maxWidth / (targetCardWidth + hGap)).toInt().coerceAtLeast(3)

        LazyVerticalGrid(
            // Hoisted so the scroll offset survives a Details round trip and the restore effect can
            // scroll the cell we want to re-focus into composition before asking for focus.
            state = gridState,
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(top = padTop, bottom = padBottom),
            horizontalArrangement = Arrangement.spacedBy(hGap),
            verticalArrangement = Arrangement.spacedBy(vGap),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(results, key = { _, it -> tvItemKey(it) }) { index, item ->
                TvPosterCard(
                    item = item,
                    // Records the cell so a Back out of Details lands focus right back on it.
                    onClick = {
                        focusTicket.record(GRID, it)
                        onOpen(it)
                    },
                    fillCell = true,
                    titleMaxLines = 2,
                    focusAnchor = if (tvItemKey(item) == focusTicket.pendingItem) {
                        focusTicket.anchor
                    } else {
                        null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        // UP out of the TOP ROW lands on the mic orb, deterministically. Geometric
                        // focus search picks whichever bar child is horizontally nearest, so UP from
                        // the middle columns dropped into the text tile instead of the orb — and the
                        // orb is what someone pressing UP from the results actually wants. Top row
                        // ONLY: focusProperties inherits to descendants, so putting this on the grid
                        // container would break row 2 -> row 1.
                        .then(if (index < columns) Modifier.focusProperties { up = upFocus } else Modifier),
                )
            }
        }
    }
}

@Composable
private fun CenteredHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = Brand.OnSurfaceDim,
            textAlign = TextAlign.Center,
        )
    }
}
