package com.slickstream.core.catalog

import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType

/**
 * One browse row ("shelf") and where its titles come from. Pure data — the repository turns it into
 * a TMDB call (CatalogRepository.getShelf).
 *
 * Why shelves exist (measured 2026-11-18): Home and the Movies/TV tabs were built from the same four
 * TMDB lists. "Popular Movies" shared 14 of 20 titles with "New & Upcoming" and 11 with "Trending",
 * the tabs repeated Home's rows 20/20, and "Top Rated" was the same all-time page forever. Shelves
 * pull from distinct slices of discover (new to streaming, specific networks, eras, languages,
 * hidden gems…), rotate daily, and the page is de-duplicated so a title appears once.
 */
data class Shelf(
    val id: String,
    val title: String,
    val source: Source,
    /** Movie or TV for DISCOVER/TOP_RATED/NOW_PLAYING; null with TRENDING = movies and shows. */
    val mediaType: MediaType?,
    /** Extra TMDB discover query params (DISCOVER only). */
    val params: Map<String, String> = emptyMap(),
    /** Pages this shelf can rotate through day to day (1 = always its best page). */
    val pages: Int = 1,
) {
    enum class Source { TRENDING_DAY, TRENDING_WEEK, DISCOVER, TOP_RATED, NOW_PLAYING }
}

object Shelves {

    /** Talk, News, Soap, Reality: TMDB's raw TV popularity is flooded by daily talk shows and soaps. */
    private const val TV_NOISE = "10767|10763|10766|10764"
    /** Region for "new to streaming" release dates (the user's market). */
    private const val REGION = "CA"

    /** Fewer distinct titles than this after de-duplication and the shelf is dropped. */
    const val MIN_ROW = 6
    const val MAX_ROW = 20

    // --- building blocks -------------------------------------------------------------------------

    /** Discover sorts by popularity, which ranks hyped UNRELEASED films first — cap at today. */
    private fun movies(id: String, title: String, pages: Int = 1, vararg p: Pair<String, String>) =
        Shelf(id, title, Shelf.Source.DISCOVER, MediaType.MOVIE,
            mapOf("primary_release_date.lte" to Date.today().iso) + mapOf(*p), pages)

    private fun shows(id: String, title: String, pages: Int = 1, vararg p: Pair<String, String>) =
        Shelf(id, title, Shelf.Source.DISCOVER, MediaType.TV, mapOf("without_genres" to TV_NOISE, *p), pages)

    @Suppress("UNUSED_PARAMETER")
    private fun network(id: String, title: String, networks: String, today: Date) =
        shows(id, title, 2, "with_networks" to networks, "first_air_date.gte" to "2010-01-01",
            "vote_count.gte" to "50", "sort_by" to "popularity.desc")

    private fun newToStream(today: Date) = movies(
        "new-streaming", "New Movies to Stream", 1,
        "with_release_type" to "4|5", "region" to REGION,
        "release_date.gte" to today.minusDays(60), "release_date.lte" to today.iso,
        "sort_by" to "popularity.desc",
    )

    private fun newShows(today: Date) = shows(
        "new-shows", "New TV Shows", 1,
        "first_air_date.gte" to today.minusDays(90), "first_air_date.lte" to today.iso,
        "with_original_language" to "en", "vote_count.gte" to "3", "sort_by" to "popularity.desc",
    )

    private fun returningThisWeek(today: Date) = shows(
        "airing-week", "New Episodes This Week", 2,
        "air_date.gte" to today.minusDays(7), "air_date.lte" to today.plusDays(1),
        "vote_count.gte" to "100", "sort_by" to "popularity.desc",
    )

    private fun inTheaters() = Shelf("theaters", "In Theaters", Shelf.Source.NOW_PLAYING, MediaType.MOVIE)

    /** The rotating mixed pool. Each day (and each pull-to-refresh) shows a different handful. */
    private fun moviePool(today: Date) = listOf(
        movies("hidden-gems", "Hidden Gems", 4, "vote_average.gte" to "7.2", "vote_count.gte" to "300",
            "vote_count.lte" to "3000", "primary_release_date.gte" to "2005-01-01", "without_genres" to "16|99",
            "sort_by" to "vote_average.desc"),
        movies("acclaimed", "Critically Acclaimed", 3, "vote_average.gte" to "7.4", "vote_count.gte" to "500",
            "primary_release_date.gte" to today.minusDays(5 * 365), "without_genres" to "16",
            "sort_by" to "vote_average.desc"),
        movies("thrillers", "Edge-of-Your-Seat Thrillers", 4, "with_genres" to "53", "vote_count.gte" to "500",
            "primary_release_date.gte" to today.minusDays(10 * 365), "sort_by" to "popularity.desc"),
        movies("comedy", "Laugh Out Loud", 4, "with_genres" to "35", "vote_count.gte" to "300",
            "primary_release_date.gte" to today.minusDays(6 * 365), "sort_by" to "popularity.desc"),
        movies("scifi", "Sci-Fi Worlds", 4, "with_genres" to "878", "vote_count.gte" to "800", "sort_by" to "vote_average.desc"),
        movies("horror", "Horror Nights", 4, "with_genres" to "27", "vote_count.gte" to "300",
            "primary_release_date.gte" to today.minusDays(10 * 365), "sort_by" to "popularity.desc"),
        movies("action", "Action Blockbusters", 5, "with_genres" to "28", "vote_count.gte" to "2000", "sort_by" to "popularity.desc"),
        movies("90s", "'90s Classics", 4, "primary_release_date.gte" to "1990-01-01",
            "primary_release_date.lte" to "1999-12-31", "vote_count.gte" to "1500", "sort_by" to "vote_average.desc"),
        movies("2000s", "2000s Favourites", 4, "primary_release_date.gte" to "2000-01-01",
            "primary_release_date.lte" to "2009-12-31", "vote_count.gte" to "2000", "sort_by" to "vote_average.desc"),
        movies("80s", "'80s Rewind", 3, "primary_release_date.gte" to "1980-01-01",
            "primary_release_date.lte" to "1989-12-31", "vote_count.gte" to "1000", "sort_by" to "vote_average.desc"),
        movies("docs", "Documentaries Worth Watching", 3, "with_genres" to "99", "vote_count.gte" to "300", "sort_by" to "vote_average.desc"),
        movies("true-story-drama", "Award-Season Dramas", 4, "with_genres" to "18", "vote_average.gte" to "7.5",
            "vote_count.gte" to "1500", "sort_by" to "vote_average.desc"),
        movies("intl-movies", "Hits From Around the World", 4, "with_original_language" to "fr|es|ko|ja|it|de|da|sv|no",
            "vote_count.gte" to "500", "sort_by" to "popularity.desc"),
        movies("romance", "Date Night", 4, "with_genres" to "10749", "vote_count.gte" to "500", "sort_by" to "popularity.desc"),
    )

    private fun tvPool(today: Date) = listOf(
        network("netflix", "Netflix Originals", "213", today),
        network("hbo", "HBO & Max", "49|3186", today),
        network("apple", "Apple TV+ Originals", "2552", today),
        network("prime", "Prime Video Originals", "1024", today),
        network("disney", "Disney+ Originals", "2739", today),
        network("hulu", "Hulu Originals", "453", today),
        network("fx", "FX & AMC Dramas", "88|174", today),
        shows("limited", "Binge-Worthy Limited Series", 3, "with_type" to "2", "with_original_language" to "en",
            "first_air_date.gte" to today.minusDays(4 * 365), "vote_count.gte" to "50", "sort_by" to "popularity.desc"),
        shows("kdrama", "Korean Dramas", 4, "with_original_language" to "ko", "with_genres" to "18",
            "vote_count.gte" to "50", "sort_by" to "popularity.desc"),
        shows("anime", "Anime Series", 4, "with_original_language" to "ja", "with_genres" to "16",
            "first_air_date.gte" to "2010-01-01", "vote_average.gte" to "7.5", "vote_count.gte" to "100",
            "sort_by" to "popularity.desc"),
        shows("british", "British TV", 4, "with_origin_country" to "GB", "vote_count.gte" to "100", "sort_by" to "popularity.desc"),
        shows("true-crime", "True Crime", 3, "with_genres" to "99,80", "vote_count.gte" to "20", "sort_by" to "popularity.desc"),
        shows("comedy-tv", "Comedy Series", 4, "with_genres" to "35", "with_original_language" to "en",
            "vote_count.gte" to "200", "first_air_date.gte" to today.minusDays(8 * 365), "sort_by" to "popularity.desc"),
        shows("scifi-tv", "Sci-Fi & Fantasy Series", 4, "with_genres" to "10765", "vote_count.gte" to "300", "first_air_date.gte" to today.minusDays(8 * 365), "sort_by" to "popularity.desc"),
        shows("crime-tv", "Crime Dramas", 4, "with_genres" to "80", "with_original_language" to "en",
            "vote_count.gte" to "300", "first_air_date.gte" to today.minusDays(8 * 365), "sort_by" to "popularity.desc"),
        shows("acclaimed-tv", "Acclaimed Series", 4, "vote_average.gte" to "8", "vote_count.gte" to "800", "sort_by" to "vote_average.desc"),
    )

    // --- page plans -----------------------------------------------------------------------------

    /**
     * Home: what's new on top, a rotating mix in the middle, a rotated Top Rated page at the end.
     * [salt] changes on pull-to-refresh so a refresh brings different rows, not the same ones.
     */
    fun home(today: Date, salt: Int = 0): List<Shelf> {
        val seed = today.epochDay * 31 + salt
        val pool = rotate(moviePool(today), 4, seed) + rotate(tvPool(today), 4, seed + 7)
        return listOf(
            Shelf("trending-day", "Trending Today", Shelf.Source.TRENDING_DAY, null),
            newToStream(today),
            newShows(today),
            Shelf("trending-week", "Popular This Week", Shelf.Source.TRENDING_WEEK, null),
        ) + interleave(pool) + listOf(
            Shelf("top-movies", "Top Rated Movies", Shelf.Source.TOP_RATED, MediaType.MOVIE, pages = 10),
            inTheaters(),
        )
    }

    /** The Movies or TV tab: its own new releases, more of its pool than Home, then genres. */
    fun tab(type: MediaType, today: Date, salt: Int = 0): List<Shelf> {
        val seed = today.epochDay * 17 + salt + if (type == MediaType.TV) 3 else 0
        return if (type == MediaType.MOVIE) listOf(
            Shelf("trending-movies", "Trending Movies", Shelf.Source.TRENDING_DAY, MediaType.MOVIE),
            newToStream(today),
            inTheaters(),
            Shelf("week-movies", "Popular This Week", Shelf.Source.TRENDING_WEEK, MediaType.MOVIE, pages = 2),
        ) + rotate(moviePool(today), 7, seed) + Shelf("top-movies", "Top Rated", Shelf.Source.TOP_RATED, MediaType.MOVIE, pages = 10)
        else listOf(
            Shelf("trending-tv", "Trending Shows", Shelf.Source.TRENDING_DAY, MediaType.TV),
            newShows(today),
            returningThisWeek(today),
            Shelf("week-tv", "Popular This Week", Shelf.Source.TRENDING_WEEK, MediaType.TV, pages = 2),
        ) + rotate(tvPool(today), 8, seed) + Shelf("top-tv", "Top Rated", Shelf.Source.TOP_RATED, MediaType.TV, pages = 10)
    }

    /** A genre carousel on the Movies/TV tab, on a rotating page so it isn't the same 20 forever. */
    fun genre(type: MediaType, genreId: Int, name: String): Shelf =
        Shelf("genre-$genreId", name, Shelf.Source.DISCOVER, type,
            buildMap {
                put("with_genres", genreId.toString())
                put("vote_count.gte", if (type == MediaType.MOVIE) "200" else "100")
                put("sort_by", "popularity.desc")
                if (type == MediaType.TV) put("without_genres", TV_NOISE)
            },
            pages = 5)

    /** Which page a shelf shows today: deterministic per day and shelf, so rows change daily but
     *  don't reshuffle on every recomposition. */
    fun pageFor(shelf: Shelf, today: Date, salt: Int = 0): Int {
        if (shelf.pages <= 1) return 1
        val h = (shelf.id.hashCode().toLong() and 0x7fffffff) + today.epochDay + salt
        return 1 + (h % shelf.pages).toInt()
    }

    /** [count] items of [pool], chosen and ordered by [seed] (a stable shuffle). */
    fun <T> rotate(pool: List<T>, count: Int, seed: Long): List<T> =
        pool.withIndex()
            .sortedBy { (i, _) -> mix(seed * 1_000_003L + i) }
            .take(count.coerceAtMost(pool.size))
            .map { it.value }

    /** Alternate movie and TV shelves from a pool built as [movies…, shows…]. */
    private fun interleave(pool: List<Shelf>): List<Shelf> {
        val m = pool.filter { it.mediaType == MediaType.MOVIE }
        val t = pool.filter { it.mediaType != MediaType.MOVIE }
        return (0 until maxOf(m.size, t.size)).flatMap { listOfNotNull(t.getOrNull(it), m.getOrNull(it)) }
    }

    /**
     * Each title appears once per page: the first shelf to list it keeps it. Shelves left with fewer
     * than [MIN_ROW] titles are dropped (a 3-item row looks broken); rows cap at [MAX_ROW].
     */
    fun dedupe(rows: List<Pair<Shelf, List<MediaItem>>>, alreadyShown: Set<String> = emptySet()): List<Pair<Shelf, List<MediaItem>>> {
        val seen = HashSet(alreadyShown)
        return rows.mapNotNull { (shelf, items) ->
            val fresh = items.filter { seen.add(key(it)) }.take(MAX_ROW)
            fresh.takeIf { it.size >= MIN_ROW }?.let { shelf to it }
        }
    }

    fun key(item: MediaItem): String = "${item.mediaType.name}:${item.id}"

    private fun mix(x: Long): Long {
        var z = x + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** A calendar day (no java.time: minSdk 24 without desugaring). */
    class Date(val epochDay: Long) {
        val iso: String get() = isoOf(epochDay)
        fun minusDays(d: Int): String = isoOf(epochDay - d)
        fun plusDays(d: Int): String = isoOf(epochDay + d)

        companion object {
            fun today(nowMs: Long = System.currentTimeMillis()): Date =
                Date(Math.floorDiv(nowMs + java.util.TimeZone.getDefault().getOffset(nowMs), 86_400_000L))

            /** Hinnant's civil_from_days. */
            fun isoOf(days: Long): String {
                val z = days + 719_468
                val era = Math.floorDiv(z, 146_097L)
                val doe = z - era * 146_097
                val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
                val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
                val mp = (5 * doy + 2) / 153
                val d = doy - (153 * mp + 2) / 5 + 1
                val m = if (mp < 10) mp + 3 else mp - 9
                val y = yoe + era * 400 + if (m <= 2) 1 else 0
                return "%04d-%02d-%02d".format(y, m, d)
            }
        }
    }
}
