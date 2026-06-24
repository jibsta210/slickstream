package com.slickstream.data.source

import com.slickstream.core.model.StreamSource
import com.slickstream.data.settings.QualityPreference
import com.slickstream.data.settings.StreamSizePreference

/**
 * Shared auto-pick used by BOTH the player (what actually plays) and the details-screen prewarm
 * (what gets prefetched), so the warmed torrent is the same one Play will use — otherwise the
 * prewarm is wasted. SEEDERS are the primary key (with a health floor) so a starved 4K/3-seeder is
 * never chosen over a healthy 1080p; low-power TV caps at 1080p; the size preference then decides
 * where on the size/bitrate range to land.
 */
object StreamPicker {
    const val MIN_SEEDERS = 8

    fun pick(
        list: List<StreamSource>,
        maxTier: Int,
        sizePref: StreamSizePreference,
        lowPower: Boolean,
    ): StreamSource? {
        if (list.isEmpty()) return null
        val effectiveTier = if (lowPower) minOf(maxTier, QualityPreference.FHD_1080.maxTier) else maxTier
        val capped = list.filter { QualityPreference.tierOf(it.quality) <= effectiveTier }.ifEmpty { list }
        val healthy = capped.filter { (it.seeders ?: 0) >= MIN_SEEDERS }.ifEmpty { capped }
        val picked = when (sizePref) {
            StreamSizePreference.HIGHEST ->
                healthy.maxWithOrNull(compareBy<StreamSource>({ it.seeders ?: 0 }, { it.sizeBytes ?: 0L }))
            StreamSizePreference.SMALLEST -> {
                val withSize = healthy.filter { (it.sizeBytes ?: 0L) > 0L }
                if (withSize.isNotEmpty()) {
                    withSize.minWithOrNull(
                        compareBy<StreamSource> { it.sizeBytes ?: 0L }.thenByDescending { it.seeders ?: 0 },
                    )
                } else {
                    healthy.maxByOrNull { it.seeders ?: 0 }
                }
            }
            StreamSizePreference.BALANCED -> {
                val withSize = healthy.filter { (it.sizeBytes ?: 0L) > 0L }.sortedBy { it.sizeBytes ?: 0L }
                val pool = if (withSize.size >= 4) withSize.take((withSize.size * 2 + 2) / 3) else healthy
                pool.minWithOrNull(
                    compareByDescending<StreamSource> { it.seeders ?: 0 }.thenBy { it.sizeBytes ?: Long.MAX_VALUE },
                )
            }
        }
        return picked ?: list.first()
    }
}
