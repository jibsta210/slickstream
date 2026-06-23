package com.slickstream.data.sync

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.core.model.PlaybackProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cloud mirror for the local library, keyed by the Firebase Auth uid (obtained by exchanging the
 * Google ID token from the existing Credential Manager sign-in). Everything is fully guarded: if
 * Firebase isn't configured (no google-services.json) or the user is signed out, every call is a
 * safe no-op and the app stays local-only.
 *
 * Layout:  users/{uid}/favorites/{TYPE_id}   and   users/{uid}/history/{TYPE_id_season_episode}
 */
@Singleton
class FirebaseSync @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Firebase only auto-initialises when google-services.json was present at build time. */
    private val available: Boolean by lazy {
        runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)
    }

    private val auth: FirebaseAuth? get() = if (available) runCatching { FirebaseAuth.getInstance() }.getOrNull() else null
    private val db: FirebaseFirestore? get() = if (available) runCatching { FirebaseFirestore.getInstance() }.getOrNull() else null

    val isAvailable: Boolean get() = available

    fun uid(): String? = auth?.currentUser?.uid

    /** Exchange the Google ID token for a Firebase session so Firestore is keyed by uid. */
    suspend fun signInWithGoogle(idToken: String): String? {
        val a = auth ?: return null
        return try {
            a.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await().user?.uid
        } catch (t: Throwable) {
            Log.w(TAG, "Firebase sign-in failed", t)
            null
        }
    }

    fun signOut() {
        runCatching { auth?.signOut() }
    }

    // --- Favourites ---------------------------------------------------------

    suspend fun pushFavorite(item: MediaItem) {
        val col = favCol() ?: return
        runCatching { col.document(favKey(item.id, item.mediaType)).set(item.toMap()).await() }
    }

    suspend fun removeFavorite(id: Int, type: MediaType) {
        val col = favCol() ?: return
        runCatching { col.document(favKey(id, type)).delete().await() }
    }

    suspend fun pullFavorites(): List<MediaItem> {
        val col = favCol() ?: return emptyList()
        return runCatching {
            col.get().await().documents.mapNotNull { it.data?.toMediaItem() }
        }.getOrDefault(emptyList())
    }

    /** Live listener that surfaces favourites added on other devices (additive — never removes). */
    fun listenFavorites(onItem: (MediaItem) -> Unit): ListenerRegistration? {
        val col = favCol() ?: return null
        return col.addSnapshotListener { snap, _ ->
            snap?.documents?.forEach { d -> d.data?.toMediaItem()?.let(onItem) }
        }
    }

    // --- History ------------------------------------------------------------

    suspend fun pushHistory(item: MediaItem, progress: PlaybackProgress) {
        val col = histCol() ?: return
        runCatching { col.document(histKey(progress)).set(item.toMap() + progress.toMap()).await() }
    }

    suspend fun pullHistory(): List<Pair<MediaItem, PlaybackProgress>> {
        val col = histCol() ?: return emptyList()
        return runCatching {
            col.get().await().documents.mapNotNull { d ->
                val data = d.data ?: return@mapNotNull null
                val media = data.toMediaItem() ?: return@mapNotNull null
                val progress = data.toPlaybackProgress() ?: return@mapNotNull null
                media to progress
            }
        }.getOrDefault(emptyList())
    }

    // --- helpers ------------------------------------------------------------

    private fun favCol() = uid()?.let { db?.collection("users")?.document(it)?.collection("favorites") }
    private fun histCol() = uid()?.let { db?.collection("users")?.document(it)?.collection("history") }

    private fun favKey(id: Int, type: MediaType) = "${type.name}_$id"
    private fun histKey(p: PlaybackProgress) =
        "${p.mediaType.name}_${p.mediaId}_${p.season ?: -1}_${p.episode ?: -1}"

    private fun MediaItem.toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "mediaType" to mediaType.name,
        "title" to title,
        "overview" to overview,
        "posterUrl" to posterUrl,
        "backdropUrl" to backdropUrl,
        "voteAverage" to voteAverage,
        "releaseDate" to releaseDate,
        "genreIds" to genreIds,
        "imdbId" to imdbId,
        "addedAt" to System.currentTimeMillis(),
    )

    private fun Map<String, Any?>.toMediaItem(): MediaItem? {
        val id = (this["id"] as? Number)?.toInt() ?: return null
        val type = (this["mediaType"] as? String)?.let { runCatching { MediaType.valueOf(it) }.getOrNull() } ?: return null
        return MediaItem(
            id = id,
            mediaType = type,
            title = this["title"] as? String ?: "",
            overview = this["overview"] as? String ?: "",
            posterUrl = this["posterUrl"] as? String,
            backdropUrl = this["backdropUrl"] as? String,
            voteAverage = (this["voteAverage"] as? Number)?.toDouble() ?: 0.0,
            releaseDate = this["releaseDate"] as? String,
            genreIds = (this["genreIds"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList(),
            imdbId = this["imdbId"] as? String,
        )
    }

    // Progress fields use distinct keys ("mt"/"mediaId") so they don't collide with the media map.
    private fun PlaybackProgress.toMap(): Map<String, Any?> = mapOf(
        "mediaId" to mediaId,
        "mt" to mediaType.name,
        "season" to season,
        "episode" to episode,
        "positionMs" to positionMs,
        "durationMs" to durationMs,
        "updatedAt" to updatedAt,
        "infoHash" to infoHash,
    )

    private fun Map<String, Any?>.toPlaybackProgress(): PlaybackProgress? {
        val mediaId = (this["mediaId"] as? Number)?.toInt() ?: return null
        val type = (this["mt"] as? String)?.let { runCatching { MediaType.valueOf(it) }.getOrNull() } ?: return null
        return PlaybackProgress(
            mediaId = mediaId,
            mediaType = type,
            season = (this["season"] as? Number)?.toInt(),
            episode = (this["episode"] as? Number)?.toInt(),
            positionMs = (this["positionMs"] as? Number)?.toLong() ?: 0L,
            durationMs = (this["durationMs"] as? Number)?.toLong() ?: 0L,
            updatedAt = (this["updatedAt"] as? Number)?.toLong() ?: 0L,
            infoHash = this["infoHash"] as? String,
        )
    }

    private companion object {
        const val TAG = "FirebaseSync"
    }
}
