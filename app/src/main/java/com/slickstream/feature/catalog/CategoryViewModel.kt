package com.slickstream.feature.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slickstream.core.model.DataResult
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.core.repository.CatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the full-screen "browse one genre" grid (e.g. Kids, Action) reached from the category
 * chips on the Movies / TV tabs. Pages through TMDB discover-by-genre, appending as the user
 * scrolls. Used by both the phone [CategoryScreen] and the TV TvCategoryScreen.
 */
@HiltViewModel
class CategoryViewModel @Inject constructor(
    private val catalog: CatalogRepository,
) : ViewModel() {

    data class State(
        val items: List<MediaItem> = emptyList(),
        val isLoading: Boolean = false,
        val isPaging: Boolean = false,
        val error: String? = null,
        val endReached: Boolean = false,
    ) {
        val isEmpty: Boolean get() = items.isEmpty()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** In-grid search text. Filtering is purely client-side over already-loaded pages. */
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /**
     * Items to render: when [query] is non-blank, the loaded items whose title contains the query
     * (case-insensitive); otherwise the full loaded list. Note: this filters loaded results only —
     * paging via [loadMore] still walks the complete genre list, not the filtered subset.
     *
     * The query is debounced (~200ms) so the whole grid isn't re-filtered/recomposed on every
     * keystroke; [setQuery] still updates the text field instantly. [onStart] re-emits the current
     * query so the first filtered list isn't delayed by the debounce on initial subscription.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val visibleItems: StateFlow<List<MediaItem>> = combine(
        _state,
        _query.debounce(200).onStart { emit(_query.value) },
    ) { state, query ->
        val q = query.trim()
        if (q.isBlank()) state.items
        else state.items.filter { it.title.contains(q, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(text: String) {
        _query.value = text
    }

    private var mediaType: MediaType? = null
    private var genreId: Int = -1
    private var page = 0
    private var loading = false

    /** Idempotent: re-fetches only when the (type, genre) pair changes. */
    fun init(type: MediaType, genreId: Int) {
        if (this.mediaType == type && this.genreId == genreId && _state.value.items.isNotEmpty()) return
        this.mediaType = type
        this.genreId = genreId
        _query.value = ""
        page = 0
        _state.value = State(isLoading = true)
        loadNext()
    }

    fun loadMore() {
        if (!loading && !_state.value.endReached && !_state.value.isLoading) loadNext()
    }

    fun retry() {
        page = 0
        _state.value = State(isLoading = true)
        loadNext()
    }

    private fun loadNext() {
        val type = mediaType ?: return
        if (loading) return
        loading = true
        val next = page + 1
        viewModelScope.launch {
            if (next > 1) _state.update { it.copy(isPaging = true) }
            when (val result = catalog.getByGenre(type, genreId, next)) {
                is DataResult.Success -> {
                    page = next
                    val merged = (_state.value.items + result.data)
                        .distinctBy { "${it.mediaType.name}-${it.id}" }
                    _state.value = _state.value.copy(
                        items = merged,
                        isLoading = false,
                        isPaging = false,
                        error = null,
                        endReached = result.data.isEmpty(),
                    )
                }
                is DataResult.Error -> _state.update {
                    it.copy(
                        isLoading = false,
                        isPaging = false,
                        error = if (it.items.isEmpty()) result.message else null,
                    )
                }
            }
            loading = false
        }
    }
}
