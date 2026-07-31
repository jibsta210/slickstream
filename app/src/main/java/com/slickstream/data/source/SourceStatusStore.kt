package com.slickstream.data.source

import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.data.local.dao.SourceStatusDao
import com.slickstream.data.local.entity.SourceStatusEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory + Room source-availability index (see [SourceStatusEntity]). Reads are synchronous map
 * lookups so catalog filtering adds ZERO latency; writes go to memory immediately and Room async.
 *
 * Tracks two verdicts per title, both populated for FREE by every real resolve (details open,
 * prewarm, play) + the Home hero probe:
 *  - hasSources — does anything playable exist (drops dead titles from the hero/rows);
 *  - camOnly — is EVERY playable release a CAM/TS cinema-rip (badges the card so the user knows it's
 *    only a bad cinema cam before opening it).
 */
@Singleton
class SourceStatusStore @Inject constructor(
    private val dao: SourceStatusDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val map = ConcurrentHashMap<String, SourceStatusEntity>()

    /** Keys of titles known to be CAM-only, exposed as a flow so the UI can badge cards reactively. */
    private val _camOnlyKeys = MutableStateFlow<Set<String>>(emptySet())
    val camOnlyKeys: StateFlow<Set<String>> = _camOnlyKeys.asStateFlow()

    init {
        scope.launch {
            runCatching {
                dao.getAll().forEach { map[key(it.mediaId, it.mediaType)] = it }
                republishCamOnly()
            }
        }
    }

    private fun key(id: Int, type: MediaType) = "${type.name}:$id"

    /** Public key so UI code can build the same lookup key for [camOnlyKeys]. */
    fun key(item: MediaItem) = key(item.id, item.mediaType)

    private fun republishCamOnly() {
        _camOnlyKeys.value = map.values.asSequence()
            .filter { it.hasSources && it.camOnly }
            .map { key(it.mediaId, it.mediaType) }
            .toSet()
    }

    /** Record a REAL resolution outcome (piggybacked from every resolve + the hero probe). */
    fun record(mediaId: Int, mediaType: MediaType, hasSources: Boolean, camOnly: Boolean = false) {
        val e = SourceStatusEntity(mediaId, mediaType, hasSources, camOnly, System.currentTimeMillis())
        map[key(mediaId, mediaType)] = e
        republishCamOnly()
        scope.launch { runCatching { dao.upsert(e) } }
    }

    /** True when we RECENTLY confirmed this title has nothing to play. Unknown/stale = false (show it). */
    fun isKnownEmpty(mediaId: Int, mediaType: MediaType): Boolean {
        val e = map[key(mediaId, mediaType)] ?: return false
        return !e.hasSources && System.currentTimeMillis() - e.checkedAt < EMPTY_TTL_MS
    }

    /** True when we RECENTLY confirmed this title has ONLY CAM/TS releases. */
    fun isCamOnly(mediaId: Int, mediaType: MediaType): Boolean {
        val e = map[key(mediaId, mediaType)] ?: return false
        return e.hasSources && e.camOnly && System.currentTimeMillis() - e.checkedAt < CAM_TTL_MS
    }

    /** True when this title's availability is worth probing (never checked, or an empty verdict aged out). */
    fun shouldProbe(mediaId: Int, mediaType: MediaType): Boolean {
        val e = map[key(mediaId, mediaType)] ?: return true
        return !e.hasSources && System.currentTimeMillis() - e.checkedAt >= EMPTY_TTL_MS
    }

    /** Drop titles we RECENTLY confirmed unplayable. Unknowns pass — lists never wait on the network. */
    fun filterBrowsable(items: List<MediaItem>): List<MediaItem> =
        items.filterNot { isKnownEmpty(it.id, it.mediaType) }

    private companion object {
        /** How long a "no sources" verdict suppresses a title before it's re-checked. */
        const val EMPTY_TTL_MS = 12L * 60 * 60 * 1000

        /** How long a CAM-only badge persists before it's re-checked (a WEB-DL usually lands within days). */
        const val CAM_TTL_MS = 3L * 24 * 60 * 60 * 1000
    }
}
