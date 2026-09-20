package com.slickstream.feature.live

import com.slickstream.core.model.MediaItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands the chosen event's live (HLS) feeds from the Sports picker to [LivePlayerViewModel] without
 * serialising headers through navigation args. Carries ALL feeds for the event + the selected
 * index so the player can switch streams in place. Process-scoped; the most recent selection wins.
 *
 * Also carries a MOVIE/EPISODE seed ([MediaSeed]) when multiview is entered from the ordinary player
 * ("Add to multiview" while watching a film): the film becomes the first tile, resumed at the
 * position it was at, and games are added around it. Exactly one of the two seeds is live at a
 * time — setting either clears the other, so a stale one can never hijack the next entry.
 */
@Singleton
class LivePlaybackHolder @Inject constructor() {

    @Volatile
    var current: Selection? = null
        private set

    @Volatile
    private var mediaSeed: MediaSeed? = null

    fun set(title: String, feeds: List<Feed>, index: Int) {
        current = Selection(title, feeds, index.coerceIn(0, (feeds.size - 1).coerceAtLeast(0)))
        mediaSeed = null
    }

    fun setMedia(seed: MediaSeed) {
        mediaSeed = seed
        current = null
    }

    /** One-shot: the seed is consumed by the multiview coordinator on entry. */
    fun consumeMediaSeed(): MediaSeed? {
        val s = mediaSeed
        mediaSeed = null
        return s
    }

    data class Feed(
        val label: String,
        val url: String,
        val headers: Map<String, String>,
        /** url is an embed page to resolve via WebView before playing (vs a direct m3u8). */
        val needsResolution: Boolean,
    )

    data class Selection(
        val title: String,
        val feeds: List<Feed>,
        val index: Int,
    ) {
        val current: Feed? get() = feeds.getOrNull(index)
    }

    /** A movie or episode to play in a tile, with where to resume. */
    data class MediaSeed(
        val item: MediaItem,
        val season: Int?,
        val episode: Int?,
        val positionMs: Long,
        val durationMs: Long,
    )
}
