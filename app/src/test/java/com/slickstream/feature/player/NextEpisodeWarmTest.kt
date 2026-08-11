package com.slickstream.feature.player

import com.slickstream.core.model.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two rules the next-episode precache lives or dies on: it must START before the "Up next"
 * card, and the release it warmed must be the release that PLAYS.
 *
 * Every case here corresponds to a way the shipped feature silently did nothing.
 */
class NextEpisodeWarmTest {

    private val minutes22 = 22L * 60_000L
    private val minutes45 = 45L * 60_000L
    private val minutes60 = 60L * 60_000L

    // --- WHEN: the trigger must be derived from the user's card threshold --------------------

    @Test
    fun `warm always fires before the up-next card, at every user threshold`() {
        // The reported bug in one assertion. The old code warmed at a hardcoded 85%; at the 85% card
        // setting that is the SAME instant as the card, and the card polls at 1 s while the warm rode a
        // 10 s ticker — so the card reliably appeared first, for an episode nothing had started.
        for (duration in listOf(minutes22, minutes45, minutes60)) {
            for (pct in listOf(0.85f, 0.90f, 0.93f, 0.95f, 0.97f)) {
                val card = NextEpisodeWarm.upNextAtMs(duration, pct)
                val warm = NextEpisodeWarm.warmAtMs(duration, pct)
                assertTrue(
                    "warm $warm must precede card $card (duration=$duration pct=$pct)",
                    warm < card,
                )
                assertTrue(
                    "lead must clear the 10 s progress-ticker period (duration=$duration pct=$pct)",
                    card - warm >= NextEpisodeWarm.MIN_LEAD_MS,
                )
            }
        }
    }

    @Test
    fun `a long episode gets the full warm budget of lead`() {
        // 45 min at the 95% default: card at 2565 s, warm at 2385 s. 180 s covers the observed worst
        // case (resolve fan-out + cold metadata + 8 MB head + moov band).
        assertEquals(
            NextEpisodeWarm.WARM_COST_BUDGET_MS,
            NextEpisodeWarm.leadMs(minutes45, 0.95f),
        )
        assertEquals(
            NextEpisodeWarm.WARM_COST_BUDGET_MS,
            NextEpisodeWarm.leadMs(minutes60, 0.97f),
        )
    }

    @Test
    fun `the 22 minute sitcom at the 85 percent card setting still gets the full budget`() {
        // This is the case that was a guaranteed cold start: hardcoded warm at 85% == card at 85%.
        val warm = NextEpisodeWarm.warmAtMs(minutes22, 0.85f)
        assertEquals(942_000L, warm)
        assertEquals(NextEpisodeWarm.WARM_COST_BUDGET_MS, NextEpisodeWarm.leadMs(minutes22, 0.85f))
    }

    @Test
    fun `never warms before halfway`() {
        // A 10-minute short at the 95% card: the full 180 s budget would put the warm at 390 s, which
        // is past halfway, so it stands. A 4-minute one would not be — check the floor holds there.
        val short = 4L * 60_000L
        val warm = NextEpisodeWarm.warmAtMs(short, 0.95f)
        assertTrue("warm=$warm", warm >= (short * NextEpisodeWarm.EARLIEST_FRACTION).toLong())
        // ...and it still lands before the card.
        assertTrue(warm < NextEpisodeWarm.upNextAtMs(short, 0.95f))
    }

    @Test
    fun `content too short for the budget falls back to the minimum lead, never past the card`() {
        val clip = 60_000L
        val card = NextEpisodeWarm.upNextAtMs(clip, 0.95f)   // 57 000
        val warm = NextEpisodeWarm.warmAtMs(clip, 0.95f)
        assertEquals(card - NextEpisodeWarm.MIN_LEAD_MS, warm)
        assertTrue(warm >= 0L)
    }

    @Test
    fun `unknown duration never triggers`() {
        // Media3 reports C.TIME_UNSET (negative) until the container index is parsed — common on the
        // low-power TV path. Warming against a negative duration would fire at position 0.
        assertFalse(NextEpisodeWarm.shouldWarmNow(positionMs = 5_000L, durationMs = -1L, upNextFraction = 0.95f))
        assertFalse(NextEpisodeWarm.shouldWarmNow(positionMs = 5_000L, durationMs = 0L, upNextFraction = 0.95f))
    }

    @Test
    fun `shouldWarmNow flips exactly at the computed point and stays true`() {
        val at = NextEpisodeWarm.warmAtMs(minutes45, 0.95f)
        assertFalse(NextEpisodeWarm.shouldWarmNow(at - 1, minutes45, 0.95f))
        assertTrue(NextEpisodeWarm.shouldWarmNow(at, minutes45, 0.95f))
        // Still true after the card, so a ticker that misses the exact edge (10 s period) still fires.
        assertTrue(NextEpisodeWarm.shouldWarmNow(minutes45 - 1_000L, minutes45, 0.95f))
    }

    // --- WHETHER: the warmed release must be the one that plays ------------------------------

    @Test
    fun `warmed release is preferred when the fresh resolve still lists it`() {
        val warmed = source("aaa")
        val other = source("bbb", seeders = 900)
        val redeemed = NextEpisodeWarm.redeem(
            warm = NextEpisodeWarm.Warmed(1, 4, warmed),
            season = 1,
            episode = 4,
            candidates = listOf(other, warmed),
            maxTier = 3,
            lowPower = false,
        )
        // Not `other`, even though it is far healthier: the warmed one has its head on disk already.
        assertSame(warmed, redeemed)
    }

    @Test
    fun `warmed release is replayed when the fresh resolve dropped it`() {
        // The dominant real failure: the play-time resolve is a SECOND addon fan-out (the resolve cache
        // TTL is 30 s and the warm ran minutes earlier). One addon timing out is enough to lose the row,
        // and the old `list.firstOrNull { it.infoHash == w }` then silently discarded the whole warm.
        val warmed = source("aaa")
        val freshOnly = source("bbb", seeders = 900)
        val redeemed = NextEpisodeWarm.redeem(
            warm = NextEpisodeWarm.Warmed(2, 1, warmed),
            season = 2,
            episode = 1,
            candidates = listOf(freshOnly),
            maxTier = 3,
            lowPower = false,
        )
        assertSame(warmed, redeemed)
    }

    @Test
    fun `a warm for a different episode is never redeemed`() {
        // playEpisode() is also the episode-LIST path. For a SEASON PACK every episode resolves to the
        // same btih, so a bare-hash lookup MATCHED and started a release picked for another episode.
        val pack = source("pack", isPack = true)
        assertNull(
            NextEpisodeWarm.redeem(
                warm = NextEpisodeWarm.Warmed(1, 3, pack),
                season = 1,
                episode = 7,
                candidates = listOf(pack),
                maxTier = 3,
                lowPower = false,
            ),
        )
    }

    @Test
    fun `a direct source is never pinned or replayed`() {
        // syntheticHash(directUrl) = SHA-1 of a Real-Debrid URL that is re-minted on every resolve, so
        // the pin is dead on arrival — and writing it into the episode's progress row poisoned the
        // saved resume hash for good.
        val direct = source("rd", direct = "https://rd.example/abc")
        assertNull(
            NextEpisodeWarm.redeem(
                warm = NextEpisodeWarm.Warmed(1, 2, direct),
                season = 1,
                episode = 2,
                candidates = listOf(direct),
                maxTier = 3,
                lowPower = false,
            ),
        )
    }

    @Test
    fun `the pin is refused when the device can no longer play it`() {
        // Prefer-if-ELIGIBLE. The warm's own pick ran minutes earlier; an unmetered -> metered flip in
        // between swaps wifiQuality for cellularQuality, so the pin is re-checked against the CURRENT
        // cap with the same gate the saved-resume path uses.
        val warmed4k = source("aaa", quality = "4K")
        val fine1080 = source("bbb", quality = "1080p")
        assertNull(
            NextEpisodeWarm.redeem(
                warm = NextEpisodeWarm.Warmed(1, 2, warmed4k),
                season = 1,
                episode = 2,
                candidates = listOf(warmed4k, fine1080),
                maxTier = 2,            // 1080p cap
                lowPower = false,
            ),
        )
    }

    @Test
    fun `an undecodable warmed release loses to a decodable candidate`() {
        val warmedXvid = source("aaa", playable = false)
        val decodable = source("bbb")
        assertNull(
            NextEpisodeWarm.redeem(
                warm = NextEpisodeWarm.Warmed(1, 2, warmedXvid),
                season = 1,
                episode = 2,
                candidates = listOf(warmedXvid, decodable),
                maxTier = 3,
                lowPower = false,
            ),
        )
    }

    @Test
    fun `no warm means re-rank normally`() {
        assertNull(
            NextEpisodeWarm.redeem(null, 1, 2, listOf(source("a")), maxTier = 3, lowPower = false),
        )
        assertNull(
            NextEpisodeWarm.redeem(
                NextEpisodeWarm.Warmed(1, 2, source("a")), 1, 2, emptyList(), maxTier = 3, lowPower = false,
            ),
        )
    }

    private fun source(
        hash: String,
        seeders: Int = 50,
        quality: String = "1080p",
        direct: String? = null,
        playable: Boolean = true,
        isPack: Boolean = false,
    ) = StreamSource(
        title = "source-$hash",
        magnetUri = if (direct == null) "magnet:?xt=urn:btih:$hash" else "",
        infoHash = hash,
        quality = quality,
        sizeBytes = 700,
        seeders = seeders,
        provider = "test",
        isPack = isPack,
        directUrl = direct,
        playable = playable,
    )

    // --- fastStart: the difference between "~1 second" and a full addon fan-out --------------------

    @Test
    fun `fastStart returns the warmed torrent when the head is on disk for this episode`() {
        val warmed = source("aa")
        val pin = NextEpisodeWarm.Warmed(1, 4, warmed, headReady = true)
        assertEquals(warmed, NextEpisodeWarm.fastStart(pin, 1, 4))
    }

    @Test
    fun `fastStart refuses a pin whose bytes never landed`() {
        // Starting instantly on a pin with nothing on disk trades a few seconds of "Finding sources…"
        // for a COLD torrent with no candidate list to fail over to — strictly worse.
        val pin = NextEpisodeWarm.Warmed(1, 4, source("aa"), headReady = false)
        assertNull(NextEpisodeWarm.fastStart(pin, 1, 4))
    }

    @Test
    fun `fastStart refuses a pin for a different episode`() {
        // playEpisode is also the episode-LIST path: jumping S1E2 -> S1E7 must not start the release
        // warmed for S1E3 — and a season pack shares one info-hash across every episode, so a hash
        // check alone would MATCH and start the wrong file.
        val pin = NextEpisodeWarm.Warmed(1, 3, source("aa"), headReady = true)
        assertNull(NextEpisodeWarm.fastStart(pin, 1, 7))
        assertNull(NextEpisodeWarm.fastStart(pin, 2, 3))
    }

    @Test
    fun `fastStart refuses a direct source and a null pin`() {
        // A direct identity is SHA-1 of the URL and Real-Debrid re-mints that URL per resolve, so the
        // recorded source is stale on arrival — and a direct stream needs no warm anyway.
        val direct = source("dd", direct = "https://x/y.mkv")
        assertNull(NextEpisodeWarm.fastStart(NextEpisodeWarm.Warmed(1, 4, direct, headReady = true), 1, 4))
        assertNull(NextEpisodeWarm.fastStart(null, 1, 4))
    }
}
