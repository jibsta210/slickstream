package com.slickstream.feature.live

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * The lowest layer of the live retry engine: absorb transient segment/playlist failures INSIDE the
 * loader, so a two-second CDN blip never becomes a `PlaybackException`, never reaches the ViewModel's
 * ladder, and never shows the user anything at all.
 *
 * WHY THE STOCK POLICY IS WRONG FOR LIVE SPORTS — the live path passed no policy, so
 * `DefaultLoadErrorHandlingPolicy()` applied. Verified against the media3 1.5.1 bytecode on the
 * classpath, not from memory:
 *
 *  - `getMinimumLoadableRetryCount` returns the verified
 *    DEFAULT_MIN_LOADABLE_RETRY_COUNT_PROGRESSIVE_LIVE = 6 ONLY for `dataType == 7`
 *    (C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE). HLS media chunks arrive as C.DATA_TYPE_MEDIA (= 1) and
 *    playlist refreshes as C.DATA_TYPE_MANIFEST (= 4), so live HLS falls through to the plain
 *    DEFAULT_MIN_LOADABLE_RETRY_COUNT = 3.
 *  - `getRetryDelayMsFor` returns `min((errorCount - 1) * 1000, 5000)`.
 *  - `Loader$LoadTask.maybeThrowError` throws the instant `errorCount > minRetryCount`, and at the
 *    live edge the buffer is roughly one segment, so the renderer starves and asks almost at once.
 *
 * Net: the entire library-level budget for a live feed was 3 attempts across about 3 seconds, then a
 * FATAL error — versus the 12 the VOD path already sets
 * (`PlayerViewModel.buildMediaSourceFactory`). That is why a blip that would have cleared itself put
 * the 3-button "Couldn't play this feed" overlay over a game.
 *
 * Three deliberate divergences from the default, each for a live-specific reason:
 *
 *  1. MORE RETRIES, but not the VOD path's 12. A torrent retries hard because the bytes are genuinely
 *     still arriving from the swarm; a live segment that has failed six times is gone forever, and
 *     further retries only deepen the freeze. Playlist refreshes get more headroom than media chunks
 *     because a live feed that stops refreshing its playlist is dead, and that is the one load that
 *     must never give up.
 *  2. SHORTER BACKOFF. The default's 5 s ceiling is itself the stall on a live feed: nothing is
 *     arriving during the wait and the playhead drifts further from the live edge the whole time.
 *  3. WIDER, SHORTER FALLBACK. The default's `isEligibleForFallback` is verified to return true ONLY
 *     for `HttpDataSource$InvalidResponseCodeException` with code 403/404/410/416/500/503 — so a
 *     socket timeout or a stuck playlist produces no variant switch at all. And when it does fire it
 *     excludes a LOCATION for the verified DEFAULT_LOCATION_EXCLUSION_MS = 300_000 ms or a TRACK for
 *     DEFAULT_TRACK_EXCLUSION_MS = 60_000 ms. On a sports CDN a bad rendition usually recovers in
 *     seconds, and a 1-5 minute exclusion on a two- or three-variant playlist strands the stream on a
 *     degraded rendition for longer than the failure lasted.
 */
@OptIn(UnstableApi::class)
class LiveLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy() {

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = when (dataType) {
        // The playlist IS the live stream. Give it the most headroom of anything here.
        C.DATA_TYPE_MANIFEST -> MANIFEST_RETRIES
        else -> MEDIA_RETRIES
    }

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val delayMs = super.getRetryDelayMsFor(loadErrorInfo)
        // C.TIME_UNSET means "do not retry at all" (ParserException, FileNotFoundException,
        // CleartextNotPermitted, UnexpectedLoaderException, position-out-of-range). Those judgements
        // are right and must pass through untouched — capping a very negative sentinel would be a bug.
        if (delayMs == C.TIME_UNSET) return delayMs
        return delayMs.coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    override fun isEligibleForFallback(exception: IOException): Boolean =
        super.isEligibleForFallback(exception) ||
            // A dead socket on one CDN node: the other variant/node is very often healthy, and the
            // default policy offers no fallback for it whatsoever.
            exception is SocketTimeoutException ||
            // The exact failure the user is reporting, seen from inside the loader: this rendition's
            // playlist stopped advancing (or reset its media sequence). Switching variant is precisely
            // the right move, and again the default policy declines to make it.
            exception is HlsPlaylistTracker.PlaylistStuckException ||
            exception is HlsPlaylistTracker.PlaylistResetException

    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? {
        if (!isEligibleForFallback(loadErrorInfo.exception)) return null
        // Order reversed from the default, which tries LOCATION (300 s) first. On these feeds the
        // variants are usually different renditions of the same origin, so swapping TRACK is the move
        // that actually changes what we are fetching; and the exclusion is short enough that we come
        // back to the good rendition within the same drive rather than the same quarter.
        if (fallbackOptions.isFallbackAvailable(LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK)) {
            return LoadErrorHandlingPolicy.FallbackSelection(
                LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK,
                TRACK_EXCLUSION_MS,
            )
        }
        if (fallbackOptions.isFallbackAvailable(LoadErrorHandlingPolicy.FALLBACK_TYPE_LOCATION)) {
            return LoadErrorHandlingPolicy.FallbackSelection(
                LoadErrorHandlingPolicy.FALLBACK_TYPE_LOCATION,
                LOCATION_EXCLUSION_MS,
            )
        }
        return null
    }

    private companion object {
        /** vs the stock 3. Six failures of one segment means it is gone, not late. */
        const val MEDIA_RETRIES = 6

        /** vs the stock 3. A live feed that stops refreshing its playlist has nothing left to play. */
        const val MANIFEST_RETRIES = 8

        /** vs the stock 5_000. On live, a 5 s backoff IS the freeze. */
        const val MAX_RETRY_DELAY_MS = 2_000L

        /** vs the verified DEFAULT_TRACK_EXCLUSION_MS = 60_000. */
        const val TRACK_EXCLUSION_MS = 15_000L

        /** vs the verified DEFAULT_LOCATION_EXCLUSION_MS = 300_000. */
        const val LOCATION_EXCLUSION_MS = 30_000L
    }
}
