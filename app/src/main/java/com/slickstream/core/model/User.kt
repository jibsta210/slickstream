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

/** Where a Continue-Watching row should actually point. */
data class ResumePoint(val season: Int?, val episode: Int?, val percent: Float)

/**
 * The (season, episode, progress) a Continue-Watching tile should both DISPLAY and RESUME — derived in
 * ONE place so the tile label, its progress bar, and the play action can never disagree (the home rail
 * used to show "S1E1 · 98%" while tapping it actually started S1E2). A FINISHED TV episode rolls forward
 * to the next one as a fresh start (0%), matching how a series naturally continues; everything else
 * stays exactly where it was left.
 */
fun WatchHistoryItem.resumePoint(): ResumePoint =
    if (media.mediaType == MediaType.TV && progress.isFinished) {
        ResumePoint(progress.season, (progress.episode ?: 1) + 1, 0f)
    } else {
        ResumePoint(progress.season, progress.episode, progress.percent)
    }
