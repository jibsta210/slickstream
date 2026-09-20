package com.slickstream.feature.live

import android.content.Context
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.slickstream.core.model.DataResult
import com.slickstream.feature.sports.SportCategory
import com.slickstream.feature.sports.SportEvent
import com.slickstream.feature.sports.SportsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Coordinates live-sports MULTIVIEW: one to [LiveMultiView.MAX_SLOTS] games at once, each a
 * self-healing [LiveSession]. One game is the ordinary full-screen player; two is picture-in-picture;
 * three or four is a 2x2 grid. The retry engine lives entirely in [LiveSession] — this class only
 * decides the layout, which tile is heard, which tiles may keep a decoder, and how a new game gets in.
 *
 * ADDING A GAME IS THE FEATURE THE USER ASKED TO BE OBVIOUS. There is no separate mode and no leaving
 * the player: an empty grid cell literally IS the "＋ Add game" button, and pressing it opens a small
 * in-player browser backed by the same [SportsRepository] the Sports tab uses. Pick a league, pick a
 * game — it drops into the next cell and starts healing on its own. Choosing the feed is skipped on
 * purpose: the tile is handed the event's WHOLE feed list, so the retry engine picks and switches
 * feeds for it exactly as it would for a single game.
 */
@OptIn(UnstableApi::class)
@HiltViewModel
class LivePlayerViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val holder: LivePlaybackHolder,
    private val resolver: WebViewStreamResolver,
    private val sportsRepo: SportsRepository,
    private val diagnostics: com.slickstream.core.diagnostics.Diagnostics,
    private val settingsRepository: com.slickstream.data.settings.SettingsRepository,
) : ViewModel() {

    private val audioLanguage: StateFlow<String> = settingsRepository.settings
        .map { it.audioLanguage.code }
        .stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            com.slickstream.data.settings.AudioLanguage.DEFAULT.code,
        )

    private val _sessions = MutableStateFlow<List<LiveSession>>(emptyList())
    val sessions: StateFlow<List<LiveSession>> = _sessions.asStateFlow()

    /** Which tile, if any, is expanded to full screen. Null = the SINGLE/PiP/grid layout for the set. */
    private val _expandedId = MutableStateFlow<Int?>(null)
    val expandedId: StateFlow<Int?> = _expandedId.asStateFlow()

    /** The one tile that is heard. */
    private val _audibleId = MutableStateFlow<Int?>(null)
    val audibleId: StateFlow<Int?> = _audibleId.asStateFlow()

    val layout: StateFlow<LiveMultiView.Layout> =
        _sessions.map { LiveMultiView.layoutFor(it.size, _expandedId.value != null) }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, LiveMultiView.Layout.SINGLE)

    /** True while the app can still fit another game — drives the "＋ Add game" cell. */
    val canAdd: StateFlow<Boolean> =
        _sessions.map { LiveMultiView.canAdd(it.size) }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, true)

    private var nextId = 0
    private val scopes = mutableMapOf<Int, CoroutineScope>()

    // --- The in-player "Add game" browser --------------------------------------------------------

    enum class PickerStep { CATEGORIES, EVENTS }

    data class Picker(
        val step: PickerStep = PickerStep.CATEGORIES,
        val categories: List<SportCategory> = emptyList(),
        val selectedCategoryId: String? = null,
        val selectedCategoryName: String = "",
        val events: List<SportEvent> = emptyList(),
        val loading: Boolean = true,
        val adding: Boolean = false,
        val error: String? = null,
    )

    private val _picker = MutableStateFlow<Picker?>(null)
    val picker: StateFlow<Picker?> = _picker.asStateFlow()

    init {
        val selection = holder.current
        diagnostics.breadcrumb("live.vm init feeds=${selection?.feeds?.size ?: 0}")
        if (selection == null || selection.feeds.isEmpty()) {
            // No first game — still a valid multiview: an empty grid whose only cell is "Add game".
            _sessions.value = emptyList()
        } else {
            val first = newSession(selection.title, selection.feeds, selection.index)
            _sessions.value = listOf(first)
            _audibleId.value = first.id
            first.start()
            applyPolicy()
        }
    }

    private fun newSession(title: String, feeds: List<LivePlaybackHolder.Feed>, index: Int): LiveSession {
        val id = nextId++
        val scope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob())
        scopes[id] = scope
        return LiveSession(
            id = id,
            title = title,
            feeds = feeds,
            startIndex = index,
            appContext = appContext,
            resolver = resolver,
            diagnostics = diagnostics,
            audioCode = { audioLanguage.value },
            scope = scope,
        )
    }

    /**
     * Re-derive every tile's audio / decoder / rendition state from the current layout. Called after
     * any structural change (add, remove, expand, collapse, change audible). This is the single place
     * the multiview resource rules from [LiveMultiView] are enforced.
     */
    private fun applyPolicy() {
        val list = _sessions.value
        val expanded = _expandedId.value
        val layout = LiveMultiView.layoutFor(list.size, expanded != null)
        // Audio must always land on a live tile; if the audible one was removed, move it.
        val audible = _audibleId.value?.takeIf { id -> list.any { it.id == id } } ?: list.firstOrNull()?.id
        if (audible != _audibleId.value) _audibleId.value = audible

        list.forEachIndexed { position, s ->
            val primary = if (expanded != null) s.id == expanded else position == 0
            s.setAudible(s.id == audible)
            s.setVideoEnabled(LiveMultiView.videoEnabledFor(s.id, expanded))
            s.setVideoCap(LiveMultiView.videoCapFor(layout, primary))
        }
    }

    fun expand(id: Int) {
        if (_sessions.value.none { it.id == id }) return
        _expandedId.value = id
        // You expanded it to watch it — so you almost certainly want to hear it too.
        _audibleId.value = id
        applyPolicy()
    }

    fun collapse() {
        _expandedId.value = null
        applyPolicy()
    }

    fun setAudible(id: Int) {
        if (_sessions.value.none { it.id == id }) return
        _audibleId.value = id
        applyPolicy()
    }

    fun switchFeed(id: Int, index: Int) {
        _sessions.value.firstOrNull { it.id == id }?.switchTo(index)
    }

    fun retry(id: Int) {
        _sessions.value.firstOrNull { it.id == id }?.retry()
    }

    fun removeSession(id: Int) {
        val list = _sessions.value
        val target = list.firstOrNull { it.id == id } ?: return
        val remaining = list.filter { it.id != id }
        // Decide the new audible tile BEFORE tearing this one down, so audio never briefly goes dead.
        _audibleId.value = LiveMultiView.audibleAfterRemoval(remaining.map { it.id }, id, _audibleId.value)
        if (_expandedId.value == id) _expandedId.value = null
        target.release()
        scopes.remove(id)?.let { (it.coroutineContext[Job] as? Job)?.cancel() }
        _sessions.value = remaining
        applyPolicy()
    }

    // --- Add-game browser actions ----------------------------------------------------------------

    fun openPicker() {
        if (!LiveMultiView.canAdd(_sessions.value.size)) return
        _picker.value = Picker(loading = true)
        viewModelScope.launch {
            when (val r = sportsRepo.categories()) {
                is DataResult.Success -> {
                    val cats = r.data.distinctBy { it.id }
                    _picker.value = _picker.value?.copy(categories = cats, loading = false)
                    cats.firstOrNull()?.let { pickerSelectCategory(it.id, it.name) }
                }
                is DataResult.Error -> _picker.value = _picker.value?.copy(loading = false, error = r.message)
            }
        }
    }

    fun pickerSelectCategory(categoryId: String, name: String) {
        val p = _picker.value ?: return
        if (p.selectedCategoryId == categoryId && p.step == PickerStep.EVENTS && !p.loading) return
        _picker.value = p.copy(
            step = PickerStep.EVENTS,
            selectedCategoryId = categoryId,
            selectedCategoryName = name,
            events = emptyList(),
            loading = true,
            error = null,
        )
        viewModelScope.launch {
            when (val r = sportsRepo.events(categoryId)) {
                is DataResult.Success -> _picker.value = _picker.value?.copy(
                    events = r.data.distinctBy { it.id }, loading = false,
                )
                is DataResult.Error -> _picker.value = _picker.value?.copy(loading = false, error = r.message)
            }
        }
    }

    /** Back out of the events list to the league list (D-pad Back inside the picker). */
    fun pickerBackToCategories() {
        _picker.value = _picker.value?.copy(step = PickerStep.CATEGORIES, error = null)
    }

    /**
     * Pick a game: resolve its feeds and drop it in as a new tile. The picker shows a spinner while
     * the streams resolve (a second or two) rather than closing onto nothing.
     */
    fun addFromEvent(event: SportEvent) {
        if (!LiveMultiView.canAdd(_sessions.value.size)) return
        _picker.value = _picker.value?.copy(adding = true, error = null)
        viewModelScope.launch {
            when (val r = sportsRepo.streams(event)) {
                is DataResult.Success -> {
                    val streams = r.data.distinctBy { it.id }
                    if (streams.isEmpty()) {
                        _picker.value = _picker.value?.copy(adding = false, error = "No live feeds for this game yet.")
                        return@launch
                    }
                    val feeds = streams.map { LivePlaybackHolder.Feed(it.label, it.url, it.headers, it.needsResolution) }
                    val session = newSession(event.title, feeds, 0)
                    _sessions.value = _sessions.value + session
                    session.start()
                    // A freshly added game is what you want to hear.
                    _audibleId.value = session.id
                    _expandedId.value = null
                    applyPolicy()
                    _picker.value = null
                    diagnostics.event("live_multiview_add", mapOf("total" to _sessions.value.size.toString()))
                }
                is DataResult.Error ->
                    _picker.value = _picker.value?.copy(adding = false, error = r.message)
            }
        }
    }

    fun closePicker() { _picker.value = null }

    override fun onCleared() {
        _sessions.value.forEach { it.release() }
        _sessions.value = emptyList()
        super.onCleared()
    }
}
