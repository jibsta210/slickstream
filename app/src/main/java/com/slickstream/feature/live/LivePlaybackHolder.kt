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

    fun set(title: String, url: String, headers: Map<String, String>) {
        current = Selection(title, url, headers)
    }

    data class Selection(
        val title: String,
        val url: String,
        val headers: Map<String, String>,
    )
}
