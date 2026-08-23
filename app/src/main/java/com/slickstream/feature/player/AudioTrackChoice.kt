package com.slickstream.feature.player

import java.util.Locale

/**
 * PURE audio-track decision logic, shared by BOTH playback backends (ExoPlayer and libVLC).
 *
 * WHY THIS EXISTS — the concrete failure it prevents: a user played the 2026 film "Mutiny" from a
 * good-looking `1080p.WEB-DL.MULTI` release and got CHINESE audio, with no way to change it. Two
 * independent defects produced that one symptom, and neither is fixable with
 * `setPreferredAudioLanguage("en")` alone:
 *
 *  1. On the libVLC path nothing applied a language preference at all, so libVLC took the container's
 *     DEFAULT-flagged / first audio ES — on a Chinese-sourced MULTI remux that is Mandarin.
 *  2. On the ExoPlayer path `DefaultTrackSelector$AudioTrackInfo.compareTo` compares
 *     `isWithinRendererCapabilities` BEFORE `preferredLanguageScore`. So when the English track is
 *     E-AC3/DTS-HD/TrueHD (no licensed decoder on most phones and plenty of TV SoCs) and the added dub
 *     is plain AAC, ExoPlayer deliberately selects the dub. The language preference never gets a vote.
 *     Worse, the app's existing VLC rescue is gated on `!tracks.isTypeSupported(TRACK_TYPE_AUDIO)`,
 *     which is FALSE the moment any one track is decodable — so nothing fired.
 *
 * And when a MULTI release ships UNTAGGED tracks (extremely common for scene MKV remuxes), ExoPlayer
 * does not fall back to "track 0": every track ties on language score, so the chain falls through to
 * the DEFAULT flag and then to CHANNEL COUNT, then sample rate, then bitrate. A MULTI's added dub is
 * routinely the 5.1 640k AC3 against a 2.0 English original — so the untagged tie-break actively
 * PREFERS the foreign track. Channel count and bitrate are therefore deliberately excluded from every
 * rule below: they are anti-signals here, not signals.
 *
 * Kept pure + unit-tested (the [com.slickstream.data.torrent.StartGate] /
 * [com.slickstream.data.torrent.EpisodeFileMatcher] pattern) because getting this wrong is exactly
 * what the user just experienced, and a wrong answer is silent — the film simply plays in a language
 * they don't speak.
 */
object AudioTrackChoice {

    /**
     * One audio track, described the same way whichever backend produced it.
     *
     * ExoPlayer fills this from a `Tracks.Group` + `Format`; VlcPlayer fills it from
     * `MediaPlayer.getAudioTracks()` cross-referenced with `IMedia.getTrack(i)`.
     */
    data class Track(
        /** Backend-scoped identity ("gi:ti" for ExoPlayer, "vlc:<esId>" for libVLC). Opaque here. */
        val id: String,
        /** Raw container language tag, exactly as the demuxer reported it: "eng", "zh", "und", null. */
        val language: String?,
        /** Free-text track name/label ("English AC3", "Track 2 - [Chinese]"), or null. */
        val label: String?,
        /** MIME type (ExoPlayer) or libVLC codec string. Display only — never a selection input. */
        val codec: String? = null,
        /** 0 when unknown. DISPLAY ONLY: see the class doc — channel count must never pick a track. */
        val channelCount: Int = 0,
        /** The container's DEFAULT disposition (MKV FlagDefault / MP4 enabled track). */
        val isDefault: Boolean = false,
        /** Can the CURRENT device actually decode this track? ExoPlayer knows; libVLC decodes
         *  everything (bundled FFmpeg), so it always passes true. */
        val supported: Boolean = true,
        /** True when this track is the one playing right now. */
        val selected: Boolean = false,
    )

    /** Why [choose] returned what it did — logged verbatim to diagnostics so a bad pick is provable. */
    enum class Reason {
        /** No audio at all (a video-only file, or tracks not enumerated yet). */
        NO_TRACKS,

        /** Exactly one track: there is no decision to make, whatever language it is. */
        SINGLE_TRACK,

        /** The container tagged a track with the preferred language. The strongest evidence there is. */
        LANGUAGE_TAG,

        /** No usable tag, but the track's LABEL names the language ("English AC3"). ExoPlayer's
         *  selector never reads `Format.label`, so this tier exists only here. */
        TRACK_LABEL,

        /** The preferred language EXISTS but this device can't decode it (E-AC3/DTS-HD/TrueHD on a
         *  phone). The caller must hand the stream to libVLC rather than settle for the dub. */
        PREFERRED_UNDECODABLE,

        /** Tracks ARE identified and none of them is the preferred language — a genuinely foreign
         *  release. Don't guess: the existing foreign-audio auto-advance owns that case. */
        NO_PREFERRED_TRACK,

        /** NOTHING identifies any track, but the RELEASE NAME reads as the preferred language and
         *  isn't a MULTI/dual — so the container's own default is the best available evidence. */
        UNIDENTIFIED_TRUSTED_RELEASE,

        /** Nothing identifies any track AND the release is MULTI/foreign-looking. A coin flip. Offer
         *  the picker instead of gambling — this is the "Mutiny" case. */
        UNIDENTIFIED_AMBIGUOUS,
    }

    /**
     * @param trackId the track to force, or null to leave the backend's own selection alone.
     * @param confident false = the app should SURFACE the picker / flag the audio button, because we
     *        could not prove the playing track is the language the user asked for.
     * @param needsFallbackDecoder true = the right-language track exists but this renderer can't play
     *        it; switch backends (libVLC) rather than accepting the wrong language.
     */
    data class Pick(
        val trackId: String?,
        val reason: Reason,
        val confident: Boolean,
        val needsFallbackDecoder: Boolean = false,
    )

    /**
     * Choose the audio track for [preferredLanguage] (any ISO-639 form; "en"/"eng"/"english" all work).
     *
     * [releaseLanguageLikely] is the RELEASE-LEVEL signal the app already computes
     * (`StreamSource.englishLikely`, from [com.slickstream.data.source.StreamPicker.looksEnglish]) and is
     * only consulted when the preference is English — it says nothing about any other language.
     * [releaseMultiAudio] is `StreamPicker.looksMultiAudio` ("MULTI", "DUAL", "VFF"…): a MULTI release
     * is precisely a release whose name tells us it contains SEVERAL languages, so its container
     * default is not evidence of anything and we must not lean on it.
     */
    fun choose(
        tracks: List<Track>,
        preferredLanguage: String,
        releaseLanguageLikely: Boolean = true,
        releaseMultiAudio: Boolean = false,
    ): Pick {
        if (tracks.isEmpty()) return Pick(null, Reason.NO_TRACKS, confident = true)
        // One track = no choice. Even if it's the wrong language there is nothing to switch TO, and
        // forcing an override on a single track only risks disabling audio entirely.
        if (tracks.size == 1) return Pick(null, Reason.SINGLE_TRACK, confident = true)

        val pref = normalizeCode(preferredLanguage) ?: "en"

        // Tier 1 — the container's own language tag. Handles eng/en, chi/zho/zh, fre/fra/fr, es-419…
        val tagged = tracks.filter { normalizeCode(it.language) == pref }
        if (tagged.isNotEmpty()) return decide(tagged, Reason.LANGUAGE_TAG)

        // Tier 2 — evidence in the LABEL. A remux that dropped the language tag very often still says
        // "English" in the track name, and ExoPlayer's selector never looks there.
        val labelled = tracks.filter { languageFromText(it.label) == pref }
        if (labelled.isNotEmpty()) return decide(labelled, Reason.TRACK_LABEL)

        // Tier 3 — nothing matched. Distinguish "identified, but foreign" from "identified nothing",
        // because they call for opposite behaviour.
        val anyIdentified = tracks.any { normalizeCode(it.language) != null || languageFromText(it.label) != null }
        if (anyIdentified) return Pick(null, Reason.NO_PREFERRED_TRACK, confident = false)

        // Nothing identifies ANY track. Fall back to the release-level signal rather than to track 0
        // (which is what both backends do today, and is how the Chinese dub won).
        val releaseTrusted = pref == "en" && releaseLanguageLikely && !releaseMultiAudio
        if (releaseTrusted) {
            val def = tracks.firstOrNull { it.isDefault && it.supported }
            return Pick(def?.id, Reason.UNIDENTIFIED_TRUSTED_RELEASE, confident = false)
        }
        return Pick(null, Reason.UNIDENTIFIED_AMBIGUOUS, confident = false)
    }

    /** Rank equally-matching candidates. Order: decodable > not-commentary > container-default. */
    private fun decide(candidates: List<Track>, reason: Reason): Pick {
        val playable = candidates.filter { it.supported }
        if (playable.isEmpty()) {
            // The user's language is IN this file — the device just can't decode it. Switching to a
            // backend that can (libVLC bundles FFmpeg) is strictly better than playing the dub.
            return Pick(
                trackId = candidates.first().id,
                reason = Reason.PREFERRED_UNDECODABLE,
                confident = true,
                needsFallbackDecoder = true,
            )
        }
        // A director's-commentary track is tagged with the same language as the feature; picking it
        // would technically satisfy "English" while ruining the film.
        val main = playable.filterNot { isCommentary(it) }.ifEmpty { playable }
        val best = main.firstOrNull { it.isDefault } ?: main.first()
        return Pick(best.id, reason, confident = true)
    }

    /** Commentary / audio-description tracks: right language, wrong content. */
    private val COMMENTARY = Regex(
        "(?i)\\b(commentary|commentaries|director'?s|audio.?description|descriptive|described|" +
            "narrat(?:ion|or)|visually.?impaired|hearing.?impaired|sdh)\\b",
    )

    private fun isCommentary(t: Track): Boolean =
        t.label?.let { COMMENTARY.containsMatchIn(it) } == true

    // ------------------------------------------------------------------------------------------
    // Language codes
    // ------------------------------------------------------------------------------------------

    /** Tags that carry no language: ISO "und"/"mul"/"zxx" plus the strings demuxers invent. */
    private val UNDETERMINED = setOf("und", "undetermined", "unknown", "none", "mul", "mis", "zxx", "qaa")

    /**
     * ISO-639-2/B (bibliographic) and /T (terminological) -> 639-1, plus the handful of 3-letter forms
     * that appear in real containers. Media3 normalises the PREFERENCE through `Util.normalizeLanguageCode`
     * but NOT `Format.language` (verified: `Format.Builder.setLanguage` is a bare putfield), and libVLC
     * does no normalisation at all — so both sides get normalised here instead, once.
     */
    private val ALIAS: Map<String, String> = mapOf(
        "eng" to "en",
        "chi" to "zh", "zho" to "zh", "cmn" to "zh", "yue" to "zh", "nan" to "zh",
        "fre" to "fr", "fra" to "fr",
        "ger" to "de", "deu" to "de",
        "spa" to "es",
        "ita" to "it",
        "por" to "pt",
        "dut" to "nl", "nld" to "nl",
        "rus" to "ru",
        "ara" to "ar",
        "hin" to "hi",
        "jpn" to "ja",
        "kor" to "ko",
        "pol" to "pl",
        "swe" to "sv",
        "dan" to "da",
        "nor" to "no", "nob" to "no", "nno" to "no",
        "fin" to "fi",
        "tur" to "tr",
        "tha" to "th",
        "vie" to "vi",
        "gre" to "el", "ell" to "el",
        "heb" to "he", "iw" to "he",
        "cze" to "cs", "ces" to "cs",
        "slo" to "sk", "slk" to "sk",
        "slv" to "sl",
        "hun" to "hu",
        "rum" to "ro", "ron" to "ro",
        "bul" to "bg",
        "ukr" to "uk",
        "srp" to "sr", "scc" to "sr",
        "hrv" to "hr", "scr" to "hr",
        "ind" to "id", "in" to "id",
        "may" to "ms", "msa" to "ms",
        "tam" to "ta",
        "tel" to "te",
        "mal" to "ml",
        "kan" to "kn",
        "ben" to "bn",
        "mar" to "mr",
        "guj" to "gu",
        "pan" to "pa",
        "urd" to "ur",
        "per" to "fa", "fas" to "fa",
        "tgl" to "tl", "fil" to "tl",
        "cat" to "ca",
        "est" to "et",
        "lav" to "lv",
        "lit" to "lt",
        "ice" to "is", "isl" to "is",
        "alb" to "sq", "sqi" to "sq",
        "mac" to "mk", "mkd" to "mk",
        "geo" to "ka", "kat" to "ka",
        "arm" to "hy", "hye" to "hy",
        "baq" to "eu", "eus" to "eu",
        "wel" to "cy", "cym" to "cy",
        "gle" to "ga",
        "lat" to "la",
    )

    /** Language NAMES as they appear in track labels ("English AC3", "Track 2 - [Chinese]"). */
    private val NAME_TO_CODE: Map<String, String> = mapOf(
        "english" to "en", "inglés" to "en", "ingles" to "en",
        "chinese" to "zh", "mandarin" to "zh", "cantonese" to "zh", "putonghua" to "zh",
        "spanish" to "es", "espanol" to "es", "español" to "es", "castilian" to "es",
        "castellano" to "es", "latino" to "es",
        "french" to "fr", "francais" to "fr", "français" to "fr", "quebecois" to "fr",
        "german" to "de", "deutsch" to "de",
        "italian" to "it", "italiano" to "it",
        "portuguese" to "pt", "portugues" to "pt", "português" to "pt", "brazilian" to "pt",
        "dutch" to "nl", "nederlands" to "nl", "flemish" to "nl",
        "russian" to "ru", "русский" to "ru",
        "arabic" to "ar",
        "hindi" to "hi",
        "japanese" to "ja",
        "korean" to "ko",
        "polish" to "pl", "polski" to "pl",
        "swedish" to "sv",
        "danish" to "da",
        "norwegian" to "no",
        "finnish" to "fi",
        "turkish" to "tr",
        "thai" to "th",
        "vietnamese" to "vi",
        "greek" to "el",
        "hebrew" to "he",
        "czech" to "cs",
        "slovak" to "sk",
        "hungarian" to "hu",
        "romanian" to "ro",
        "bulgarian" to "bg",
        "ukrainian" to "uk",
        "serbian" to "sr",
        "croatian" to "hr",
        "indonesian" to "id",
        "malay" to "ms",
        "tamil" to "ta",
        "telugu" to "te",
        "malayalam" to "ml",
        "kannada" to "kn",
        "bengali" to "bn",
        "persian" to "fa", "farsi" to "fa",
        "filipino" to "tl", "tagalog" to "tl",
        "catalan" to "ca",
    )

    /**
     * 3-letter codes that are SAFE to look for inside free text. Deliberately excludes the ISO codes
     * that are also ordinary English words — "nor", "fin", "may", "per", "ben", "mar", "lit", "pan",
     * "kan", "lat", "in" — because a label like "Original / no dub" or "5.1 per channel" would
     * otherwise be read as a language. Tags are matched against the FULL [ALIAS] map; only free-text
     * scanning is restricted.
     */
    private val TEXT_SAFE_CODES = setOf(
        "eng", "chi", "zho", "cmn", "yue", "fre", "fra", "ger", "deu", "spa", "ita", "por", "dut",
        "nld", "rus", "ara", "hin", "jpn", "kor", "pol", "swe", "dan", "tur", "tha", "vie", "gre",
        "ell", "heb", "cze", "ces", "slk", "slo", "hun", "rum", "ron", "bul", "ukr", "srp", "hrv",
        "ind", "msa", "tam", "tel", "mal", "urd", "guj", "fas", "cat", "tgl", "fil",
    )

    private val TOKEN = Regex("[\\p{L}]{2,}")

    /**
     * Normalise any ISO-639 form (or a bare language name) to a single 639-1-ish code, or null when the
     * tag carries no language.
     *
     * Handles: "eng"->"en", "zho"/"chi"->"zh", "en-US"/"pt_BR"/"zh-Hans"->"en"/"pt"/"zh",
     * "und"/""/"mul"->null. An unknown 3-letter code is returned as-is (conservative: it simply won't
     * match the preference, rather than being coerced into the wrong language).
     */
    fun normalizeCode(raw: String?): String? {
        val t = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        NAME_TO_CODE[t]?.let { return it }
        val base = t.split('-', '_', ' ', '(').first().trim()
        if (base.isEmpty() || base in UNDETERMINED) return null
        ALIAS[base]?.let { return it }
        NAME_TO_CODE[base]?.let { return it }
        return base.takeIf { it.length in 2..3 }
    }

    /**
     * Pull a language out of free text (a track label, or libVLC's `TrackDescription.name`, which looks
     * like "Track 1 - [English]").
     *
     * A bare TWO-letter token is only accepted when it was UPPERCASE in the source ("EN AC3"), because
     * lowercase "it", "no", "de" and "in" are all far more likely to be ordinary words than ISO codes —
     * that mis-read is how a label such as "Director commentary, in English" would have scored Italian.
     */
    fun languageFromText(text: String?): String? {
        val s = text?.takeIf { it.isNotBlank() } ?: return null
        for (m in TOKEN.findAll(s)) {
            val raw = m.value
            val low = raw.lowercase(Locale.ROOT)
            NAME_TO_CODE[low]?.let { return it }
            if (low.length == 3 && low in TEXT_SAFE_CODES) return ALIAS[low] ?: low
            if (low.length == 2 && raw == raw.uppercase(Locale.ROOT) && ALIAS.values.contains(low)) return low
        }
        return null
    }

    /** The track's language after every tier of evidence, or null when genuinely unidentified. */
    fun resolveLanguage(t: Track): String? = normalizeCode(t.language) ?: languageFromText(t.label)

    /** ISO code -> a human name for the picker ("en" -> "English"). Null when the code is unknown. */
    fun displayName(code: String?): String? {
        val c = code?.takeIf { it.isNotBlank() } ?: return null
        val loc = Locale.forLanguageTag(c)
        val name = runCatching { loc.getDisplayLanguage(Locale.ENGLISH) }.getOrNull()
        return name?.takeIf { it.isNotBlank() && !it.equals(c, ignoreCase = true) }
            ?: c.uppercase(Locale.ROOT)
    }

    /** Short display code for the transport chip: "EN", "ZH", or null when unidentified. */
    fun shortCode(t: Track): String? = resolveLanguage(t)?.uppercase(Locale.ROOT)

    private val CODEC_NAMES: Map<String, String> = mapOf(
        "audio/mp4a-latm" to "AAC", "mp4a" to "AAC", "aac" to "AAC", "mpeg aac audio" to "AAC",
        "audio/ac3" to "AC3", "a52" to "AC3", "ac-3" to "AC3", "ac3" to "AC3",
        "audio/eac3" to "E-AC3", "audio/eac3-joc" to "E-AC3", "eac3" to "E-AC3", "ec-3" to "E-AC3",
        "audio/vnd.dts" to "DTS", "dts" to "DTS", "dca" to "DTS",
        "audio/vnd.dts.hd" to "DTS-HD", "audio/vnd.dts.hd;profile=lbr" to "DTS-HD",
        "audio/true-hd" to "TrueHD", "mlp" to "TrueHD", "truehd" to "TrueHD",
        "audio/opus" to "Opus", "opus" to "Opus",
        "audio/vorbis" to "Vorbis", "vorb" to "Vorbis",
        "audio/flac" to "FLAC", "flac" to "FLAC",
        "audio/mpeg" to "MP3", "audio/mpeg-l2" to "MP2", "mpga" to "MP3", "mp3" to "MP3",
        "audio/raw" to "PCM", "lpcm" to "PCM",
    )

    /** Codec MIME (ExoPlayer) or libVLC codec string -> a short display name. Display only. */
    fun codecName(codec: String?): String? {
        val c = codec?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        CODEC_NAMES[c]?.let { return it }
        val tail = c.substringAfterLast('/')
        CODEC_NAMES[tail]?.let { return it }
        return tail.takeIf { it.length in 2..10 }?.uppercase(Locale.ROOT)
    }

    /** Channel count -> a name people recognise. Display only — never a selection input. */
    fun channelName(channels: Int): String? = when {
        channels <= 0 -> null
        channels == 1 -> "Mono"
        channels == 2 -> "Stereo"
        channels == 6 -> "5.1"
        channels == 7 -> "6.1"
        channels == 8 -> "7.1"
        else -> "$channels ch"
    }

    /**
     * A row label that DISAMBIGUATES. The old picker showed `Format.label ?: languageDisplayName(...)`,
     * which on a 3-track untagged MULTI rendered three identical rows reading "Unknown" — a picker you
     * cannot pick from. Always leads with something unique (language, else the track ordinal) and adds
     * the codec/channel detail the Format already carries.
     */
    fun describe(t: Track, index: Int): String {
        val lang = displayName(resolveLanguage(t))
        val labelText = t.label?.trim()?.takeIf { it.isNotEmpty() }
        val head = lang ?: labelText ?: "Track ${index + 1}"
        val bits = buildList {
            if (lang == null) {
                add("unknown language")
            } else if (labelText != null && labelText != head && COMMENTARY.containsMatchIn(labelText)) {
                // Keep the raw label when it says something the language name doesn't — a
                // same-language commentary track is the case that most needs telling apart.
                add(labelText)
            }
            codecName(t.codec)?.let { add(it) }
            channelName(t.channelCount)?.let { add(it) }
            if (t.isDefault) add("default")
        }
        return if (bits.isEmpty()) head else head + " · " + bits.joinToString(" · ")
    }

    /**
     * The comma-separated list for libVLC's `--audio-language` media option. libVLC 3.x matches the
     * track's language string by prefix against these, and containers write every form in the wild
     * ("en", "eng", "English") — so send all three rather than betting on one.
     */
    fun vlcPreferenceList(preferredLanguage: String): String {
        val code = normalizeCode(preferredLanguage) ?: "en"
        val threeLetter = ALIAS.entries.filter { it.value == code }.map { it.key }
        val name = displayName(code)?.lowercase(Locale.ROOT)
        return (listOf(code) + threeLetter + listOfNotNull(name)).distinct().joinToString(",")
    }
}
