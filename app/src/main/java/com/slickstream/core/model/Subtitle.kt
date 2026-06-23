package com.slickstream.core.model

/** A selectable external subtitle source (SRT/VTT) for a title. */
data class SubtitleTrack(
    val id: String,
    val label: String,        // human label, e.g. "English"
    val languageCode: String, // the addon's code (e.g. "eng") — used as the ExoPlayer track language
    val url: String,
)
