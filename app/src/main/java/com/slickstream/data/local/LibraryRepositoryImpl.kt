package com.slickstream.data.local

import com.slickstream.core.model.FavoriteItem
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.core.model.PlaybackProgress
import com.slickstream.core.model.WatchHistoryItem
import com.slickstream.core.repository.LibraryRepository
import com.slickstream.data.local.dao.FavoriteDao
import com.slickstream.data.local.dao.WatchHistoryDao
import com.slickstream.data.local.entity.FavoriteEntity
import com.slickstream.data.local.entity.WatchHistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed [LibraryRepository]: favourites + "continue watching" history.
 * All entities carry full image URLs already (resolved upstream by the catalog layer),
 * so rebuilt [MediaItem]s need no further URL resolution.
 */
@Singleton
class LibraryRepositoryImpl @Inject constructor(
    private val favoriteDao: FavoriteDao,
    private val watchHistoryDao: WatchHistoryDao,
) : LibraryRepository {

    // --- Favourites ---

    override fun observeFavorites(): Flow<List<FavoriteItem>> =
        favoriteDao.observeAll().map { rows -> rows.map(FavoriteEntity::toFavoriteItem) }

    override suspend fun isFavorite(id: Int, type: MediaType): Boolean =
        favoriteDao.isFavorite(id, type)

    override fun observeIsFavorite(id: Int, type: MediaType): Flow<Boolean> =
        favoriteDao.observeIsFavorite(id, type).distinctUntilChanged()

    override suspend fun toggleFavorite(item: MediaItem) {
        if (favoriteDao.isFavorite(item.id, item.mediaType)) {
            favoriteDao.deleteById(item.id, item.mediaType)
        } else {
            favoriteDao.upsert(FavoriteEntity.from(item, addedAt = System.currentTimeMillis()))
        }
    }

    // --- Watch history / continue watching ---

    override fun observeHistory(): Flow<List<WatchHistoryItem>> =
        watchHistoryDao.observeAll().map { rows -> rows.map(WatchHistoryEntity::toWatchHistoryItem) }

    override suspend fun saveProgress(item: MediaItem, progress: PlaybackProgress) {
        // UPSERT always — even a finished episode persists (as 'watched'). The "Continue watching"
        // rail filters finished rows out downstream (HomeViewModel), so it isn't shown there.
        watchHistoryDao.upsert(
            WatchHistoryEntity.from(item, progress, addedAt = System.currentTimeMillis()),
        )
    }

    override suspend fun getProgress(
        id: Int,
        type: MediaType,
        season: Int?,
        episode: Int?,
    ): PlaybackProgress? =
        watchHistoryDao
            .getByEpisode(id, type, season ?: WatchHistoryEntity.NO_KEY, episode ?: WatchHistoryEntity.NO_KEY)
            ?.toPlaybackProgress()

    override suspend fun removeFromHistory(id: Int, type: MediaType) {
        // Remove the whole title (all episodes) from history.
        watchHistoryDao.deleteAllForMedia(id, type)
    }

    override suspend fun clearHistory() {
        watchHistoryDao.clear()
    }

    override suspend fun markWatched(item: MediaItem, season: Int?, episode: Int?) {
        // Sentinel finished row: position == duration == 1 -> percent == 1.0f (>= 0.92f).
        val now = System.currentTimeMillis()
        val progress = PlaybackProgress(
            mediaId = item.id,
            mediaType = item.mediaType,
            season = season,
            episode = episode,
            positionMs = WATCHED_SENTINEL_MS,
            durationMs = WATCHED_SENTINEL_MS,
            updatedAt = now,
        )
        watchHistoryDao.upsert(WatchHistoryEntity.from(item, progress, addedAt = now))
    }

    override suspend fun markUnwatched(item: MediaItem, season: Int?, episode: Int?) {
        watchHistoryDao.deleteEpisode(
            item.id,
            item.mediaType,
            season ?: WatchHistoryEntity.NO_KEY,
            episode ?: WatchHistoryEntity.NO_KEY,
        )
    }

    private companion object {
        /** position == duration == 1ms -> percent 1.0f, so the row reads as finished/watched. */
        const val WATCHED_SENTINEL_MS = 1L
    }
}
