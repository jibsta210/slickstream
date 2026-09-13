package com.slickstream.feature.live

/**
 * The decision half of the live-feed retry engine: given WHAT broke, HOW MANY times we have already
 * tried inside this stall, and WHICH feeds are still worth trying, decide the single next move.
 *
 * Pure — no Android types, no ExoPlayer, no coroutines — so the whole escalation policy is unit
 * testable, in the same shape as [com.slickstream.data.torrent.StartGate],
 * [com.slickstream.feature.player.SurfaceRebindGate] and
 * [com.slickstream.feature.player.AudioTrackChoice]. [LivePlayerViewModel] only executes what this
 * returns.
 *
 * WHY THIS EXISTS — the "NFL day: fox 1 freezes, I switch to fox 2, it freezes a few minutes later,
 * I switch back" report. The user was hand-executing a failover the app already had every ingredient
 * for. The old live path had no recovery machinery at all: a mid-stream freeze left the UI in
 * `Playing` forever, and the only response to an error was `play(index)` — a full teardown that
 * re-spins an off-screen WebView (`WebViewStreamResolver.resolve` default timeout 40_000 ms) for a
 * two-second hiccup.
 *
 * The policy is an ESCALATING LADDER, cheapest first, and it refuses to skip rungs unless the
 * failure is one the cheap rungs provably cannot fix:
 *
 *  0-1. [LiveRecoveryAction.SEEK_TO_LIVE_EDGE] — `seekToDefaultPosition()`. Verified against media3
 *       1.5.1: `ExoPlayerImpl.seekTo` keeps the same MediaSource, the same HlsMediaPeriod and the
 *       same codec; it costs one sample-queue reset plus a chunk fetch. Nothing else in the ladder
 *       is anywhere near this cheap.
 *  2-3. [LiveRecoveryAction.REPREPARE] — stop + re-set the media item + prepare, on the SAME
 *       ExoPlayer and the SAME PlayerView surface. The stop is not optional: `ExoPlayerImpl.prepare()`
 *       opens with `if (playbackInfo.playbackState != STATE_IDLE) return`, so "just re-prepare on a
 *       stall" is a verified no-op while the player is BUFFERING or READY. This restarts
 *       `DefaultHlsPlaylistTracker` against the same URL, which a seek cannot do — the fix for a
 *       playlist that stopped advancing.
 *    4. [LiveRecoveryAction.REBUILD] — re-resolve the embed and rebind the media item. The only rung
 *       that can pay the WebView cost, so it is the LAST single-feed rung, not the first response.
 *   5+. [LiveRecoveryAction.SWITCH_FEED] / [LiveRecoveryAction.GIVE_UP].
 *
 * Two kinds jump the ladder because starting at rung 0 would be dishonest work:
 *  - [LiveFailureKind.STALE_URL] (HTTP 401/403/410 on a feed that had been playing) — the embed mints
 *    a ROTATING token in client-side JS, so the URL in hand is dead. A seek or a re-prepare re-requests
 *    exactly that dead URL and is guaranteed to fail. Straight to rung 4.
 *  - [LiveFailureKind.FATAL] (decoder init, parse, unsupported container) — this feed's bytes are not
 *    playable on this device; no amount of re-fetching changes that. Straight to another feed.
 */
object LiveRecoveryPlan {

    /** How a live feed can break, as classified from the player's own numbers or its error code. */
    enum class LiveFailureKind {
        /** `playWhenReady` but not playing, for longer than a hiccup — a rebuffer that never ends. */
        BUFFER_STALL,

        /**
         * The player says it is playing and reports no error, but `currentPosition` is not moving.
         * The dominant live-sports freeze, and the one nothing in the old code could even see:
         * `HlsChunkSource.getNextChunk` returns with no chunk and no error when a live playlist stops
         * advancing, and `DefaultHlsPlaylistTracker$MediaPlaylistBundle.processLoadedPlaylist` only
         * forms a `PlaylistStuckException` after 3.5x targetDuration of no change (the verified
         * DEFAULT_PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT = 3.5d — ~21 s on 6 s segments), and even
         * then throws it only lazily from `maybeThrowError`.
         */
        POSITION_STALL,

        /** STATE_ENDED on a live feed: the playlist grew an EXT-X-ENDLIST — the feed hung up. */
        STREAM_ENDED,

        /** STATE_IDLE while we still want to play, with no error delivered. */
        PLAYER_IDLE,

        /**
         * ERROR_CODE_BEHIND_LIVE_WINDOW (verified = 1002). The playhead fell off the back of the
         * sliding window. `ExoPlayerImplInternal` routes `BehindLiveWindowException` through
         * `handleIoException(e, 1002)`, which is unconditionally fatal (createForSource -> stopInternal
         * -> copyWithPlaybackError) — media3 1.5.1 does NOT self-heal this, recovery is the app's job.
         */
        BEHIND_LIVE_WINDOW,

        /** 2000..2999 (bad status, timeout, connection reset) or ERROR_CODE_TIMEOUT (1003). */
        TRANSIENT_IO,

        /** 401/403/410 on a feed that had already been playing: the signed/rotating token expired. */
        STALE_URL,

        /** Decoder init, parse, unsupported — this feed will not play here no matter how often we ask. */
        FATAL,
    }

    /** The single next move. The ViewModel executes exactly one of these per stall tick. */
    enum class LiveRecoveryAction {
        /** `seekToDefaultPosition()` — on a live timeline that is the live edge minus the target offset. */
        SEEK_TO_LIVE_EDGE,

        /** `stop()` + `setMediaItem(sameUrl)` + `prepare()` on the same player and the same surface. */
        REPREPARE,

        /** Re-resolve the embed (or re-take the direct URL) and rebind — the only rung that may use WebView. */
        REBUILD,

        /** Abandon this feed for [LiveRecoveryStep.targetFeedIndex]. */
        SWITCH_FEED,

        /** Automatic recovery is genuinely exhausted — show the 3-button overlay. */
        GIVE_UP,
    }

    /** What a feed is minimally known by, for ranking. */
    data class LiveFeedInfo(
        val index: Int,
        /** True when playing it costs a `WebViewStreamResolver.resolve` round trip first. */
        val needsResolution: Boolean,
    )

    /** Rolling per-feed record, so we never bounce back into a feed that just died. */
    data class LiveFeedHealth(
        /** Uptime stamp at which we last abandoned this feed. 0 = never. */
        val lastFailedAtMs: Long = 0L,
        /** Failures since this feed last managed a sustained clean run. */
        val consecutiveFailures: Int = 0,
        /** How much real playback this feed has carried this session — the "it was working" signal. */
        val totalPlayedMs: Long = 0L,
    )

    data class Input(
        val kind: LiveFailureKind,
        /** Recovery attempts ALREADY made in THIS stall episode. Reset by sustained clean playback. */
        val attempt: Int,
        /**
         * Whether `bufferedPosition` is still moving. Decisive for rung 0: if the buffer is ALSO
         * flat, the playlist itself is stuck, the timeline is stale, and a seek lands on the same
         * stale edge — provably useless, so skip straight to the re-prepare.
         */
        val bufferAdvancing: Boolean,
        val currentFeedIndex: Int,
        val feeds: List<LiveFeedInfo>,
        val health: Map<Int, LiveFeedHealth>,
        val nowMs: Long,
        /** Automatic feed switches since the last sustained clean run — the anti-rotation budget. */
        val autoSwitchesSinceHealthy: Int = 0,
    )

    data class LiveRecoveryStep(
        val action: LiveRecoveryAction,
        /** Backoff before executing. Capped at [MAX_BACKOFF_MS]: on live there is nothing arriving during a wait. */
        val delayMs: Long,
        /** Only meaningful for [LiveRecoveryAction.SWITCH_FEED]. */
        val targetFeedIndex: Int = -1,
        /** Short, breadcrumb-safe explanation — this string ends up in the Crashlytics trail. */
        val reason: String,
    )

    /** Watchdog tick. 1 s is fast enough to beat the ~21 s PlaylistStuckException and cheap enough to run always. */
    const val WATCHDOG_TICK_MS = 1_000L

    /**
     * How long the picture must be frozen before we call it a stall. Three ticks: long enough that a
     * single late segment on a healthy feed is not treated as a failure, short enough that the user
     * sees "Reconnecting…" instead of reaching for the remote.
     */
    const val STALL_AFTER_MS = 3_000L

    /** Position movement below this in a tick is noise, not progress. */
    const val POSITION_EPSILON_MS = 250L

    /**
     * Sustained clean playback that forgives everything. THE key constant for "freezes every few
     * minutes but then works": the budget is not a monotonic counter, it RESETS after this much
     * continuous playback, so a feed that stalls every few minutes for a three-hour game never
     * accumulates its way to the error screen — each stall starts the ladder at rung 0 again.
     * A feed stalling every few SECONDS never reaches 60 s clean and does escalate.
     */
    const val RECOVERY_RESET_MS = 60_000L

    /**
     * Never return to a feed within this long of abandoning it. This single rule is what breaks the
     * fox1 -> fox2 -> fox1 ping-pong the user was performing by hand: by the time fox1 is eligible
     * again, either fox2 is carrying the game or fox1 has genuinely recovered.
     */
    const val FEED_COOLDOWN_MS = 90_000L

    /** Rungs 0..4 are single-feed repairs; attempt 5 leaves the feed. */
    const val MAX_ATTEMPTS_PER_EPISODE = 5

    /** A live viewer will not sit through more than this, and unlike a torrent nothing arrives while we wait. */
    const val MAX_BACKOFF_MS = 5_000L

    private val BACKOFF_MS = longArrayOf(0L, 750L, 1_500L, 3_000L, 5_000L)

    /** Try each feed at most once before giving up, capped so a 12-feed event can't rotate for minutes. */
    fun maxAutoSwitches(feedCount: Int): Int = feedCount.coerceIn(1, 4)

    /** Backoff for the nth attempt in an episode, capped at [MAX_BACKOFF_MS]. */
    fun backoffFor(attempt: Int): Long =
        BACKOFF_MS[attempt.coerceIn(0, BACKOFF_MS.size - 1)]

    /**
     * Clean playback proving the rung we just applied actually WORKED, so the stall episode is over.
     *
     * Separate from [RECOVERY_RESET_MS] on purpose, and the distinction matters. The ladder position
     * (`attempt`) says "how many things have I already tried for THIS freeze". If it is only forgiven
     * after a full clean minute, then a feed that hiccups every 20 seconds — each hiccup cured instantly
     * by a rung-0 seek — still climbs 0,1,2,3,4,5 and abandons a feed that was being fixed every single
     * time. The ladder must restart once a repair is demonstrably holding.
     *
     * The per-feed failure history ([LiveFeedHealth.consecutiveFailures]) deliberately does NOT reset
     * here: that is the slow-moving signal for a feed which is genuinely degrading, and it still needs a
     * full clean minute before it is forgiven.
     */
    const val RECOVERY_CONFIRMED_MS = 8_000L

    /** True once the rung just applied has held long enough to call the stall episode finished. */
    fun recoveryConfirmed(playingForMs: Long): Boolean = playingForMs >= RECOVERY_CONFIRMED_MS

    /** True once the feed has played cleanly long enough to forgive its whole failure history. */
    fun shouldResetBudget(playingForMs: Long): Boolean = playingForMs >= RECOVERY_RESET_MS

    /**
     * The next feed worth trying, or null when there is none.
     *
     * Ranking, in order: out of cooldown at all; then fewest consecutive failures; then the feed that
     * has actually carried the most of this session (the "it was working ten minutes ago" signal);
     * then a direct m3u8 before an embed, because a wrong direct guess costs ~1 s and a wrong embed
     * guess costs a WebView resolve; then index, for determinism.
     */
    fun chooseFeed(
        currentIndex: Int,
        feeds: List<LiveFeedInfo>,
        health: Map<Int, LiveFeedHealth>,
        nowMs: Long,
        cooldownMs: Long = FEED_COOLDOWN_MS,
    ): Int? {
        val candidates = feeds.filter { it.index != currentIndex }.filter {
            val failedAt = health[it.index]?.lastFailedAtMs ?: 0L
            failedAt == 0L || nowMs - failedAt >= cooldownMs
        }
        if (candidates.isEmpty()) return null
        return candidates.minWithOrNull(
            compareBy<LiveFeedInfo> { health[it.index]?.consecutiveFailures ?: 0 }
                .thenByDescending { health[it.index]?.totalPlayedMs ?: 0L }
                .thenBy { if (it.needsResolution) 1 else 0 }
                .thenBy { it.index },
        )?.index
    }

    fun decide(input: Input): LiveRecoveryStep {
        val needsResolution = input.feeds.firstOrNull { it.index == input.currentFeedIndex }?.needsResolution == true

        // FATAL: the bytes are not playable on this device. Re-fetching them changes nothing, and
        // another feed may well carry a codec this box can decode.
        if (input.kind == LiveFailureKind.FATAL) {
            return leaveFeed(input, "fatal error on this feed")
        }

        // STALE_URL: a 401/403/410 on a URL that HAD been playing means the rotating token minted in
        // the embed's JS expired. Rungs 0-3 all re-request that exact dead URL, so they would burn
        // attempts and several seconds of frozen picture to learn nothing. Go straight to rung 4 —
        // but only for a feed we can actually re-resolve; a DIRECT m3u8 answering 403 is a host-side
        // block, and there is nothing to re-mint.
        if (input.kind == LiveFailureKind.STALE_URL) {
            return if (needsResolution && input.attempt < 2) {
                LiveRecoveryStep(
                    LiveRecoveryAction.REBUILD,
                    delayMs = backoffFor(input.attempt),
                    reason = "token expired -> re-resolve",
                )
            } else {
                leaveFeed(input, if (needsResolution) "re-resolve didn't help" else "direct url blocked")
            }
        }

        if (input.attempt >= MAX_ATTEMPTS_PER_EPISODE) {
            return leaveFeed(input, "ladder exhausted on this feed")
        }

        val rung = when (input.attempt) {
            0, 1 -> LiveRecoveryAction.SEEK_TO_LIVE_EDGE
            2, 3 -> LiveRecoveryAction.REPREPARE
            else -> LiveRecoveryAction.REBUILD
        }

        // A seek only helps when the player still HAS a live timeline that is moving underneath it.
        // After an error the player is IDLE (nothing to seek in), and when the buffer is flat too the
        // playlist itself is stuck, so the seek would land on the same stale edge. Promote to rung 2.
        val seekIsUseless = rung == LiveRecoveryAction.SEEK_TO_LIVE_EDGE && !canSeekHelp(input)
        val action = if (seekIsUseless) LiveRecoveryAction.REPREPARE else rung
        val reason = when {
            seekIsUseless && !input.bufferAdvancing -> "buffer flat too -> playlist stuck, seek can't help"
            seekIsUseless -> "player idle after ${input.kind} -> seek can't help"
            else -> "ladder rung ${input.attempt} for ${input.kind}"
        }
        return LiveRecoveryStep(action, delayMs = backoffFor(input.attempt), reason = reason)
    }

    private fun canSeekHelp(input: Input): Boolean = when (input.kind) {
        // Still has a live timeline; a seek to the edge is the cheapest real fix — but only if the
        // timeline is actually advancing, which `bufferAdvancing` is the proxy for.
        LiveFailureKind.POSITION_STALL, LiveFailureKind.BUFFER_STALL -> input.bufferAdvancing
        // Everything else leaves the player IDLE (an error) or ENDED, where a seek alone is a no-op.
        else -> false
    }

    private fun leaveFeed(input: Input, why: String): LiveRecoveryStep {
        // Anti-rotation: without this, a 3-feed event whose feeds are all dead would cycle forever
        // once the 90 s cooldowns expire. Try each feed about once, then stop and be honest.
        if (input.autoSwitchesSinceHealthy >= maxAutoSwitches(input.feeds.size)) {
            return LiveRecoveryStep(LiveRecoveryAction.GIVE_UP, delayMs = 0L, reason = "$why; every feed tried")
        }
        val target = chooseFeed(input.currentFeedIndex, input.feeds, input.health, input.nowMs)
            ?: return LiveRecoveryStep(LiveRecoveryAction.GIVE_UP, delayMs = 0L, reason = "$why; no feed out of cooldown")
        return LiveRecoveryStep(
            LiveRecoveryAction.SWITCH_FEED,
            delayMs = 0L,
            targetFeedIndex = target,
            reason = "$why; switching to feed $target",
        )
    }
}
