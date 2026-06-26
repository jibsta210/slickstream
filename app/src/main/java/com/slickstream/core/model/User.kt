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

/**
 * A "Continue watching" entry: a media item plus its resume point. NOTE: the Home rail RESOLVES these in
 * HomeViewModel before display — a finished TV episode is already rolled forward to the next aired one
 * (progress points at it, 0%), and a finished/last episode is dropped — so the UI + nav can read
 * [progress] directly and the tile, its progress bar, and the play target always agree.
 */
data class WatchHistoryItem(
    val media: MediaItem,
    val progress: PlaybackProgress,
)
