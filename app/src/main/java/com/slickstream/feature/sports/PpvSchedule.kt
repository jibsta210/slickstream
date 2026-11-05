package com.slickstream.feature.sports

/**
 * Pure mapping of the ppv.st schedule document ([PpvScheduleDto]) into the app's sports model.
 * No I/O, no android — unit-tested in PpvScheduleTest.
 */
object PpvSchedule {

    /** ppv.st's category of round-the-clock channels (NFL Network, ESPN…): listed last, never "live now". */
    private const val CHANNELS_CATEGORY = "24/7 streams"

    /** Page the embeds expect to be framed by — the WebView resolver loads them with this Referer. */
    const val PAGE_REFERER = "https://ppv.st/"

    private const val ID_PREFIX = "ppv-"
    /** An event with no end time counts as live for this long after its start. */
    private const val LIVE_WINDOW_MS = 3L * 60 * 60 * 1000
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

    private data class Meta(val name: String, val logo: String?, val order: Int)

    /** The leagues people asked for first (MLB / NBA / NHL / NFL), keyed by ppv.st's sport name. */
    private val META = mapOf(
        "baseball" to Meta("MLB", "https://a.espncdn.com/i/teamlogos/leagues/500/mlb.png", 1),
        "basketball" to Meta("NBA", "https://a.espncdn.com/i/teamlogos/leagues/500/nba.png", 2),
        "ice hockey" to Meta("NHL", "https://a.espncdn.com/i/teamlogos/leagues/500/nhl.png", 3),
        "american football" to Meta("NFL", "https://a.espncdn.com/i/teamlogos/leagues/500/nfl.png", 4),
        "football" to Meta("Soccer", "https://a.espncdn.com/i/teamlogos/leagues/500/uefa.champions.png", 5),
        "combat sports" to Meta("UFC / Boxing", "https://a.espncdn.com/i/teamlogos/leagues/500/ufc.png", 6),
        "tennis" to Meta("Tennis", "https://a.espncdn.com/i/teamlogos/leagues/500/atp.png", 7),
        CHANNELS_CATEGORY to Meta("24/7 Channels", null, 1000),
    )

    fun categoryId(dto: PpvCategoryDto): String = "$ID_PREFIX${dto.id}"
    private fun eventId(dto: PpvStreamDto): String = "$ID_PREFIX${dto.id}"

    /** Sports that have at least one current or upcoming event: MLB/NBA/NHL/NFL first, channels last. */
    fun categories(schedule: PpvScheduleDto, nowMs: Long): List<SportCategory> =
        schedule.streams
            .filter { c -> c.category.isNotBlank() && c.streams.any { !ended(it, nowMs) } }
            .map { c ->
                val meta = META[c.category.trim().lowercase()]
                Triple(SportCategory(categoryId(c), meta?.name ?: c.category.trim(), meta?.logo), meta?.order ?: 500, c.category)
            }
            .sortedWith(compareBy({ it.second }, { it.first.name }))
            .map { it.first }
            .distinctBy { it.id }

    /** One sport's events: live games first, then upcoming by start time, its 24/7 channels last. */
    fun events(schedule: PpvScheduleDto, categoryId: String, nowMs: Long): List<SportEvent> {
        val cat = schedule.streams.firstOrNull { categoryId(it) == categoryId } ?: return emptyList()
        return cat.streams.filter { !ended(it, nowMs) && !it.iframe.isNullOrBlank() }
            .map { it.toEvent(categoryId, nowMs) }
            .sortedWith(order(cat.streams))
    }

    /** Games live right now across every sport. Round-the-clock channels are not games, so they stay
     *  in their own sport (and the 24/7 list) instead of flooding "Live now". */
    fun liveEvents(schedule: PpvScheduleDto, nowMs: Long): List<SportEvent> =
        schedule.streams
            .filter { it.category.trim().lowercase() != CHANNELS_CATEGORY }
            .flatMap { c ->
                c.streams.filter { !it.alwaysLive && isLive(it, nowMs) && !it.iframe.isNullOrBlank() }
                    .map { it.toEvent(categoryId(c), nowMs) }
            }
            .distinctBy { it.id }
            .sortedBy { it.startEpochMs }

    /** The feeds for one event: its main embed plus any substreams (an SD copy, another broadcaster). */
    fun streamsFor(schedule: PpvScheduleDto, eventId: String): List<SportStream> {
        val s = schedule.streams.asSequence().flatMap { it.streams.asSequence() }
            .firstOrNull { eventId(it) == eventId } ?: return emptyList()
        val main = s.iframe?.takeIf { it.isNotBlank() }?.let { Triple(s.id, s.sourceTag ?: s.tag, it) }
        val subs = s.substreams.mapNotNull { sub ->
            sub.iframe?.takeIf { it.isNotBlank() }?.let { Triple(sub.id, sub.sourceTag ?: sub.tag, it) }
        }
        return (listOfNotNull(main) + subs)
            .distinctBy { it.third }
            .mapIndexed { i, (id, label, url) ->
                val origin = originOf(url)
                SportStream(
                    id = "$ID_PREFIX$id",
                    label = label?.takeIf { it.isNotBlank() } ?: "Stream ${i + 1}",
                    url = url,
                    headers = buildMap {
                        // The playlist/segments are fetched as if from inside the embed page.
                        if (origin != null) {
                            put("Referer", "$origin/")
                            put("Origin", origin)
                        }
                        put("User-Agent", USER_AGENT)
                    },
                    needsResolution = true,
                    pageReferer = PAGE_REFERER,
                )
            }
    }

    fun isLive(s: PpvStreamDto, nowMs: Long): Boolean {
        if (s.alwaysLive) return true
        if (s.startsAt <= 0L) return false
        val start = s.startsAt * 1000
        val end = if (s.endsAt > 0L) s.endsAt * 1000 else start + LIVE_WINDOW_MS
        return nowMs in start..end
    }

    private fun ended(s: PpvStreamDto, nowMs: Long): Boolean =
        !s.alwaysLive && s.endsAt > 0L && s.endsAt * 1000 < nowMs

    private fun PpvStreamDto.toEvent(categoryId: String, nowMs: Long) = SportEvent(
        id = eventId(this),
        title = name.ifBlank { "Untitled event" },
        categoryId = categoryId,
        startEpochMs = if (alwaysLive) 0L else startsAt * 1000,
        posterUrl = poster?.takeIf { it.startsWith("http") },
        isLive = isLive(this, nowMs),
        sources = listOf(SportSourceRef("ppv", id.toString())),
    )

    /** Live games, then upcoming by start, then channels by name. */
    private fun order(raw: List<PpvStreamDto>): Comparator<SportEvent> {
        val channels = raw.filter { it.alwaysLive }.map { eventId(it) }.toSet()
        return compareBy<SportEvent>({ it.id in channels }, { !it.isLive }, { it.startEpochMs }, { it.title })
    }

    private fun originOf(url: String): String? =
        Regex("^(https?://[^/?#]+)").find(url)?.groupValues?.get(1)
}
