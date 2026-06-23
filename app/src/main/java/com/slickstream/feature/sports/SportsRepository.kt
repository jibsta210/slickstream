package com.slickstream.feature.sports

import com.slickstream.core.model.DataResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Live-sports schedule + stream source. Implementations are keyless and best-effort. */
interface SportsRepository {
    /** True when a real source is configured (SPORTS_BASE_URL non-blank). */
    val enabled: Boolean

    suspend fun categories(): DataResult<List<SportCategory>>

    /** Events for a category id, or live events when [categoryId] == [LIVE_CATEGORY_ID]. */
    suspend fun events(categoryId: String): DataResult<List<SportEvent>>

    /** Resolve playable HLS feeds for an event (one or more per source). */
    suspend fun streams(event: SportEvent): DataResult<List<SportStream>>

    companion object {
        const val LIVE_CATEGORY_ID = "__live__"
    }
}

class SportsRepositoryImpl(
    private val api: SportsApi,
    private val baseUrl: String,
) : SportsRepository {

    override val enabled: Boolean get() = baseUrl.isNotBlank()

    override suspend fun categories(): DataResult<List<SportCategory>> = guard {
        val live = SportCategory(SportsRepository.LIVE_CATEGORY_ID, "Live now")
        val rest = api.sports()
            .filter { it.id.isNotBlank() }
            .map { SportCategory(it.id, it.name.ifBlank { it.id.replaceFirstChar(Char::uppercase) }) }
        listOf(live) + rest
    }

    override suspend fun events(categoryId: String): DataResult<List<SportEvent>> = guard {
        val matches = if (categoryId == SportsRepository.LIVE_CATEGORY_ID) {
            api.liveMatches()
        } else {
            api.matches(categoryId)
        }
        val now = System.currentTimeMillis()
        matches
            .filter { it.id.isNotBlank() && it.sources.isNotEmpty() }
            .map { it.toEvent(now, forcedLive = categoryId == SportsRepository.LIVE_CATEGORY_ID) }
            .sortedWith(compareByDescending<SportEvent> { it.isLive }.thenBy { it.startEpochMs })
    }

    override suspend fun streams(event: SportEvent): DataResult<List<SportStream>> = guard {
        coroutineScope {
            event.sources
                .map { ref -> async(Dispatchers.IO) { runCatching { api.streams(ref.source, ref.id) }.getOrDefault(emptyList()) } }
                .flatMap { it.await() }
                .filter { it.embedUrl.isNotBlank() }
                .map { it.toStream() }
        }
    }

    // --- mapping ---------------------------------------------------------------------------

    private fun MatchDto.toEvent(now: Long, forcedLive: Boolean): SportEvent {
        val live = forcedLive || (date in 1..now && now - date <= LIVE_WINDOW_MS)
        return SportEvent(
            id = id,
            title = title.ifBlank { "Untitled event" },
            categoryId = category,
            startEpochMs = date,
            posterUrl = poster?.let(::absolutePoster),
            isLive = live,
            sources = sources.filter { it.source.isNotBlank() && it.id.isNotBlank() }
                .map { SportSourceRef(it.source, it.id) },
        )
    }

    private fun StreamDto.toStream(): SportStream {
        val lang = language?.takeIf { it.isNotBlank() }
        val label = buildString {
            append(source.replaceFirstChar(Char::uppercase))
            if (lang != null) append(" · $lang")
            if (hd) append(" · HD")
            if (streamNo > 0) append(" · #$streamNo")
        }
        // These hosts validate Referer + User-Agent on the playlist and segments.
        val origin = runCatching { java.net.URI(embedUrl).let { "${it.scheme}://${it.host}/" } }
            .getOrDefault(embedUrl)
        return SportStream(
            id = "$source-$id-$streamNo",
            label = label,
            url = embedUrl,
            headers = mapOf(
                "Referer" to origin,
                "User-Agent" to USER_AGENT,
                "Origin" to origin.trimEnd('/'),
            ),
        )
    }

    private fun absolutePoster(path: String): String? = when {
        path.isBlank() -> null
        path.startsWith("http") -> path
        else -> baseUrl.trimEnd('/') + "/" + path.trimStart('/')
    }

    private suspend inline fun <T> guard(crossinline block: suspend () -> T): DataResult<T> =
        withContext(Dispatchers.IO) {
            try {
                DataResult.Success(block())
            } catch (e: Exception) {
                DataResult.Error(e.message ?: "Couldn't reach the live-sports source.", e)
            }
        }

    private companion object {
        const val LIVE_WINDOW_MS = 3L * 60 * 60 * 1000 // treat first 3h after kickoff as "live"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    }
}
