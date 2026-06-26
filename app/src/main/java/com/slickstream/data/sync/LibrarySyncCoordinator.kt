package com.slickstream.data.sync

import android.util.Log
import com.google.firebase.firestore.ListenerRegistration
import com.slickstream.core.repository.LibraryRepository
import com.slickstream.core.repository.ProfileRepository
import com.slickstream.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import com.slickstream.data.local.entity.ProfileEntity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the local library/profiles and the cloud [FirebaseSync] mirror in step while signed in.
 *
 * Everything is scoped BY PROFILE so a favourite added on the TV under profile X is re-attached to
 * profile X on the phone (not the phone's active profile). On sign-in:
 *   1. pull remote profiles → upsert each (without changing the active profile)
 *   2. push every local profile up
 *   3. listen for profiles created/edited on other devices → upsert each
 *   4. initial merge: pull favourites/history (each carrying its origin profileId), add/merge the
 *      missing/newer ones, then push the local union back up
 *   5. ongoing favourite push: a delta against the last known per-profile key set
 *   6. favourite listener: add-if-missing into the item's ORIGIN profile
 *   7. ongoing history push: debounced, keyed by origin profile
 *
 * Loop-free: listeners only add-if-missing, and pushes only diff against the last known set, so
 * steady state performs no writes. Removals do NOT propagate across devices (union semantics) — see
 * README — so the favourite push never sends deletes.
 */
@Singleton
class LibrarySyncCoordinator @Inject constructor(
    private val library: LibraryRepository,
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
    private val sync: FirebaseSync,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableListOf<Job>()
    private var favListener: ListenerRegistration? = null
    private var profileListener: ListenerRegistration? = null
    private var settingsListener: ListenerRegistration? = null
    private var lastFavKeys: Set<String> = emptySet()
    private var lastProfileIds: Set<String> = emptySet()
    private var lastSettingsSig: String? = null
    @Volatile private var running = false

    /** Called after a successful sign-in (Google → Firebase) and on session restore. */
    fun onSignedIn() {
        if (running || !sync.isAvailable || sync.uid() == null) return
        running = true
        jobs += scope.launch {
            runCatching { syncProfiles() }.onFailure { Log.w(TAG, "profile sync failed", it) }
            runCatching { syncSettings() }.onFailure { Log.w(TAG, "settings sync failed", it) }
            runCatching { initialMerge() }.onFailure { Log.w(TAG, "initial sync failed", it) }
            startFavoritePush()
            startHistoryPush()
            favListener = sync.listenFavorites { pid, item ->
                scope.launch {
                    if (!library.isFavoriteInProfile(pid, item.id, item.mediaType)) {
                        library.addFavoriteForProfile(pid, item)
                    }
                }
            }
        }
    }

    fun onSignedOut() {
        stop()
        sync.signOut()
    }

    /** Profiles: pull remote → upsert each; reconcile same-named duplicates; push the local set up;
     *  start a delta push (so a profile created mid-session syncs immediately); then listen live. */
    private suspend fun syncProfiles() {
        sync.pullProfiles().forEach { profiles.upsertFromSync(it) }
        reconcileDuplicateProfiles()
        profiles.allProfiles().forEach { sync.pushProfileNow(it) }
        lastProfileIds = profiles.allProfiles().map { it.id }.toSet()
        startProfilePush()
        profileListener = sync.listenProfiles { p ->
            scope.launch {
                lastProfileIds = lastProfileIds + p.id   // pre-mark so the push observer doesn't echo it back
                profiles.upsertFromSync(p)
                reconcileDuplicateProfiles()
            }
        }
    }

    /**
     * THE favourites-sync fix. Two profiles that are the SAME PERSON but were created independently on
     * different devices get divergent random UUIDs (createProfile mints UUID.randomUUID()), so their
     * profile-keyed favourites/history never match across devices — only the shared "default" id did.
     * Here we merge same-(trimmed,lower)-named profiles into ONE canonical id (the earliest-created,
     * deterministic on every device), re-key favourites/history to it, and drop the loser — WITHOUT a
     * visible profile switch (if the active profile WAS the loser, we silently retarget it to canonical).
     * "default" is never reconciled away.
     */
    private suspend fun reconcileDuplicateProfiles() {
        profiles.allProfiles()
            .groupBy { it.name.trim().lowercase() }
            .values
            .filter { it.size > 1 }
            .forEach { dups ->
                val canonical = dups.minWithOrNull(compareBy({ it.createdAt }, { it.id })) ?: return@forEach
                dups.filter { it.id != canonical.id && it.id != ProfileEntity.DEFAULT_PROFILE_ID }
                    .forEach { loser ->
                        library.reassignProfile(loser.id, canonical.id)
                        if (profiles.currentProfileId() == loser.id) profiles.setActiveProfile(canonical.id)
                        profiles.deleteProfile(loser.id)
                        sync.removeProfile(loser.id)
                    }
            }
    }

    /** Push a profile to the cloud the moment it's created/edited (delta vs lastProfileIds), so the
     *  other device adopts the EXISTING id instead of minting a fresh UUID for the same name. */
    private fun startProfilePush() {
        jobs += scope.launch {
            profiles.observeProfiles().collectLatest { list ->
                list.filter { it.id !in lastProfileIds }.forEach { sync.pushProfile(it) }
                lastProfileIds = list.map { it.id }.toSet()
            }
        }
    }

    /**
     * Device-AGNOSTIC settings sync (quality, subtitles, stream size, up-next thresholds, density).
     * Screen calibration + cache size are per-device and never travel. Last-write-wins by updatedAt:
     * on sign-in the fresher of {local, cloud} prevails; thereafter a content-signature guard makes it
     * loop-free (applying a remote value sets the signature so the resulting DataStore emit doesn't
     * echo a push back up).
     */
    private suspend fun syncSettings() {
        val remote = sync.pullSettings()
        val remoteTs = (remote?.get("updatedAt") as? Number)?.toLong() ?: -1L
        if (remote != null && remoteTs > settings.syncedUpdatedAt()) {
            settings.applySyncedSettings(remote)
            lastSettingsSig = settings.syncedSignature(remote)
        } else {
            pushSettingsNow()
        }
        startSettingsPush()
        settingsListener = sync.listenSettings { remoteMap ->
            scope.launch {
                val rTs = (remoteMap["updatedAt"] as? Number)?.toLong() ?: -1L
                val sig = settings.syncedSignature(remoteMap)
                if (sig != lastSettingsSig && rTs > settings.syncedUpdatedAt()) {
                    settings.applySyncedSettings(remoteMap)
                    lastSettingsSig = sig
                }
            }
        }
    }

    /** Push the local synced settings up, stamping a fresh updatedAt. */
    private suspend fun pushSettingsNow() {
        val map = settings.syncedSettingsMap()
        val ts = System.currentTimeMillis()
        settings.stampSyncedUpdated(ts)
        sync.pushSettings(map + ("updatedAt" to ts))
        lastSettingsSig = settings.syncedSignature(map)
    }

    /** Watch local settings; push when the synced fields actually change (signature guard skips
     *  echoes of a value we just pulled). Debounced — option toggles can tick quickly. */
    private fun startSettingsPush() {
        jobs += scope.launch {
            settings.settings.debounce(SETTINGS_DEBOUNCE_MS).collectLatest {
                val map = settings.syncedSettingsMap()
                if (settings.syncedSignature(map) != lastSettingsSig) pushSettingsNow()
            }
        }
    }

    private suspend fun initialMerge() {
        // Remote → local, each item re-attached to its origin profile.
        // Favourites: add-if-missing in that profile.
        sync.pullFavorites().forEach { (pid, item) ->
            if (!library.isFavoriteInProfile(pid, item.id, item.mediaType)) {
                library.addFavoriteForProfile(pid, item)
            }
        }
        // History: take the newer (per origin profile).
        sync.pullHistory().forEach { (pid, item, remote) ->
            val local = library.getProgressForProfile(pid, remote.mediaId, remote.mediaType, remote.season, remote.episode)
            if (local == null || remote.updatedAt > local.updatedAt) {
                library.saveProgressForProfile(pid, item, remote)
            }
        }
        // Local → remote: push the union of every profile's library.
        val favs = library.allFavoritesForSync()
        favs.forEach { (pid, fav) -> sync.pushFavorite(pid, fav.media) }
        lastFavKeys = favs.map { (pid, fav) -> favKey(pid, fav.media.id, fav.media.mediaType) }.toSet()
        library.allHistoryForSync().forEach { (pid, item, progress) -> sync.pushHistory(pid, item, progress) }
    }

    private fun startFavoritePush() {
        jobs += scope.launch {
            // Cross-profile feed: push only keys not seen before (delta). Removals are union-only and
            // are NOT propagated, so we never send deletes here.
            library.observeAllFavoritesForSync().collectLatest { favs ->
                val keys = favs.map { (pid, fav) -> favKey(pid, fav.media.id, fav.media.mediaType) }.toSet()
                favs.filter { (pid, fav) -> favKey(pid, fav.media.id, fav.media.mediaType) !in lastFavKeys }
                    .forEach { (pid, fav) -> sync.pushFavorite(pid, fav.media) }
                lastFavKeys = keys
            }
        }
    }

    private fun startHistoryPush() {
        jobs += scope.launch {
            // Progress saves tick frequently; debounce so we don't hammer Firestore.
            library.observeAllHistoryForSync().debounce(HISTORY_DEBOUNCE_MS).collectLatest { history ->
                history.forEach { (pid, item, progress) -> sync.pushHistory(pid, item, progress) }
            }
        }
    }

    private fun stop() {
        running = false
        favListener?.remove()
        favListener = null
        profileListener?.remove()
        profileListener = null
        settingsListener?.remove()
        settingsListener = null
        jobs.forEach { it.cancel() }
        jobs.clear()
        lastFavKeys = emptySet()
        lastProfileIds = emptySet()
        lastSettingsSig = null
    }

    private fun favKey(profileId: String, id: Int, type: com.slickstream.core.model.MediaType) =
        "${profileId}__${type.name}_$id"

    private companion object {
        const val TAG = "LibrarySync"
        const val HISTORY_DEBOUNCE_MS = 15_000L
        const val SETTINGS_DEBOUNCE_MS = 2_000L
    }
}
