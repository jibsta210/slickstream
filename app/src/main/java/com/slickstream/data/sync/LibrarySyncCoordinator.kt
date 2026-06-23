package com.slickstream.data.sync

import android.util.Log
import com.google.firebase.firestore.ListenerRegistration
import com.slickstream.core.model.MediaType
import com.slickstream.core.repository.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the local [LibraryRepository] and the cloud [FirebaseSync] mirror in step while signed in.
 *
 * On sign-in: pull remote → merge into local (favourites are additive; history takes the newer
 * timestamp), then push the local union up. Ongoing: local favourite add/remove is pushed as a
 * delta, history is pushed (debounced, since progress ticks every ~10s), and a live listener brings
 * favourites added on other devices down. Loop-free: the listener only adds-if-missing and pushes
 * only diff against the last known set, so steady state performs no writes.
 *
 * Removals do not propagate across devices in this version (union semantics) — see README.
 */
@Singleton
class LibrarySyncCoordinator @Inject constructor(
    private val library: LibraryRepository,
    private val sync: FirebaseSync,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableListOf<Job>()
    private var favListener: ListenerRegistration? = null
    private var lastFavKeys: Set<String> = emptySet()
    @Volatile private var running = false

    /** Called after a successful sign-in (Google → Firebase) and on session restore. */
    fun onSignedIn() {
        if (running || !sync.isAvailable || sync.uid() == null) return
        running = true
        jobs += scope.launch {
            runCatching { initialMerge() }.onFailure { Log.w(TAG, "initial sync failed", it) }
            startFavoritePush()
            startHistoryPush()
            favListener = sync.listenFavorites { item ->
                scope.launch {
                    if (!library.isFavorite(item.id, item.mediaType)) library.toggleFavorite(item)
                }
            }
        }
    }

    fun onSignedOut() {
        stop()
        sync.signOut()
    }

    private suspend fun initialMerge() {
        // Remote → local (favourites: add missing; history: take the newer).
        sync.pullFavorites().forEach { item ->
            if (!library.isFavorite(item.id, item.mediaType)) library.toggleFavorite(item)
        }
        sync.pullHistory().forEach { (item, remote) ->
            val local = library.getProgress(remote.mediaId, remote.mediaType, remote.season, remote.episode)
            if (local == null || remote.updatedAt > local.updatedAt) library.saveProgress(item, remote)
        }
        // Local → remote (push the union).
        val favs = library.observeFavorites().first()
        favs.forEach { sync.pushFavorite(it.media) }
        lastFavKeys = favs.map { key(it.media.id, it.media.mediaType) }.toSet()
        library.observeHistory().first().forEach { sync.pushHistory(it.media, it.progress) }
    }

    private fun startFavoritePush() {
        jobs += scope.launch {
            library.observeFavorites().collectLatest { favs ->
                val keys = favs.map { key(it.media.id, it.media.mediaType) }.toSet()
                favs.filter { key(it.media.id, it.media.mediaType) !in lastFavKeys }
                    .forEach { sync.pushFavorite(it.media) }
                (lastFavKeys - keys).forEach { k ->
                    parseKey(k)?.let { (id, type) -> sync.removeFavorite(id, type) }
                }
                lastFavKeys = keys
            }
        }
    }

    private fun startHistoryPush() {
        jobs += scope.launch {
            // Progress saves tick frequently; debounce so we don't hammer Firestore.
            library.observeHistory().debounce(HISTORY_DEBOUNCE_MS).collectLatest { history ->
                history.forEach { sync.pushHistory(it.media, it.progress) }
            }
        }
    }

    private fun stop() {
        running = false
        favListener?.remove()
        favListener = null
        jobs.forEach { it.cancel() }
        jobs.clear()
        lastFavKeys = emptySet()
    }

    private fun key(id: Int, type: MediaType) = "${type.name}_$id"

    private fun parseKey(k: String): Pair<Int, MediaType>? {
        val sep = k.lastIndexOf('_')
        if (sep <= 0) return null
        val type = runCatching { MediaType.valueOf(k.substring(0, sep)) }.getOrNull() ?: return null
        val id = k.substring(sep + 1).toIntOrNull() ?: return null
        return id to type
    }

    private companion object {
        const val TAG = "LibrarySync"
        const val HISTORY_DEBOUNCE_MS = 15_000L
    }
}
