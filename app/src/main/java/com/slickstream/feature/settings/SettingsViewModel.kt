package com.slickstream.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slickstream.core.repository.TorrentStreamer
import com.slickstream.data.settings.AppSettings
import com.slickstream.data.settings.CacheSize
import com.slickstream.data.settings.QualityPreference
import com.slickstream.data.settings.SettingsRepository
import com.slickstream.data.settings.SubtitleLanguage
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
) : ViewModel() {

    val settings: StateFlow<AppSettings> = repo.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings(),
    )

    private val _cacheStats = MutableStateFlow(CacheStats())
    val cacheStats: StateFlow<CacheStats> = _cacheStats.asStateFlow()

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
    fun setWifiQuality(q: QualityPreference) = viewModelScope.launch { repo.setWifiQuality(q) }
    fun setCellularQuality(q: QualityPreference) = viewModelScope.launch { repo.setCellularQuality(q) }
    fun setDensity(d: UiDensity) = viewModelScope.launch { repo.setDensity(d) }
    fun setSubtitlesEnabled(enabled: Boolean) = viewModelScope.launch { repo.setSubtitlesEnabled(enabled) }
    fun setSubtitleLanguage(lang: SubtitleLanguage) = viewModelScope.launch { repo.setSubtitleLanguage(lang) }
}
