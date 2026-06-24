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
    private val fanCode: FanCodeSource,
    private val baseUrl: String,
) : SportsRepository {

    private val streamedEnabled: Boolean get() = baseUrl.isNotBlank()

    // FanCode is always available (keyless community feed), so the tab is never fully disabled.
    override val enabled: Boolean get() = true

    override suspend fun categories(): DataResult<List<SportCategory>> = guard {
        val live = SportCategory(SportsRepository.LIVE_CATEGORY_ID, "Live now")
        val fanCats = runCatching { fanCode.liveMatches() }.getOrDefault(emptyList())
            .map { it.categoryId }
            .distinct()
            .map { id ->
                SportCategory(id, id.removePrefix(FanCodeSource.FC_PREFIX).replaceFirstChar(Char::uppercase))
            }
        val streamedCats = if (!streamedEnabled) emptyList() else
            runCatching { api.sports() }
                .onFailure { android.util.Log.w("SportsRepository", "streamed.pk categories failed", it) }
                .getOrDefault(emptyList())
                .filter { it.id.isNotBlank() }
                .map { dto ->
                    val league = LEAGUE_META[dto.id.lowercase()]
                    SportCategory(
                        id = dto.id,
                        name = league?.name ?: dto.name.ifBlank { dto.id.replaceFirstChar(Char::uppercase) },
                        logoUrl = league?.logo,
                    )
                }
                // MLB / NBA / NHL / NFL first (in that order), then the rest alphabetically.
                .sortedWith(compareBy({ LEAGUE_META[it.id.lowercase()]?.order ?: 99 }, { it.name }))
        // Live now first (the cross-sport live aggregate), then the leagues, then FanCode (live-only,
        // often geo-blocked) last.
        (listOf(live) + streamedCats + fanCats).distinctBy { it.id }
    }

    override suspend fun events(categoryId: String): DataResult<List<SportEvent>> = guard {
        val now = System.currentTimeMillis()
        when {
            categoryId == SportsRepository.LIVE_CATEGORY_ID -> {
                val fc = runCatching { fanCode.liveMatches() }.getOrDefault(emptyList())
                val sp = if (!streamedEnabled) emptyList() else
                    runCatching { api.liveMatches() }.getOrDefault(emptyList())
                        .filter { it.id.isNotBlank() && it.sources.isNotEmpty() }
                        .map { it.toEvent(now, forcedLive = true) }
                (fc + sp).sortedWith(compareByDescending<SportEvent> { it.isLive }.thenBy { it.startEpochMs })
            }
            categoryId.startsWith(FanCodeSource.FC_PREFIX) ->
                runCatching { fanCode.liveMatches() }.getOrDefault(emptyList())
                    .filter { it.categoryId == categoryId }
            else ->
                api.matches(categoryId)
                    .filter { it.id.isNotBlank() && it.sources.isNotEmpty() }
                    .map { it.toEvent(now, forcedLive = false) }
                    .sortedWith(compareByDescending<SportEvent> { it.isLive }.thenBy { it.startEpochMs })
        }
    }

    override suspend fun streams(event: SportEvent): DataResult<List<SportStream>> = guard {
        val direct = event.directStream
        if (direct != null) {
            listOf(direct)
        } else {
            coroutineScope {
                event.sources
                    .map { ref -> async(Dispatchers.IO) { runCatching { api.streams(ref.source, ref.id) }.getOrDefault(emptyList()) } }
                    .flatMap { it.await() }
                    .filter { it.embedUrl.isNotBlank() }
                    .map { it.toStream() }
            }
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
        // The embed renders + the resulting playlist/segments 403 unless the referrer is streamed.pk.
        // url is the embed PAGE; needsResolution=true routes it through the WebView resolver first.
        return SportStream(
            id = "$source-$id-$streamNo",
            label = label,
            url = embedUrl,
            headers = mapOf(
                // Playback headers for the resolved m3u8 — the CDN expects the embed host (embed.st).
                "Referer" to "https://embed.st/",
                "Origin" to "https://embed.st",
                "User-Agent" to USER_AGENT,
            ),
            needsResolution = true,
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

    private data class LeagueMeta(val name: String, val logo: String?, val order: Int)

    private companion object {
        const val LIVE_WINDOW_MS = 3L * 60 * 60 * 1000 // treat first 3h after kickoff as "live"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

        /** Map streamed.pk sport ids -> league display name + badge (ESPN CDN) + sort order. */
        val LEAGUE_META = mapOf(
            "baseball" to LeagueMeta("MLB", "https://a.espncdn.com/i/teamlogos/leagues/500/mlb.png", 1),
            "basketball" to LeagueMeta("NBA", "https://a.espncdn.com/i/teamlogos/leagues/500/nba.png", 2),
            "hockey" to LeagueMeta("NHL", "https://a.espncdn.com/i/teamlogos/leagues/500/nhl.png", 3),
            "american-football" to LeagueMeta("NFL", "https://a.espncdn.com/i/teamlogos/leagues/500/nfl.png", 4),
            "football" to LeagueMeta("Soccer", "https://a.espncdn.com/i/teamlogos/leagues/500/uefa.champions.png", 5),
            "fight" to LeagueMeta("UFC / Boxing", "https://a.espncdn.com/i/teamlogos/leagues/500/ufc.png", 6),
            "tennis" to LeagueMeta("Tennis", "https://a.espncdn.com/i/teamlogos/leagues/500/atp.png", 7),
        )
    }
}
