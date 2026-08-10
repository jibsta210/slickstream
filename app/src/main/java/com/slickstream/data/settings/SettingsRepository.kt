package com.slickstream.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    /** When (as a % of runtime) the end-of-content prompts appear: the next-EPISODE "Up next" card and
     *  the end-of-MOVIE "similar titles" bar (movies later — a 2-hour film's last 5% is still ~6 min). */
    val upNextPercent: Int = 95,
    val movieBarPercent: Int = 97,
    /** Screen calibration (TV overscan/underscan fit): uniform scale of the whole app (1f = none) and
     *  a positional nudge in dp. Lets the user grow/shrink + center the picture to their panel. */
    val screenScale: Float = 1f,
    val screenOffsetX: Float = 0f,
    val screenOffsetY: Float = 0f,
    /** A user-supplied streaming-source addon URL (e.g. a debrid-backed Torrentio that returns instant
     *  cached direct streams). Queried FIRST, before the built-in indexer + auto-discovered addons.
     *  Comma-separated for multiple. Contains a private API token, so it is entered per-device (never
     *  baked into the shipped APK) — but DOES sync to the user's other signed-in devices for convenience
     *  (painful to type a long URL on a TV remote). */
    val customSourceUrl: String = "",
    /** Offline DOWNLOAD quality cap (separate from streaming): default ≤720p so a whole-season cache is
     *  lean (~300 MB episodes), overridable per the size preference below. */
    val downloadQuality: QualityPreference = QualityPreference.HD_720,
    /** Within [downloadQuality], bias downloads toward smaller files vs best bitrate. Default SMALLEST
     *  (the "cache a season in 720p/300 MB" case). */
    val downloadSize: StreamSizePreference = StreamSizePreference.SMALLEST,
    /** Hide Indian-language films & TV (Hindi/Tamil/Telugu/…) from every listing. Per-device. */
    val hideIndianContent: Boolean = false,
)

/** Screen-calibration triple (uniform scale + dp shift) used for the live, in-memory preview. */
data class ScreenCalibration(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

/**
 * App preferences, backed by the shared DataStore. Holds the per-network default quality caps
 * and the UI density scale.
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    /**
     * Live, in-memory calibration override used ONLY while the user is on the calibration screen.
     * null = no override active → the app uses the persisted [AppSettings] values. This decouples the
     * live preview from DataStore so each D-pad nudge updates instantly (no disk write per keypress) and
     * does NOT re-emit the whole settings flow; the value is persisted once, on leaving the screen.
     */
    private val _liveCalibration = MutableStateFlow<ScreenCalibration?>(null)
    val liveCalibration: StateFlow<ScreenCalibration?> = _liveCalibration.asStateFlow()

    /** Instant in-memory update for the calibration preview (no disk). */
    fun setLiveCalibration(scale: Float, offsetX: Float, offsetY: Float) {
        _liveCalibration.value = ScreenCalibration(
            scale = scale.coerceIn(SCALE_MIN, SCALE_MAX),
            offsetX = offsetX.coerceIn(OFFSET_MIN, OFFSET_MAX),
            offsetY = offsetY.coerceIn(OFFSET_MIN, OFFSET_MAX),
        )
    }

    /** Persist the calibration AND keep the live override in sync. Call on leaving the screen. */
    suspend fun commitScreenCalibration(scale: Float, offsetX: Float, offsetY: Float) {
        setLiveCalibration(scale, offsetX, offsetY)
        setScreenCalibration(scale, offsetX, offsetY)
    }

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
            upNextPercent = (p[KEY_UP_NEXT_PCT] ?: 95).coerceIn(PCT_MIN, PCT_MAX),
            movieBarPercent = (p[KEY_MOVIE_BAR_PCT] ?: 97).coerceIn(PCT_MIN, PCT_MAX),
            screenScale = (p[KEY_SCREEN_SCALE] ?: 1f).coerceIn(SCALE_MIN, SCALE_MAX),
            screenOffsetX = (p[KEY_SCREEN_OFF_X] ?: 0f).coerceIn(OFFSET_MIN, OFFSET_MAX),
            screenOffsetY = (p[KEY_SCREEN_OFF_Y] ?: 0f).coerceIn(OFFSET_MIN, OFFSET_MAX),
            customSourceUrl = p[KEY_CUSTOM_SOURCE] ?: "",
            downloadQuality = p[KEY_DL_QUALITY].toQuality(QualityPreference.HD_720),
            downloadSize = p[KEY_DL_SIZE]?.let { runCatching { StreamSizePreference.valueOf(it) }.getOrNull() }
                ?: StreamSizePreference.SMALLEST,
            hideIndianContent = p[KEY_HIDE_INDIAN] ?: false,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    // Every SYNCED-field setter stamps the LWW clock at write time (not just at push time): a change
    // made while signed out — or lost in the push debounce window when the app dies — otherwise keeps
    // a stale syncedUpdatedAt, and the next sign-in lets an OLDER remote doc overwrite the newer local
    // value. Device-local settings (cache size, calibration) deliberately don't stamp.
    suspend fun setWifiQuality(q: QualityPreference) = dataStore.edit { it[KEY_WIFI] = q.name; it.stampSynced() }
    suspend fun setCellularQuality(q: QualityPreference) = dataStore.edit { it[KEY_CELL] = q.name; it.stampSynced() }
    suspend fun setDensity(d: UiDensity) = dataStore.edit { it[KEY_DENSITY] = d.name; it.stampSynced() }
    suspend fun setSubtitlesEnabled(enabled: Boolean) = dataStore.edit { it[KEY_SUBS_ON] = enabled; it.stampSynced() }
    suspend fun setSubtitleLanguage(lang: SubtitleLanguage) = dataStore.edit { it[KEY_SUB_LANG] = lang.name; it.stampSynced() }
    suspend fun setSubtitleSize(size: SubtitleSize) = dataStore.edit { it[KEY_SUB_SIZE] = size.name; it.stampSynced() }
    suspend fun setSubtitleStyle(style: SubtitleStyle) = dataStore.edit { it[KEY_SUB_STYLE] = style.name; it.stampSynced() }
    suspend fun setStreamSize(s: StreamSizePreference) = dataStore.edit { it[KEY_STREAM_SIZE] = s.name; it.stampSynced() }
    suspend fun setMaxCacheSize(size: CacheSize) = dataStore.edit { it[KEY_MAX_CACHE] = size.name }
    suspend fun setDownloadQuality(q: QualityPreference) = dataStore.edit { it[KEY_DL_QUALITY] = q.name }
    suspend fun setDownloadSize(s: StreamSizePreference) = dataStore.edit { it[KEY_DL_SIZE] = s.name }
    suspend fun setHideIndianContent(hide: Boolean) = dataStore.edit { it[KEY_HIDE_INDIAN] = hide }
    /**
     * Accept a bare Real-Debrid API token and turn it into a configured Torrentio base.
     *
     * WHY THIS EXISTS: Torrentio only consults Real-Debrid when the ADDON URL ITSELF carries the config
     * ("…/realdebrid=<token>/"). The built-in indexer is the PLAIN public endpoint, which never talks to
     * RD at all — so a paid RD subscription did precisely nothing for source finding unless the user
     * happened to paste a full configured URL here by hand. A cached RD release streams instantly as a
     * direct HTTP file: no swarm, no EOF-index wait, none of the startup cost this app fights.
     *
     * Pass-through: anything that already looks like a URL is stored verbatim (so a hand-built or
     * multi-service config, e.g. premiumize/alldebrid, still works). Only a bare token is wrapped.
     */
    suspend fun setDebridToken(token: String) {
        setCustomSourceUrl(normalizeSourceInput(token))
    }

    /**
     * Turn whatever the user pasted into something the resolver can actually query.
     *
     * The old heuristic ("contains a slash -> it's a URL, store verbatim") silently accepted three
     * plausible pastes that then never worked, while the UI still showed a green "✓ Custom source
     * active": the /configure page's Install button yields a `stremio://…` link, a copied address is
     * often schemeless (`torrentio.strem.fun/realdebrid=…/`), and people paste `realdebrid=<token>`.
     * Each is normalised here instead of failing invisibly at request time.
     */
    internal fun normalizeSourceInput(raw: String): String = normalizeSourceInputImpl(raw)

    private fun normalizeSourceInputImpl(raw: String): String =
        normalizeSourceInputForTest(raw)


    suspend fun setCustomSourceUrl(url: String) = dataStore.edit {
        it[KEY_CUSTOM_SOURCE] = url.trim()
        it.stampSynced()
    }
    suspend fun setUpNextPercent(pct: Int) = dataStore.edit { it[KEY_UP_NEXT_PCT] = pct.coerceIn(PCT_MIN, PCT_MAX); it.stampSynced() }
    suspend fun setMovieBarPercent(pct: Int) = dataStore.edit { it[KEY_MOVIE_BAR_PCT] = pct.coerceIn(PCT_MIN, PCT_MAX); it.stampSynced() }

    /** LWW clock bump for a synced-field write. */
    private fun MutablePreferences.stampSynced() {
        this[KEY_SYNC_UPDATED] = System.currentTimeMillis()
    }
    suspend fun setScreenCalibration(scale: Float, offsetX: Float, offsetY: Float) = dataStore.edit {
        it[KEY_SCREEN_SCALE] = scale.coerceIn(SCALE_MIN, SCALE_MAX)
        it[KEY_SCREEN_OFF_X] = offsetX.coerceIn(OFFSET_MIN, OFFSET_MAX)
        it[KEY_SCREEN_OFF_Y] = offsetY.coerceIn(OFFSET_MIN, OFFSET_MAX)
    }

    // --- Cloud sync surface (device-AGNOSTIC subset) ----------------------------------------------
    // The cloud mirrors only these account-level preferences. Screen calibration (panel overscan) and
    // cache size (per-device storage) are deliberately EXCLUDED — they're physically device-specific.
    // [SYNC_UPDATED_KEY] is a last-write-wins clock so the freshest change wins on sign-in.

    /** The synced fields as a flat map (enum NAMEs for stability). No timestamp — the coordinator
     *  stamps "updatedAt" at push time. */
    suspend fun syncedSettingsMap(): Map<String, Any?> {
        val s = current()
        return mapOf(
            "wifiQuality" to s.wifiQuality.name,
            "cellularQuality" to s.cellularQuality.name,
            "density" to s.density.name,
            "subtitlesEnabled" to s.subtitlesEnabled,
            "subtitleLanguage" to s.subtitleLanguage.name,
            "subtitleSize" to s.subtitleSize.name,
            "subtitleStyle" to s.subtitleStyle.name,
            "streamSize" to s.streamSize.name,
            "upNextPercent" to s.upNextPercent,
            "movieBarPercent" to s.movieBarPercent,
            "customSourceUrl" to s.customSourceUrl,
        )
    }

    /** A stable content signature over the synced fields (ignores updatedAt) — lets the coordinator
     *  skip echo-pushes when a write merely reflects a value we just applied from the cloud. */
    fun syncedSignature(map: Map<String, Any?>): String =
        SYNCED_KEYS.joinToString("|") { "${it}=${map[it]}" }

    /** When the local synced settings were last changed (ms). 0 = never (defaults). */
    suspend fun syncedUpdatedAt(): Long = dataStore.data.first()[KEY_SYNC_UPDATED] ?: 0L

    suspend fun stampSyncedUpdated(ts: Long) = dataStore.edit { it[KEY_SYNC_UPDATED] = ts }

    /** Apply a settings doc pulled from the cloud, writing only the synced fields (unknown/garbage
     *  values are ignored, keeping the current local value). Stamps the local LWW clock to the
     *  remote's updatedAt so we don't immediately re-push. */
    suspend fun applySyncedSettings(remote: Map<String, Any?>) {
        val ts = (remote["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
        dataStore.edit { p ->
            (remote["wifiQuality"] as? String)?.let { if (it.isQuality()) p[KEY_WIFI] = it }
            (remote["cellularQuality"] as? String)?.let { if (it.isQuality()) p[KEY_CELL] = it }
            (remote["density"] as? String)?.let { v -> if (runCatching { UiDensity.valueOf(v) }.isSuccess) p[KEY_DENSITY] = v }
            (remote["subtitlesEnabled"] as? Boolean)?.let { p[KEY_SUBS_ON] = it }
            (remote["subtitleLanguage"] as? String)?.let { v -> if (runCatching { SubtitleLanguage.valueOf(v) }.isSuccess) p[KEY_SUB_LANG] = v }
            (remote["subtitleSize"] as? String)?.let { v -> if (runCatching { SubtitleSize.valueOf(v) }.isSuccess) p[KEY_SUB_SIZE] = v }
            (remote["subtitleStyle"] as? String)?.let { v -> if (runCatching { SubtitleStyle.valueOf(v) }.isSuccess) p[KEY_SUB_STYLE] = v }
            (remote["streamSize"] as? String)?.let { v -> if (runCatching { StreamSizePreference.valueOf(v) }.isSuccess) p[KEY_STREAM_SIZE] = v }
            (remote["upNextPercent"] as? Number)?.toInt()?.let { p[KEY_UP_NEXT_PCT] = it.coerceIn(PCT_MIN, PCT_MAX) }
            (remote["movieBarPercent"] as? Number)?.toInt()?.let { p[KEY_MOVIE_BAR_PCT] = it.coerceIn(PCT_MIN, PCT_MAX) }
            // Only adopt a NON-BLANK remote custom source — a device that never set one must not wipe a
            // URL another device configured (whole-doc LWW would otherwise clobber it). Clearing is local.
            // Validated, not trusted. This value now carries a Real-Debrid credential AND decides which
            // host every source request goes to, so a doc that was ever written by anything other than
            // this user must not be able to repoint the app at an arbitrary server. Accept https only.
            (remote["customSourceUrl"] as? String)?.let {
                if (it.isNotBlank() && it.startsWith("https://", ignoreCase = true)) p[KEY_CUSTOM_SOURCE] = it
            }
            p[KEY_SYNC_UPDATED] = ts
        }
    }

    private fun String.isQuality(): Boolean = runCatching { QualityPreference.valueOf(this) }.isSuccess

    private fun String?.toQuality(default: QualityPreference): QualityPreference =
        this?.let { runCatching { QualityPreference.valueOf(it) }.getOrNull() } ?: default

    companion object {
        /** Pure entry point for [normalizeSourceInput] so its paste-handling can be unit-tested without
         *  a DataStore. Kept beside the implementation so the two cannot drift. */
        fun normalizeSourceInputForTest(raw: String): String {
            var t = raw.trim()
            if (t.isEmpty()) return ""
            if (t.startsWith("stremio://", ignoreCase = true)) {
                t = "https://" + t.substring("stremio://".length)
            }
            if (t.startsWith("realdebrid=", ignoreCase = true)) t = "https://torrentio.strem.fun/$t/"
            return when {
                // UPGRADE http, never pass it through: this URL carries an account credential in its
                // PATH, so cleartext exposes it to the ISP, the LAN and any transparent proxy — and
                // usesCleartextTraffic=true in the manifest means the platform would not block it.
                // It also keeps this normaliser agreeing with applySyncedSettings' https-only gate,
                // which would otherwise accept the value on the phone and silently refuse it on the TV.
                t.startsWith("http://", true) -> "https://" + t.substring("http://".length)
                t.startsWith("https://", true) -> t
                t.contains('/') || t.contains('.') -> "https://$t"
                else -> "https://torrentio.strem.fun/realdebrid=" +
                    java.net.URLEncoder.encode(t, "UTF-8") + "/"
            }
        }

        val KEY_WIFI = stringPreferencesKey("quality_wifi")
        val KEY_CELL = stringPreferencesKey("quality_cellular")
        val KEY_DENSITY = stringPreferencesKey("ui_density")
        val KEY_SUBS_ON = booleanPreferencesKey("subs_enabled")
        val KEY_SUB_LANG = stringPreferencesKey("sub_language")
        val KEY_SUB_SIZE = stringPreferencesKey("sub_size")
        val KEY_SUB_STYLE = stringPreferencesKey("sub_style")
        val KEY_STREAM_SIZE = stringPreferencesKey("stream_size")
        val KEY_MAX_CACHE = stringPreferencesKey("max_cache_size")
        val KEY_CUSTOM_SOURCE = stringPreferencesKey("custom_source_url")
        val KEY_HIDE_INDIAN = booleanPreferencesKey("hide_indian_content")
        val KEY_DL_QUALITY = stringPreferencesKey("download_quality")
        val KEY_DL_SIZE = stringPreferencesKey("download_size")
        val KEY_UP_NEXT_PCT = intPreferencesKey("up_next_pct")
        val KEY_MOVIE_BAR_PCT = intPreferencesKey("movie_bar_pct")
        val KEY_SCREEN_SCALE = floatPreferencesKey("screen_scale")
        val KEY_SCREEN_OFF_X = floatPreferencesKey("screen_offset_x")
        val KEY_SCREEN_OFF_Y = floatPreferencesKey("screen_offset_y")
        val KEY_SYNC_UPDATED = longPreferencesKey("settings_sync_updated_at")

        /** Field order for [syncedSignature] — keep in sync with [syncedSettingsMap]'s keys. */
        val SYNCED_KEYS = listOf(
            "wifiQuality", "cellularQuality", "density", "subtitlesEnabled", "subtitleLanguage",
            "subtitleSize", "subtitleStyle", "streamSize", "upNextPercent", "movieBarPercent",
            "customSourceUrl",
        )

        // Calibration bounds (shared by the persisted read/write and the live in-memory preview).
        const val SCALE_MIN = 0.80f
        const val SCALE_MAX = 1.20f
        const val OFFSET_MIN = -200f
        const val OFFSET_MAX = 200f

        // Up-next / movie-bar threshold bounds (% of runtime).
        const val PCT_MIN = 80
        const val PCT_MAX = 99
    }

}
