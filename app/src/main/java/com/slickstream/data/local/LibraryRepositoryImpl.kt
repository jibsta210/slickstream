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
        // A finished title shouldn't linger in the "Continue watching" rail.
        if (progress.isFinished) {
            watchHistoryDao.deleteById(item.id, item.mediaType)
            return
        }
        watchHistoryDao.upsert(
            WatchHistoryEntity.from(item, progress, addedAt = System.currentTimeMillis()),
        )
    }

    override suspend fun getProgress(
        id: Int,
        type: MediaType,
        season: Int?,
        episode: Int?,
    ): PlaybackProgress? {
        val row = watchHistoryDao.getByMedia(id, type) ?: return null
        // For TV, only return the stored resume point if it matches the requested episode.
        if (type == MediaType.TV && (row.season != season || row.episode != episode)) return null
        return row.toPlaybackProgress()
    }

    override suspend fun removeFromHistory(id: Int, type: MediaType) {
        watchHistoryDao.deleteById(id, type)
    }

    override suspend fun clearHistory() {
        watchHistoryDao.clear()
    }
}
