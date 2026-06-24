package com.slickstream.data.local.entity

import androidx.room.Entity
import com.slickstream.core.model.FavoriteItem
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType

/**
 * A favourited catalog item. Stores enough [MediaItem] fields to rebuild the card
 * without a network round-trip. Composite primary key = (id, mediaType, profileId) so each
 * profile has its own favourites.
 */
@Entity(tableName = "favorites", primaryKeys = ["id", "mediaType", "profileId"])
data class FavoriteEntity(
    val id: Int,
    val mediaType: MediaType,
    val title: String,
    val overview: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val voteAverage: Double,
    val releaseDate: String?,
    val imdbId: String?,
    val addedAt: Long,
    val profileId: String,
) {
    fun toMediaItem(): MediaItem = MediaItem(
        id = id,
        mediaType = mediaType,
        title = title,
        overview = overview,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        voteAverage = voteAverage,
        releaseDate = releaseDate,
        imdbId = imdbId,
    )

    fun toFavoriteItem(): FavoriteItem = FavoriteItem(
        media = toMediaItem(),
        addedAt = addedAt,
    )

    companion object {
        fun from(item: MediaItem, addedAt: Long, profileId: String): FavoriteEntity = FavoriteEntity(
            id = item.id,
            mediaType = item.mediaType,
            title = item.title,
            overview = item.overview,
            posterUrl = item.posterUrl,
            backdropUrl = item.backdropUrl,
            voteAverage = item.voteAverage,
            releaseDate = item.releaseDate,
            imdbId = item.imdbId,
            addedAt = addedAt,
            profileId = profileId,
        )
    }
}
