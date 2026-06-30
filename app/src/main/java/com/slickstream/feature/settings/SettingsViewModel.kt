package com.slickstream.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slickstream.core.repository.TorrentStreamer
import com.slickstream.data.sync.FirebaseSync
import com.slickstream.data.settings.AppSettings
import com.slickstream.data.settings.CacheSize
import com.slickstream.data.settings.QualityPreference
import com.slickstream.data.settings.SettingsRepository
import com.slickstream.data.settings.SubtitleLanguage
import com.slickstream.data.settings.SubtitleSize
import com.slickstream.data.settings.SubtitleStyle
import com.slickstream.data.settings.UiDensity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class CacheStats(
    val sizeBytes: Long = 0L,
    val titleCount: Int = 0,
    val loading: Boolean = true,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val torrentStreamer: TorrentStreamer,
    private val firebaseSync: FirebaseSync,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = repo.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings(),
    )

    private val _cacheStats = MutableStateFlow(CacheStats())
    val cacheStats: StateFlow<CacheStats> = _cacheStats.asStateFlow()

    /** Result of the last "Test cloud sync" run (null = not run / dismissed). */
    private val _syncDiagnostic = MutableStateFlow<String?>(null)
    val syncDiagnostic: StateFlow<String?> = _syncDiagnostic.asStateFlow()

    /** Run the live sync self-test (auth + Firestore round-trip) and surface the human-readable result. */
    fun testSync() {
        _syncDiagnostic.value = "Testing cloud sync…"
        viewModelScope.launch { _syncDiagnostic.value = firebaseSync.diagnose() }
    }

    fun dismissSyncDiagnostic() { _syncDiagnostic.value = null }

    init { refreshCacheStats() }

    fun refreshCacheStats() {
        viewModelScope.launch {
            _cacheStats.value = withContext(Dispatchers.IO) {
                CacheStats(
                    sizeBytes = torrentStreamer.cacheSizeBytes(),
                    titleCount = torrentStreamer.cachedTorrents().size,
                    loading = false,
                )
            }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            _cacheStats.value = _cacheStats.value.copy(loading = true)
            torrentStreamer.clearCache() // suspend, hops to IO inside the impl
            refreshCacheStats()
        }
    }

    fun setMaxCacheSize(size: CacheSize) = viewModelScope.launch { repo.setMaxCacheSize(size) }
    fun setCustomSourceUrl(url: String) = viewModelScope.launch { repo.setCustomSourceUrl(url) }
    fun setWifiQuality(q: QualityPreference) = viewModelScope.launch { repo.setWifiQuality(q) }
    fun setCellularQuality(q: QualityPreference) = viewModelScope.launch { repo.setCellularQuality(q) }
    fun setDensity(d: UiDensity) = viewModelScope.launch { repo.setDensity(d) }
    fun setSubtitlesEnabled(enabled: Boolean) = viewModelScope.launch { repo.setSubtitlesEnabled(enabled) }
    fun setSubtitleLanguage(lang: SubtitleLanguage) = viewModelScope.launch { repo.setSubtitleLanguage(lang) }
    fun setSubtitleSize(size: SubtitleSize) = viewModelScope.launch { repo.setSubtitleSize(size) }
    fun setSubtitleStyle(style: SubtitleStyle) = viewModelScope.launch { repo.setSubtitleStyle(style) }
    fun setStreamSize(s: com.slickstream.data.settings.StreamSizePreference) =
        viewModelScope.launch { repo.setStreamSize(s) }
    fun setUpNextPercent(pct: Int) = viewModelScope.launch { repo.setUpNextPercent(pct) }
    fun setMovieBarPercent(pct: Int) = viewModelScope.launch { repo.setMovieBarPercent(pct) }

    /**
     * Screen calibration (TV fit). The live preview updates an in-memory value instantly on every
     * D-pad nudge ([setLiveCalibration], no disk), and we persist once when the user leaves the
     * calibration screen ([commitScreenCalibration]). Writing DataStore per keypress used to re-emit
     * the whole settings flow and re-fit the app on every press — janky, and it tore focus apart.
     */
    fun setLiveCalibration(scale: Float, offsetX: Float, offsetY: Float) =
        repo.setLiveCalibration(scale, offsetX, offsetY)

    fun commitScreenCalibration(scale: Float, offsetX: Float, offsetY: Float) =
        viewModelScope.launch { repo.commitScreenCalibration(scale, offsetX, offsetY) }
}
