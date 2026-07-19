package com.slickstream.data.source

import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.data.local.dao.SourceStatusDao
import com.slickstream.data.local.entity.SourceStatusEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory + Room source-availability index (see [SourceStatusEntity]). Reads are synchronous map
 * lookups so catalog filtering adds ZERO latency; writes go to memory immediately and Room async.
 *
 * Staleness: a KNOWN-EMPTY verdict only suppresses a title for [EMPTY_TTL_MS] — sources appear over
 * time (a theatrical release gets rips), so an old "nothing" must not hide a title forever. A
 * has-sources verdict never suppresses anything, so it needs no TTL.
 */
@Singleton
class SourceStatusStore @Inject constructor(
    private val dao: SourceStatusDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val map = ConcurrentHashMap<String, SourceStatusEntity>()

    init {
        scope.launch { runCatching { dao.getAll().forEach { map[key(it.mediaId, it.mediaType)] = it } } }
    }

    private fun key(id: Int, type: MediaType) = "${type.name}:$id"

    /** Record a REAL resolution outcome (piggybacked from every resolve + the hero probe). */
    fun record(mediaId: Int, mediaType: MediaType, hasSources: Boolean) {
        val e = SourceStatusEntity(mediaId, mediaType, hasSources, System.currentTimeMillis())
        map[key(mediaId, mediaType)] = e
        scope.launch { runCatching { dao.upsert(e) } }
    }

    /** True when we RECENTLY confirmed this title has nothing to play. Unknown/stale = false (show it). */
    fun isKnownEmpty(mediaId: Int, mediaType: MediaType): Boolean {
        val e = map[key(mediaId, mediaType)] ?: return false
        return !e.hasSources && System.currentTimeMillis() - e.checkedAt < EMPTY_TTL_MS
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
    }
}
