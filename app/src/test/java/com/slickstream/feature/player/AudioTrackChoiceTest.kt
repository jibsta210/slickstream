package com.slickstream.feature.player

import com.slickstream.feature.player.AudioTrackChoice.Reason
import com.slickstream.feature.player.AudioTrackChoice.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The regression suite for "Mutiny played in Chinese". Every scenario below is a real release shape,
 * not a hypothetical — the MULTI cases in particular are what the user actually hit.
 */
class AudioTrackChoiceTest {

    private fun t(
        id: String,
        language: String? = null,
        label: String? = null,
        codec: String? = null,
        channels: Int = 0,
        default: Boolean = false,
        supported: Boolean = true,
        selected: Boolean = false,
    ) = Track(id, language, label, codec, channels, default, supported, selected)

    // --- Tier 1: container language tags -------------------------------------------------------

    @Test
    fun `picks the english tagged track over the default-flagged chinese one`() {
        // The literal Mutiny shape: Mandarin is track 0 AND flagged default; English is appended.
        val pick = AudioTrackChoice.choose(
            listOf(
                t("0:0", language = "chi", channels = 6, default = true),
                t("0:1", language = "eng", channels = 2),
            ),
            preferredLanguage = "en",
        )
        assertEquals("0:1", pick.trackId)
        assertEquals(Reason.LANGUAGE_TAG, pick.reason)
        assertTrue(pick.confident)
        assertFalse(pick.needsFallbackDecoder)
    }

    @Test
    fun `iso 639-2 B and T and region subtags all normalise`() {
        assertEquals("en", AudioTrackChoice.normalizeCode("eng"))
        assertEquals("en", AudioTrackChoice.normalizeCode("en-US"))
        assertEquals("en", AudioTrackChoice.normalizeCode("EN"))
        assertEquals("zh", AudioTrackChoice.normalizeCode("chi"))
        assertEquals("zh", AudioTrackChoice.normalizeCode("zho"))
        assertEquals("zh", AudioTrackChoice.normalizeCode("cmn"))
        assertEquals("fr", AudioTrackChoice.normalizeCode("fre"))
        assertEquals("fr", AudioTrackChoice.normalizeCode("fra"))
        assertEquals("de", AudioTrackChoice.normalizeCode("ger"))
        assertEquals("de", AudioTrackChoice.normalizeCode("deu"))
        assertEquals("es", AudioTrackChoice.normalizeCode("spa"))
        assertEquals("pt", AudioTrackChoice.normalizeCode("pt_BR"))
        assertEquals("nl", AudioTrackChoice.normalizeCode("dut"))
    }

    @Test
    fun `undetermined and empty tags carry no language`() {
        assertNull(AudioTrackChoice.normalizeCode("und"))
        assertNull(AudioTrackChoice.normalizeCode(""))
        assertNull(AudioTrackChoice.normalizeCode("   "))
        assertNull(AudioTrackChoice.normalizeCode(null))
        // "mul" (multiple) and "zxx" (no linguistic content) are tags, but not a language.
        assertNull(AudioTrackChoice.normalizeCode("mul"))
        assertNull(AudioTrackChoice.normalizeCode("zxx"))
    }

    @Test
    fun `a non-english preference works the same way`() {
        val pick = AudioTrackChoice.choose(
            listOf(t("0:0", language = "eng", default = true), t("0:1", language = "spa")),
            preferredLanguage = "spa",
        )
        assertEquals("0:1", pick.trackId)
        assertEquals(Reason.LANGUAGE_TAG, pick.reason)
    }

    // --- Renderer capability: the ExoPlayer trap -----------------------------------------------

    @Test
    fun `english present but undecodable asks for the fallback decoder instead of the dub`() {
        // ExoPlayer's own comparator puts renderer capability BEFORE language, so it would silently
        // select the AAC Chinese dub here. That is the whole point of needsFallbackDecoder.
        val pick = AudioTrackChoice.choose(
            listOf(
                t("0:0", language = "chi", codec = "audio/mp4a-latm", channels = 2, supported = true),
                t("0:1", language = "eng", codec = "audio/eac3", channels = 6, supported = false),
            ),
            preferredLanguage = "en",
        )
        assertEquals("0:1", pick.trackId)
        assertEquals(Reason.PREFERRED_UNDECODABLE, pick.reason)
        assertTrue(pick.needsFallbackDecoder)
    }

    @Test
    fun `a decodable english track is preferred over an undecodable english track`() {
        val pick = AudioTrackChoice.choose(
            listOf(
                t("0:0", language = "eng", codec = "audio/true-hd", supported = false),
                t("0:1", language = "eng", codec = "audio/ac3", supported = true),
                t("0:2", language = "chi"),
            ),
            preferredLanguage = "en",
        )
        assertEquals("0:1", pick.trackId)
        assertFalse(pick.needsFallbackDecoder)
    }

    // --- Commentary --------------------------------------------------------------------------

    @Test
    fun `commentary never wins over the feature track in the same language`() {
        val pick = AudioTrackChoice.choose(
            listOf(
                t("0:0", language = "eng", label = "English Director's Commentary", default = true),
                t("0:1", language = "eng", label = "English 5.1"),
            ),
            preferredLanguage = "en",
        )
        assertEquals("0:1", pick.trackId)
    }

    @Test
    fun `commentary is still used when it is the only english track`() {
        val pick = AudioTrackChoice.choose(
            listOf(
                t("0:0", language = "chi", default = true),
                t("0:1", language = "eng", label = "Commentary"),
            ),
            preferredLanguage = "en",
        )
        assertEquals("0:1", pick.trackId)
    }

    // --- Tier 2: label evidence ---------------------------------------------------------------

    @Test
    fun `an untagged track whose label names english is chosen`() {
        // ExoPlayer's DefaultTrackSelector never reads Format.label, so without this tier the untagged
        // tie-break falls through to channel count and picks the 5.1 dub.
        val pick = AudioTrackChoice.choose(
            listOf(
                t("0:0", language = "und", label = "AC3 5.1", channels = 6, default = true),
                t("0:1", language = "und", label = "English AAC", channels = 2),
            ),
            preferredLanguage = "en",
        )
        assertEquals("0:1", pick.trackId)
        assertEquals(Reason.TRACK_LABEL, pick.reason)
        assertTrue(pick.confident)
    }

    @Test
    fun `libvlc style track names are parsed`() {
        assertEquals("en", AudioTrackChoice.languageFromText("Track 1 - [English]"))
        assertEquals("zh", AudioTrackChoice.languageFromText("Track 2 - [Chinese]"))
        assertEquals("fr", AudioTrackChoice.languageFromText("Piste 3 - [Francais]"))
        assertEquals("en", AudioTrackChoice.languageFromText("eng - Surround 5.1"))
        assertEquals("en", AudioTrackChoice.languageFromText("EN AC3"))
    }

    @Test
    fun `lowercase two-letter words are not mistaken for language codes`() {
        // "it"/"no"/"de"/"in" are ordinary words far more often than they are ISO codes.
        assertNull(AudioTrackChoice.languageFromText("Play it as it is"))
        assertNull(AudioTrackChoice.languageFromText("no dub"))
        assertNull(AudioTrackChoice.languageFromText("5.1 surround"))
        assertNull(AudioTrackChoice.languageFromText("Original"))
    }

    @Test
    fun `three-letter codes that are english words are not read as languages`() {
        // "nor", "fin", "may", "per" are all real ISO-639-2 codes AND ordinary words.
        assertNull(AudioTrackChoice.languageFromText("nor any other"))
        assertNull(AudioTrackChoice.languageFromText("640 kbps per channel"))
        assertNull(AudioTrackChoice.languageFromText("fin"))
    }

    // --- Tier 3: nothing identifies anything --------------------------------------------------

    @Test
    fun `identified but foreign tracks are left alone for the auto-advance to handle`() {
        val pick = AudioTrackChoice.choose(
            listOf(t("0:0", language = "chi"), t("0:1", language = "jpn")),
            preferredLanguage = "en",
        )
        assertNull(pick.trackId)
        assertEquals(Reason.NO_PREFERRED_TRACK, pick.reason)
        assertFalse(pick.confident)
    }

    @Test
    fun `untagged tracks on an english-looking release fall back to the container default`() {
        val pick = AudioTrackChoice.choose(
            listOf(
                t("0:0", language = "und", channels = 2),
                t("0:1", language = "und", channels = 6, default = true),
            ),
            preferredLanguage = "en",
            releaseLanguageLikely = true,
            releaseMultiAudio = false,
        )
        assertEquals("0:1", pick.trackId)
        assertEquals(Reason.UNIDENTIFIED_TRUSTED_RELEASE, pick.reason)
        // Evidence-free: still surface the picker rather than claim we got it right.
        assertFalse(pick.confident)
    }

    @Test
    fun `untagged tracks on a MULTI release are never guessed`() {
        // THE Mutiny case with the tags stripped: the release name literally says it holds several
        // languages, so its container default proves nothing and channel count is an anti-signal.
        val pick = AudioTrackChoice.choose(
            listOf(
                t("0:0", language = null, channels = 6, default = true),
                t("0:1", language = null, channels = 2),
                t("0:2", language = null, channels = 6),
            ),
            preferredLanguage = "en",
            releaseLanguageLikely = true,
            releaseMultiAudio = true,
        )
        assertNull(pick.trackId)
        assertEquals(Reason.UNIDENTIFIED_AMBIGUOUS, pick.reason)
        assertFalse(pick.confident)
    }

    @Test
    fun `untagged tracks on a non-english-looking release are never guessed`() {
        val pick = AudioTrackChoice.choose(
            listOf(t("0:0", language = null, default = true), t("0:1", language = null)),
            preferredLanguage = "en",
            releaseLanguageLikely = false,
        )
        assertNull(pick.trackId)
        assertEquals(Reason.UNIDENTIFIED_AMBIGUOUS, pick.reason)
    }

    @Test
    fun `the release signal is only consulted for english`() {
        // englishLikely says nothing about whether a Spanish track is present.
        val pick = AudioTrackChoice.choose(
            listOf(t("0:0", language = null, default = true), t("0:1", language = null)),
            preferredLanguage = "spa",
            releaseLanguageLikely = true,
        )
        assertNull(pick.trackId)
        assertEquals(Reason.UNIDENTIFIED_AMBIGUOUS, pick.reason)
    }

    // --- Degenerate cases ---------------------------------------------------------------------

    @Test
    fun `no tracks and one track are both no-ops`() {
        val none = AudioTrackChoice.choose(emptyList(), "en")
        assertNull(none.trackId)
        assertEquals(Reason.NO_TRACKS, none.reason)

        val one = AudioTrackChoice.choose(listOf(t("0:0", language = "chi")), "en")
        assertNull(one.trackId)
        assertEquals(Reason.SINGLE_TRACK, one.reason)
        // Nothing to switch to — forcing an override here could only mute the film.
        assertTrue(one.confident)
    }

    // --- Labels the user actually reads --------------------------------------------------------

    @Test
    fun `describe disambiguates three untagged tracks`() {
        val tracks = listOf(
            t("0:0", channels = 6, codec = "audio/ac3", default = true),
            t("0:1", channels = 2, codec = "audio/mp4a-latm"),
            t("0:2", channels = 8, codec = "audio/eac3"),
        )
        val labels = tracks.mapIndexed { i, tr -> AudioTrackChoice.describe(tr, i) }
        assertEquals(labels.size, labels.distinct().size)   // the old picker rendered three "Unknown"
        assertEquals("Track 1 · unknown language · AC3 · 5.1 · default", labels[0])
        assertEquals("Track 2 · unknown language · AAC · Stereo", labels[1])
    }

    @Test
    fun `describe leads with the language when there is one`() {
        assertEquals(
            "English · AC3 · 5.1",
            AudioTrackChoice.describe(t("0:0", language = "eng", codec = "audio/ac3", channels = 6), 0),
        )
        assertEquals(
            "Chinese · AAC · Stereo · default",
            AudioTrackChoice.describe(
                t("0:1", language = "zho", codec = "audio/mp4a-latm", channels = 2, default = true), 1,
            ),
        )
    }

    @Test
    fun `describe keeps a commentary label visible next to the language`() {
        val s = AudioTrackChoice.describe(t("0:0", language = "eng", label = "Director's Commentary"), 0)
        assertTrue(s, s.startsWith("English"))
        assertTrue(s, s.contains("Commentary"))
    }

    // --- libVLC option string ------------------------------------------------------------------

    @Test
    fun `vlc preference list carries every form a container might write`() {
        val en = AudioTrackChoice.vlcPreferenceList("en")
        assertTrue(en, en.split(",").containsAll(listOf("en", "eng", "english")))
        val fr = AudioTrackChoice.vlcPreferenceList("fre")
        assertTrue(fr, fr.split(",").containsAll(listOf("fr", "fre", "fra", "french")))
    }

    // =============================================================================================
    // The incremental-ES case: libVLC announces one track at a time
    // =============================================================================================

    @Test
    fun `a decision taken on a partial track list is corrected once the rest arrive`() {
        // libVLC fires ESAdded once PER elementary stream, so a 3-track MULTI is observed as 1, then 2,
        // then 3 separate callbacks — unlike ExoPlayer, which hands over the complete Tracks at once.
        // Deciding at 2 tracks and latching meant picking "the best of the first two" and never
        // reconsidering when the English track finally showed up. The ViewModel now re-runs whenever the
        // set GROWS; this pins that re-running actually changes the answer.
        val zho = AudioTrackChoice.Track(id = "vlc:0", language = "zho", label = null, isDefault = true)
        val und = AudioTrackChoice.Track(id = "vlc:1", language = "und", label = null)
        val eng = AudioTrackChoice.Track(id = "vlc:2", language = "eng", label = null)

        // Partial view: no English present, so English cannot be chosen.
        val partial = AudioTrackChoice.choose(listOf(zho, und), preferredLanguage = "en")
        assertNotEquals("vlc:2", partial.trackId)

        // Full view: the tagged English track wins outright.
        val full = AudioTrackChoice.choose(listOf(zho, und, eng), preferredLanguage = "en")
        assertEquals("vlc:2", full.trackId)
        assertEquals(AudioTrackChoice.Reason.LANGUAGE_TAG, full.reason)
        assertTrue(full.confident)
    }

    @Test
    fun `a foreign default flag does not beat a tagged english track`() {
        // The exact "Mutiny" shape: the container marks the foreign dub DEFAULT, which is what libVLC
        // honours when given no audio-language preference at all.
        val tracks = listOf(
            AudioTrackChoice.Track(id = "a", language = "zho", label = null, isDefault = true, channelCount = 6),
            AudioTrackChoice.Track(id = "b", language = "eng", label = null, channelCount = 2),
        )
        val pick = AudioTrackChoice.choose(tracks, preferredLanguage = "en")
        assertEquals("b", pick.trackId)
    }
}
