package com.slickstream.data.source

import com.slickstream.core.model.MediaDetails
import com.slickstream.core.model.MediaType
import com.slickstream.data.source.dto.CinemetaMetaDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where to find one title on IMDB-keyed services (Stremio stream addons, the tracker fallback,
 * opensubtitles). [season] is in IMDB's numbering, which can differ from TMDB's — see [ImdbIdResolver].
 */
data class ImdbCoords(
    val imdbId: String,
    val season: Int?,
    val episode: Int?,
    /** IMDB's series name when the id was found by title (TMDB had none); null otherwise. */
    val seriesName: String? = null,
)

/**
 * The ONE place a TMDB (season, episode) is translated into IMDB coordinates. The app keeps TMDB
 * numbering everywhere else (UI, progress, next-episode, history, download keys); only the calls to
 * IMDB-keyed services go through here.
 *
 * Measured incident: "Monster: The Lizzie Borden Story" (TMDB tv 299939) showed "No IMDB id … cannot
 * resolve sources" on its 2026-09-17 release day. TMDB's imdb_id is "" for every Netflix Monster entry,
 * yet Torrentio answers tt13207736:4:1 with the Lizzie releases — IMDB files the anthology as ONE series
 * whose season 4 is TMDB's "Lizzie season 1". Using the bare id without the season remap is worse than
 * failing: tt13207736:1:1 is DAHMER (opensubtitles returned Dahmer subs), i.e. silently the wrong show.
 *
 * Titles WITH a TMDB imdb id return immediately with their coordinates untouched and ZERO network, so
 * everything that resolved before behaves exactly as it did. For the blank-id case we ask Cinemeta
 * (Stremio's IMDB metadata addon) and pair seasons by premiere date via [ImdbSeasonMatcher], which
 * refuses anything ambiguous: no mapping keeps today's "no sources" message, a wrong one plays the wrong
 * show.
 */
@Singleton
class ImdbIdResolver @Inject constructor(
    private val cinemeta: CinemetaApi,
) {

    /**
     * IMDB coordinates for [details] at TMDB ([season], [episode]), or null when none can be established
     * (blank TMDB id and no confident Cinemeta match, or a TMDB season the IMDB map does not cover).
     */
    suspend fun coordinates(details: MediaDetails, season: Int?, episode: Int?): ImdbCoords? {
        details.imdbId?.takeIf { it.isNotBlank() }?.let { return ImdbCoords(it, season, episode) }
        val tmdbId = details.item.id
        return when (details.item.mediaType) {
            MediaType.TV -> {
                // Nothing dated to pair on (details not fully loaded, or TMDB has no season dates): skip
                // the network AND the memo, so a later call with full details is not blocked by a null.
                if (details.seasons.none { it.seasonNumber > 0 && it.airDate?.let { d -> ImdbSeasonMatcher.epochDay(d) } != null }) {
                    return null
                }
                val key = "TV:$tmdbId"
                var mapping = memoized(key) { lookupTv(details) } ?: return null
                if (season == null || episode == null) {
                    ImdbCoords(mapping.imdbId, null, null, mapping.seriesName)
                } else {
                    var imdbSeason = mapping.imdbSeasonFor(season)
                    // A season the cached map doesn't cover may simply be NEWER than the map (a renewed
                    // show whose next season premiered after we cached it for 12h) — the release-day
                    // "no sources" bug all over again. Re-ask once the map is old enough to be stale.
                    if (imdbSeason == null && expireIfOlderThan(key, SEASON_REFRESH_NANOS)) {
                        mapping = memoized(key) { lookupTv(details) } ?: return null
                        imdbSeason = mapping.imdbSeasonFor(season)
                    }
                    ImdbCoords(mapping.imdbId, imdbSeason ?: return null, episode, mapping.seriesName)
                }
            }
            MediaType.MOVIE -> {
                // matchMovie requires a year; without one there is no point spending a request.
                if (movieYear(details) == null) return null
                memoized("MOVIE:$tmdbId") { lookupMovie(details) }?.let { ImdbCoords(it, null, null) }
            }
        }
    }

    // --- memo + in-flight dedupe ------------------------------------------------------------------

    private class Memo(val value: Any?, val storedAtNanos: Long, val ttlNanos: Long)

    /** Thrown by a lookup whose null answer is NOT a verdict — a timeout or a failed Cinemeta call. It is
     *  remembered only briefly, so an outage doesn't pass for "this title has no IMDB entry" for minutes. */
    private class TransientLookupFailure : Exception()

    private val mutex = Mutex()
    private val memo = HashMap<String, Memo>()
    private val inFlight = HashMap<String, Deferred<Any?>>()

    /** App-lifetime scope (this is a singleton): details prewarm and player startup ask for the same
     *  title concurrently, and either may be cancelled while the other still waits. The lookup lives
     *  here so cancelling one waiter never cancels the shared Cinemeta round-trip — and its result is
     *  memoized even if every waiter has left, so the next open is instant. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Suppress("UNCHECKED_CAST") // each key prefix ("TV:"/"MOVIE:") is only ever stored with one T
    private suspend fun <T : Any> memoized(key: String, lookup: suspend () -> T?): T? {
        val shared = mutex.withLock {
            val now = System.nanoTime()
            memo.entries.removeAll { (_, m) -> now - m.storedAtNanos >= m.ttlNanos }
            memo[key]?.let { return it.value as T? }
            inFlight[key] ?: scope.async {
                val (result: Any?, ttl: Long) = try {
                    val r = lookup()
                    r to ttlFor(r)
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { mutex.withLock { inFlight.remove(key) } }
                    throw e
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "$key lookup failed: ${t.javaClass.simpleName}")
                    null to TRANSIENT_TTL_NANOS
                }
                mutex.withLock {
                    memo[key] = Memo(result, System.nanoTime(), ttl)
                    inFlight.remove(key)
                }
                result
            }.also { inFlight[key] = it }
        }
        return try {
            shared.await() as T?
        } catch (e: CancellationException) {
            // Rethrow if THIS caller was cancelled; if only the shared lookup was, answer "unknown".
            currentCoroutineContext().ensureActive()
            null
        }
    }

    private fun ttlFor(value: Any?): Long = if (value != null) SUCCESS_TTL_NANOS else MISS_TTL_NANOS

    /** Drops [key]'s memo if it is at least [ageNanos] old; true when it did (the caller may re-ask). */
    private suspend fun expireIfOlderThan(key: String, ageNanos: Long): Boolean = mutex.withLock {
        val m = memo[key] ?: return@withLock false
        if (System.nanoTime() - m.storedAtNanos < ageNanos) return@withLock false
        memo.remove(key)
        true
    }

    // --- TV -------------------------------------------------------------------------------------------

    private suspend fun lookupTv(details: MediaDetails): ImdbSeasonMatcher.Mapping? {
        val title = details.item.title.trim()
        if (title.isBlank()) return null
        val tmdbSeasons = details.seasons.map {
            ImdbSeasonMatcher.TmdbSeason(number = it.seasonNumber, airDate = it.airDate, episodeCount = it.episodeCount)
        }
        val failed = AtomicBoolean(false)
        var finished = false
        val mapping = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
            // The full TMDB title finds the series for all four measured Monster entries. The prefix
            // retries cover TMDB titles that decorate IMDB's series name ("Show: The X Story",
            // "DAHMER - Monster: …"); matching still checks the FULL title, which is the stricter side.
            val queries = listOf(title) + shortTitles(title)
            var found: ImdbSeasonMatcher.Mapping? = null
            for (q in queries) {
                found = matchTv(q, title, tmdbSeasons, failed)
                if (found != null) break
            }
            finished = true
            found
        }
        // No answer because Cinemeta was slow or failing is not "no IMDB entry": keep it out of the
        // 10-minute miss memo, or a two-second blip blocks the title for ten minutes.
        if (mapping == null && (!finished || failed.get())) {
            android.util.Log.i(TAG, "TMDB tv ${details.item.id}: lookup incomplete (timeout or Cinemeta error)")
            throw TransientLookupFailure()
        }
        if (mapping == null) {
            android.util.Log.i(TAG, "TMDB tv ${details.item.id}: no confident IMDB mapping")
        } else {
            android.util.Log.i(
                TAG,
                "TMDB tv ${details.item.id} -> ${mapping.imdbId} seasons=${mapping.seasonMap} identity=${mapping.identity}",
            )
        }
        return mapping
    }

    private suspend fun matchTv(
        query: String,
        tmdbTitle: String,
        tmdbSeasons: List<ImdbSeasonMatcher.TmdbSeason>,
        failed: AtomicBoolean,
    ): ImdbSeasonMatcher.Mapping? {
        val metas = search("series", query, failed)
        val picks = metas.asSequence()
            .mapNotNull { m -> imdbIdOf(m)?.let { id -> id to m.name.orEmpty() } }
            .filter { (_, name) -> ImdbSeasonMatcher.titlesCompatible(tmdbTitle, name) }
            .distinctBy { it.first }
            .take(MAX_CANDIDATES)
            .toList()
        if (picks.isEmpty()) return null
        val candidates = coroutineScope {
            picks.map { (id, searchName) ->
                async {
                    val meta = fetchSeriesMeta(id, failed) ?: return@async null
                    ImdbSeasonMatcher.Candidate(
                        imdbId = id,
                        name = meta.name?.takeIf { it.isNotBlank() } ?: searchName,
                        episodes = meta.videos.mapNotNull { v ->
                            val s = v.season ?: return@mapNotNull null
                            val e = v.episode ?: v.number ?: return@mapNotNull null
                            ImdbSeasonMatcher.CandidateEpisode(season = s, episode = e, released = v.released ?: v.firstAired)
                        },
                    )
                }
            }.awaitAll().filterNotNull()
        }
        return ImdbSeasonMatcher.match(tmdbTitle, tmdbSeasons, candidates)
    }

    private suspend fun fetchSeriesMeta(imdbId: String, failed: AtomicBoolean): CinemetaMetaDto? = try {
        cinemeta.meta("${CinemetaApi.BASE_URL}meta/series/$imdbId.json").meta
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        failed.set(true)
        null
    }

    // --- Movie ----------------------------------------------------------------------------------------

    private suspend fun lookupMovie(details: MediaDetails): String? {
        val title = details.item.title.trim()
        val year = movieYear(details)
        if (title.isBlank() || year == null) return null
        val failed = AtomicBoolean(false)
        var finished = false
        val id = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
            val candidates = search("movie", title, failed).mapNotNull { m ->
                val id = imdbIdOf(m) ?: return@mapNotNull null
                ImdbSeasonMatcher.MovieCandidate(
                    imdbId = id,
                    name = m.name.orEmpty(),
                    year = leadingYear(m.releaseInfo) ?: leadingYear(m.yearText) ?: leadingYear(m.released),
                )
            }
            ImdbSeasonMatcher.matchMovie(title, year, candidates).also { finished = true }
        }
        if (id == null && (!finished || failed.get())) throw TransientLookupFailure()
        android.util.Log.i(TAG, "TMDB movie ${details.item.id} -> ${id ?: "no confident IMDB match"}")
        return id
    }

    private fun movieYear(details: MediaDetails): Int? = leadingYear(details.item.releaseDate)

    // --- shared -----------------------------------------------------------------------------------

    private suspend fun search(type: String, query: String, failed: AtomicBoolean): List<CinemetaMetaDto> = try {
        val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        cinemeta.searchCatalog("${CinemetaApi.BASE_URL}catalog/$type/top/search=$q.json").metas
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        failed.set(true)
        emptyList()
    }

    /** Some search metas carry only "imdb_id", others only "id" — prefer imdb_id, accept either if tt. */
    private fun imdbIdOf(m: CinemetaMetaDto): String? =
        m.imdbId?.trim()?.takeIf { IMDB_ID.matches(it) } ?: m.id?.trim()?.takeIf { IMDB_ID.matches(it) }

    /** Distinct prefixes before the first ':' and before the first dash separator, e.g.
     *  "Monster: The Lizzie Borden Story" -> ["Monster"]. */
    private fun shortTitles(title: String): List<String> {
        val beforeColon = title.substringBefore(':', missingDelimiterValue = "")
        val dash = DASH_SEPARATOR.find(title)?.range?.first
        val beforeDash = if (dash != null) title.substring(0, dash) else ""
        return listOf(beforeColon, beforeDash)
            .map { it.trim() }
            .filter { it.isNotBlank() && it != title }
            .distinct()
    }

    private fun leadingYear(text: String?): Int? =
        text?.trim()?.takeIf { it.length >= 4 }?.substring(0, 4)?.toIntOrNull()?.takeIf { it in 1870..2100 }

    private companion object {
        const val TAG = "ImdbIdResolver"
        /** Whole-lookup budget: the play button is waiting on this, and a miss only costs the fallback
         *  message the title already showed before this resolver existed. */
        const val LOOKUP_TIMEOUT_MS = 8_000L
        const val MAX_CANDIDATES = 5
        const val SUCCESS_TTL_NANOS = 12L * 60 * 60 * 1_000_000_000
        /** A miss is retried sooner: a title that premiered today may reach Cinemeta within minutes. */
        const val MISS_TTL_NANOS = 10L * 60 * 1_000_000_000
        /** A timeout or Cinemeta error: long enough to absorb the prewarm + play burst, no longer. */
        const val TRANSIENT_TTL_NANOS = 30L * 1_000_000_000
        /** How old a cached map must be before a season it doesn't cover triggers one re-lookup. */
        const val SEASON_REFRESH_NANOS = 30L * 60 * 1_000_000_000
        val IMDB_ID = Regex("^tt\\d+$")
        /** " - ", " – ", " — ": TMDB's measured "DAHMER - Monster: …" uses a hyphen; en/em dashes
         *  appear in other TMDB titles. */
        val DASH_SEPARATOR = Regex("\\s[-\u2013\u2014]\\s")
    }
}
