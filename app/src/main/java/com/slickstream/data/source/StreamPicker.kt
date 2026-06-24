package com.slickstream.data.source

import com.slickstream.core.model.StreamSource
import com.slickstream.data.settings.QualityPreference
import com.slickstream.data.settings.StreamSizePreference

/**
 * Shared auto-pick used by BOTH the player (what actually plays) and the details-screen prewarm
 * (what gets prefetched), so the warmed torrent is the same one Play will use — otherwise the
 * prewarm is wasted. SEEDERS are the primary key (with a health floor) so a starved 4K/3-seeder is
 * never chosen over a healthy 1080p; low-power TV caps at 1080p; the size preference then decides
 * where on the size/bitrate range to land.
 */
object StreamPicker {
    const val MIN_SEEDERS = 8

    fun pick(
        list: List<StreamSource>,
        maxTier: Int,
        sizePref: StreamSizePreference,
        lowPower: Boolean,
    ): StreamSource? {
        if (list.isEmpty()) return null
        val effectiveTier = if (lowPower) minOf(maxTier, QualityPreference.FHD_1080.maxTier) else maxTier
        val capped = list.filter { QualityPreference.tierOf(it.quality) <= effectiveTier }.ifEmpty { list }
        val seeded = capped.filter { (it.seeders ?: 0) >= MIN_SEEDERS }.ifEmpty { capped }
        // Drop releases in a codec/container ExoPlayer can't decode (XviD/DivX/AVI/WMV…) BEFORE the
        // size/seeder ranking. This is the #1 cause of "healthy torrent, black screen": a brand-new
        // movie often has a high-seeded XviD CAM sitting next to a playable x264 CAM, and ranking by
        // seeders alone would take the XviD (which ExoPlayer can't decode -> instant parse crash).
        // Fall back to the whole set only if NOTHING looks playable, so we never strand the user.
        val decodable = seeded.filter { it.playable }.ifEmpty { seeded }
        // Default to English: torrent names usually flag the language (Cyrillic/CJK script, a country
        // flag emoji, or tags like RUS/VOSTFR/PLSUB). Prefer the English-looking ones; only fall back
        // to the rest if there are none (don't leave the user with no playable option).
        val healthy = decodable.filter { it.englishLikely }.ifEmpty { decodable }
        val picked = when (sizePref) {
            StreamSizePreference.HIGHEST ->
                healthy.maxWithOrNull(compareBy<StreamSource>({ it.seeders ?: 0 }, { it.sizeBytes ?: 0L }))
            StreamSizePreference.SMALLEST -> {
                val withSize = healthy.filter { (it.sizeBytes ?: 0L) > 0L }
                if (withSize.isNotEmpty()) {
                    withSize.minWithOrNull(
                        compareBy<StreamSource> { it.sizeBytes ?: 0L }.thenByDescending { it.seeders ?: 0 },
                    )
                } else {
                    healthy.maxByOrNull { it.seeders ?: 0 }
                }
            }
            StreamSizePreference.BALANCED -> {
                val withSize = healthy.filter { (it.sizeBytes ?: 0L) > 0L }.sortedBy { it.sizeBytes ?: 0L }
                val pool = if (withSize.size >= 4) withSize.take((withSize.size * 2 + 2) / 3) else healthy
                pool.minWithOrNull(
                    compareByDescending<StreamSource> { it.seeders ?: 0 }.thenBy { it.sizeBytes ?: Long.MAX_VALUE },
                )
            }
        }
        return picked ?: list.first()
    }

    // Cyrillic + CJK (hiragana/katakana/CJK ideographs) — a hard non-English signal in a torrent name.
    private val NON_LATIN = Regex("[\\u0400-\\u04FF\\u3040-\\u30FF\\u4E00-\\u9FFF]")
    // Common language tags torrents use. 3-letter codes are bounded to avoid matching 'challenge' etc.
    private val FOREIGN = Regex(
        "(?i)\\b(rus|russian|ita|italian|ger|german|deutsch|fre|french|vostfr|truefrench|spa|spanish|" +
            "castellano|latino|hindi|tamil|telugu|kor|korean|jpn|japanese|chi|chinese|" +
            "pol|polish|plsub|plsubbed|ofdub|dublado|legendado)\\b",
    )
    private val ENGLISH = Regex("(?i)\\b(eng|english)\\b")
    // A pair of regional-indicator symbols = a country flag emoji. Torrentio tags foreign releases
    // with one (🇵🇱 🇪🇸 🇲🇽 …); any flag that isn't 🇬🇧/🇺🇸 is a strong non-English signal.
    private val FLAG = Regex("\\uD83C[\\uDDE6-\\uDDFF]\\uD83C[\\uDDE6-\\uDDFF]")
    private const val FLAG_GB = "🇬🇧"
    private const val FLAG_US = "🇺🇸"

    /** True unless the torrent text clearly signals a non-English language (with no English tag). */
    fun looksEnglish(text: String): Boolean {
        if (NON_LATIN.containsMatchIn(text)) return false
        if (FLAG.containsMatchIn(text) && !text.contains(FLAG_GB) && !text.contains(FLAG_US)) return false
        if (FOREIGN.containsMatchIn(text)) return ENGLISH.containsMatchIn(text)
        return true
    }

    // Codecs/containers Android's ExoPlayer cannot decode without the FFmpeg extension: XviD/DivX
    // (MPEG-4 ASP), raw AVI, WMV/VC-1, MPEG-2, RealMedia. Auto-picking one of these yields a black
    // screen / parse crash on a perfectly healthy torrent, so we treat them as unplayable. We only
    // flag KNOWN-bad markers — a release with no codec hint is assumed playable (most are h264/h265).
    private val BAD_CODEC = Regex(
        "(?i)(\\bxvid\\b|\\bdivx\\b|\\bwmv\\b|\\bvc-?1\\b|\\bmpeg-?2\\b|\\brmvb?\\b|\\.avi\\b)",
    )

    /** False when the release names a codec/container ExoPlayer can't decode (XviD/DivX/AVI/WMV…). */
    fun looksPlayable(text: String): Boolean = !BAD_CODEC.containsMatchIn(text)
}
