package com.slickstream.core.model

/**
 * A named viewing profile under a single account (e.g. Jake, Bob, Sally). Each profile has its own
 * favourites + watch history. [isKids] flags a kids profile (content filtering is a later wave).
 * No PINs. [colorIndex] selects an avatar tint from the UI palette.
 */
data class Profile(
    val id: String,
    val name: String,
    val isKids: Boolean,
    val colorIndex: Int,
    val createdAt: Long,
)
