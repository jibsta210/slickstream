package com.slickstream.feature.live

import com.slickstream.feature.live.LiveRecoveryPlan.Input
import com.slickstream.feature.live.LiveRecoveryPlan.LiveFailureKind
import com.slickstream.feature.live.LiveRecoveryPlan.LiveFeedHealth
import com.slickstream.feature.live.LiveRecoveryPlan.LiveFeedInfo
import com.slickstream.feature.live.LiveRecoveryPlan.LiveRecoveryAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the escalation policy behind "NFL day: fox 1 freezes, I switch to fox 2, a few minutes later
 * it freezes too, I switch back".
 *
 * Every case here is a way the shipped live player either did nothing at all (a mid-stream freeze
 * left the UI in `Playing` forever), or reached for the most expensive possible repair (`retry()`
 * was a full teardown plus a WebView resolve with a 40 s timeout), or would — with a naive automatic
 * failover bolted on — have reproduced the ping-pong the user was doing by hand.
 */
class LiveRecoveryPlanTest {

    private val twoFeeds = listOf(LiveFeedInfo(0, needsResolution = true), LiveFeedInfo(1, needsResolution = true))
    private val oneFeed = listOf(LiveFeedInfo(0, needsResolution = true))

    private fun input(
        kind: LiveFailureKind = LiveFailureKind.POSITION_STALL,
        attempt: Int = 0,
        bufferAdvancing: Boolean = true,
        currentFeedIndex: Int = 0,
        feeds: List<LiveFeedInfo> = twoFeeds,
        health: Map<Int, LiveFeedHealth> = emptyMap(),
        nowMs: Long = 1_000_000L,
        autoSwitchesSinceHealthy: Int = 0,
    ) = Input(kind, attempt, bufferAdvancing, currentFeedIndex, feeds, health, nowMs, autoSwitchesSinceHealthy)

    // --- The ladder ------------------------------------------------------------------------------

    @Test
    fun `first response to a freeze is the cheapest possible repair, not a teardown`() {
        val step = LiveRecoveryPlan.decide(input(attempt = 0))
        assertEquals(LiveRecoveryAction.SEEK_TO_LIVE_EDGE, step.action)
        // And with no backoff: the picture is frozen RIGHT NOW, there is nothing to wait for.
        assertEquals(0L, step.delayMs)
    }

    @Test
    fun `ladder escalates seek - seek - reprepare - reprepare - rebuild - switch`() {
        val actions = (0..5).map { LiveRecoveryPlan.decide(input(attempt = it)).action }
        assertEquals(
            listOf(
                LiveRecoveryAction.SEEK_TO_LIVE_EDGE,
                LiveRecoveryAction.SEEK_TO_LIVE_EDGE,
                LiveRecoveryAction.REPREPARE,
                LiveRecoveryAction.REPREPARE,
                LiveRecoveryAction.REBUILD,
                LiveRecoveryAction.SWITCH_FEED,
            ),
            actions,
        )
    }

    @Test
    fun `a cheaper rung is never skipped for a plain stall`() {
        // The old code's only response to anything was play(index): release the ExoPlayer, drop to a
        // spinner and re-spin a WebView. Nothing below rung 4 may do that.
        for (attempt in 0..3) {
            val action = LiveRecoveryPlan.decide(input(attempt = attempt)).action
            assertTrue(
                "attempt $attempt reached $action",
                action == LiveRecoveryAction.SEEK_TO_LIVE_EDGE || action == LiveRecoveryAction.REPREPARE,
            )
        }
    }

    @Test
    fun `stuck playlist - buffer flat too - skips the seek because it provably cannot help`() {
        // A seek resolves against the TIMELINE. If the playlist stopped advancing, the timeline is
        // stale and the seek lands on the same stale edge. Only a re-prepare restarts the tracker.
        val step = LiveRecoveryPlan.decide(input(attempt = 0, bufferAdvancing = false))
        assertEquals(LiveRecoveryAction.REPREPARE, step.action)
        assertTrue(step.reason.contains("playlist stuck"))
    }

    @Test
    fun `behind live window - the player is IDLE, so a bare seek is a no-op`() {
        // ExoPlayerImplInternal routes BehindLiveWindowException to handleIoException(e, 1002), which
        // is unconditionally fatal in media3 1.5.1 — the player is stopped, not merely stalled.
        val step = LiveRecoveryPlan.decide(input(kind = LiveFailureKind.BEHIND_LIVE_WINDOW, attempt = 0))
        assertEquals(LiveRecoveryAction.REPREPARE, step.action)
    }

    @Test
    fun `transient IO and player-idle and stream-ended all start at the re-prepare rung`() {
        for (kind in listOf(
            LiveFailureKind.TRANSIENT_IO,
            LiveFailureKind.PLAYER_IDLE,
            LiveFailureKind.STREAM_ENDED,
        )) {
            assertEquals(
                "kind=$kind",
                LiveRecoveryAction.REPREPARE,
                LiveRecoveryPlan.decide(input(kind = kind, attempt = 0)).action,
            )
        }
    }

    // --- Jumping the ladder ----------------------------------------------------------------------

    @Test
    fun `expired token jumps straight to a re-resolve - re-requesting a dead signed url cannot work`() {
        val step = LiveRecoveryPlan.decide(input(kind = LiveFailureKind.STALE_URL, attempt = 0))
        assertEquals(LiveRecoveryAction.REBUILD, step.action)
        assertTrue(step.reason.contains("token expired"))
    }

    @Test
    fun `expired token on a DIRECT feed leaves the feed - there is no token to re-mint`() {
        val direct = listOf(LiveFeedInfo(0, needsResolution = false), LiveFeedInfo(1, needsResolution = false))
        val step = LiveRecoveryPlan.decide(input(kind = LiveFailureKind.STALE_URL, attempt = 0, feeds = direct))
        assertEquals(LiveRecoveryAction.SWITCH_FEED, step.action)
        assertEquals(1, step.targetFeedIndex)
    }

    @Test
    fun `a second re-resolve is not attempted - if the token was not the problem, the feed is`() {
        val step = LiveRecoveryPlan.decide(input(kind = LiveFailureKind.STALE_URL, attempt = 2))
        assertEquals(LiveRecoveryAction.SWITCH_FEED, step.action)
    }

    @Test
    fun `a fatal decoder or parse error leaves the feed immediately`() {
        // No amount of re-fetching makes an undecodable stream decodable on this box.
        val step = LiveRecoveryPlan.decide(input(kind = LiveFailureKind.FATAL, attempt = 0))
        assertEquals(LiveRecoveryAction.SWITCH_FEED, step.action)
        assertEquals(1, step.targetFeedIndex)
    }

    // --- Backoff ---------------------------------------------------------------------------------

    @Test
    fun `backoff grows but never exceeds five seconds - on live nothing arrives while we wait`() {
        val delays = (0..8).map { LiveRecoveryPlan.backoffFor(it) }
        assertEquals(0L, delays.first())
        assertTrue("not monotonic: $delays", delays.zipWithNext().all { (a, b) -> b >= a })
        assertTrue("exceeded cap: $delays", delays.all { it <= LiveRecoveryPlan.MAX_BACKOFF_MS })
        assertEquals(LiveRecoveryPlan.MAX_BACKOFF_MS, delays.last())
    }

    @Test
    fun `the switch away from a dead feed is not delayed`() {
        val step = LiveRecoveryPlan.decide(input(kind = LiveFailureKind.FATAL))
        assertEquals(0L, step.delayMs)
    }

    // --- Budget ----------------------------------------------------------------------------------

    @Test
    fun `a minute of clean playback resets the budget - the three-hour-game rule`() {
        assertTrue(LiveRecoveryPlan.shouldResetBudget(LiveRecoveryPlan.RECOVERY_RESET_MS))
        assertTrue(LiveRecoveryPlan.shouldResetBudget(5 * 60_000L))
        // A feed stalling every few SECONDS never earns the reset, so it does escalate.
        assertTrue(!LiveRecoveryPlan.shouldResetBudget(9_000L))
    }

    @Test
    fun `freeze every few minutes for three hours - each episode starts at rung zero, forever`() {
        // The reported behaviour, simulated: the ViewModel zeroes `attempt` after each clean minute,
        // so every one of the 36 stalls in a 3-hour game is met with the cheapest repair, and the
        // error overlay is never reached.
        var attempt = 0
        var overlays = 0
        repeat(36) {
            val step = LiveRecoveryPlan.decide(input(attempt = attempt))
            if (step.action == LiveRecoveryAction.GIVE_UP) overlays++
            assertEquals(LiveRecoveryAction.SEEK_TO_LIVE_EDGE, step.action)
            attempt++
            // ...then a clean minute of football.
            if (LiveRecoveryPlan.shouldResetBudget(90_000L)) attempt = 0
        }
        assertEquals(0, overlays)
    }

    @Test
    fun `a genuinely dead single-feed event does give up rather than loop`() {
        val step = LiveRecoveryPlan.decide(input(attempt = 5, feeds = oneFeed))
        assertEquals(LiveRecoveryAction.GIVE_UP, step.action)
    }

    @Test
    fun `rotation is bounded - once every feed has been tried, stop and be honest`() {
        val four = (0..3).map { LiveFeedInfo(it, needsResolution = false) }
        val step = LiveRecoveryPlan.decide(
            input(kind = LiveFailureKind.FATAL, feeds = four, autoSwitchesSinceHealthy = 4),
        )
        assertEquals(LiveRecoveryAction.GIVE_UP, step.action)
        assertTrue(step.reason.contains("every feed tried"))
    }

    @Test
    fun `maxAutoSwitches is bounded by the feed count and capped`() {
        assertEquals(1, LiveRecoveryPlan.maxAutoSwitches(1))
        assertEquals(2, LiveRecoveryPlan.maxAutoSwitches(2))
        assertEquals(4, LiveRecoveryPlan.maxAutoSwitches(12))
    }

    // --- Ping-pong guard -------------------------------------------------------------------------

    @Test
    fun `fox1 to fox2 to fox1 within the cooldown is refused - the exact loop the user was doing`() {
        val now = 1_000_000L
        // fox1 (index 0) was abandoned 10 s ago; we are on fox2 (index 1) and it just died too.
        val health = mapOf(0 to LiveFeedHealth(lastFailedAtMs = now - 10_000L, consecutiveFailures = 1))
        val step = LiveRecoveryPlan.decide(
            input(kind = LiveFailureKind.FATAL, currentFeedIndex = 1, health = health, nowMs = now),
        )
        assertEquals(LiveRecoveryAction.GIVE_UP, step.action)
        assertTrue(step.reason.contains("cooldown"))
    }

    @Test
    fun `the same feed IS eligible again once the cooldown has passed`() {
        val now = 1_000_000L
        val health = mapOf(
            0 to LiveFeedHealth(lastFailedAtMs = now - LiveRecoveryPlan.FEED_COOLDOWN_MS - 1, consecutiveFailures = 1),
        )
        val step = LiveRecoveryPlan.decide(
            input(kind = LiveFailureKind.FATAL, currentFeedIndex = 1, health = health, nowMs = now),
        )
        assertEquals(LiveRecoveryAction.SWITCH_FEED, step.action)
        assertEquals(0, step.targetFeedIndex)
    }

    @Test
    fun `feed ranking prefers the one that has actually been carrying the game`() {
        val now = 1_000_000L
        val feeds = listOf(
            LiveFeedInfo(0, needsResolution = false),
            LiveFeedInfo(1, needsResolution = false),
            LiveFeedInfo(2, needsResolution = false),
        )
        val health = mapOf(
            1 to LiveFeedHealth(totalPlayedMs = 30_000L),
            2 to LiveFeedHealth(totalPlayedMs = 20 * 60_000L),
        )
        assertEquals(2, LiveRecoveryPlan.chooseFeed(0, feeds, health, now))
    }

    @Test
    fun `fewest failures beats most playtime`() {
        val now = 1_000_000L
        val feeds = listOf(
            LiveFeedInfo(0, needsResolution = false),
            LiveFeedInfo(1, needsResolution = false),
            LiveFeedInfo(2, needsResolution = false),
        )
        val health = mapOf(
            // Long-serving but now failing repeatedly, and long out of cooldown.
            1 to LiveFeedHealth(
                lastFailedAtMs = now - 10 * 60_000L,
                consecutiveFailures = 3,
                totalPlayedMs = 40 * 60_000L,
            ),
            2 to LiveFeedHealth(consecutiveFailures = 0, totalPlayedMs = 1_000L),
        )
        assertEquals(2, LiveRecoveryPlan.chooseFeed(0, feeds, health, now))
    }

    @Test
    fun `a direct m3u8 is preferred over an embed - a wrong guess costs 1s instead of a WebView resolve`() {
        val feeds = listOf(
            LiveFeedInfo(0, needsResolution = false),
            LiveFeedInfo(1, needsResolution = true),
            LiveFeedInfo(2, needsResolution = false),
        )
        assertEquals(2, LiveRecoveryPlan.chooseFeed(0, feeds, emptyMap(), 1_000_000L))
    }

    @Test
    fun `the current feed is never chosen as its own replacement`() {
        assertNull(LiveRecoveryPlan.chooseFeed(0, oneFeed, emptyMap(), 1_000_000L))
    }

    @Test
    fun `a feed that has never failed is eligible even at time zero`() {
        // lastFailedAtMs defaults to 0, which must mean "never failed", not "failed at uptime 0" —
        // the difference matters on the very first stall after boot, when nowMs is still small.
        assertEquals(1, LiveRecoveryPlan.chooseFeed(0, twoFeeds, emptyMap(), 500L))
    }

    // =============================================================================================
    // The ladder restarts once a repair HOLDS — independent freezes must not inherit a rung
    // =============================================================================================

    @Test
    fun `a rung that holds ends the stall episode well before the full budget reset`() {
        // The distinction that matters for the reported case. RECOVERY_CONFIRMED_MS proves "the thing I
        // just did worked"; RECOVERY_RESET_MS forgives the feed's whole failure history. If only the
        // latter existed, a feed hiccuping every 20s — each hiccup cured instantly by the rung-0 seek —
        // would still climb the ladder and abandon a feed that was being fixed every single time.
        assertTrue(LiveRecoveryPlan.recoveryConfirmed(LiveRecoveryPlan.RECOVERY_CONFIRMED_MS))
        assertFalse(LiveRecoveryPlan.recoveryConfirmed(LiveRecoveryPlan.RECOVERY_CONFIRMED_MS - 1))
        // Confirmation must be reachable long before the clean-minute budget, or it changes nothing.
        assertTrue(LiveRecoveryPlan.RECOVERY_CONFIRMED_MS < LiveRecoveryPlan.RECOVERY_RESET_MS)
        // ...and must still be long enough that a stall recurring immediately is NOT called a success.
        assertTrue(LiveRecoveryPlan.RECOVERY_CONFIRMED_MS > LiveRecoveryPlan.STALL_AFTER_MS)
    }

    @Test
    fun `a confirmed recovery does not by itself forgive the feed's failure history`() {
        // consecutiveFailures is the slow signal for a feed that is genuinely degrading, so it keeps the
        // full clean-minute requirement even though the ladder restarts sooner.
        assertFalse(LiveRecoveryPlan.shouldResetBudget(LiveRecoveryPlan.RECOVERY_CONFIRMED_MS))
        assertTrue(LiveRecoveryPlan.shouldResetBudget(LiveRecoveryPlan.RECOVERY_RESET_MS))
    }
}
