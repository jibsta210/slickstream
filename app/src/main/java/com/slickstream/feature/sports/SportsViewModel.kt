package com.slickstream.feature.sports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slickstream.core.model.DataResult
import com.slickstream.feature.live.LivePlaybackHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the Live Sports tab: a category chip rail (sport / league + a pinned "Live now") over a
 * list of events. Tapping an event resolves its feeds into a picker; choosing one stashes it for
 * the live player. All keyless, via [SportsRepository].
 */
@HiltViewModel
class SportsViewModel @Inject constructor(
    private val repo: SportsRepository,
    private val playbackHolder: LivePlaybackHolder,
    private val diagnostics: com.slickstream.core.diagnostics.Diagnostics,
) : ViewModel() {

    data class State(
        val enabled: Boolean = true,
        val categories: List<SportCategory> = emptyList(),
        val selectedId: String? = null,
        val events: List<SportEvent> = emptyList(),
        val loadingCategories: Boolean = true,
        val loadingEvents: Boolean = false,
        val error: String? = null,
    )

    /** Stream picker shown when an event is tapped. */
    data class StreamSheet(
        val event: SportEvent,
        val loading: Boolean = true,
        val streams: List<SportStream> = emptyList(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State(enabled = repo.enabled, loadingCategories = repo.enabled))
    val state: StateFlow<State> = _state.asStateFlow()

    private val _sheet = MutableStateFlow<StreamSheet?>(null)
    val sheet: StateFlow<StreamSheet?> = _sheet.asStateFlow()

    init {
        if (repo.enabled) loadCategories()
    }

    fun loadCategories() {
        if (!repo.enabled) {
            _state.value = State(enabled = false, loadingCategories = false)
            return
        }
        _state.update { it.copy(loadingCategories = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.categories()) {
                is DataResult.Success -> {
                    val cats = r.data.distinctBy { it.id }
                    val first = cats.firstOrNull()
                    _state.update { it.copy(categories = cats, loadingCategories = false) }
                    first?.let { select(it.id) }
                }
                is DataResult.Error ->
                    _state.update { it.copy(loadingCategories = false, error = r.message) }
            }
        }
    }

    private var eventsJob: Job? = null

    fun select(categoryId: String) {
        // Select-on-focus drives this from the TV chip rail, so ignore re-selecting the current tab
        // and cancel any in-flight load when the tab changes (no stale results racing in).
        if (_state.value.selectedId == categoryId) return
        eventsJob?.cancel()
        _state.update { it.copy(selectedId = categoryId, loadingEvents = true, events = emptyList(), error = null) }
        eventsJob = viewModelScope.launch {
            when (val r = repo.events(categoryId)) {
                // distinctBy id: the provider lists the SAME PPV event in multiple categories (and
                // sometimes twice in "Live now"), and the events grid keys on id — a duplicate id made
                // Compose's LazyColumn/Row throw IllegalArgumentException (the instant Android-TV sports
                // crash on stream select, once a recompose re-measured the list).
                is DataResult.Success -> _state.update { it.copy(events = r.data.distinctBy { e -> e.id }, loadingEvents = false) }
                is DataResult.Error -> _state.update { it.copy(loadingEvents = false, error = r.message) }
            }
        }
    }

    fun retry() = loadCategories()

    fun openStreams(event: SportEvent) {
        _sheet.value = StreamSheet(event = event, loading = true)
        viewModelScope.launch {
            when (val r = repo.streams(event)) {
                is DataResult.Success -> _sheet.update {
                    val streams = r.data.distinctBy { s -> s.id }   // same guard for the feed list's keys
                    it?.copy(loading = false, streams = streams, error = if (streams.isEmpty()) "No live feeds for this event yet." else null)
                }
                is DataResult.Error -> _sheet.update { it?.copy(loading = false, error = r.message) }
            }
        }
    }

    fun dismissStreams() { _sheet.value = null }

    /** Stash the chosen feed for [com.slickstream.feature.live.LivePlayerViewModel] and clear the sheet. */
    fun prepareToPlay(sheet: StreamSheet, stream: SportStream) {
        diagnostics.breadcrumb(
            "sports.prepareToPlay id=${stream.id} needsResolution=${stream.needsResolution} " +
                "feeds=${sheet.streams.size}",
        )
        val feeds = sheet.streams.map {
            com.slickstream.feature.live.LivePlaybackHolder.Feed(it.label, it.url, it.headers, it.needsResolution)
        }
        playbackHolder.set(
            title = sheet.event.title,
            feeds = feeds,
            index = sheet.streams.indexOf(stream).coerceAtLeast(0),
        )
        _sheet.value = null
        diagnostics.breadcrumb("sports.prepareToPlay done -> navigating to live player")
    }
}
