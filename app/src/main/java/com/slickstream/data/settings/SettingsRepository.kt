package com.slickstream.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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

/**
 * Bias the torrent auto-pick toward smaller files vs higher bitrate, WITHIN the resolution cap.
 * A 1080p episode can be 700 MB or 4 GB; this picks where on that range to land so you don't pull
 * a 10 GB remux when a lean web-dl will do.
 */
enum class StreamSizePreference(val label: String) {
    SMALLEST("Smaller files"),
    BALANCED("Balanced"),
    HIGHEST("Best quality"),
    ;

    companion object {
        val DEFAULT = BALANCED
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

/** Subtitle text size, as a fraction of the view height (Media3 SubtitleView.setFractionalTextSize). */
enum class SubtitleSize(val label: String, val fraction: Float) {
    // Fraction of the VIEW HEIGHT, so it scales with the panel — which is why the old values looked
    // huge on 4K (0.045 x 2160 ≈ 97 px even for "Small"). The whole scale is shifted down: Small is now
    // genuinely tiny, and the default (Medium) sits below the Media3 default of 0.0533, which read as
    // oversized on a TV.
    SMALL("Small", 0.028f),
    MEDIUM("Medium", 0.040f),
    LARGE("Large", 0.056f),
    XLARGE("Extra large", 0.075f),
    ;

    companion object {
        val DEFAULT = MEDIUM
    }
}

/** Subtitle appearance preset (mapped to a Media3 CaptionStyleCompat in the player). */
enum class SubtitleStyle(val label: String) {
    DROP_SHADOW("White · shadow"),
    OUTLINE("White · outline"),
    BLACK_BOX("White on black"),
    YELLOW("Yellow · shadow"),
    ;

    companion object {
        val DEFAULT = DROP_SHADOW
    }
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
    val subtitleSize: SubtitleSize = SubtitleSize.DEFAULT,
    val subtitleStyle: SubtitleStyle = SubtitleStyle.DEFAULT,
    val streamSize: StreamSizePreference = StreamSizePreference.DEFAULT,
    val maxCacheSize: CacheSize = CacheSize.DEFAULT,
    /** Screen calibration (TV overscan/underscan fit): uniform scale of the whole app (1f = none) and
     *  a positional nudge in dp. Lets the user grow/shrink + center the picture to their panel. */
    val screenScale: Float = 1f,
    val screenOffsetX: Float = 0f,
    val screenOffsetY: Float = 0f,
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
            subtitleSize = p[KEY_SUB_SIZE]?.let { runCatching { SubtitleSize.valueOf(it) }.getOrNull() }
                ?: SubtitleSize.DEFAULT,
            subtitleStyle = p[KEY_SUB_STYLE]?.let { runCatching { SubtitleStyle.valueOf(it) }.getOrNull() }
                ?: SubtitleStyle.DEFAULT,
            streamSize = p[KEY_STREAM_SIZE]?.let { runCatching { StreamSizePreference.valueOf(it) }.getOrNull() }
                ?: StreamSizePreference.DEFAULT,
            maxCacheSize = p[KEY_MAX_CACHE]?.let { runCatching { CacheSize.valueOf(it) }.getOrNull() }
                ?: CacheSize.DEFAULT,
            screenScale = (p[KEY_SCREEN_SCALE] ?: 1f).coerceIn(0.80f, 1.20f),
            screenOffsetX = (p[KEY_SCREEN_OFF_X] ?: 0f).coerceIn(-200f, 200f),
            screenOffsetY = (p[KEY_SCREEN_OFF_Y] ?: 0f).coerceIn(-200f, 200f),
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setWifiQuality(q: QualityPreference) = dataStore.edit { it[KEY_WIFI] = q.name }
    suspend fun setCellularQuality(q: QualityPreference) = dataStore.edit { it[KEY_CELL] = q.name }
    suspend fun setDensity(d: UiDensity) = dataStore.edit { it[KEY_DENSITY] = d.name }
    suspend fun setSubtitlesEnabled(enabled: Boolean) = dataStore.edit { it[KEY_SUBS_ON] = enabled }
    suspend fun setSubtitleLanguage(lang: SubtitleLanguage) = dataStore.edit { it[KEY_SUB_LANG] = lang.name }
    suspend fun setSubtitleSize(size: SubtitleSize) = dataStore.edit { it[KEY_SUB_SIZE] = size.name }
    suspend fun setSubtitleStyle(style: SubtitleStyle) = dataStore.edit { it[KEY_SUB_STYLE] = style.name }
    suspend fun setStreamSize(s: StreamSizePreference) = dataStore.edit { it[KEY_STREAM_SIZE] = s.name }
    suspend fun setMaxCacheSize(size: CacheSize) = dataStore.edit { it[KEY_MAX_CACHE] = size.name }
    suspend fun setScreenCalibration(scale: Float, offsetX: Float, offsetY: Float) = dataStore.edit {
        it[KEY_SCREEN_SCALE] = scale.coerceIn(0.80f, 1.20f)
        it[KEY_SCREEN_OFF_X] = offsetX.coerceIn(-200f, 200f)
        it[KEY_SCREEN_OFF_Y] = offsetY.coerceIn(-200f, 200f)
    }

    private fun String?.toQuality(default: QualityPreference): QualityPreference =
        this?.let { runCatching { QualityPreference.valueOf(it) }.getOrNull() } ?: default

    private companion object {
        val KEY_WIFI = stringPreferencesKey("quality_wifi")
        val KEY_CELL = stringPreferencesKey("quality_cellular")
        val KEY_DENSITY = stringPreferencesKey("ui_density")
        val KEY_SUBS_ON = booleanPreferencesKey("subs_enabled")
        val KEY_SUB_LANG = stringPreferencesKey("sub_language")
        val KEY_SUB_SIZE = stringPreferencesKey("sub_size")
        val KEY_SUB_STYLE = stringPreferencesKey("sub_style")
        val KEY_STREAM_SIZE = stringPreferencesKey("stream_size")
        val KEY_MAX_CACHE = stringPreferencesKey("max_cache_size")
        val KEY_SCREEN_SCALE = floatPreferencesKey("screen_scale")
        val KEY_SCREEN_OFF_X = floatPreferencesKey("screen_offset_x")
        val KEY_SCREEN_OFF_Y = floatPreferencesKey("screen_offset_y")
    }
}
