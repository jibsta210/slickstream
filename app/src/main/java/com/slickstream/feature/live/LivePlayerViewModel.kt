package com.slickstream.feature.live

import android.content.Context
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.slickstream.core.common.DeviceProfile
import com.slickstream.core.model.DataResult
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.core.repository.CatalogRepository
import com.slickstream.core.repository.LibraryRepository
import com.slickstream.core.repository.SourceRepository
import com.slickstream.core.repository.TorrentStreamer
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Coordinates MULTIVIEW: one to [LiveMultiView.MAX_SLOTS] tiles at once, each a self-contained
 * [TileSession] — a live game ([LiveSession]) or a movie/episode ([MediaSession]). One tile is the
 * ordinary full-screen player; two is picture-in-picture; three or four is a 2x2 grid. This class
 * only decides the layout, which tile is heard, which tiles may keep a decoder, and how a new tile
 * gets in.
 *
 * ADDING IS TYPE-FIRST. The "Add" browser opens on a single flat list: Continue watching,
 * Favourites, then every sport. It deliberately does NOT jump into the sport you are already
 * watching — when you are in a film and want a game beside it, or in the NFL and want the NHL, the
 * first question is "what kind of thing", and that is the first row. Media is limited to Continue
 * watching and Favourites on purpose: those are the titles you actually mean when you say "put my
 * show on the side", and anything wider would be the whole catalogue in a side panel.
 */
@OptIn(UnstableApi::class)
@HiltViewModel
class LivePlayerViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val holder: LivePlaybackHolder,
    private val resolver: WebViewStreamResolver,
    private val sportsRepo: SportsRepository,
    private val catalogRepository: CatalogRepository,
    private val sourceRepository: SourceRepository,
    private val torrentStreamer: TorrentStreamer,
    private val libraryRepository: LibraryRepository,
    private val deviceProfile: DeviceProfile,
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

    private val _sessions = MutableStateFlow<List<TileSession>>(emptyList())
    val sessions: StateFlow<List<TileSession>> = _sessions.asStateFlow()

    private val _expandedId = MutableStateFlow<Int?>(null)
    val expandedId: StateFlow<Int?> = _expandedId.asStateFlow()

    private val _audibleId = MutableStateFlow<Int?>(null)
    val audibleId: StateFlow<Int?> = _audibleId.asStateFlow()

    val layout: StateFlow<LiveMultiView.Layout> =
        _sessions.map { LiveMultiView.layoutFor(it.size, _expandedId.value != null) }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, LiveMultiView.Layout.SINGLE)

    val canAdd: StateFlow<Boolean> =
        _sessions.map { LiveMultiView.canAdd(it.size) }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, true)

    private var nextId = 0
    private val scopes = mutableMapOf<Int, CoroutineScope>()

    // --- The "Add" browser -----------------------------------------------------------------------

    enum class PickerStep { ROOT, EVENTS, MEDIA }
    enum class MediaListKind(val label: String) { CONTINUE("Continue watching"), FAVOURITES("Favourites") }

    /** One row in a media list: what to play, and where from. */
    data class MediaPick(
        val item: MediaItem,
        val season: Int?,
        val episode: Int?,
        val positionMs: Long,
        val durationMs: Long,
        /** "S2 E4 · 37%" / "Movie · resume 1:12" / "" */
        val subtitle: String,
    )

    data class Picker(
        val step: PickerStep = PickerStep.ROOT,
        val categories: List<SportCategory> = emptyList(),
        val selectedCategoryName: String = "",
        val events: List<SportEvent> = emptyList(),
        val mediaKind: MediaListKind? = null,
        val media: List<MediaPick> = emptyList(),
        val loading: Boolean = true,
        val adding: Boolean = false,
        val error: String? = null,
    )

    private val _picker = MutableStateFlow<Picker?>(null)
    val picker: StateFlow<Picker?> = _picker.asStateFlow()

    init {
        val mediaSeed = holder.consumeMediaSeed()
        val selection = holder.current
        diagnostics.breadcrumb("mv.vm init media=${mediaSeed != null} feeds=${selection?.feeds?.size ?: 0}")
        val first: TileSession? = when {
            mediaSeed != null -> newMediaSession(mediaSeed)
            selection != null && selection.feeds.isNotEmpty() ->
                newLiveSession(selection.title, selection.feeds, selection.index)
            else -> null
        }
        if (first != null) {
            _sessions.value = listOf(first)
            _audibleId.value = first.id
            when (first) {
                is LiveSession -> first.start()
                is MediaSession -> first.start()
            }
            applyPolicy()
        }
    }

    private fun sessionScope(id: Int): CoroutineScope =
        CoroutineScope(viewModelScope.coroutineContext + SupervisorJob()).also { scopes[id] = it }

    private fun newLiveSession(title: String, feeds: List<LivePlaybackHolder.Feed>, index: Int): LiveSession {
        val id = nextId++
        return LiveSession(
            id = id, title = title, feeds = feeds, startIndex = index,
            appContext = appContext, resolver = resolver, diagnostics = diagnostics,
            audioCode = { audioLanguage.value }, scope = sessionScope(id),
        )
    }

    private fun newMediaSession(seed: LivePlaybackHolder.MediaSeed): MediaSession {
        val id = nextId++
        return MediaSession(
            id = id, seed = seed, appContext = appContext,
            catalogRepository = catalogRepository, sourceRepository = sourceRepository,
            torrentStreamer = torrentStreamer, libraryRepository = libraryRepository,
            settingsRepository = settingsRepository, deviceProfile = deviceProfile,
            diagnostics = diagnostics, audioCode = { audioLanguage.value }, scope = sessionScope(id),
        )
    }

    /** Re-derive every tile's audio / decoder / rendition state from the layout — the one place [LiveMultiView] is enforced. */
    private fun applyPolicy() {
        val list = _sessions.value
        val expanded = _expandedId.value
        val layout = LiveMultiView.layoutFor(list.size, expanded != null)
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
        _audibleId.value = id
        applyPolicy()
    }

    fun collapse() { _expandedId.value = null; applyPolicy() }

    /**
     * Make [id] the PRIMARY tile — the big picture in PiP, top-left in the grid — WITHOUT hiding
     * anyone. Position 0 in the list is the primary by definition, so this is a reorder; the layout
     * and the other tile both survive. "Watch full screen" is the other operation: it expands one
     * tile and hides the rest, which in a two-tile PiP read as the small game simply vanishing rather
     * than the two trading places — and trading places is what people mean there.
     */
    fun swapToPrimary(id: Int) {
        val list = _sessions.value
        val target = list.firstOrNull { it.id == id } ?: return
        if (list.firstOrNull()?.id == id) return
        _sessions.value = listOf(target) + list.filter { it.id != id }
        // You promoted it to the big picture — you want to hear it.
        _audibleId.value = id
        _expandedId.value = null
        applyPolicy()
    }

    fun setAudible(id: Int) {
        if (_sessions.value.none { it.id == id }) return
        _audibleId.value = id
        applyPolicy()
    }

    fun switchFeed(id: Int, index: Int) {
        (_sessions.value.firstOrNull { it.id == id } as? LiveSession)?.switchTo(index)
    }

    fun retry(id: Int) { _sessions.value.firstOrNull { it.id == id }?.retry() }

    fun removeSession(id: Int) {
        val list = _sessions.value
        val target = list.firstOrNull { it.id == id } ?: return
        val remaining = list.filter { it.id != id }
        _audibleId.value = LiveMultiView.audibleAfterRemoval(remaining.map { it.id }, id, _audibleId.value)
        if (_expandedId.value == id) _expandedId.value = null
        target.release()
        scopes.remove(id)?.let { (it.coroutineContext[Job] as? Job)?.cancel() }
        _sessions.value = remaining
        applyPolicy()
    }

    private fun addSession(session: TileSession) {
        _sessions.value = _sessions.value + session
        when (session) {
            is LiveSession -> session.start()
            is MediaSession -> session.start()
        }
        // A freshly added tile is what you want to hear.
        _audibleId.value = session.id
        _expandedId.value = null
        applyPolicy()
        _picker.value = null
        diagnostics.event("live_multiview_add", mapOf("total" to _sessions.value.size.toString()))
    }

    // --- Add-browser actions ---------------------------------------------------------------------

    /** Opens on the ROOT list: media kinds + every sport. Never auto-descends. */
    fun openPicker() {
        if (!LiveMultiView.canAdd(_sessions.value.size)) return
        _picker.value = Picker(loading = true)
        viewModelScope.launch {
            when (val r = sportsRepo.categories()) {
                is DataResult.Success ->
                    _picker.value = _picker.value?.copy(categories = r.data.distinctBy { it.id }, loading = false)
                // Sports failing must not take the media rows down with it.
                is DataResult.Error -> _picker.value = _picker.value?.copy(loading = false)
            }
        }
    }

    fun pickerSelectCategory(categoryId: String, name: String) {
        _picker.value = _picker.value?.copy(
            step = PickerStep.EVENTS, selectedCategoryName = name, events = emptyList(), loading = true, error = null,
        )
        viewModelScope.launch {
            when (val r = sportsRepo.events(categoryId)) {
                is DataResult.Success -> _picker.value = _picker.value?.copy(events = r.data.distinctBy { it.id }, loading = false)
                is DataResult.Error -> _picker.value = _picker.value?.copy(loading = false, error = r.message)
            }
        }
    }

    fun pickerSelectMedia(kind: MediaListKind) {
        _picker.value = _picker.value?.copy(step = PickerStep.MEDIA, mediaKind = kind, media = emptyList(), loading = true, error = null)
        viewModelScope.launch {
            val picks = runCatching {
                when (kind) {
                    MediaListKind.CONTINUE -> libraryRepository.observeHistory().first()
                        .filter { !it.progress.isFinished }
                        .map { h ->
                            MediaPick(
                                item = h.media, season = h.progress.season, episode = h.progress.episode,
                                positionMs = h.progress.positionMs, durationMs = h.progress.durationMs,
                                subtitle = resumeSubtitle(h.progress.season, h.progress.episode, h.progress.percent),
                            )
                        }
                    MediaListKind.FAVOURITES -> {
                        val history = libraryRepository.observeHistory().first()
                        libraryRepository.observeFavorites().first().map { f ->
                            // Resume the most recent unfinished spot for this title if there is one;
                            // otherwise a movie starts at 0 and a show at S1 E1.
                            val h = history
                                .filter { it.media.id == f.media.id && it.media.mediaType == f.media.mediaType && !it.progress.isFinished }
                                .maxByOrNull { it.progress.updatedAt }
                            val isTv = f.media.mediaType == MediaType.TV
                            MediaPick(
                                item = f.media,
                                season = h?.progress?.season ?: if (isTv) 1 else null,
                                episode = h?.progress?.episode ?: if (isTv) 1 else null,
                                positionMs = h?.progress?.positionMs ?: 0L,
                                durationMs = h?.progress?.durationMs ?: 0L,
                                subtitle = h?.let { resumeSubtitle(it.progress.season, it.progress.episode, it.progress.percent) }
                                    ?: if (isTv) "S1 E1 · from the start" else "Movie",
                            )
                        }
                    }
                }
            }.getOrElse { emptyList() }
            _picker.value = _picker.value?.copy(media = picks, loading = false)
        }
    }

    private fun resumeSubtitle(season: Int?, episode: Int?, percent: Float): String {
        val ep = if (season != null && episode != null) "S$season E$episode" else "Movie"
        val pct = (percent * 100).toInt()
        return if (pct > 0) "$ep · $pct% watched" else ep
    }

    fun pickerBack() {
        _picker.value = _picker.value?.copy(step = PickerStep.ROOT, error = null)
    }

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
                    addSession(newLiveSession(event.title, feeds, 0))
                }
                is DataResult.Error -> _picker.value = _picker.value?.copy(adding = false, error = r.message)
            }
        }
    }

    fun addFromMedia(pick: MediaPick) {
        if (!LiveMultiView.canAdd(_sessions.value.size)) return
        addSession(
            newMediaSession(
                LivePlaybackHolder.MediaSeed(pick.item, pick.season, pick.episode, pick.positionMs, pick.durationMs),
            ),
        )
    }

    fun closePicker() { _picker.value = null }

    override fun onCleared() {
        _sessions.value.forEach { it.release() }
        _sessions.value = emptyList()
        super.onCleared()
    }
}
