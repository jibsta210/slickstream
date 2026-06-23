package com.slickstream.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** UI scale options — a uniform density multiplier applied app-wide via LocalDensity. */
enum class UiDensity(val label: String, val scale: Float) {
    COMPACT("Compact", 0.85f),
    COMFORTABLE("Comfortable", 1.0f),
    SPACIOUS("Spacious", 1.12f),
}

/** Per-network cap for the auto-selected stream quality. */
enum class QualityPreference(val label: String, val maxTier: Int) {
    AUTO("Auto — best available", Int.MAX_VALUE),
    UHD_4K("Up to 4K", 4),
    FHD_1080("Up to 1080p", 3),
    HD_720("Up to 720p", 2),
    SD_480("Up to 480p", 1);

    companion object {
        /** Map a [com.slickstream.core.model.StreamSource.quality] string to a comparable tier. */
        fun tierOf(quality: String): Int = when (quality.uppercase()) {
            "4K", "2160P", "UHD" -> 4
            "1080P" -> 3
            "720P" -> 2
            "480P" -> 1
            else -> 0
        }
    }
}

/** Preferred subtitle language; [code] is the addon's 3-letter code used for ExoPlayer matching. */
enum class SubtitleLanguage(val label: String, val code: String) {
    ENGLISH("English", "eng"),
    SPANISH("Spanish", "spa"),
    FRENCH("French", "fre"),
    GERMAN("German", "ger"),
    ITALIAN("Italian", "ita"),
    PORTUGUESE("Portuguese", "por"),
    DUTCH("Dutch", "dut"),
    RUSSIAN("Russian", "rus"),
    ARABIC("Arabic", "ara"),
    HINDI("Hindi", "hin"),
}

/** Selectable cap for the on-disk torrent cache (binary GiB to match the engine budget). */
enum class CacheSize(val label: String, val bytes: Long) {
    GB_1("1 GB", 1L * 1024 * 1024 * 1024),
    GB_2("2 GB", 2L * 1024 * 1024 * 1024),
    GB_4("4 GB", 4L * 1024 * 1024 * 1024),
    GB_8("8 GB", 8L * 1024 * 1024 * 1024),
    GB_16("16 GB", 16L * 1024 * 1024 * 1024);

    companion object {
        val DEFAULT = GB_4
    }
}

data class AppSettings(
    val wifiQuality: QualityPreference = QualityPreference.AUTO,
    val cellularQuality: QualityPreference = QualityPreference.HD_720,
    val density: UiDensity = UiDensity.COMFORTABLE,
    val subtitlesEnabled: Boolean = false,
    val subtitleLanguage: SubtitleLanguage = SubtitleLanguage.ENGLISH,
    val maxCacheSize: CacheSize = CacheSize.DEFAULT,
)

/**
 * App preferences, backed by the shared DataStore. Holds the per-network default quality caps
 * and the UI density scale.
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<AppSettings> = dataStore.data.map { p ->
        AppSettings(
            wifiQuality = p[KEY_WIFI].toQuality(QualityPreference.AUTO),
            cellularQuality = p[KEY_CELL].toQuality(QualityPreference.HD_720),
            density = p[KEY_DENSITY]?.let { runCatching { UiDensity.valueOf(it) }.getOrNull() }
                ?: UiDensity.COMFORTABLE,
            subtitlesEnabled = p[KEY_SUBS_ON] ?: false,
            subtitleLanguage = p[KEY_SUB_LANG]?.let { runCatching { SubtitleLanguage.valueOf(it) }.getOrNull() }
                ?: SubtitleLanguage.ENGLISH,
            maxCacheSize = p[KEY_MAX_CACHE]?.let { runCatching { CacheSize.valueOf(it) }.getOrNull() }
                ?: CacheSize.DEFAULT,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setWifiQuality(q: QualityPreference) = dataStore.edit { it[KEY_WIFI] = q.name }
    suspend fun setCellularQuality(q: QualityPreference) = dataStore.edit { it[KEY_CELL] = q.name }
    suspend fun setDensity(d: UiDensity) = dataStore.edit { it[KEY_DENSITY] = d.name }
    suspend fun setSubtitlesEnabled(enabled: Boolean) = dataStore.edit { it[KEY_SUBS_ON] = enabled }
    suspend fun setSubtitleLanguage(lang: SubtitleLanguage) = dataStore.edit { it[KEY_SUB_LANG] = lang.name }
    suspend fun setMaxCacheSize(size: CacheSize) = dataStore.edit { it[KEY_MAX_CACHE] = size.name }

    private fun String?.toQuality(default: QualityPreference): QualityPreference =
        this?.let { runCatching { QualityPreference.valueOf(it) }.getOrNull() } ?: default

    private companion object {
        val KEY_WIFI = stringPreferencesKey("quality_wifi")
        val KEY_CELL = stringPreferencesKey("quality_cellular")
        val KEY_DENSITY = stringPreferencesKey("ui_density")
        val KEY_SUBS_ON = booleanPreferencesKey("subs_enabled")
        val KEY_SUB_LANG = stringPreferencesKey("sub_language")
        val KEY_MAX_CACHE = stringPreferencesKey("max_cache_size")
    }
}
