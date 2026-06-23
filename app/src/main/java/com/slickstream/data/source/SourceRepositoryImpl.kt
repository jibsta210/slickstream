package com.slickstream.data.source

import com.slickstream.core.model.DataResult
import com.slickstream.core.model.MediaDetails
import com.slickstream.core.model.MediaType
import com.slickstream.core.model.StreamSource
import com.slickstream.core.repository.SourceRepository
import com.slickstream.data.source.dto.StreamDto
import java.net.URLEncoder
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a title (by IMDB id) into a ranked list of playable torrent sources via a
 * Stremio/Torrentio-compatible indexer.
 *
 * The raw indexer rows embed quality, seeders and file size as decorated text inside the
 * `name` / `title` strings (e.g. "👤 123" for seeders, "💾 2.1 GB" for size); this class
 * extracts those, builds a magnet URI from the info-hash plus public trackers, maps each row
 * to a [StreamSource] and returns them sorted by [StreamSource.rank] (best first).
 */
@Singleton
class SourceRepositoryImpl @Inject constructor(
    private val api: IndexerApi,
) : SourceRepository {

    override suspend fun resolve(
        details: MediaDetails,
        season: Int?,
        episode: Int?,
    ): DataResult<List<StreamSource>> {
        val imdbId = details.imdbId?.takeIf { it.isNotBlank() }
            ?: return DataResult.Error("No IMDB id for \"${details.item.title}\" — cannot resolve sources")

        val isSeries = details.item.mediaType == MediaType.TV
        val type = if (isSeries) "series" else "movie"
        val id = if (isSeries && season != null && episode != null) {
            "$imdbId:$season:$episode"
        } else {
            imdbId
        }

        return try {
            val response = api.getStreams(type, id)
            val sources = response.streams
                .mapNotNull { it.toStreamSource() }
                .sortedByDescending { it.rank }
            DataResult.Success(sources)
        } catch (t: Throwable) {
            DataResult.Error(
                "Failed to resolve sources for \"${details.item.title}\": ${t.message ?: "unknown error"}",
                t,
            )
        }
    }

    /** Map one indexer row to a [StreamSource], or null if it has no usable info-hash. */
    private fun StreamDto.toStreamSource(): StreamSource? {
        val hash = infoHash?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return null

        // Pool every text field that may carry quality / seeders / size metadata.
        val haystack = listOfNotNull(name, title, description, behaviorHints?.bingeGroup)
            .joinToString("\n")

        val displayName = behaviorHints?.filename
            ?: title?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: name?.replace('\n', ' ')?.trim()
            ?: "Torrent"

        return StreamSource(
            title = displayName.trim(),
            magnetUri = buildMagnet(hash, displayName),
            infoHash = hash,
            quality = parseQuality(haystack),
            sizeBytes = behaviorHints?.videoSize ?: parseSize(haystack),
            seeders = parseSeeders(haystack),
            provider = parseProvider(name),
            fileIndex = fileIdx,
        )
    }

    private fun buildMagnet(infoHash: String, displayName: String): String {
        val sb = StringBuilder("magnet:?xt=urn:btih:").append(infoHash)
        val dn = displayName.lineSequence().firstOrNull()?.trim().orEmpty()
        if (dn.isNotEmpty()) {
            sb.append("&dn=").append(URLEncoder.encode(dn, "UTF-8"))
        }
        for (tracker in TRACKERS) {
            sb.append("&tr=").append(URLEncoder.encode(tracker, "UTF-8"))
        }
        return sb.toString()
    }

    private fun parseQuality(text: String): String {
        val upper = text.uppercase(Locale.ROOT)
        return when {
            "2160P" in upper || "4K" in upper || "UHD" in upper -> "4K"
            "1080P" in upper || "FULLHD" in upper || "FHD" in upper -> "1080p"
            "720P" in upper || "HD" in upper -> "720p"
            "480P" in upper -> "480p"
            else -> "SD"
        }
    }

    private fun parseSeeders(text: String): Int? {
        // Torrentio formats seeders as "👤 123"; also tolerate "Seeders: 123" variants.
        SEEDER_EMOJI_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return SEEDER_WORD_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun parseSize(text: String): Long? {
        val match = SIZE_REGEX.find(text) ?: return null
        val value = match.groupValues.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull() ?: return null
        val unit = match.groupValues.getOrNull(2)?.uppercase(Locale.ROOT) ?: return null
        val multiplier = when (unit) {
            "TB", "TIB" -> 1024.0 * 1024 * 1024 * 1024
            "GB", "GIB" -> 1024.0 * 1024 * 1024
            "MB", "MIB" -> 1024.0 * 1024
            "KB", "KIB" -> 1024.0
            else -> 1.0
        }
        return (value * multiplier).toLong()
    }

    /** Provider/tracker label from the `name` field, e.g. "Torrentio\n1080p" -> "Torrentio". */
    private fun parseProvider(name: String?): String {
        val firstLine = name?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        return firstLine.takeIf { it.isNotBlank() } ?: "Torrentio"
    }

    private companion object {
        // 👤 followed by an optional space and a seeder count.
        val SEEDER_EMOJI_REGEX = Regex("""👤\s*(\d+)""")
        val SEEDER_WORD_REGEX = Regex("""(?i)seed(?:er)?s?\s*[:=]?\s*(\d+)""")

        // e.g. "💾 2.1 GB" / "Size: 700 MB" / "1,5 GiB"
        val SIZE_REGEX = Regex("""(\d+(?:[.,]\d+)?)\s*(TB|TiB|GB|GiB|MB|MiB|KB|KiB)""", RegexOption.IGNORE_CASE)

        /** Common public BitTorrent trackers appended to every magnet for faster peer discovery. */
        val TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.tracker.cl:1337/announce",
            "udp://9.rarbg.com:2810/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://opentracker.i2p.rocks:6969/announce",
            "udp://tracker.internetwarriors.net:1337/announce",
            "udp://tracker.leechers-paradise.org:6969/announce",
            "udp://tracker.coppersurfer.tk:6969/announce",
            "udp://tracker.dler.org:6969/announce",
            "udp://open.stealth.si:80/announce",
            "udp://ipv4.tracker.harry.lu:80/announce",
            "udp://explodie.org:6969/announce",
            "https://tracker.gbitt.info:443/announce",
        )
    }
}
