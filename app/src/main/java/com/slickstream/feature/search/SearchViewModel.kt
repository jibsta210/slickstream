package com.slickstream.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slickstream.core.model.DataResult
import com.slickstream.core.model.MediaItem
import com.slickstream.core.repository.CatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/** UI-facing snapshot of the search screen. */
data class SearchUiState(
    val query: String = "",
    val results: List<MediaItem> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
) {
    /** True once a real query has been entered and a search has settled with nothing to show. */
    val isEmpty: Boolean
        get() = !isLoading && errorMessage == null && results.isEmpty() && query.isNotBlank()

    /** True before the user has typed/said anything — drives the "Tap the mic" prompt. */
    val isIdle: Boolean
        get() = query.isBlank() && results.isEmpty() && !isLoading && errorMessage == null
}

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    val voiceSearch: VoiceSearchManager,
    profileRepository: com.slickstream.core.repository.ProfileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    /** Live voice recognizer state for the screen (pulsing mic, partial transcript, errors). */
    val voiceState: StateFlow<VoiceState> = voiceSearch.state

    private val queryFlow = MutableStateFlow("")
    private var searchJob: Job? = null

    init {
        observeQueryDebounced()
        observeVoiceTranscripts()
        // The repository gates results by the ACTIVE profile (kids quarantine). On a profile switch,
        // results from the previous profile must not linger: re-run the live query under the new
        // profile's rules, or clear if the field is empty. drop(1) skips the startup emission.
        profileRepository.activeProfile
            .map { it?.id to (it?.isKids ?: false) }
            .distinctUntilChanged()
            .drop(1)
            .onEach {
                val q = _uiState.value.query.trim()
                if (q.isBlank()) clear() else runSearch(q)
            }
            .launchIn(viewModelScope)
    }

    /** Called as the user types — debounced before it hits the network. */
    fun setQuery(value: String) {
        _uiState.value = _uiState.value.copy(query = value, errorMessage = null)
        queryFlow.value = value
    }

    /** Force an immediate search (e.g. keyboard "Search" action) bypassing the debounce. */
    fun submit() {
        runSearch(_uiState.value.query.trim())
    }

    /** Clear the field and results. */
    fun clear() {
        searchJob?.cancel()
        queryFlow.value = ""
        _uiState.value = SearchUiState()
    }

    // --- Voice ---

    fun startVoice() {
        // Reset any benign prior error and begin a fresh listening session.
        voiceSearch.acknowledge()
        voiceSearch.start()
    }

    fun stopVoice() = voiceSearch.stop()

    /** Dismiss a consumed/handled voice error so it doesn't linger. */
    fun acknowledgeVoice() = voiceSearch.acknowledge()

    override fun onCleared() {
        voiceSearch.cancel()
        super.onCleared()
    }

    // --- internals ---

    private fun observeQueryDebounced() {
        viewModelScope.launch {
            queryFlow
                .map { it.trim() }
                .debounce(DEBOUNCE_MS)
                .distinctUntilChanged()
                .collectLatest { q -> runSearch(q) }
        }
    }

    private fun observeVoiceTranscripts() {
        viewModelScope.launch {
            voiceSearch.state.collectLatest { vs ->
                // Mirror partial transcripts into the field live as the user speaks.
                if (vs.isListening && vs.partialTranscript.isNotBlank() &&
                    vs.partialTranscript != _uiState.value.query
                ) {
                    setQuery(vs.partialTranscript)
                }
                // On a committed result, set the final query and search immediately.
                vs.finalTranscript?.let { final ->
                    if (final.isNotBlank()) {
                        _uiState.value = _uiState.value.copy(query = final, errorMessage = null)
                        queryFlow.value = final
                        runSearch(final.trim())
                    }
                    voiceSearch.acknowledge()
                }
            }
        }
    }

    private fun runSearch(rawQuery: String) {
        val query = rawQuery.trim()
        searchJob?.cancel()

        if (query.length < MIN_QUERY_LEN) {
            _uiState.value = _uiState.value.copy(
                results = emptyList(),
                isLoading = false,
                errorMessage = null,
            )
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        searchJob = viewModelScope.launch {
            when (val result = catalogRepository.search(query)) {
                is DataResult.Success -> {
                    // Only commit if this is still the query the user cares about.
                    if (query == _uiState.value.query.trim()) {
                        _uiState.value = _uiState.value.copy(
                            results = result.data,
                            isLoading = false,
                            errorMessage = null,
                        )
                    }
                }
                is DataResult.Error -> {
                    if (query == _uiState.value.query.trim()) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            errorMessage = result.message,
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 350L
        const val MIN_QUERY_LEN = 2
    }
}
