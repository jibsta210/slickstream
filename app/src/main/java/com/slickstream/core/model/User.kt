package com.slickstream.core.model

/** Signed-in Google user. */
data class UserProfile(
    val id: String,
    val displayName: String?,
    val email: String?,
    val photoUrl: String?,
)

data class FavoriteItem(
    val media: MediaItem,
    val addedAt: Long,
)

/** A "Continue watching" entry: a media item plus its resume point. */
data class WatchHistoryItem(
    val media: MediaItem,
    val progress: PlaybackProgress,
)
