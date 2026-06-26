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

    /**
     * A candidate must have at least this fraction of the BEST available swarm's seeders to be
     * eligible, on top of [MIN_SEEDERS]. This is what stops the picker taking a 1-seed 800 MB over a
     * 66-seed 1.1 GB: 1 < max(8, 66·0.2 = 13), so the near-dead release is dropped while a healthy one
     * exists. Relative (not a fixed number) so it scales — a 1000-seed title demands ~200, a thinly
     * seeded one still works off the absolute floor.
     */
    private const val RELATIVE_HEALTH_FRACTION = 0.2

    fun pick(
        list: List<StreamSource>,
        maxTier: Int,
        sizePref: StreamSizePreference,
        lowPower: Boolean,
    ): StreamSource? {
        if (list.isEmpty()) return null
        val effectiveTier = if (lowPower) minOf(maxTier, QualityPreference.FHD_1080.maxTier) else maxTier
        val capped = list.filter { QualityPreference.tierOf(it.quality) <= effectiveTier }.ifEmpty { list }
        // Drop releases in a codec/container ExoPlayer can't decode (XviD/DivX/AVI/WMV…) and non-English
        // ones BEFORE ranking, AND before the health floor — so the floor is computed over genuinely
        // usable candidates (otherwise a high-seeded XviD CAM would inflate the floor and knock out the
        // real x264 we want). Fall back to the whole set if a stage empties, so we never strand the user.
        val decodable = capped.filter { it.playable }.ifEmpty { capped }
        // Default to English: torrent names usually flag the language (Cyrillic/CJK script, a country
        // flag emoji, or tags like RUS/VOSTFR/PLSUB). Prefer the English-looking ones; only fall back
        // to the rest if there are none.
        val english = decodable.filter { it.englishLikely }.ifEmpty { decodable }
        // Prefer a SINGLE-FILE episode over a season PACK when one exists. A pack streams slower to
        // start — big pieces (often 32 MB), and the wanted episode sits mid-torrent behind a shared
        // boundary piece that must download in full before the head unlocks. We still IGNORE the other
        // files, but the piece geometry makes packs sluggish, so skip them while a single-file release
        // is available. Soft: fall back to packs if that's all there is.
        val singleFile = english.filter { !it.isPack }.ifEmpty { english }
        // Health floor RELATIVE to the best swarm available: never pick a near-dead torrent when a
        // well-seeded one exists, even if the dead one is a little smaller (the old bug). The size
        // preference below then only ever chooses among genuinely healthy options.
        val bestSeeders = singleFile.maxOfOrNull { it.seeders ?: 0 } ?: 0
        val floor = maxOf(MIN_SEEDERS, (bestSeeders * RELATIVE_HEALTH_FRACTION).toInt())
        val healthy = singleFile.filter { (it.seeders ?: 0) >= floor }.ifEmpty { singleFile }
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

    // Any non-Latin script in a torrent name is a hard non-English signal: Cyrillic, Greek, Hebrew,
    // Arabic, Devanagari (Hindi), Thai, Hangul (Korean), Hiragana/Katakana + CJK (Chinese/Japanese).
    private val NON_LATIN = Regex(
        "[\\u0370-\\u03FF\\u0400-\\u052F\\u0590-\\u05FF\\u0600-\\u06FF\\u0900-\\u097F\\u0E00-\\u0E7F" +
            "\\u1100-\\u11FF\\u3040-\\u30FF\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uAC00-\\uD7AF\\uF900-\\uFAFF]",
    )
    // Foreign-language markers torrents use. Deliberately NO bare English language-names (french,
    // spanish, italian, chinese…) — those appear in real English titles ("The French Dispatch", "The
    // Italian Job", "Tai Chi") and we now FILTER on this, so a collision would hide the movie. Instead:
    // word-bounded language CODES, foreign-NATIVE words, and release JARGON that never appears in an
    // English title (temporada/capitulo/doblado/vostfr/castellano…).
    private val FOREIGN = Regex(
        "(?i)\\b(rus|ita|ger|deutsch|fre|francais|vostfr|vff|truefrench|spa|espanol|castellano|latino|" +
            "temporada|capitulo|capitulos|subtitulad[oa]|doblad[oa]|doblaje|vose|hindi|tamil|telugu|" +
            "kor|jpn|cantonese|mandarin|pol|plsub|plsubbed|ofdub|dublado|dublagem|legendado|" +
            "portugues|swesub)\\b",
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

    /** True if the text contains any non-Latin script (CJK/Cyrillic/Arabic/…) — an unreadable name we
     *  never want to surface as a pickable source. */
    fun hasNonLatin(text: String): Boolean = NON_LATIN.containsMatchIn(text)

    // Bare English language-NAMES. Kept OUT of FOREIGN (they collide with real English titles) and only
    // applied with the movie's own title words stripped — see [noForeignLanguageTag].
    private val LANG_NAME = Regex(
        "(?i)\\b(spanish|french|italian|german|chinese|japanese|korean|russian|polish|portuguese|" +
            "dutch|swedish|danish|norwegian|finnish|turkish|arabic|thai|vietnamese|greek|hebrew)\\b",
    )
    private val NON_WORD = Regex("[^\\p{L}0-9]+")

    /**
     * True only if NO foreign-language marker — a language CODE/native word ([FOREIGN]: ita, spa,
     * castellano…) OR a bare language NAME ([LANG_NAME]) — survives once the movie's OWN title words
     * are removed. So "The French Dispatch" keeps 'french' (a title word → stripped → fine), but a
     * ".ITA.ENG." dual-audio or ".SPANISH." dub leaves the foreign tag behind → rejected, even when it
     * ALSO tags English (an English-only viewer doesn't want a release whose first audio track is
     * Italian). Pairs with [looksEnglish]/[hasNonLatin] for the full English-only decision.
     */
    fun noForeignTag(sourceText: String, movieTitle: String): Boolean {
        val titleWords = movieTitle.lowercase().split(NON_WORD).filter { it.length >= 3 }.toSet()
        val rest = sourceText.split(NON_WORD).filterNot { it.lowercase() in titleWords }.joinToString(" ")
        return !FOREIGN.containsMatchIn(rest) && !LANG_NAME.containsMatchIn(rest)
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

    // Season / multi-episode PACK markers, usually carried in the pack's FOLDER name (which Torrentio
    // includes alongside the chosen file name): "Season 1", "Complete", a season range "S01-S03", an
    // episode range "E01-E10", "Pack"/"Collection"/"Batch". A single-episode filename ("…S01E01…")
    // matches none of these. Used together with a multi-file fileIndex to flag packs.
    private val PACK_MARKERS = Regex(
        "(?i)(\\bcomplete\\b|\\bseasons?\\b|\\bpack\\b|\\bcollection\\b|\\bbatch\\b|" +
            "\\bs\\d{1,2}[\\s._-]*-[\\s._-]*s?\\d{1,2}\\b|" +
            "\\be\\d{1,3}[\\s._-]*-[\\s._-]*e?\\d{1,3}\\b)",
    )

    /**
     * True when the release text looks like a season/multi-episode pack. [fileIndex] is the chosen
     * file's index within the torrent: anything past the first file is a multi-file torrent, a strong
     * structural pack signal on its own; the text markers catch packs where the episode is file 0.
     */
    fun looksLikePack(text: String, fileIndex: Int?): Boolean =
        (fileIndex != null && fileIndex > 0) || PACK_MARKERS.containsMatchIn(text)
}
