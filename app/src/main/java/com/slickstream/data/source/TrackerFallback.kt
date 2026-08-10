package com.slickstream.data.source

import com.slickstream.core.model.StreamSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LAST-RESORT source lookup: query public trackers DIRECTLY, bypassing the Stremio addon layer.
 *
 * Why this exists. Every source in this app used to arrive through addons, and Torrentio is effectively
 * a single point of failure: measured live, its own manifest served 200 while
 * `series/tt0098844:7:9.json` returned 502 twelve times running — a title with hundreds of real
 * releases looked completely sourceless. Torrentio scrapes these same trackers internally, so when its
 * scrape fails there is nothing structurally stopping US from asking them ourselves.
 *
 * Cost control: this runs ONLY when the addon fan-out produced no usable rows, so the healthy path is
 * completely unaffected — no extra requests, no added latency. Both trackers are queried in parallel
 * under a short timeout and merged; either one failing is fine.
 *
 * These are metadata lookups (name + info-hash + swarm counts) exactly like the addon call they stand
 * in for; the actual transfer is the same BitTorrent swarm either way.
 */
@Singleton
class TrackerFallback @Inject constructor(
    private val api: IndexerApi,
) {

    /**
     * @param imdbId "tt0098844".
     * @param season/[episode] non-null for a series; used to keep only the requested episode (plus
     *        season packs, which the picker already knows how to handle).
     * @param buildMagnet supplied by the caller so magnets are built with the SAME tracker set and
     *        display-name handling as the addon path — one magnet format, one code path downstream.
     */
    suspend fun resolve(
        imdbId: String,
        season: Int?,
        episode: Int?,
        buildMagnet: (infoHash: String, displayName: String) -> String,
        parseQuality: (String) -> String,
    ): List<StreamSource> = coroutineScope {
        // Strip ONLY the "tt" — keep the zero padding. EZTV matches on the padded numeric id: measured
        // live, imdb_id=0098844 returns 371 torrents while imdb_id=98844 returns ZERO. Trimming the zeros
        // silently made the EZTV half of this fallback return nothing at all.
        val numericImdb = imdbId.removePrefix("tt").ifEmpty { "0" }
        val jobs = listOf(
            async {
                runCatchingCancellable {
                    withTimeoutOrNull(TRACKER_TIMEOUT_MS) {
                        api.getPirateBay("$PIRATE_BAY_BASE?q=$imdbId&cat=0")
                    }.orEmpty().mapNotNull { it.toSource(season, episode, buildMagnet, parseQuality) }
                }
            },
            async {
                // EZTV is TV-only and wants the id WITHOUT the "tt" prefix and without leading zeros.
                if (season == null) emptyList() else runCatchingCancellable {
                    withTimeoutOrNull(TRACKER_TIMEOUT_MS) {
                        api.getEztv("$EZTV_BASE?imdb_id=$numericImdb&limit=100")
                    }?.torrents.orEmpty().mapNotNull { it.toSource(season, episode, buildMagnet, parseQuality) }
                }
            },
        )
        jobs.awaitAll().flatten().distinctBy { it.infoHash }
    }

    private suspend fun <T> runCatchingCancellable(block: suspend () -> List<T>): List<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        emptyList()
    }

    private fun com.slickstream.data.source.dto.PirateBayRowDto.toSource(
        season: Int?,
        episode: Int?,
        buildMagnet: (String, String) -> String,
        parseQuality: (String) -> String,
    ): StreamSource? {
        val hash = infoHash?.trim()?.lowercase()?.takeIf { it.length == 40 } ?: return null
        val label = name?.trim().orEmpty()
        // apibay answers "nothing found" with a single sentinel row rather than an empty array.
        if (label.isEmpty() || id == "0" || label.equals("No results returned", true)) return null
        if (!matchesEpisode(label, season, episode)) return null
        return sourceOf(label, hash, seeders?.toIntOrNull(), size?.toLongOrNull(), "The Pirate Bay", season, episode, buildMagnet, parseQuality)
    }

    private fun com.slickstream.data.source.dto.EztvTorrentDto.toSource(
        season: Int?,
        episode: Int?,
        buildMagnet: (String, String) -> String,
        parseQuality: (String) -> String,
    ): StreamSource? {
        val hash = hash?.trim()?.lowercase()?.takeIf { it.length == 40 } ?: return null
        val label = title?.trim().orEmpty().ifEmpty { return null }
        // EZTV carries season/episode as fields, so match on those and fall back to the filename only
        // when it reports 0 (which it does for whole-season packs).
        val s = season?.toString()
        val e = episode?.toString()
        val fieldMatch = s != null && e != null && this.season == s && this.episode == e
        if (!fieldMatch && !matchesEpisode(label, season, episode)) return null
        return sourceOf(label, hash, seeds, sizeBytes?.toLongOrNull(), "EZTV", season, episode, buildMagnet, parseQuality)
    }

    private fun sourceOf(
        label: String,
        hash: String,
        seeders: Int?,
        sizeBytes: Long?,
        provider: String,
        season: Int?,
        episode: Int?,
        buildMagnet: (String, String) -> String,
        parseQuality: (String) -> String,
    ): StreamSource = StreamSource(
        title = label,
        magnetUri = buildMagnet(hash, label),
        infoHash = hash,
        quality = parseQuality(label),
        sizeBytes = sizeBytes?.takeIf { it > 0L },
        seeders = seeders,
        provider = provider,
        // No fileIdx from a raw tracker: the engine's own season/episode filename matching picks the
        // right file out of a pack, which is exactly the path a fileIdx-less addon row already takes.
        fileIndex = null,
        expectedSeason = season,
        expectedEpisode = episode,
        isPack = StreamPicker.looksLikePack(label, null),
        englishLikely = StreamPicker.looksEnglish(label, ""),
        playable = StreamPicker.looksPlayable(label),
        isCam = StreamPicker.looksLikeCam(label, ""),
        frontIndexContainer = StreamPicker.looksFrontIndexContainer(label),
    )

    /** Keep an episode match or a season pack; drop other episodes. Movies (null season) keep everything. */
    private fun matchesEpisode(label: String, season: Int?, episode: Int?): Boolean {
        if (season == null || episode == null) return true
        val sxe = Regex("(?i)(?:^|[^a-z0-9])s0*$season[^a-z0-9]*e0*$episode(?:[^0-9]|$)")
        val x = Regex("(?i)(?:^|[^0-9])0*${season}x0*$episode(?:[^0-9]|$)")
        if (sxe.containsMatchIn(label) || x.containsMatchIn(label)) return true
        // A pack for the right season is useful — the engine selects the episode file inside it.
        val seasonOnly = Regex("(?i)(?:^|[^a-z0-9])(?:s0*$season|season[^0-9]*0*$season)(?:[^0-9]|$)")
        return seasonOnly.containsMatchIn(label) && StreamPicker.looksLikePack(label, null)
    }

    private companion object {
        const val PIRATE_BAY_BASE = "https://apibay.org/q.php"
        const val EZTV_BASE = "https://eztvx.to/api/get-torrents"

        /** Short: this only ever runs after the addon layer already failed, and the user is waiting. */
        const val TRACKER_TIMEOUT_MS = 7_000L
    }
}
