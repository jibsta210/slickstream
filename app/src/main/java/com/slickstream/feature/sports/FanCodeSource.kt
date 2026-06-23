package com.slickstream.feature.sports

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FanCode live-sports source — keyless and, crucially, DIRECT: each match carries a ready-to-play
 * Google-DAI HLS master (`stream_link`) that needs no headers and no WebView resolution. We read a
 * community-maintained JSON feed (refreshed continuously) rather than scraping FanCode ourselves.
 * Cricket/football heavy, but rock-solid where it has coverage.
 */
@Singleton
class FanCodeSource @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
) {
    /** All currently-live FanCode matches as ready-to-play [SportEvent]s. */
    suspend fun liveMatches(): List<SportEvent> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(FEED_URL).header("User-Agent", UA).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext emptyList()
            val body = resp.body?.string().orEmpty()
            val feed = runCatching { json.decodeFromString<FanCodeFeed>(body) }.getOrNull()
                ?: return@withContext emptyList()
            feed.matches
                .filter { it.streamLink.isNotBlank() && it.matchId != 0L }
                .map { it.toEvent() }
        }
    }

    private fun FanCodeMatch.toEvent(): SportEvent {
        val cat = category.ifBlank { "other" }.lowercase()
        val title = matchName.ifBlank { eventName }.ifBlank { "Live match" }
        return SportEvent(
            id = "fc-$matchId",
            title = title,
            categoryId = "$FC_PREFIX$cat",
            startEpochMs = 0L,
            posterUrl = banner?.takeIf { it.isNotBlank() } ?: team1Flag,
            isLive = true,
            sources = emptyList(),
            directStream = SportStream(
                id = "fc-$matchId",
                label = "FanCode${if (eventName.isNotBlank()) " · $eventName" else ""}",
                url = streamLink,
                headers = emptyMap(),       // DAI HLS needs no special headers
                needsResolution = false,
            ),
        )
    }

    companion object {
        const val FC_PREFIX = "fc:"
        private const val FEED_URL =
            "https://raw.githubusercontent.com/byte-capsule/FanCode-Hls-Fetcher/main/Fancode_hls_m3u8.Json"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"
    }
}

@Serializable
private data class FanCodeFeed(val matches: List<FanCodeMatch> = emptyList())

@Serializable
private data class FanCodeMatch(
    @SerialName("event_catagory") val category: String = "",
    @SerialName("event_name") val eventName: String = "",
    @SerialName("match_id") val matchId: Long = 0,
    @SerialName("match_name") val matchName: String = "",
    @SerialName("team_1_flag") val team1Flag: String? = null,
    val banner: String? = null,
    @SerialName("stream_link") val streamLink: String = "",
)
