package com.slickstream.feature.sports

/** A sport / league category, e.g. "Baseball" (MLB), "Hockey" (NHL). */
data class SportCategory(
    val id: String,
    val name: String,
)

/** A scheduled or live game. */
data class SportEvent(
    val id: String,
    val title: String,
    val categoryId: String,
    val startEpochMs: Long,
    val posterUrl: String?,
    val isLive: Boolean,
    val sources: List<SportSourceRef>,
    /** FanCode-style direct feed that needs no resolution (skips the streamed.pk source dance). */
    val directStream: SportStream? = null,
)

/** Points at one provider's copy of an event's streams ({source}/{id} for the stream endpoint). */
data class SportSourceRef(
    val source: String,
    val id: String,
)

/** One playable feed for an event (HLS), with the request headers the host requires. */
data class SportStream(
    val id: String,
    val label: String,
    val url: String,
    val headers: Map<String, String>,
    /**
     * When true, [url] is an obfuscated embed PAGE (streamed.pk) that must be run through the
     * WebView resolver to capture the real .m3u8 before ExoPlayer can play it. When false, [url]
     * is already a playable HLS master (FanCode).
     */
    val needsResolution: Boolean = false,
)
