package com.slickstream.data.source

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Maps a TMDB show (title + dated seasons) onto an IMDB series and its season numbering, for titles
 * whose TMDB record carries NO imdb id.
 *
 * Measured incident: "Monster: The Lizzie Borden Story" (TMDB tv 299939, one season, 8 eps, premiere
 * 2026-09-17) said "No IMDB id … cannot resolve sources" on release day. TMDB's external_ids.imdb_id is
 * "" for ALL four Netflix Monster entries, each a separate one-season TMDB show, while IMDB/Cinemeta —
 * and therefore Torrentio and opensubtitles — file them as ONE series, tt13207736 S1 (Dahmer) … S4
 * (Lizzie Borden). The id alone is not enough: opensubtitles for tt13207736:1:1 returns DAHMER subs, so
 * TMDB "season 1" must become IMDB season 4 or the user silently gets the wrong show.
 *
 * The only field both catalogues agree on is the season PREMIERE DATE, so seasons are paired by date
 * (±[DAY_WINDOW] days: Cinemeta stores UTC timestamps, so a US-evening premiere lands a day later than
 * TMDB's local date) and the result is refused whenever it is ambiguous. A wrong mapping plays the
 * wrong show; no mapping just keeps today's "no sources" message.
 *
 * PURE Kotlin (no android.*, no coroutines, no java.time — minSdk 24 without desugaring) so it is
 * unit-tested on the JVM.
 */
object ImdbSeasonMatcher {

    data class TmdbSeason(val number: Int, val airDate: String?, val episodeCount: Int)
    data class CandidateEpisode(val season: Int, val episode: Int, val released: String?)
    data class Candidate(val imdbId: String, val name: String, val episodes: List<CandidateEpisode>)

    /**
     * [seasonMap] is TMDB season → IMDB season. An [identity] map (every pair tmdb == imdb — an ordinary
     * show whose numbering simply agrees) extrapolates to TMDB seasons that were undated or not yet on
     * Cinemeta. A NON-identity map (anthology / offset numbering) never extrapolates: an unmapped season
     * returns null, because guessing "season 2 → 5" is how you end up streaming a different story.
     */
    data class Mapping(
        val imdbId: String,
        val seasonMap: Map<Int, Int>,
        val identity: Boolean,
        /** IMDB's name for the series ("Monster"), for [entryTokens]. */
        val seriesName: String = "",
        /** TMDB seasons that WERE dated but found no (or no unambiguous) IMDB premiere. Identity
         *  extrapolation is for seasons nothing could be checked against — never for ones that failed. */
        val refused: Set<Int> = emptySet(),
    ) {
        /** Identity extrapolation only reaches seasons NEWER than every mapped one (not on Cinemeta yet).
         *  An undated season sitting between mapped ones can't be checked — TMDB and IMDB may split
         *  episodes differently there (measured: One Piece TMDB S1 = 61 eps, IMDB S1 = 8). */
        fun imdbSeasonFor(tmdbSeason: Int): Int? = seasonMap[tmdbSeason]
            ?: if (identity && tmdbSeason !in refused && tmdbSeason > (seasonMap.keys.maxOrNull() ?: 0)) tmdbSeason else null
    }

    data class MovieCandidate(val imdbId: String, val name: String, val year: Int?)

    /** A season premiere this many days either side of TMDB's still counts as the same season. */
    private const val DAY_WINDOW = 2L

    private val IMDB_ID = Regex("^tt\\d+$")
    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val APOSTROPHES = Regex("['’`]")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
    private val STOPWORDS = setOf("the", "a", "an", "of", "and")
    /** Words every anthology entry shares ("The X Story"), so they can't tell entries apart. */
    private val GENERIC_ENTRY_WORDS = setOf("story", "series", "season", "show", "complete", "saga", "part", "limited", "miniseries")

    /**
     * Picks the IMDB series (and its season map) that [tmdbSeasons] belong to, or null when no
     * candidate matches or two match equally well (ambiguity is refused, never guessed).
     */
    fun match(tmdbTitle: String, tmdbSeasons: List<TmdbSeason>, candidates: List<Candidate>): Mapping? {
        // Season 0 is "Specials" on both sides and is dated inconsistently; it never anchors a match.
        val dated = tmdbSeasons.asSequence()
            .filter { it.number > 0 }
            .mapNotNull { s -> s.airDate?.let { epochDay(it) }?.let { day -> DatedSeason(s.number, day, s.episodeCount) } }
            .distinctBy { it.number }
            .toList()
        if (dated.isEmpty()) return null
        val lowest = dated.minOf { it.number }

        val valid = candidates.asSequence()
            .filter { IMDB_ID.matches(it.imdbId) && titlesCompatible(tmdbTitle, it.name) }
            .distinctBy { it.imdbId }
            .mapNotNull { c ->
                val map = seasonMap(dated, c)
                // Must map the show's FIRST dated season: a candidate that only lines up with some later
                // season by coincidence of date is not the same show.
                if (map.isEmpty() || lowest !in map) return@mapNotNull null
                // An OFFSET map (TMDB S1 -> IMDB S4) must also agree on episode counts. A companion
                // show premiering the same night as its parent's season passes the date check alone —
                // measured: "Cobra Kai: Inside the Dojo" (3 eps, 2024-07-18) mapped onto Cobra Kai S6
                // (15 eps, same day) and played Cobra Kai. The Monster stories match exactly (8/9/8/8).
                if (map.any { (tmdb, imdb) -> tmdb != imdb }) {
                    val imdbCounts = c.episodes.filter { it.season > 0 }.groupBy { it.season }
                        .mapValues { (_, eps) -> eps.map { it.episode }.distinct().size }
                    val tmdbCounts = dated.associate { it.number to it.episodeCount }
                    if (map.any { (tmdb, imdb) -> tmdbCounts[tmdb] != imdbCounts[imdb] }) return@mapNotNull null
                }
                Scored(c, map, closeness(tmdbTitle, c.name))
            }
            .toList()
        if (valid.isEmpty()) return null

        val mostSeasons = valid.maxOf { it.map.size }
        val widest = valid.filter { it.map.size == mostSeasons }
        val chosen = widest.singleOrNull() ?: run {
            val bestScore = widest.maxOf { it.score }
            widest.filter { it.score == bestScore }.singleOrNull() ?: return null
        }
        // A winner that only CONTAINS the TMDB title loses to an IMDB entry named exactly like it, even
        // one that couldn't be evaluated (no episodes listed yet, or its meta failed). Measured: a
        // release-day "9-1-1: Nashville" whose own entry had no episodes yet mapped onto 9-1-1 S9
        // (same premiere night, same 18 episodes) and every source was 9-1-1.
        if (chosen.score == 1 && candidates.any { it.imdbId != chosen.candidate.imdbId && closeness(tmdbTitle, it.name) >= 2 }) {
            return null
        }
        return Mapping(
            imdbId = chosen.candidate.imdbId,
            seasonMap = chosen.map,
            identity = chosen.map.all { (tmdb, imdb) -> tmdb == imdb },
            seriesName = chosen.candidate.name,
            refused = dated.map { it.number }.toSet() - chosen.map.keys,
        )
    }

    /**
     * The words that name THIS TMDB entry rather than the IMDB series it is filed under: "DAHMER - Monster:
     * The Jeffrey Dahmer Story" under IMDB's "Monster" gives {dahmer, jeffrey}. Empty for an ordinary show
     * whose TMDB and IMDB names agree — no guard needed there.
     *
     * Why: IMDB files all four Netflix Monster stories as ONE series, and every one of them is released
     * as "…S01E01…". Measured live, Torrentio's tt13207736:1:1 (Dahmer) answer is mostly LIZZIE BORDEN
     * releases — so without this, Dahmer would auto-play Lizzie Borden.
     */
    fun entryTokens(tmdbTitle: String, seriesName: String): Set<String> =
        (contentTokens(tmdbTitle) - contentTokens(seriesName) - GENERIC_ENTRY_WORDS)
            .filter { it.length >= 3 && !it.all(Char::isDigit) }
            .toSet()

    /** True when [text] (a release's FILE name) carries at least one of [entryTokens], or there are none. */
    fun namesEntry(text: String, entryTokens: Set<String>): Boolean =
        entryTokens.isEmpty() || contentTokens(text).any { it in entryTokens }

    /**
     * True iff the smaller content-token set (lowercased, accent-folded, stopwords dropped, plural 's'
     * folded) is non-empty and wholly contained in the larger. Deliberately loose — "DAHMER - Monster: The Jeffrey Dahmer
     * Story" must accept IMDB's "Monster" — because the DATE match in [match] is what rejects look-alikes
     * such as "The Story Monster"; this only prunes candidates that share no name at all.
     */
    fun titlesCompatible(tmdbTitle: String, candidateName: String): Boolean {
        val a = contentTokens(tmdbTitle)
        val b = contentTokens(candidateName)
        val (small, large) = if (a.size <= b.size) a to b else b to a
        return small.isNotEmpty() && large.containsAll(small)
    }

    /**
     * Movies have no seasons to cross-check, so the bar is much higher: normalized title EQUALITY and a
     * release year within ±1 of TMDB's (festival vs. wide release). An exact year wins; anything still
     * ambiguous — or a missing TMDB year — returns null.
     */
    fun matchMovie(tmdbTitle: String, tmdbYear: Int?, candidates: List<MovieCandidate>): String? {
        if (tmdbYear == null) return null
        val want = tokens(tmdbTitle)
        if (want.isEmpty()) return null
        // EXACT year only. A one-year window let a brand-new film with no IMDB id adopt last year's
        // same-titled (different) film when the new one wasn't on Cinemeta yet — the wrong movie plays.
        // A missed rescue only costs the message the title showed before this resolver existed.
        return candidates.asSequence()
            .filter { IMDB_ID.matches(it.imdbId) && it.year == tmdbYear }
            .filter { tokens(it.name) == want }
            .distinctBy { it.imdbId }
            .toList()
            .singleOrNull()?.imdbId
    }

    /**
     * Days since 1970-01-01 for the leading "YYYY-MM-DD" of [isoDate] (so both TMDB's "2026-09-17" and
     * Cinemeta's "2026-09-17T07:00:00.000Z" parse), or null when malformed. Howard Hinnant's
     * days_from_civil — no java.time (minSdk 24, no desugaring) and no TimeZone, so it is exact and
     * device-locale independent.
     */
    fun epochDay(isoDate: String): Long? {
        if (isoDate.length < 10 || isoDate[4] != '-' || isoDate[7] != '-') return null
        val y = digits(isoDate, 0, 4) ?: return null
        val m = digits(isoDate, 5, 7) ?: return null
        val d = digits(isoDate, 8, 10) ?: return null
        if (m !in 1..12 || d < 1 || d > daysInMonth(y, m)) return null
        val yy = (if (m <= 2) y - 1 else y).toLong()
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val mp = if (m > 2) m - 3 else m + 9
        val doy = (153L * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    // ---------------------------------------------------------------------------------------------

    private data class DatedSeason(val number: Int, val day: Long, val episodeCount: Int)
    private data class Scored(val candidate: Candidate, val map: Map<Int, Int>, val score: Int)

    /** TMDB season → IMDB season for one candidate; injective (a shared target drops every claimant). */
    private fun seasonMap(dated: List<DatedSeason>, candidate: Candidate): Map<Int, Int> {
        val bySeason = candidate.episodes.filter { it.season > 0 }.groupBy { it.season }
        val premieres = bySeason.mapNotNull { (season, eps) ->
            eps.mapNotNull { e -> e.released?.let { epochDay(it) } }.minOrNull()?.let { season to it }
        }.toMap()
        if (premieres.isEmpty()) return emptyMap()
        val episodeCounts = bySeason.mapValues { (_, eps) -> eps.map { it.episode }.distinct().size }

        val tentative = HashMap<Int, Int>()
        for (s in dated) {
            val near = premieres.filter { (_, day) -> abs(day - s.day) <= DAY_WINDOW }.keys
            val pick = when {
                near.size == 1 -> near.single()
                near.size > 1 -> near.filter { episodeCounts[it] == s.episodeCount }.singleOrNull()
                else -> null
            }
            if (pick != null) tentative[s.number] = pick
        }
        val shared = tentative.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return tentative.filterValues { it !in shared }
    }

    /** Tie-break only: 3 = identical token lists, 2 = identical content tokens, 1 = containment. */
    private fun closeness(tmdbTitle: String, candidateName: String): Int = when {
        tokens(tmdbTitle) == tokens(candidateName) -> 3
        contentTokens(tmdbTitle) == contentTokens(candidateName) -> 2
        else -> 1
    }

    private fun tokens(title: String): List<String> {
        val folded = COMBINING_MARKS.replace(Normalizer.normalize(title, Normalizer.Form.NFD), "")
        return folded.lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace(APOSTROPHES, "")            // "Grey's" == "Greys", not "grey s"
            .replace(NON_ALNUM, " ")
            .split(' ')
            .filter { it.isNotEmpty() }
    }

    /** Stopwords dropped and a plain plural 's' folded: TMDB titles the Menendez entry "MONSTERS: The Lyle
     *  and Erik Menendez Story" while IMDB/Cinemeta calls the series "Monster" (measured), and without the
     *  fold that one entry could never match. Folding both sides keeps the comparison symmetric; "ss"
     *  endings ("Boss") and short words ("Us") are left alone. */
    private fun contentTokens(title: String): Set<String> =
        tokens(title).filterNot { it in STOPWORDS }.map { singular(it) }.toSet()

    private fun singular(token: String): String =
        if (token.length > 3 && token.endsWith('s') && !token.endsWith("ss")) token.dropLast(1) else token

    private fun digits(s: String, from: Int, to: Int): Int? {
        var v = 0
        for (i in from until to) {
            val c = s[i]
            if (c !in '0'..'9') return null
            v = v * 10 + (c - '0')
        }
        return v
    }

    private fun daysInMonth(y: Int, m: Int): Int = when (m) {
        2 -> if ((y % 4 == 0 && y % 100 != 0) || y % 400 == 0) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
}
