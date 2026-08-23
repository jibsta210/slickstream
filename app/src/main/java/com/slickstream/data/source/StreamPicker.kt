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
        /** Absolute minimum plausible size in bytes; 0 = disabled (player/prewarm default). Downloads
         *  pass a movie/episode floor so SMALLEST means "smallest REAL release", not "the 2 MB fake" —
         *  new releases attract tiny decoy torrents that would otherwise win the min-size sort. */
        minPlausibleBytes: Long = 0L,
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
        // Drop CAM/TS releases BEFORE the health floor — a heavily-seeded CAM of a new release must not
        // raise the floor and knock out the real (lower-seeded) BluRay/WEB encode (same reason foreign /
        // unplayable are dropped pre-floor). Soft: fall back to CAMs only if there is literally nothing else.
        val notCam = english.filterNot { it.isCam }.ifEmpty { english }
        // Fake/sample floor (opt-in): drop sources whose ADVERTISED size is implausibly small for real
        // content. Runs BEFORE the health floor for the same reason CAMs do — fake torrents advertise
        // huge seeder counts, and a 5000-"seeder" 2 MB decoy must not inflate the relative floor and
        // knock out the real 200-seed encode (it would then also WIN the SMALLEST sort). Null sizes
        // pass (missing metadata ≠ fake — the caller's post-download check is the backstop). Soft: if
        // everything is sub-floor, keep the pool rather than strand the caller — downloads then reject
        // the tiny result after the fact instead of never resolving.
        val plausible = if (minPlausibleBytes <= 0L) notCam else {
            notCam.filter { it.sizeBytes == null || it.sizeBytes >= minPlausibleBytes }.ifEmpty { notCam }
        }
        // Health floor RELATIVE to the best swarm available: never pick a near-dead torrent when a
        // well-seeded one exists, even if the dead one is a little smaller (the old bug). The size
        // preference below then only ever chooses among genuinely healthy options.
        val bestSeeders = plausible.maxOfOrNull { it.seeders ?: 0 } ?: 0
        val floor = maxOf(MIN_SEEDERS, (bestSeeders * RELATIVE_HEALTH_FRACTION).toInt())
        val healthy = plausible.filter { (it.seeders ?: 0) >= floor }.ifEmpty { plausible }
        // Pack avoidance is deliberately SOFT and therefore runs only after the health floor. Applying
        // it first made a 1-seed single-episode release hide a healthy 100-seed season pack, then compute
        // the floor from that already-starved subset. Prefer a single release only when one survives the
        // same health test as the packs; otherwise the healthy pack is the faster source in practice.
        val singleFiles = healthy.filter { !it.isPack }
        val bestHealth = healthy.maxOfOrNull { it.seeders ?: 0 } ?: 0
        val comparableSingletons = if (bestHealth <= 0) singleFiles else singleFiles.filter {
            (it.seeders ?: 0) >= (bestHealth * PACK_SINGLETON_MIN_HEALTH_FRACTION).toInt().coerceAtLeast(1)
        }
        val packs = healthy.filter { it.isPack }
        val shortlist = when {
            comparableSingletons.isNotEmpty() -> comparableSingletons
            packs.isNotEmpty() -> packs
            else -> healthy
        }
        // PREFER A FRONT-INDEX CONTAINER (mkv/webm) among otherwise-equal candidates. This is the
        // cheapest possible startup win: an mkv carries its cues in the header, so playback begins on the
        // contiguous head alone, while an mp4 usually keeps its 'moov' index at EOF and the engine must
        // first fetch bytes from the far end of the file — piece-rounded, tens of MB on a big-piece
        // torrent, and the dominant component of time-to-first-frame. Choosing the mkv does not optimise
        // that wait, it REMOVES it.
        // Deliberately applied LAST and SOFT, for the same reason the CAM/foreign filters are: it must
        // never override swarm health or the size preference — it only breaks ties among candidates that
        // already passed every health gate. Falls straight through when the shortlist has no mkv (and an
        // untagged release is treated as "not known to be fast", never penalised into oblivion).
        val candidates = shortlist.filter { it.frontIndexContainer }.ifEmpty { shortlist }
        val picked = when (sizePref) {
            StreamSizePreference.HIGHEST ->
                candidates.maxWithOrNull(compareBy<StreamSource>({ it.seeders ?: 0 }, { it.sizeBytes ?: 0L }))
            StreamSizePreference.SMALLEST -> {
                val withSize = candidates.filter { (it.sizeBytes ?: 0L) > 0L }
                if (withSize.isNotEmpty()) {
                    withSize.minWithOrNull(
                        compareBy<StreamSource> { it.sizeBytes ?: 0L }.thenByDescending { it.seeders ?: 0 },
                    )
                } else {
                    candidates.maxByOrNull { it.seeders ?: 0 }
                }
            }
            StreamSizePreference.BALANCED -> {
                val withSize = candidates.filter { (it.sizeBytes ?: 0L) > 0L }.sortedBy { it.sizeBytes ?: 0L }
                val pool = if (withSize.size >= 4) withSize.take((withSize.size * 2 + 2) / 3) else candidates
                pool.minWithOrNull(
                    compareByDescending<StreamSource> { it.seeders ?: 0 }.thenBy { it.sizeBytes ?: Long.MAX_VALUE },
                )
            }
        }
        return picked ?: list.first()
    }

    /** Pick the best direct HTTP source using the same quality/display cap as the player. Kept
     *  separate from [pick] because torrent health/size ranking is intentionally irrelevant to direct
     *  URLs, while callers such as downloads may still explicitly rank torrents. */
    fun pickDirect(list: List<StreamSource>, maxTier: Int, lowPower: Boolean): StreamSource? {
        val directs = list.filter { it.isDirect }
        if (directs.isEmpty()) return null
        val effectiveTier = if (lowPower) minOf(maxTier, QualityPreference.FHD_1080.maxTier) else maxTier
        val capped = directs.filter { QualityPreference.tierOf(it.quality) <= effectiveTier }.ifEmpty { directs }
        val english = capped.filter { it.englishLikely }.ifEmpty { capped }
        return english
            .maxByOrNull { it.rank }
    }

    /**
     * Whether an exact saved torrent release is still a safe resume choice on THIS device. Watch
     * progress (including its info-hash) syncs between devices, but display/decoder limits do not: a
     * 4K release picked on a phone or stronger TV must not bypass the weaker TV's 1080p cap merely
     * because it was saved in history. Apply the same staged gates as [pick], but strictly — the caller
     * already has a freshly ranked fallback and should use it when this old release is unsuitable.
     */
    fun isResumeCompatible(
        source: StreamSource,
        candidates: List<StreamSource>,
        maxTier: Int,
        lowPower: Boolean,
    ): Boolean {
        val effectiveTier = if (lowPower) minOf(maxTier, QualityPreference.FHD_1080.maxTier) else maxTier
        if (QualityPreference.tierOf(source.quality) > effectiveTier) return false

        val capped = candidates.filter { QualityPreference.tierOf(it.quality) <= effectiveTier }
        if (capped.any { it.playable } && !source.playable) return false

        val decodable = capped.filter { it.playable }.ifEmpty { capped }
        if (decodable.any { it.englishLikely } && !source.englishLikely) return false

        val english = decodable.filter { it.englishLikely }.ifEmpty { decodable }
        if (english.any { !it.isCam } && source.isCam) return false

        return true
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
    // NB: match by CODE POINT (\x{1F1E6}-\x{1F1FF}), not surrogate code units — Java/Kotlin regex runs
    // over code points, so the old "\uD83C[\uDDE6-\uDDFF]" form never matched a real flag emoji.
    private val FLAG = Regex("[\\x{1F1E6}-\\x{1F1FF}]{2}")
    private const val FLAG_GB = "🇬🇧"
    private const val FLAG_US = "🇺🇸"

    /**
     * True unless the torrent text clearly signals a non-English language. The movie's OWN title words
     * are stripped FIRST so a language name that's part of the title ("The French Dispatch", "The
     * Italian Job") isn't read as a foreign-audio tag. Non-Latin script or a non-GB/US country flag is
     * an immediate reject; otherwise a surviving foreign CODE *or bare NAME* ([FOREIGN]/[LANG_NAME])
     * counts as English only if it ALSO carries an explicit English tag (so a dual-audio "ITA+ENG"
     * release is still English-watchable, but a bare ".FRENCH." dub is not).
     *
     * Bare language NAMES (french/spanish/italian/russian…) MUST be checked here, not just in
     * [noForeignTag]: they're the single most common release tag, and the picker trusts this flag
     * directly — leaving them out (as it briefly did) makes every foreign release read as English.
     */
    fun looksEnglish(text: String, movieTitle: String): Boolean {
        if (NON_LATIN.containsMatchIn(text)) return false
        if (FLAG.containsMatchIn(text) && !text.contains(FLAG_GB) && !text.contains(FLAG_US)) return false
        val rest = stripTitleWords(text, movieTitle)
        if (FOREIGN.containsMatchIn(rest) || LANG_NAME.containsMatchIn(rest)) {
            return ENGLISH.containsMatchIn(rest)
        }
        return true
    }

    /** True if the text contains any non-Latin script (CJK/Cyrillic/Arabic/…) — an unreadable name we
     *  never want to surface as a pickable source. */
    fun hasNonLatin(text: String): Boolean = NON_LATIN.containsMatchIn(text)

    // Release tags that advertise SEVERAL audio languages in one file. Deliberately NOT part of
    // [FOREIGN]: a MULTI release usually DOES contain English and rejecting them would throw away good
    // sources. What it changes is the PLAYER's confidence — a release whose name says "several
    // languages" cannot have its container-default audio track trusted as English when the tracks
    // themselves carry no language tags. That is precisely the 1080p WEB-DL MULTI of "Mutiny" that
    // played in Chinese.
    // NB: the German scene's "DL" (Dual Language) is deliberately ABSENT — `\bdl\b` matches the "DL"
    // in "WEB-DL" (the hyphen is a word boundary), which would mark essentially every modern release
    // as multi-audio and permanently disable the container-default fallback.
    private val MULTI_AUDIO = Regex(
        "(?i)\\b(multi|multi-?audio|multi-?lang(?:ue|uage)?|dual|dual-?audio|" +
            "vff|vfq|vfi|vf2|truefrench)\\b",
    )

    /** True when the release name advertises multiple audio languages ("MULTi", "DUAL AUDIO", "VFF"). */
    fun looksMultiAudio(text: String): Boolean = MULTI_AUDIO.containsMatchIn(text)

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
        val rest = stripTitleWords(sourceText, movieTitle)
        return !FOREIGN.containsMatchIn(rest) && !LANG_NAME.containsMatchIn(rest)
    }

    /** Drop the movie's own title words (3+ chars) from [text] so a language name that's part of the
     *  title isn't mistaken for a foreign-audio tag. Shared by [looksEnglish] and [noForeignTag]. */
    private fun stripTitleWords(text: String, movieTitle: String): String {
        val titleWords = movieTitle.lowercase().split(NON_WORD).filter { it.length >= 3 }.toSet()
        return text.split(NON_WORD).filterNot { it.lowercase() in titleWords }.joinToString(" ")
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

    // CAM-class source markers: a cinema-filmed rip (CAM/CAMRIP/HDCAM/HQCAM/CAMHD), a telesync
    // (TS/HDTS/TELESYNC), a telecine (TC/TELECINE/HDTC) or an early cammed DVD (PDVD/PreDVDRip) —
    // terrible quality, common for brand-new releases. NO screener (SCR) — it's frequently a clean
    // high-bitrate source, so badging it CAM would mislead. The short tags TS/TC are word-bounded and
    // applied only AFTER the movie's own title words are stripped (so the 2018 film "Cam" or a title
    // containing "ts" isn't flagged). Still slightly fuzzy on a literal ".ts" container hint, but a
    // real telesync is the far more likely meaning in a release name.
    private val CAM_MARKERS = Regex(
        "(?i)\\b(" +
            "cam|camrip|hd-?cam|hq-?cam|cam-?hd|cam-?rip|" +   // camcorder rips
            "ts|hd-?ts|hq-?ts|telesync|hd-?telesync|" +        // telesync
            "tc|hd-?tc|telecine|hd-?telecine|" +               // telecine
            "pdvd|pre-?dvd(rip)?|dvdcam" +                     // early cammed DVD rips (NOT DVDSCR — often clean)
        ")\\b",
    )

    // Containers whose index lives at the FRONT. mkv/webm carry their cues in the header, so playback can
    // begin on the contiguous head alone. mp4/m4v/mov routinely put the 'moov' index at EOF, and the
    // engine must then fetch bytes from the far end of the file BEFORE the first frame — piece-rounded,
    // that is tens of MB on a big-piece torrent and the dominant startup cost. Preferring the front-index
    // release when one is comparably healthy removes that wait entirely rather than optimising it.
    private val FRONT_INDEX_CONTAINER = Regex("(?i)\\b(mkv|matroska|webm)\\b")

    /** True when the release names a front-index container (mkv/webm) — see [FRONT_INDEX_CONTAINER]. */
    fun looksFrontIndexContainer(sourceText: String): Boolean =
        FRONT_INDEX_CONTAINER.containsMatchIn(sourceText)

    /** True when the release name marks it a CAM/TS/TELESYNC (title-word-aware, like [noForeignTag]). */
    fun looksLikeCam(sourceText: String, movieTitle: String): Boolean =
        CAM_MARKERS.containsMatchIn(stripTitleWords(sourceText, movieTitle))

    // A "configure me" PLACEHOLDER, not a real stream. Many catalog addons advertise no config in their
    // manifest yet, at /stream time, return a single fake entry that carries a real-looking `url` (often a
    // debrid/playback gate) with a name/title like "ℹ Kindly configure this addon to access streams." or
    // "Addon not configured · Needs API keys". Playing it does nothing — it's the root cause of "found
    // direct sources, none played". Detect it by that prompt language (which never appears in a real
    // release name like "Movie.2024.1080p.WEB-DL"), so it's filtered at discovery AND mapping time.
    private val CONFIG_PROMPT = Regex(
        "(?i)(" +
            "kindly\\s+configure|" +
            "configure\\s+(this\\s+)?add[\\s-]?on|" +
            "(addon|add-on)\\s+not\\s+configured|" +
            "not\\s+configured|" +
            "needs?\\s+(an?\\s+)?(\\d+\\s+)?api\\s*keys?|" +
            "api[\\s-]?key\\s+(is\\s+)?required|" +
            "requires?\\s+configuration|" +
            "configuration\\s+(is\\s+)?required|" +
            "please\\s+configure|" +
            "to\\s+access\\s+(the\\s+|your\\s+)?streams?|" +
            "setup\\s+required|" +
            "sign[\\s-]?in\\s+to\\s+|" +
            "log[\\s-]?in\\s+to\\s+" +
        ")",
    )

    /** True when a stream is a "kindly configure this addon" placeholder rather than a playable stream.
     *  Pass the pooled name/title/description text. */
    fun looksLikeConfigPrompt(sourceText: String): Boolean = CONFIG_PROMPT.containsMatchIn(sourceText)

    // Season / multi-episode PACK markers, usually carried in the pack's FOLDER name (which Torrentio
    // includes alongside the chosen file name): "Season 1", "Complete", a season range "S01-S03", an
    // episode range "E01-E10", "Pack"/"Collection"/"Batch". A single-episode filename ("…S01E01…")
    // matches none of these. File indexes are deliberately NOT used: index > 0 only means the selected
    // video was not the first torrent entry (a subtitle/sample can occupy index 0), not that it is a pack.
    private val PACK_MARKERS = Regex(
        "(?i)(\\bcomplete\\b|\\bseasons?\\b|\\bpack\\b|\\bcollection\\b|\\bbatch\\b|" +
            "\\bs\\d{1,2}\\b|" +
            "\\bs\\d{1,2}[\\s._-]*-[\\s._-]*s?\\d{1,2}\\b|" +
            "\\be\\d{1,3}[\\s._-]*-[\\s._-]*e?\\d{1,3}\\b)",
    )

    /**
     * True when the release text looks like a season/multi-episode pack. [fileIndex] is retained for
     * source compatibility but is not evidence of a pack: ordinary single-video torrents can put a
     * sample, poster, or subtitle before the main video. The release/folder markers are authoritative.
     */
    @Suppress("UNUSED_PARAMETER")
    fun looksLikePack(text: String, fileIndex: Int?): Boolean =
        PACK_MARKERS.containsMatchIn(text)

    private const val PACK_SINGLETON_MIN_HEALTH_FRACTION = 0.5
}
