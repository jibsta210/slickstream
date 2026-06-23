package com.slickstream.feature.live

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands a chosen live (HLS) feed from the Sports picker to [LivePlayerViewModel] without
 * serialising headers through navigation args. Process-scoped; the most recent selection wins.
 */
@Singleton
class LivePlaybackHolder @Inject constructor() {

    @Volatile
    var current: Selection? = null
        private set

    fun set(title: String, url: String, headers: Map<String, String>, needsResolution: Boolean) {
        current = Selection(title, url, headers, needsResolution)
    }

    data class Selection(
        val title: String,
        val url: String,
        val headers: Map<String, String>,
        /** url is an embed page to resolve via WebView before playing (vs a direct m3u8). */
        val needsResolution: Boolean,
    )
}
