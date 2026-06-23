package com.slickstream.feature.live

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands the chosen event's live (HLS) feeds from the Sports picker to [LivePlayerViewModel] without
 * serialising headers through navigation args. Carries ALL feeds for the event + the selected
 * index so the player can switch streams in place. Process-scoped; the most recent selection wins.
 */
@Singleton
class LivePlaybackHolder @Inject constructor() {

    @Volatile
    var current: Selection? = null
        private set

    fun set(title: String, feeds: List<Feed>, index: Int) {
        current = Selection(title, feeds, index.coerceIn(0, (feeds.size - 1).coerceAtLeast(0)))
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
}
