package com.slickstream.feature.live

import com.slickstream.feature.live.LiveRecoveryPlan.LiveFailureKind

/**
 * The detection half of the live retry engine: decides, from the player's own numbers alone, whether
 * the picture is actually moving.
 *
 * Pure — the caller feeds it one sample per tick and gets back a verdict; no Android types, no
 * ExoPlayer, no clock of its own. Same shape as [com.slickstream.feature.player.SurfaceRebindGate].
 *
 * WHY A POSITION WATCHDOG AND NOT A STATE WATCHDOG — this is the core of the reported bug. The old
 * live path only reacted to `onPlaybackStateChanged`, and did so with
 * `STATE_BUFFERING -> if (uiState !is Playing) uiState = Buffering`, which made `Playing` an
 * ABSORBING state: once playing, nothing but a fatal error could leave it. A mid-game freeze
 * therefore produced no state change, no spinner, no log and no recovery attempt — the user's only
 * remedy was the remote.
 *
 * Worse, the dominant live-sports freeze never reaches STATE_BUFFERING at all. Verified in media3
 * 1.5.1: `HlsChunkSource.getNextChunk` hits `if (segmentHolder == null && !playlist.hasEndTag)` — a
 * live playlist that stopped advancing — and simply returns with no chunk and no error, and
 * `DefaultHlsPlaylistTracker$MediaPlaylistBundle.processLoadedPlaylist` only forms a
 * `PlaylistStuckException` after the playlist has been unchanged for 3.5x targetDuration (the
 * verified DEFAULT_PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT = 3.5d, so ~21 s on 6 s segments),
 * throwing it only lazily from `maybeThrowError` on a second starved pass. Watching `currentPosition`
 * instead sees the same freeze in [LiveRecoveryPlan.STALL_AFTER_MS] — 3 s, not 21 s.
 *
 * KNOWN LIMIT, stated honestly: when video freezes but AUDIO keeps playing, the media clock IS the
 * audio clock, so `currentPosition` keeps advancing and this monitor reports healthy. That case is
 * covered separately by the renderer configuration in [LivePlayerViewModel] (decoder fallback on and
 * `allowedVideoJoiningTimeMs = 0`, matching the VOD players), not here.
 */
class LiveStallMonitor(
    private val stallAfterMs: Long = LiveRecoveryPlan.STALL_AFTER_MS,
    private val positionEpsilonMs: Long = LiveRecoveryPlan.POSITION_EPSILON_MS,
) {

    /** Mirrors `androidx.media3.common.Player.STATE_*` so this file stays free of Android types. */
    object State {
        const val IDLE = 1
        const val BUFFERING = 2
        const val READY = 3
        const val ENDED = 4
    }

    sealed interface Verdict {
        /** The picture is moving. [playingForMs] is the length of the current UNBROKEN clean run. */
        data class Healthy(val playingForMs: Long) : Verdict

        /** Not enough evidence yet: first sample, deliberately paused, or a repair still landing. */
        data object Settling : Verdict

        data class Stalled(
            val kind: LiveFailureKind,
            /** `bufferedPosition` still moving? If not, the playlist is stuck and a seek can't help. */
            val bufferAdvancing: Boolean,
            val stalledForMs: Long,
        ) : Verdict
    }

    private var lastPositionMs = UNSET
    private var lastBufferedMs = UNSET
    private var lastAdvanceAtMs = 0L
    private var lastBufferAdvanceAtMs = 0L
    private var healthySinceMs = 0L
    private var suppressedUntilMs = 0L

    /**
     * A fresh media item is bound (new feed, or a re-prepare that restarted at the live edge). Both
     * baselines are dropped rather than merely paused: a re-prepare moves `currentPosition` by a large
     * arbitrary amount, and comparing across that jump would read either as a huge false "advance" or,
     * on a backwards jump, as a frozen clock.
     */
    fun reset() {
        lastPositionMs = UNSET
        lastBufferedMs = UNSET
        lastAdvanceAtMs = 0L
        lastBufferAdvanceAtMs = 0L
        healthySinceMs = 0L
        suppressedUntilMs = 0L
    }

    /**
     * A repair was just issued — hold fire until it has had a chance to land. Without this the 1 s
     * watchdog would fire again on the very next tick (the picture is still frozen while the seek's
     * chunk is being fetched) and race the ladder up to a teardown for a stall that was already fixed.
     */
    fun onRecoveryIssued(nowMs: Long, settleMs: Long) {
        reset()
        suppressedUntilMs = nowMs + settleMs
    }

    fun sample(
        nowMs: Long,
        positionMs: Long,
        bufferedPositionMs: Long,
        playWhenReady: Boolean,
        isPlaying: Boolean,
        playbackState: Int,
    ): Verdict {
        if (nowMs < suppressedUntilMs) return Verdict.Settling

        // Deliberately not playing (the user paused, or we paused for a cast hand-off). There is no
        // freeze to rescue, and treating it as one would fight the user.
        if (!playWhenReady) {
            healthySinceMs = 0L
            lastPositionMs = UNSET
            lastBufferedMs = UNSET
            return Verdict.Settling
        }

        val firstSample = lastPositionMs == UNSET
        if (firstSample) {
            lastPositionMs = positionMs
            lastBufferedMs = bufferedPositionMs
            lastAdvanceAtMs = nowMs
            lastBufferAdvanceAtMs = nowMs
            return Verdict.Settling
        }

        if (positionMs - lastPositionMs > positionEpsilonMs) lastAdvanceAtMs = nowMs
        if (bufferedPositionMs - lastBufferedMs > positionEpsilonMs) lastBufferAdvanceAtMs = nowMs
        lastPositionMs = positionMs
        lastBufferedMs = bufferedPositionMs

        val frozenForMs = nowMs - lastAdvanceAtMs
        val bufferAdvancing = nowMs - lastBufferAdvanceAtMs < stallAfterMs

        // A live feed that reaches ENDED has hung up (the playlist grew an EXT-X-ENDLIST, or the CDN
        // closed the event). It is never the normal outcome here, and the old `else -> Unit` swallowed
        // it entirely, leaving the UI claiming to play a stream that had stopped existing.
        if (playbackState == State.ENDED) {
            healthySinceMs = 0L
            return Verdict.Stalled(LiveFailureKind.STREAM_ENDED, bufferAdvancing, frozenForMs)
        }
        // IDLE while we still want to play, with no error routed to onPlayerError — also swallowed by
        // the old `else -> Unit`. Nothing will ever move again on its own from here.
        if (playbackState == State.IDLE) {
            healthySinceMs = 0L
            return Verdict.Stalled(LiveFailureKind.PLAYER_IDLE, bufferAdvancing, frozenForMs)
        }

        if (isPlaying && frozenForMs < stallAfterMs) {
            if (healthySinceMs == 0L) healthySinceMs = nowMs
            return Verdict.Healthy(nowMs - healthySinceMs)
        }

        healthySinceMs = 0L
        if (frozenForMs < stallAfterMs) return Verdict.Settling

        // `isPlaying` already folds in playWhenReady + suppression reasons, so this split is exactly
        // "the renderer says it is drawing but the clock is dead" vs "a rebuffer that never ended".
        val kind = if (isPlaying) LiveFailureKind.POSITION_STALL else LiveFailureKind.BUFFER_STALL
        return Verdict.Stalled(kind, bufferAdvancing, frozenForMs)
    }

    private companion object {
        const val UNSET = -1L
    }
}
