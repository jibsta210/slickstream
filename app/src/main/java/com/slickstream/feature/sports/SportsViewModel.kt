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
                    val first = r.data.firstOrNull()
                    _state.update { it.copy(categories = r.data, loadingCategories = false) }
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
                is DataResult.Success -> _state.update { it.copy(events = r.data, loadingEvents = false) }
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
                    it?.copy(loading = false, streams = r.data, error = if (r.data.isEmpty()) "No live feeds for this event yet." else null)
                }
                is DataResult.Error -> _sheet.update { it?.copy(loading = false, error = r.message) }
            }
        }
    }

    fun dismissStreams() { _sheet.value = null }

    /** Stash the chosen feed for [com.slickstream.feature.live.LivePlayerViewModel] and clear the sheet. */
    fun prepareToPlay(event: SportEvent, stream: SportStream) {
        playbackHolder.set(
            title = event.title,
            url = stream.url,
            headers = stream.headers,
            needsResolution = stream.needsResolution,
        )
        _sheet.value = null
    }
}
