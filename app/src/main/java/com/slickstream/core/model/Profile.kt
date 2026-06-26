package com.slickstream.core.model

/**
 * A named viewing profile under a single account (e.g. Jake, Bob, Sally). Each profile has its own
 * favourites + watch history. [isKids] flags a kids profile (content filtering is a later wave).
 * No PINs. [colorIndex] selects an avatar tint from the UI palette; [avatarIndex] picks the face —
 * 0 = the name's initial letter, 1+ = an emoji avatar (see ProfileAvatarEmojis).
 */
data class Profile(
    val id: String,
    val name: String,
    val isKids: Boolean,
    val colorIndex: Int,
    val avatarIndex: Int = 0,
    val createdAt: Long,
)
