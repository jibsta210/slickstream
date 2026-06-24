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
        // Default to English: torrent names usually flag the language (Cyrillic/CJK script, or tags
        // like RUS/VOSTFR/Dublado). Prefer the English-looking ones; only fall back to the rest if
        // there are none (don't leave the user with no playable option).
        val healthy = seeded.filter { it.englishLikely }.ifEmpty { seeded }
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
            "castellano|latino|hindi|tamil|telugu|kor|korean|jpn|japanese|chi|chinese|dublado|legendado)\\b",
    )
    private val ENGLISH = Regex("(?i)\\b(eng|english)\\b")

    /** True unless the torrent text clearly signals a non-English language (with no English tag). */
    fun looksEnglish(text: String): Boolean {
        if (NON_LATIN.containsMatchIn(text)) return false
        if (FOREIGN.containsMatchIn(text)) return ENGLISH.containsMatchIn(text)
        return true
    }
}
