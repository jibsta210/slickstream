package com.slickstream.data.source.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Rows from the two PUBLIC tracker APIs used as a last-resort fallback when every Stremio addon
 * fails. These are queried DIRECTLY, so an outage of the addon layer (Torrentio was returning 502
 * per-episode while its own manifest served 200) can no longer read as "this title has no releases".
 *
 * Both are keyed by IMDB id, which is exactly what the resolver already has in hand.
 */

/** apibay (The Pirate Bay) — `GET https://apibay.org/q.php?q=tt1234567&cat=0`, a flat JSON array.
 *  Every field arrives as a STRING, including the numeric ones. A "no results" reply is a single row
 *  with id "0" and name "No results returned", which the mapper drops. */
@Serializable
data class PirateBayRowDto(
    val id: String? = null,
    val name: String? = null,
    @SerialName("info_hash") val infoHash: String? = null,
    val seeders: String? = null,
    val leechers: String? = null,
    val size: String? = null,
    val category: String? = null,
)

/** EZTV — `GET https://eztvx.to/api/get-torrents?imdb_id=0098844&limit=100` (note: NO "tt" prefix).
 *  TV only, and it carries season/episode as separate fields, so an episode match needs no filename
 *  parsing at all. */
@Serializable
data class EztvResponseDto(
    @SerialName("torrents_count") val torrentsCount: Int = 0,
    val torrents: List<EztvTorrentDto> = emptyList(),
)

@Serializable
data class EztvTorrentDto(
    val title: String? = null,
    val hash: String? = null,
    @SerialName("magnet_url") val magnetUrl: String? = null,
    val season: String? = null,
    val episode: String? = null,
    val seeds: Int? = null,
    val peers: Int? = null,
    @SerialName("size_bytes") val sizeBytes: String? = null,
)
