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

    // --- Cross-profile sync surface ---------------------------------------------------------------
    // These operate on an EXPLICIT profileId (never the active profile) so cloud sync can push and
    // merge every profile's library, keyed by its origin profile. The UI never touches these.

    /** Every favourite across ALL profiles, paired with its origin profileId. */
    suspend fun allFavoritesForSync(): List<Pair<String, FavoriteItem>>

    /** Live cross-profile favourites feed (origin profileId + item) — drives the delta push. */
    fun observeAllFavoritesForSync(): Flow<List<Pair<String, FavoriteItem>>>

    /** Is [id]/[type] already favourited under [profileId] specifically? */
    suspend fun isFavoriteInProfile(profileId: String, id: Int, type: MediaType): Boolean

    /** UPSERT (never toggle) [item] as a favourite under [profileId] — used by the sync listener. */
    suspend fun addFavoriteForProfile(profileId: String, item: MediaItem)

    /** Every history row across ALL profiles, as (origin profileId, media, progress). */
    suspend fun allHistoryForSync(): List<Triple<String, MediaItem, PlaybackProgress>>

    /** Live cross-profile history feed (origin profileId, media, progress) — drives the delta push. */
    fun observeAllHistoryForSync(): Flow<List<Triple<String, MediaItem, PlaybackProgress>>>

    /** The resume point for [id]/[type]/[season]/[episode] under [profileId] specifically. */
    suspend fun getProgressForProfile(
        profileId: String,
        id: Int,
        type: MediaType,
        season: Int?,
        episode: Int?,
    ): PlaybackProgress?

    /** UPSERT [progress] for [item] under [profileId] specifically — used by the initial sync merge. */
    suspend fun saveProgressForProfile(profileId: String, item: MediaItem, progress: PlaybackProgress)

    /** Move every favourite + history row from [fromId] to [toId] — used to reconcile two profile rows
     *  that are the same person but got divergent ids on different devices. */
    suspend fun reassignProfile(fromId: String, toId: String)
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
    suspend fun createProfile(name: String, isKids: Boolean, colorIndex: Int, avatarIndex: Int = 0): Profile
    suspend fun updateProfile(profile: Profile)
    suspend fun deleteProfile(profileId: String)

    /** One-shot snapshot of every profile — used by cloud sync to push the full set. */
    suspend fun allProfiles(): List<Profile>

    /** UPSERT a profile arriving from another device WITHOUT changing the active profile. */
    suspend fun upsertFromSync(profile: Profile)
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

    /**
     * System memory pressure (onTrimMemory): evict the on-disk cache down to [maxBytes] on a
     * background thread, protecting only the currently-streaming + warmed torrents. Non-blocking —
     * safe to call from the main thread.
     */
    fun onMemoryPressure(maxBytes: Long)

    /** Length (bytes) of the selected file for a stream, or 0 until known. */
    fun fileLength(infoHash: String): Long

    /** On-disk absolute path of the selected video file, or null until known. Lets the thumbnail
     *  extractor decode frames straight off disk — far more reliable than MediaMetadataRetriever over
     *  the local HTTP server (which silently fails on some TVs). */
    fun filePath(infoHash: String): String?

    /**
     * Best-effort, NON-BLOCKING: nudge the engine to fetch the slices covering these file byte-offsets
     * for scrub-preview thumbnails, at a relaxed priority so playback's head/read-ahead always wins.
     */
    fun prefetchPreviewOffsets(infoHash: String, offsets: List<Long>)

    /** Non-blocking: is the slice covering this file byte-offset on disk yet? */
    fun isByteAvailable(infoHash: String, byteOffset: Long): Boolean

    /** Downsample the file's downloaded-piece state into [buckets] fill fractions (0f..1f), start→end,
     *  for the player's chunk/piece bar. Empty until pieces exist. */
    fun pieceMap(infoHash: String, buckets: Int): FloatArray
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
