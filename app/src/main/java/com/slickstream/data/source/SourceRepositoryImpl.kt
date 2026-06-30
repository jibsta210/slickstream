package com.slickstream.data.source

import com.slickstream.core.common.Indexer
import com.slickstream.core.model.DataResult
import com.slickstream.core.model.MediaDetails
import com.slickstream.core.model.MediaType
import com.slickstream.core.model.StreamSource
import com.slickstream.core.repository.SourceRepository
import com.slickstream.data.source.dto.StreamDto
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    private val addonRegistry: AddonRegistry,
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
            // Query every CONFIGURED indexer addon PLUS the auto-discovered, health-checked free
            // streaming addons (AddonRegistry — kept current automatically, no manual URLs) in parallel.
            // One being down/slow never blocks the others (per-addon timeout + getOrNull). Merge + de-dupe
            // by info-hash (keep the row with the most seeders).
            val autoBases = runCatching { addonRegistry.streamingBaseUrls() }.getOrDefault(emptyList())
            val allBases = (baseUrls + autoBases).distinct()
            val responses = coroutineScope {
                allBases.map { base ->
                    async {
                        kotlinx.coroutines.withTimeoutOrNull(ADDON_QUERY_TIMEOUT_MS) {
                            runCatching { api.getStreamsAt("${base}stream/$type/$id.json") }.getOrNull()
                        }
                    }
                }.awaitAll()
            }
            val ok = responses.filterNotNull()
            if (ok.isEmpty()) {
                return DataResult.Error("Failed to resolve sources for \"${details.item.title}\"")
            }
            // Title-aware language detection needs the title at MAP time so a language name that's part
            // of the title ("The French Dispatch") isn't read as a foreign-audio tag.
            val movieTitle = details.item.title
            val sources = ok
                .flatMap { it.streams }
                .mapNotNull { it.toStreamSource(movieTitle) }
                .groupBy { it.infoHash }
                .map { (_, rows) -> rows.maxByOrNull { it.seeders ?: 0 }!! }
                // Direct (file-server) streams first — they play instantly with no swarm — then torrents
                // by health, so the manual source list matches the auto-pick's file-server-first order.
                .sortedWith(compareByDescending<StreamSource> { it.isDirect }.thenByDescending { it.rank })
            // English-only by default — foreign releases shouldn't even be an OPTION (the user keeps
            // hitting random Spanish/Chinese). Prefer English; if a title genuinely has no English
            // release, still NEVER surface an unreadable non-Latin name — fall back to Latin-script
            // (e.g. a Spanish-only foreign film), and only to the raw list if even that is empty.
            val english = sources.filter {
                it.englishLikely && StreamPicker.noForeignTag(it.title, movieTitle)
            }
            val filtered = english.ifEmpty {
                sources.filterNot { StreamPicker.hasNonLatin(it.title) }.ifEmpty { sources }
            }
            DataResult.Success(filtered)
        } catch (t: Throwable) {
            DataResult.Error(
                "Failed to resolve sources for \"${details.item.title}\": ${t.message ?: "unknown error"}",
                t,
            )
        }
    }

    /**
     * Configured indexer addon base URLs. [Indexer.BASE_URL] may be a COMMA-SEPARATED list so the
     * user can add more sources (e.g. a debrid-backed Torrentio that returns hundreds of cached
     * releases) without a code change. Each is normalised to end in '/'.
     */
    private val baseUrls: List<String> = Indexer.BASE_URL
        .split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { if (it.endsWith("/")) it else "$it/" }
        .ifEmpty { listOf("https://torrentio.strem.fun/") }

    /** Map one indexer row to a [StreamSource], or null if it's neither a torrent nor a direct URL. */
    private fun StreamDto.toStreamSource(movieTitle: String): StreamSource? {
        // A DIRECT http/hls stream (free streaming addon) — no torrent. Synthesize a stable info-hash
        // from the URL so all the existing infoHash-keyed bookkeeping (resume, history, failover) works
        // unchanged. Torrent rows keep their real info-hash; rows with neither are ignored (ytId/external).
        val directUrl = url?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val hash = infoHash?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
            ?: directUrl?.let { syntheticHash(it) }
            ?: return null

        // Pool every text field that may carry quality / seeders / size / codec metadata (the codec
        // and container often live in the filename, e.g. "…XviD-MAXX.avi", so include it).
        val haystack = listOfNotNull(name, title, description, behaviorHints?.bingeGroup, behaviorHints?.filename)
            .joinToString("\n")

        // A "kindly configure this addon to access streams" placeholder is NOT playable — its url is a
        // debrid/config gate, not a video. Drop it so it never appears as a (broken) direct source.
        // (This is exactly what the user hit: every "direct" option said "kindly configure".)
        if (directUrl != null && StreamPicker.looksLikeConfigPrompt(haystack)) return null

        val displayName = behaviorHints?.filename
            ?: title?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: name?.replace('\n', ' ')?.trim()
            ?: if (directUrl != null) "Direct stream" else "Torrent"

        return StreamSource(
            title = displayName.trim(),
            magnetUri = if (directUrl != null) "" else buildMagnet(hash, displayName),
            infoHash = hash,
            quality = parseQuality(haystack),
            sizeBytes = behaviorHints?.videoSize ?: parseSize(haystack),
            seeders = parseSeeders(haystack),
            provider = parseProvider(name),
            fileIndex = fileIdx,
            // Flag season/multi-episode packs so the picker prefers a single-file episode (faster start).
            isPack = StreamPicker.looksLikePack(haystack, fileIdx),
            // Detect language from the FULL text (filename + Torrentio title/description), not just
            // the short label — so a Russian/foreign release is de-prioritized in favour of English.
            englishLikely = StreamPicker.looksEnglish(haystack, movieTitle),
            // Detect the codec/container so an undecodable XviD/AVI release is never auto-picked over
            // a playable x264 (the "valid torrent, black screen" bug).
            playable = StreamPicker.looksPlayable(haystack),
            // Flag cinema-rips (CAM/TS/TELESYNC) so they're badged in the UI + sunk in the picker.
            isCam = StreamPicker.looksLikeCam(haystack, movieTitle),
            // Direct http/hls source -> the player skips the torrent engine and streams this URL.
            directUrl = directUrl,
            // Headers the host requires (Referer/Origin/User-Agent) — only for direct URLs, so torrents
            // never carry stray headers. Without these many free-streaming hosts 403 and the stream
            // "starts but never plays". Drop blank keys/values defensively.
            requestHeaders = if (directUrl != null) {
                behaviorHints?.proxyHeaders?.request
                    ?.filterKeys { it.isNotBlank() }
                    ?.filterValues { it.isNotBlank() }
                    ?: emptyMap()
            } else {
                emptyMap()
            },
        )
    }

    /** A stable synthetic info-hash for a direct (non-torrent) URL = SHA-1(url) hex. Lets a direct
     *  stream key into all the existing infoHash bookkeeping (resume/history/failover) like a torrent. */
    private fun syntheticHash(url: String): String =
        java.security.MessageDigest.getInstance("SHA-1")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

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
        /** Per-addon resolve timeout — a slow/dead auto-discovered addon can't stall the whole resolve. */
        const val ADDON_QUERY_TIMEOUT_MS = 10_000L
        // 👤 followed by an optional space and a seeder count.
        val SEEDER_EMOJI_REGEX = Regex("""👤\s*(\d+)""")
        val SEEDER_WORD_REGEX = Regex("""(?i)seed(?:er)?s?\s*[:=]?\s*(\d+)""")

        // e.g. "💾 2.1 GB" / "Size: 700 MB" / "1,5 GiB"
        val SIZE_REGEX = Regex("""(\d+(?:[.,]\d+)?)\s*(TB|TiB|GB|GiB|MB|MiB|KB|KiB)""", RegexOption.IGNORE_CASE)

        /** Common public BitTorrent trackers appended to every magnet for faster peer discovery. */
        // Live, high-population trackers (ngosang trackers_best snapshot). Dead trackers were pruned
        // (9.rarbg, internetwarriors, leechers-paradise, coppersurfer, harry.lu, dler, i2p.rocks):
        // each dead UDP tracker wastes an 8s connect timeout before falling through to DHT, slowing
        // the metadata fetch. libtorrent honours these for ut_metadata, so a live tracker returns
        // peers in ~1-2s vs a cold DHT. DHT stays on as the backstop.
        val TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.demonii.com:1337/announce",
            "udp://open.tracker.cl:1337/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://open.stealth.si:80/announce",
            "udp://explodie.org:6969/announce",
            "udp://tracker.gbitt.info:80/announce",
            "udp://opentracker.io:6969/announce",
            "https://tracker.gbitt.info:443/announce",
        )
    }
}
