package com.slickstream.data.source

import com.slickstream.core.common.Indexer
import com.slickstream.core.model.DataResult
import com.slickstream.core.model.MediaDetails
import com.slickstream.core.model.MediaType
import com.slickstream.core.model.StreamSource
import com.slickstream.core.repository.SourceRepository
import com.slickstream.data.settings.SettingsRepository
import com.slickstream.data.source.dto.StreamDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val settingsRepository: SettingsRepository,
    private val sourceStatusStore: SourceStatusStore,
) : SourceRepository {

    /** Successful resolves are briefly reusable by details prewarm + player startup. [inFlight] also
     *  makes those overlapping calls share one addon fan-out instead of issuing the same dozen HTTP
     *  requests twice. Failures are never cached. */
    private val resolveMutex = Mutex()
    private val resolveCache = mutableMapOf<ResolveKey, CachedResolve>()
    /** Repository-owned because one caller (usually details prewarm) may be cancelled while another
     *  (the player) is awaiting the same resolve. Cancelling either waiter must not cancel their shared
     *  network request out from under the other. The repository is an app-lifetime singleton. */
    private val resolveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = mutableMapOf<ResolveKey, SharedResolve>()

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

        val customBases = try {
            settingsRepository.current().customSourceUrl
                .split(",").map { it.trim() }.filter { it.isNotBlank() }.map(::normalizeBase)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }
        val autoBases = try {
            addonRegistry.streamingBaseUrls()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }
        // Include the complete addon set without retaining a token-bearing custom URL in the key.
        val addonFingerprint = syntheticHash((customBases + baseUrls + autoBases).distinct().joinToString("\n"))
        val key = ResolveKey(type, id, addonFingerprint)
        val shared = resolveMutex.withLock {
            val now = System.nanoTime()
            resolveCache.entries.removeAll { now - it.value.storedAtNanos >= RESOLVE_CACHE_TTL_NANOS }
            resolveCache[key]?.let { return it.result }
            inFlight[key]?.also { it.waiters++ } ?: SharedResolve(
                deferred = resolveScope.async(start = CoroutineStart.LAZY) {
                    resolveAndCache(key, details, type, id, customBases, autoBases)
                },
                waiters = 1,
            ).also { inFlight[key] = it }
        }
        shared.deferred.start()
        // Await remains cancellable for this caller, but the app-scope request can still satisfy other
        // waiters and populate the short cache. If every waiter leaves, cancel the fan-out immediately
        // so rapid Details browsing cannot fill OkHttp with abandoned addon requests.
        return try {
            shared.deferred.await()
        } finally {
            val cancel = resolveMutex.withLock {
                val current = inFlight[key]
                if (current !== shared) {
                    false
                } else {
                    current.waiters--
                    if (current.waiters <= 0) {
                        inFlight.remove(key, current)
                        !current.deferred.isCompleted
                    } else {
                        false
                    }
                }
            }
            if (cancel) shared.deferred.cancel()
        }
    }

    private suspend fun resolveAndCache(
        key: ResolveKey,
        details: MediaDetails,
        type: String,
        id: String,
        customBases: List<String>,
        autoBases: List<String>,
    ): DataResult<List<StreamSource>> {
        try {
            val result = resolveUncached(details, type, id, customBases, autoBases)
            if (result is DataResult.Success) {
                resolveMutex.withLock {
                    resolveCache[key] = CachedResolve(result, System.nanoTime())
                }
                // FREE availability signal: every real resolution (details open, prewarm, play) records
                // whether this title has anything to play, so the catalog can stop headlining dead
                // titles. Success-only — a network error says nothing about availability.
                // Also derive a CAM-only verdict: among the sources a user could actually PLAY, are they
                // ALL cinema-cams? If so the card gets a "CAM" badge. Basing it on playable rows (not the
                // whole list) means a lone unplayable junk row can't hide a real CAM-only title, and a
                // single real WEB/BluRay release clears the badge.
                val playable = result.data.filter { it.playable }
                val camOnly = playable.isNotEmpty() && playable.all { it.isCam }
                sourceStatusStore.record(
                    details.item.id, details.item.mediaType, result.data.isNotEmpty(), camOnly,
                )
            }
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return DataResult.Error(
                "Failed to resolve sources for \"${details.item.title}\": ${t.message ?: "unknown error"}",
                t,
            )
        }
    }

    private suspend fun resolveUncached(
        details: MediaDetails,
        type: String,
        id: String,
        customBases: List<String>,
        autoBases: List<String>,
    ): DataResult<List<StreamSource>> {
        return try {
            // Query every CONFIGURED indexer addon PLUS the auto-discovered, health-checked free
            // streaming addons (AddonRegistry — kept current automatically, no manual URLs) in parallel.
            // One being down/slow never blocks the others (per-addon timeout + getOrNull). Merge + de-dupe
            // by info-hash (keep the row with the most seeders).
            // The user's OWN configured source (e.g. a debrid-backed Torrentio that returns instant
            // cached direct streams) is queried FIRST, then the built-in indexer, then the
            // auto-discovered free addons. Paste-tolerant: a ".../manifest.json" install URL is
            // normalised to the query base.
            val allBases = (customBases + baseUrls + autoBases).distinct()
            val responses = coroutineScope {
                allBases.map { base ->
                    async {
                        // Auto-discovered file-server addons are optional enrichment. One dead entry
                        // must not hold a ready Torrentio/RD result for the old blanket 10-second timeout.
                        val optionalAuto = base in autoBases && base !in customBases && base !in baseUrls
                        val timeout = if (optionalAuto) AUTO_ADDON_QUERY_TIMEOUT_MS else ADDON_QUERY_TIMEOUT_MS
                        kotlinx.coroutines.withTimeoutOrNull(timeout) {
                            // RETRY TRANSIENT INDEXER FAILURES. Torrentio answers a cold-cache episode by
                            // scraping upstream trackers, and when that scrape fails it returns 502 — measured
                            // live: one episode 502'd twelve times running while the NEXT episode 502'd once
                            // and then served 200 seconds later. A single attempt turned that into "no
                            // streamable sources" for a title with hundreds of real releases, which is not a
                            // slow answer but a WRONG one.
                            // The retry costs nothing on the healthy path (it only runs after a failure) and
                            // stays inside the existing per-addon timeout, so a dead addon still can't hold up
                            // the ones that answered. 4xx is a real reply ("this addon has nothing for that
                            // id") and is NOT retried — hammering it would just burn the budget.
                            var attempt = 0
                            var response: com.slickstream.data.source.dto.StreamResponseDto? = null
                            while (attempt < INDEXER_ATTEMPTS) {
                                response = try {
                                    api.getStreamsAt("${base}stream/$type/$id.json")
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (t: Throwable) {
                                    if (!isTransientIndexerFailure(t)) return@withTimeoutOrNull null
                                    null
                                }
                                if (response != null) break
                                attempt++
                                if (attempt < INDEXER_ATTEMPTS) {
                                    kotlinx.coroutines.delay(INDEXER_RETRY_DELAY_MS * attempt)
                                }
                            }
                            response
                        }
                    }
                }.awaitAll()
            }
            val ok = responses.filterNotNull()
            if (ok.isEmpty()) {
                // EVERY addon failed (after retries). Say that, rather than implying the title has no
                // releases: "no streamable sources" for a show with hundreds of them reads as a broken
                // app and sends the user hunting for a fault on their side. This is also why the result
                // is an Error and never an empty Success — an empty Success would be recorded by
                // resolveAndCache as a genuine "nothing to play" verdict and suppress the title from
                // browse for 12h on the strength of an indexer outage.
                return DataResult.Error(
                    "Couldn't reach the source index for \"${details.item.title}\" — it's not responding " +
                        "right now. This is the indexer, not your connection. Try again shortly.",
                )
            }
            // Title-aware language detection needs the title at MAP time so a language name that's part
            // of the title ("The French Dispatch") isn't read as a foreign-audio tag.
            val movieTitle = details.item.title
            val idParts = id.split(':')
            val expectedSeason = idParts.getOrNull(1)?.toIntOrNull()
            val expectedEpisode = idParts.getOrNull(2)?.toIntOrNull()
            val sources = ok
                .flatMap { it.streams }
                .mapNotNull { it.toStreamSource(movieTitle, expectedSeason, expectedEpisode) }
                .groupBy { it.infoHash }
                // A cached debrid URL and a torrent fallback can share an info-hash. Keep the direct
                // one even if a duplicate torrent row reports more seeders; seeders are irrelevant to
                // an already-hosted file and dropping it defeats file-server-first playback.
                .map { (_, rows) ->
                    rows.maxWithOrNull(
                        compareBy<StreamSource> { it.isDirect }.thenBy { it.seeders ?: 0 },
                    )!!
                }
                // Direct (file-server) streams first — they play instantly with no swarm — then torrents
                // by health, so the manual source list matches the auto-pick's file-server-first order.
                .sortedWith(compareByDescending<StreamSource> { it.isDirect }.thenByDescending { it.rank })
            // Keep every viable transport for manual selection and late failover. StreamPicker applies
            // English preference softly, so an obscure title is never stranded just because its only
            // healthy release is tagged as foreign/original audio.
            DataResult.Success(sources)
        } catch (e: CancellationException) {
            throw e
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

    /** Normalise a user-pasted addon URL into a query base: drop a trailing manifest.json and ensure a
     *  trailing '/'. Users paste the ".../manifest.json" install URL; we query {base}stream/{type}/{id}.json. */
    private fun normalizeBase(url: String): String =
        url.substringBefore("manifest.json").let { if (it.endsWith("/")) it else "$it/" }

    /** Map one indexer row to a [StreamSource], or null if it's neither a torrent nor a direct URL. */
    private fun StreamDto.toStreamSource(movieTitle: String, season: Int?, episode: Int?): StreamSource? {
        // Pool every text field that may carry quality / seeders / size / codec metadata (the codec
        // and container often live in the filename, e.g. "…XviD-MAXX.avi", so include it).
        val haystack = listOfNotNull(name, title, description, behaviorHints?.bingeGroup, behaviorHints?.filename)
            .joinToString("\n")

        // Stremio addons sometimes expose an HTTP *action* that asks the debrid service to download an
        // uncached torrent. It is not a playable file URL. When the row also carries a hash, preserve
        // the useful torrent fallback and ignore the action URL; without a hash there is nothing this
        // app can play, so discard it. `notWebReady` is NOT torrent evidence: Stremio also sets it for
        // valid HLS/header-proxied direct streams which Android can play.
        val rawUrl = url?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val declaredHash = infoHash?.trim()?.let(::canonicalInfoHash)
        // Stremio `dht:` sources are peer/node hints, not torrent identities. Treating a 40-hex node ID
        // as BTIH creates a perfectly valid-looking magnet for the wrong/nonexistent torrent.
        val torrentHash = declaredHash
        val isDebridAction = DEBRID_DOWNLOAD_ACTION.containsMatchIn(haystack)
        if (isDebridAction && torrentHash == null) return null
        val forceTorrent = torrentHash != null && isDebridAction
        val directUrl = rawUrl.takeUnless { forceTorrent }
        // Direct playback identity is the URL, not its underlying torrent hash. RD can return several
        // independently expiring URLs plus a P2P fallback for one hash; keying all of them by that hash
        // collapsed the list to one dead link and made failover impossible.
        val hash = directUrl?.let { syntheticHash(it) } ?: torrentHash ?: return null

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
            magnetUri = if (directUrl != null) "" else buildMagnet(hash, displayName, sources),
            infoHash = hash,
            quality = parseQuality(haystack),
            sizeBytes = behaviorHints?.videoSize ?: parseSize(haystack),
            seeders = parseSeeders(haystack),
            provider = parseProvider(name),
            fileIndex = fileIdx,
            expectedSeason = season,
            expectedEpisode = episode,
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

    private fun buildMagnet(infoHash: String, displayName: String, sources: List<String>): String {
        val sb = StringBuilder("magnet:?xt=urn:btih:").append(infoHash)
        val dn = displayName.lineSequence().firstOrNull()?.trim().orEmpty()
        if (dn.isNotEmpty()) {
            sb.append("&dn=").append(URLEncoder.encode(dn, "UTF-8"))
        }
        // Addon-provided trackers are often private/specialised and can be the only peers for an old
        // release. Preserve them ahead of the public fallback set.
        val addonTrackers = sources.mapNotNull(::trackerFromSource)
        for (tracker in (addonTrackers + TRACKERS).distinct()) {
            sb.append("&tr=").append(URLEncoder.encode(tracker, "UTF-8"))
        }
        return sb.toString()
    }

    private fun trackerFromSource(source: String): String? {
        if (!source.startsWith("tracker:", ignoreCase = true)) return null
        return source.substringAfter(':').trim().takeIf {
            it.startsWith("http://", ignoreCase = true) ||
                it.startsWith("https://", ignoreCase = true) ||
                it.startsWith("udp://", ignoreCase = true)
        }
    }

    /** Normalize hex/base32 v1 BTIHs to the engine/cache's one lowercase 40-hex identity. */
    private fun canonicalInfoHash(value: String): String? {
        val hash = value.trim()
        if (HEX_INFO_HASH.matches(hash)) return hash.lowercase(Locale.ROOT)
        if (!BASE32_INFO_HASH.matches(hash)) return null
        var buffer = 0
        var bits = 0
        val out = ByteArray(20)
        var outIndex = 0
        for (ch in hash.uppercase(Locale.ROOT)) {
            val digit = BASE32_ALPHABET.indexOf(ch)
            if (digit < 0) return null
            buffer = (buffer shl 5) or digit
            bits += 5
            if (bits >= 8) {
                bits -= 8
                if (outIndex >= out.size) return null
                out[outIndex++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }
        if (outIndex != out.size) return null
        return out.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
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

    /**
     * Worth another attempt? A 5xx or a network/timeout error means the indexer could not ANSWER — the
     * cold-cache-scrape 502 that makes a well-seeded episode look sourceless. A 4xx is a real answer
     * ("no such id here"), and retrying it just burns the per-addon budget that the addons which DID
     * respond are waiting on. Anything unrecognised is treated as transient: the cost of one extra
     * request is far lower than falsely telling the user a title has no releases.
     */
    private fun isTransientIndexerFailure(t: Throwable): Boolean = when (t) {
        is retrofit2.HttpException -> t.code() >= 500
        is java.io.IOException -> true               // socket/DNS/TLS/timeout
        else -> true
    }

    private companion object {
        data class ResolveKey(val type: String, val id: String, val addonFingerprint: String)
        data class SharedResolve(
            val deferred: Deferred<DataResult<List<StreamSource>>>,
            var waiters: Int,
        )
        data class CachedResolve(
            val result: DataResult.Success<List<StreamSource>>,
            val storedAtNanos: Long,
        )

        const val RESOLVE_CACHE_TTL_NANOS = 30_000_000_000L
        /** Per-addon resolve timeout — a slow/dead auto-discovered addon can't stall the whole resolve. */
        /** Raised from 8s to fit the transient-failure retries below; a healthy addon is unaffected
         *  (it answers on the first attempt and the budget is never touched). */
        const val ADDON_QUERY_TIMEOUT_MS = 12_000L
        const val AUTO_ADDON_QUERY_TIMEOUT_MS = 2_500L

        /** Attempts per addon before giving up on it — see the transient-502 comment at the call site. */
        const val INDEXER_ATTEMPTS = 3

        /** Linear backoff between attempts (x1, x2). Short: a cold-cache scrape either recovers quickly
         *  or is genuinely broken, and the whole resolve is latency-critical. */
        const val INDEXER_RETRY_DELAY_MS = 400L
        val HEX_INFO_HASH = Regex("(?i)^[a-f0-9]{40}$")
        val BASE32_INFO_HASH = Regex("(?i)^[a-z2-7]{32}$")
        const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        /** Torrentio/debrid rows whose URL initiates an uncached download instead of serving video. */
        val DEBRID_DOWNLOAD_ACTION = Regex(
            "(?i)(\\b(?:rd|ad|pm)\\s*download\\b|" +
                "\\bdownload\\s+to\\s+(?:your\\s+)?(?:real[\\s-]?debrid|all[\\s-]?debrid|" +
                "premiumize|debrid)\\b|\\bdebrid\\s+(?:download|action)\\b)",
        )
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
