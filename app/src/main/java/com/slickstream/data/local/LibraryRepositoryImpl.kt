package com.slickstream.data.local

import com.slickstream.core.model.FavoriteItem
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.core.model.PlaybackProgress
import com.slickstream.core.model.WatchHistoryItem
import com.slickstream.core.repository.LibraryRepository
import com.slickstream.core.repository.ProfileRepository
import com.slickstream.data.local.dao.FavoriteDao
import com.slickstream.data.local.dao.WatchHistoryDao
import com.slickstream.data.local.entity.FavoriteEntity
import com.slickstream.data.local.entity.WatchHistoryEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed [LibraryRepository]: favourites + "continue watching" history.
 * All entities carry full image URLs already (resolved upstream by the catalog layer),
 * so rebuilt [MediaItem]s need no further URL resolution.
 *
 * Every read/write is scoped to the active profile (via [ProfileRepository]); the observe* flows
 * re-query live whenever the active profile changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class LibraryRepositoryImpl @Inject constructor(
    private val favoriteDao: FavoriteDao,
    private val watchHistoryDao: WatchHistoryDao,
    private val profileRepository: ProfileRepository,
) : LibraryRepository {

    // --- Favourites ---

    override fun observeFavorites(): Flow<List<FavoriteItem>> =
        profileRepository.activeProfileId.flatMapLatest { pid ->
            favoriteDao.observeAll(pid).map { rows -> rows.map(FavoriteEntity::toFavoriteItem) }
        }

    override suspend fun isFavorite(id: Int, type: MediaType): Boolean {
        val pid = profileRepository.currentProfileId()
        return favoriteDao.isFavorite(id, type, pid)
    }

    override fun observeIsFavorite(id: Int, type: MediaType): Flow<Boolean> =
        profileRepository.activeProfileId.flatMapLatest { pid ->
            favoriteDao.observeIsFavorite(id, type, pid)
        }.distinctUntilChanged()

    override suspend fun toggleFavorite(item: MediaItem) {
        val pid = profileRepository.currentProfileId()
        if (favoriteDao.isFavorite(item.id, item.mediaType, pid)) {
            favoriteDao.deleteById(item.id, item.mediaType, pid)
        } else {
            favoriteDao.upsert(
                FavoriteEntity.from(item, addedAt = System.currentTimeMillis(), profileId = pid),
            )
        }
    }

    // --- Watch history / continue watching ---

    override fun observeHistory(): Flow<List<WatchHistoryItem>> =
        profileRepository.activeProfileId.flatMapLatest { pid ->
            watchHistoryDao.observeAll(pid).map { rows -> rows.map(WatchHistoryEntity::toWatchHistoryItem) }
        }

    override suspend fun saveProgress(item: MediaItem, progress: PlaybackProgress) {
        // UPSERT always — even a finished episode persists (as 'watched'). The "Continue watching"
        // rail filters finished rows out downstream (HomeViewModel), so it isn't shown there.
        val pid = profileRepository.currentProfileId()
        watchHistoryDao.upsert(
            WatchHistoryEntity.from(item, progress, addedAt = System.currentTimeMillis(), profileId = pid),
        )
    }

    override suspend fun getProgress(
        id: Int,
        type: MediaType,
        season: Int?,
        episode: Int?,
    ): PlaybackProgress? {
        val pid = profileRepository.currentProfileId()
        return watchHistoryDao
            .getByEpisode(
                id,
                type,
                season ?: WatchHistoryEntity.NO_KEY,
                episode ?: WatchHistoryEntity.NO_KEY,
                pid,
            )
            ?.toPlaybackProgress()
    }

    override suspend fun removeFromHistory(id: Int, type: MediaType) {
        // Remove the whole title (all episodes) from history.
        val pid = profileRepository.currentProfileId()
        watchHistoryDao.deleteAllForMedia(id, type, pid)
    }

    override suspend fun clearHistory() {
        // Clears only the active profile's history.
        val pid = profileRepository.currentProfileId()
        watchHistoryDao.clear(pid)
    }

    override suspend fun markWatched(item: MediaItem, season: Int?, episode: Int?) {
        // Sentinel finished row: position == duration == 1 -> percent == 1.0f (>= 0.92f).
        val pid = profileRepository.currentProfileId()
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
        watchHistoryDao.upsert(WatchHistoryEntity.from(item, progress, addedAt = now, profileId = pid))
    }

    override suspend fun markUnwatched(item: MediaItem, season: Int?, episode: Int?) {
        val pid = profileRepository.currentProfileId()
        watchHistoryDao.deleteEpisode(
            item.id,
            item.mediaType,
            season ?: WatchHistoryEntity.NO_KEY,
            episode ?: WatchHistoryEntity.NO_KEY,
            pid,
        )
    }

    private companion object {
        /** position == duration == 1ms -> percent 1.0f, so the row reads as finished/watched. */
        const val WATCHED_SENTINEL_MS = 1L
    }
}
