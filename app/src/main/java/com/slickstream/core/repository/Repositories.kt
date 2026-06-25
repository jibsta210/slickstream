package com.slickstream.core.repository

import android.app.Activity
import com.slickstream.core.model.DataResult
import com.slickstream.core.model.Episode
import com.slickstream.core.model.FavoriteItem
import com.slickstream.core.model.Genre
import com.slickstream.core.model.MediaDetails
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.core.model.PlaybackProgress
import com.slickstream.core.model.Profile
import com.slickstream.core.model.StreamSource
import com.slickstream.core.model.StreamStatus
import com.slickstream.core.model.UserProfile
import com.slickstream.core.model.WatchHistoryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Catalog metadata (TMDB). Implemented by data/tmdb.
 * Implementations resolve all image paths to full URLs before returning.
 */
interface CatalogRepository {
    suspend fun getTrending(mediaType: MediaType?, page: Int = 1): DataResult<List<MediaItem>>
    suspend fun getPopular(mediaType: MediaType, page: Int = 1): DataResult<List<MediaItem>>
    suspend fun getTopRated(mediaType: MediaType, page: Int = 1): DataResult<List<MediaItem>>
    /** Now playing (movies) / on the air (tv). */
    suspend fun getNewAndUpcoming(mediaType: MediaType, page: Int = 1): DataResult<List<MediaItem>>
    suspend fun getByGenre(mediaType: MediaType, genreId: Int, page: Int = 1): DataResult<List<MediaItem>>
    suspend fun getGenres(mediaType: MediaType): DataResult<List<Genre>>
    suspend fun getDetails(id: Int, mediaType: MediaType): DataResult<MediaDetails>
    suspend fun getEpisodes(tvId: Int, seasonNumber: Int): DataResult<List<Episode>>
    suspend fun getSimilar(id: Int, mediaType: MediaType): DataResult<List<MediaItem>>
    /** Multi-search across movies + tv. */
    suspend fun search(query: String, page: Int = 1): DataResult<List<MediaItem>>
}

/**
 * Local library: favourites + "continue watching" history. Implemented by data/local (Room).
 */
interface LibraryRepository {
    fun observeFavorites(): Flow<List<FavoriteItem>>
    suspend fun isFavorite(id: Int, type: MediaType): Boolean
    fun observeIsFavorite(id: Int, type: MediaType): Flow<Boolean>
    suspend fun toggleFavorite(item: MediaItem)

    fun observeHistory(): Flow<List<WatchHistoryItem>>
    suspend fun saveProgress(item: MediaItem, progress: PlaybackProgress)
    suspend fun getProgress(id: Int, type: MediaType, season: Int?, episode: Int?): PlaybackProgress?
    suspend fun removeFromHistory(id: Int, type: MediaType)
    suspend fun clearHistory()

    /** Mark a movie (season/episode null) or a specific episode as fully watched (a finished row). */
    suspend fun markWatched(item: MediaItem, season: Int?, episode: Int?)

    /** Clear the watched/in-progress row for a movie or a specific episode. */
    suspend fun markUnwatched(item: MediaItem, season: Int?, episode: Int?)
}

/**
 * The active-profile source of truth + CRUD over the named profiles. Implemented by data/local.
 * The active profile drives which favourites/watch-history rows the [LibraryRepository] reads/writes.
 */
interface ProfileRepository {
    /** All profiles (always contains at least the default). */
    fun observeProfiles(): Flow<List<Profile>>

    /** The active profile id, persisted across launches. Never empty (defaults to the default profile). */
    val activeProfileId: StateFlow<String>

    /** The active profile object (kids flag etc.), or null until first load. */
    val activeProfile: StateFlow<Profile?>

    suspend fun currentProfileId(): String
    suspend fun setActiveProfile(profileId: String)
    suspend fun createProfile(name: String, isKids: Boolean, colorIndex: Int): Profile
    suspend fun updateProfile(profile: Profile)
    suspend fun deleteProfile(profileId: String)
}

/**
 * Resolves a title (by IMDB id) into a ranked list of playable torrent sources.
 * Implemented by data/source (Stremio/Torrentio-compatible indexer).
 */
interface SourceRepository {
    suspend fun resolve(
        details: MediaDetails,
        season: Int? = null,
        episode: Int? = null,
    ): DataResult<List<StreamSource>>
}

/**
 * The torrent streaming engine (libtorrent4j + local HTTP bridge). Implemented by data/torrent.
 * Sequential download: emits [StreamStatus] until a [StreamStatus.streamUrl] is available,
 * then keeps emitting progress. Recently used torrents are cached for fast resume.
 */
interface TorrentStreamer {
    /** Begin streaming. The returned flow stays hot until cancelled. */
    fun start(source: StreamSource): Flow<StreamStatus>
    suspend fun pause(infoHash: String)
    suspend fun resume(infoHash: String)
    /** Stop a stream. [removeFiles]=false keeps the partial download in cache. */
    suspend fun stop(infoHash: String, removeFiles: Boolean = false)

    /**
     * Warm a source for instant start later: add the magnet, fetch/cache metadata, buffer a small
     * head, then PAUSE. Does NOT create a player, stream URL, or foreground stream — the partial
     * download stays in the LRU cache. Returns the warmed info-hash, or null on failure.
     * [protectedHashes] are never evicted while making room (pass the playing torrent).
     */
    suspend fun prefetch(source: StreamSource, protectedHashes: Set<String> = emptySet()): String?

    /** Info-hashes currently held in the on-disk cache. */
    fun cachedTorrents(): List<String>
    suspend fun clearCache()
    fun cacheSizeBytes(): Long

    /** Length (bytes) of the selected file for a stream, or 0 until known. */
    fun fileLength(infoHash: String): Long

    /**
     * Best-effort, NON-BLOCKING: nudge the engine to fetch the slices covering these file byte-offsets
     * for scrub-preview thumbnails, at a relaxed priority so playback's head/read-ahead always wins.
     */
    fun prefetchPreviewOffsets(infoHash: String, offsets: List<Long>)

    /** Non-blocking: is the slice covering this file byte-offset on disk yet? */
    fun isByteAvailable(infoHash: String, byteOffset: Long): Boolean
}

/** Google OAuth via Credential Manager. Implemented by feature/auth. */
interface AuthRepository {
    val currentUser: StateFlow<UserProfile?>
    /** Launches the Google credential flow; requires an Activity context. */
    suspend fun signIn(activity: Activity): DataResult<UserProfile>
    /** Complete sign-in from a Google ID token obtained out-of-band (e.g. handed over from a phone). */
    suspend fun signInWithIdToken(idToken: String): DataResult<UserProfile>
    /** Fetch a fresh Google ID token for the current account (used to link a TV from the phone). */
    suspend fun acquireIdToken(activity: Activity): DataResult<String>
    suspend fun signOut()
    /** Restore a previously authorized session silently (no UI). */
    suspend fun restoreSession()
}
