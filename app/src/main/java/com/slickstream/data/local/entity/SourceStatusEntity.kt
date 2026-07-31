package com.slickstream.data.local.entity

import androidx.room.Entity
import com.slickstream.core.model.MediaType

/**
 * Cached source availability per title — does at least one playable source exist? Populated for FREE
 * every time a real resolve happens (details open, prewarm, play) and by the Home hero probe, so the
 * catalog can stop headlining titles that have nothing to play (an unreleased film trending at #1)
 * WITHOUT blocking any list on a live indexer query.
 */
@Entity(tableName = "source_status", primaryKeys = ["mediaId", "mediaType"])
data class SourceStatusEntity(
    val mediaId: Int,
    val mediaType: MediaType,
    val hasSources: Boolean,
    /** True when every playable source found for this title was a CAM/TS cinema-rip (only-CAM). */
    val camOnly: Boolean = false,
    val checkedAt: Long,
)
