package com.slickstream.ui.components

import androidx.compose.runtime.compositionLocalOf
import com.slickstream.core.model.MediaItem

/**
 * The set of `"TYPE:id"` keys (e.g. `"MOVIE:12345"`) for titles whose ONLY playable sources are CAM
 * cinema-rips. Provided once at the app shell from [com.slickstream.data.source.SourceStatusStore]'s
 * `camOnlyKeys` flow; poster cards read it to overlay a "CAM" badge so the user knows a title is only
 * a bad cinema cam before opening it.
 *
 * `compositionLocalOf` (read-tracked, not static): the set updates at runtime as resolves complete,
 * so only the handful of cards that actually read it recompose when a new CAM verdict lands — the
 * rest of the subtree is untouched.
 */
val LocalCamOnlyKeys = compositionLocalOf { emptySet<String>() }

/** True when this title is known to have ONLY CAM releases (looked up in [LocalCamOnlyKeys]'s set). */
fun Set<String>.isCamOnly(item: MediaItem): Boolean =
    contains("${item.mediaType.name}:${item.id}")
