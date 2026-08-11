package com.slickstream.feature.player

import com.slickstream.core.model.StreamSource
import com.slickstream.data.source.StreamPicker

/**
 * The two non-trivial decisions behind the next-episode precache, isolated so they can be tested
 * without a player, a swarm or a network:
 *
 *  1. WHEN to start warming, given the playhead and the user's own "Up next" card threshold.
 *  2. WHETHER the release we warmed is still the one that should play.
 *
 * Both used to live inline in PlayerViewModel and both were wrong in ways that made the whole feature
 * read as "precaching does not seem to work at all":
 *
 *  - The trigger was a hardcoded 85% (or T-3min, whichever came first) while the card threshold is a
 *    USER SETTING offering 85/90/93/95/97. At the 85% setting the card fired at the same instant as the
 *    warm — and since the card polls at 1 s while the warm rode a 10 s progress ticker, the card
 *    reliably won. The user got a "Up next" button for an episode nothing had started fetching.
 *  - The warm remembered only an info-HASH, redeemed by looking it up in a SECOND, later resolve. The
 *    resolve cache TTL is 30 s and the warm runs minutes before the hop, so that second resolve is a
 *    real addon fan-out every time. One addon timing out (or seeder counts moving, which moves
 *    StreamPicker's relative-health floor) drops the warmed release from the list, the lookup misses,
 *    and a COLD release starts instead — with the warmed bytes sitting unused on disk.
 */
object NextEpisodeWarm {

    /** The release pinned by a warm, keyed to the episode it was warmed FOR.
     *
     *  Keyed, because playEpisode() is also the episode-LIST path: jumping S1E2 -> S1E7 used to redeem
     *  the warm computed for S1E3. For a single-file release that just missed harmlessly, but a SEASON
     *  PACK carries the same 40-hex btih for every episode, so the lookup MATCHED and we started a
     *  release chosen for a different episode, whose warmed file was the wrong one. */
    data class Warmed(
        val season: Int,
        val episode: Int,
        val source: StreamSource,
        /** True once the warm has actually put this episode's head (and index, where the container needs
         *  one) on disk. Only then may [fastStart] skip the resolve: starting instantly on a pin whose
         *  bytes never landed would trade a few seconds of "Finding sources…" for a cold torrent with no
         *  candidate list to fail over to. */
        val headReady: Boolean = false,
    )

    /**
     * Wall-clock the warm must be allowed to finish in, measured against the observed worst case:
     *  - resolve fan-out: ADDON_QUERY_TIMEOUT_MS 12 s x INDEXER_ATTEMPTS 3 per custom addon, plus the
     *    TPB/EZTV tracker fallback — 1-4 s healthy, 15-40 s realistic bad case;
     *  - metadata on a cold magnet: 0 s on a .meta cache hit, 5-20 s typical, up to
     *    METADATA_TIMEOUT_SECONDS = 60 s;
     *  - 8 MB head + the mp4 moov band: 1.3-4 s of pure transfer at 2-6 MB/s, 10-30 s including swarm
     *    ramp, and the whole head phase is capped by the streamer's own warm budget.
     * ~20 s best case, 100-150 s realistic bad case. 180 s is that bad case with headroom, and it is
     * deliberately the SAME budget TorrentStreamerImpl.WARM_TOTAL_BUDGET_MS bounds the warm to, so the
     * lead we ask for and the time the warm is allowed to take cannot drift apart.
     */
    const val WARM_COST_BUDGET_MS = 180_000L

    /**
     * Absolute floor on the lead, for content too short for [WARM_COST_BUDGET_MS] to fit before the
     * card. Without it a 60 s clip would compute a warm point AFTER its own card. 30 s still comfortably
     * exceeds the 10 s progress-ticker period the trigger is evaluated on, so even a maximally unlucky
     * tick lands before the card appears.
     */
    const val MIN_LEAD_MS = 30_000L

    /**
     * Never warm before the halfway mark, whatever the arithmetic says. Warming at 20% of a 60-minute
     * episode would hold a second torrent's bytes (and its cache-eviction protection) for 40 minutes
     * for a next episode the viewer may never reach — and the LRU budget is 4 GB.
     */
    const val EARLIEST_FRACTION = 0.5f

    /** Where the "Up next" card appears, in ms. Mirrors TvPlayerScreen/PlayerScreen, which compare
     *  `position >= duration * upNextPercent/100`. */
    fun upNextAtMs(durationMs: Long, upNextFraction: Float): Long =
        (durationMs * upNextFraction.coerceIn(0.5f, 1f)).toLong()

    /**
     * The playhead position at which the warm should FIRE, derived from the card rather than from a
     * constant that can sit after it.
     *
     * Worked examples (the cases the old hardcoded 0.85f got wrong):
     *  - 22-min sitcom (1320 s), card at 85%: card 1122 s, warm 942 s -> 180 s of lead. The old code
     *    warmed at 1122 s — the same second the card appeared, and in practice after it.
     *  - 45-min drama (2700 s), card at 90%: card 2430 s, warm 2250 s -> 180 s.
     *  - 60-min (3600 s), card at 97%: card 3492 s, warm 3312 s -> 180 s.
     *  - 10-min short (600 s), card at 95%: card 570 s, warm 390 s -> 180 s (still past the 300 s floor).
     *  - 60 s clip, card at 95%: card 57 s, floor 30 s, warm clamped to 27 s -> the [MIN_LEAD_MS] floor.
     */
    fun warmAtMs(durationMs: Long, upNextFraction: Float): Long {
        if (durationMs <= 0L) return Long.MAX_VALUE
        val card = upNextAtMs(durationMs, upNextFraction)
        val earliest = (durationMs * EARLIEST_FRACTION).toLong()
        // Take the full budget when the episode is long enough to afford it, but never start before
        // halfway...
        val wanted = maxOf(earliest, card - WARM_COST_BUDGET_MS)
        // ...and never AT or after the card, which is the whole bug this replaces.
        return maxOf(0L, minOf(card - MIN_LEAD_MS, wanted))
    }

    /** How much lead the current settings actually buy. Logged so a diagnostics report can say whether
     *  a cold next-episode start was "the warm never ran" or "the warm had 30 s and needed 90". */
    fun leadMs(durationMs: Long, upNextFraction: Float): Long =
        if (durationMs <= 0L) 0L else upNextAtMs(durationMs, upNextFraction) - warmAtMs(durationMs, upNextFraction)

    /** True once the playhead has reached [warmAtMs]. Duration is C.TIME_UNSET (negative) until the
     *  container index is parsed, so an unknown duration never triggers. */
    fun shouldWarmNow(positionMs: Long, durationMs: Long, upNextFraction: Float): Boolean {
        if (durationMs <= 0L || positionMs < 0L) return false
        return positionMs >= warmAtMs(durationMs, upNextFraction)
    }

    /**
     * PREFER-IF-ELIGIBLE, never force: the release we warmed, if it is still a legitimate pick for this
     * device and this candidate set. Returns null to mean "re-rank normally" — the caller then falls
     * through to its usual resume/auto-pick, so no health or quality rule is weakened.
     *
     * Three things this fixes over `list.firstOrNull { it.infoHash == warmedHash }`:
     *
     *  - It is keyed to (season, episode). The episode-list and previous-episode paths reach the same
     *    playEpisode() and must never redeem a warm computed for a different episode (see [Warmed]).
     *  - When the fresh resolve has LOST the warmed release (an addon that answered at warm time timing
     *    out now — routine), we replay the warmed StreamSource OBJECT. A magnet does not expire and its
     *    head is already on disk, so this is strictly better than cold-starting a stranger.
     *  - It refuses a DIRECT source outright. A direct source's identity is syntheticHash(directUrl) =
     *    SHA-1 of the URL, and Real-Debrid mints a fresh unrestrict URL per resolve — so the recorded
     *    hash is dead on arrival and the URL itself is stale. Directs also need no warm: they play in
     *    about a second anyway.
     *
     * Eligibility is [StreamPicker.isResumeCompatible] — deliberately the exact gate the saved-resume
     * path already trusts, not a second parallel set of rules. It re-checks the pin against the device's
     * CURRENT tier, which matters because the warm's own pick ran minutes earlier and an
     * unmetered -> metered flip in between swaps wifiQuality for cellularQuality.
     */
    /**
     * The warm that can be started WITHOUT waiting for a fresh resolve.
     *
     * Redeeming only after the network resolve is what kept the whole feature from meeting its point:
     * playEpisode shows "Finding sources…" and awaits a full addon fan-out before it will look at the
     * warm, and that fan-out is GUARANTEED cold (the resolve cache TTL is 30 s; the warm ran minutes
     * ago). So a perfect warm — head and index already on disk — still cost the user the slowest part of
     * starting an episode. When the pin is for exactly this episode and is a torrent, its own
     * StreamSource is sufficient to start: it carries the magnet, the file index and the episode
     * coordinates. The real candidate list is then fetched in the background, only to populate the
     * source picker and the failover pool.
     *
     * Deliberately NOT tier-checked here: [redeem]'s device-compatibility gate needs the candidate list
     * it is ranked against. The caller re-runs redeem when the background resolve lands and can correct
     * course then; an eligible-looking warm starting instantly is the entire feature.
     */
    fun fastStart(warm: Warmed?, season: Int, episode: Int): StreamSource? {
        if (warm == null || warm.season != season || warm.episode != episode) return null
        if (warm.source.isDirect) return null
        if (warm.source.magnetUri.isBlank()) return null
        return warm.source.takeIf { warm.headReady }
    }

    fun redeem(
        warm: Warmed?,
        season: Int,
        episode: Int,
        candidates: List<StreamSource>,
        maxTier: Int,
        lowPower: Boolean,
    ): StreamSource? {
        if (warm == null || warm.season != season || warm.episode != episode) return null
        if (warm.source.isDirect) return null
        if (candidates.isEmpty()) return null
        val pick = candidates.firstOrNull { it.infoHash.equals(warm.source.infoHash, ignoreCase = true) }
            ?: warm.source
        if (!StreamPicker.isResumeCompatible(pick, candidates, maxTier, lowPower)) return null
        return pick
    }
}
