package com.slickstream.data.sync

import android.util.Log
import com.google.firebase.firestore.ListenerRegistration
import com.slickstream.core.repository.LibraryRepository
import com.slickstream.core.repository.ProfileRepository
import com.slickstream.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import com.slickstream.data.local.entity.ProfileEntity
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
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
 *   7. ongoing history push: sampled (never starved by the 10-second player ticker), keyed by origin
 *   8. history listener: last-write-wins progress from other devices into the origin profile
 *
 * Loop-free: incoming history timestamps are pre-marked before the Room write, so the local observer
 * does not echo them; local history pushes only rows newer than their last synced timestamp. Removals
 * do NOT propagate across devices (union semantics) — see README.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
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
    private var historyListener: ListenerRegistration? = null
    private var profileListener: ListenerRegistration? = null
    private var settingsListener: ListenerRegistration? = null
    private var lastFavKeys: Set<String> = emptySet()
    /** Per-profile last-pushed updatedAt, so the push observer fires on EDITS (rename/avatar), not just
     *  new ids — and skips echoing a profile we just pulled (same updatedAt). */
    private var lastProfileSigs: Map<String, Long> = emptyMap()
    /** Per profile/title/episode last-synced progress timestamp; concurrent because Firestore may
     *  deliver multiple document changes in parallel with the Room push collector. */
    private val lastHistorySigs = ConcurrentHashMap<String, Long>()
    private var lastSettingsSig: String? = null
    @Volatile private var running = false

    /** Called after a successful sign-in (Google → Firebase) and on session restore. */
    fun onSignedIn() {
        if (running || !sync.isAvailable || sync.uid() == null) return
        running = true
        jobs += scope.launch {
            // Rethrow cancellation out of each stage: stop() (sign-out) cancels THIS job, but a bare
            // runCatching swallowed the CancellationException mid-suspend and the zombie coroutine
            // then re-registered listeners + relaunched pushes on the still-live scope AFTER stop()
            // had cleared them — a leaked Firestore listener per sign-in/out cycle.
            runCatching { syncProfiles() }.onFailure { if (it is CancellationException) throw it; Log.w(TAG, "profile sync failed", it) }
            runCatching { syncSettings() }.onFailure { if (it is CancellationException) throw it; Log.w(TAG, "settings sync failed", it) }
            runCatching { initialMerge() }.onFailure { if (it is CancellationException) throw it; Log.w(TAG, "initial sync failed", it) }
            // Belt-and-braces for cancellation swallowed deeper down (FirebaseSync wraps its awaits).
            if (!running || !isActive) return@launch
            startFavoritePush()
            startHistoryPush()
            favListener = sync.listenFavorites { pid, item ->
                scope.launch {
                    if (!library.isFavoriteInProfile(pid, item.id, item.mediaType)) {
                        library.addFavoriteForProfile(pid, item)
                    }
                }
            }
            historyListener = sync.listenHistory { pid, item, remote ->
                scope.launch {
                    if (!running) return@launch
                    val local = library.getProgressForProfile(
                        pid,
                        remote.mediaId,
                        remote.mediaType,
                        remote.season,
                        remote.episode,
                    )
                    if (local == null || remote.updatedAt > local.updatedAt) {
                        // Mark before writing Room so startHistoryPush treats the resulting emission as
                        // an incoming echo, not a fresh local edit that needs uploading again.
                        lastHistorySigs.merge(historyKey(pid, remote), remote.updatedAt, ::maxOf)
                        if (!running) return@launch
                        library.saveProgressForProfile(pid, item, remote)
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
        lastProfileSigs = profiles.allProfiles().associate { it.id to it.updatedAt }
        if (!running) return   // signed out mid-sync — don't register pushes/listeners stop() can't see
        startProfilePush()
        profileListener = sync.listenProfiles { p ->
            scope.launch {
                // Pre-mark the incoming updatedAt so the push observer doesn't echo this profile back up.
                lastProfileSigs = lastProfileSigs + (p.id to p.updatedAt)
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
                        // Re-key the loser's CLOUD favourites/history onto the canonical id too — otherwise
                        // they stay orphaned under the dead id and initialMerge re-pulls them as a split.
                        sync.reassignProfileDocs(loser.id, canonical.id)
                        if (profiles.currentProfileId() == loser.id) profiles.setActiveProfile(canonical.id)
                        profiles.deleteProfile(loser.id)
                        sync.removeProfile(loser.id)
                    }
            }
    }

    /** Push a profile to the cloud the moment it's created OR edited, so the other device adopts the
     *  EXISTING id (no divergent UUID) and gets renames/avatar changes. Pushes on any CONTENT change by
     *  comparing updatedAt per id — the old id-only delta never pushed edits (the "rename didn't sync" bug). */
    private fun startProfilePush() {
        jobs += scope.launch {
            profiles.observeProfiles().collectLatest { list ->
                list.forEach { p ->
                    val seen = lastProfileSigs[p.id]
                    if (seen == null || p.updatedAt > seen) sync.pushProfile(p)
                }
                lastProfileSigs = list.associate { it.id to it.updatedAt }
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
        if (!running) return   // signed out mid-sync — same guard as syncProfiles
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
        val remoteHistory = sync.pullHistory()
        remoteHistory.forEach { (pid, item, remote) ->
            val local = library.getProgressForProfile(pid, remote.mediaId, remote.mediaType, remote.season, remote.episode)
            if (local == null || remote.updatedAt > local.updatedAt) {
                library.saveProgressForProfile(pid, item, remote)
            }
        }
        // Local → remote: push the union of every profile's library.
        val favs = library.allFavoritesForSync()
        favs.forEach { (pid, fav) -> sync.pushFavorite(pid, fav.media) }
        lastFavKeys = favs.map { (pid, fav) -> favKey(pid, fav.media.id, fav.media.mediaType) }.toSet()

        // History documents are immutable per episode key except for their progress timestamp. The
        // old code rewrote EVERY episode on EVERY launch, delaying the live listener and burning I/O
        // as libraries grew. Start with what the pull proved is already remote, then upload only rows
        // whose local timestamp is newer (or whose cloud document is missing).
        val syncedHistory = remoteHistory.associate { (pid, _, progress) ->
            historyKey(pid, progress) to progress.updatedAt
        }.toMutableMap()
        val history = library.allHistoryForSync()
        history.forEach { (pid, item, progress) ->
            val key = historyKey(pid, progress)
            if (progress.updatedAt > (syncedHistory[key] ?: Long.MIN_VALUE) &&
                sync.pushHistory(pid, item, progress)
            ) {
                syncedHistory[key] = progress.updatedAt
            }
        }
        lastHistorySigs.clear()
        lastHistorySigs.putAll(syncedHistory)
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
            // Player progress arrives every 10 seconds. The old 15-second debounce was therefore
            // perpetually reset during playback and often uploaded ONLY episode 1 during the pause
            // before episode 2 — exactly the stale cross-device Continue Watching bug. Sampling emits
            // the newest snapshot on a fixed cadence, and the timestamp map sends only changed rows.
            // Do not use collectLatest here: canceling an in-flight Firestore write when the next
            // sample arrives can starve slow/offline uploads just like the old debounce did.
            library.observeAllHistoryForSync().sample(HISTORY_SYNC_INTERVAL_MS).collect { history ->
                history.forEach { (pid, item, progress) ->
                    val key = historyKey(pid, progress)
                    if (progress.updatedAt > (lastHistorySigs[key] ?: Long.MIN_VALUE)) {
                        if (sync.pushHistory(pid, item, progress)) {
                            lastHistorySigs.merge(key, progress.updatedAt, ::maxOf)
                        }
                    }
                }
            }
        }
    }

    private fun stop() {
        running = false
        favListener?.remove()
        favListener = null
        historyListener?.remove()
        historyListener = null
        profileListener?.remove()
        profileListener = null
        settingsListener?.remove()
        settingsListener = null
        jobs.forEach { it.cancel() }
        jobs.clear()
        lastFavKeys = emptySet()
        lastProfileSigs = emptyMap()
        lastHistorySigs.clear()
        lastSettingsSig = null
    }

    private fun favKey(profileId: String, id: Int, type: com.slickstream.core.model.MediaType) =
        "${profileId}__${type.name}_$id"

    private fun historyKey(profileId: String, progress: com.slickstream.core.model.PlaybackProgress) =
        "${profileId}__${progress.mediaType.name}_${progress.mediaId}_${progress.season ?: -1}_${progress.episode ?: -1}"

    private companion object {
        const val TAG = "LibrarySync"
        const val HISTORY_SYNC_INTERVAL_MS = 15_000L
        const val SETTINGS_DEBOUNCE_MS = 2_000L
    }
}
