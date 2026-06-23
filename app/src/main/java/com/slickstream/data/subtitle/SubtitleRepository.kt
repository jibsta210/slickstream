package com.slickstream.data.subtitle

import com.slickstream.core.model.MediaDetails
import com.slickstream.core.model.MediaType
import com.slickstream.core.model.SubtitleTrack
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Resolves external subtitles for a title via the Stremio subtitle addon. Best-effort. */
@Singleton
class SubtitleRepository @Inject constructor(
    private val api: SubtitleApi,
) {
    private val io: CoroutineDispatcher = Dispatchers.IO

    suspend fun fetch(details: MediaDetails, season: Int?, episode: Int?): List<SubtitleTrack> =
        withContext(io) {
            val imdb = details.imdbId?.takeIf { it.isNotBlank() } ?: return@withContext emptyList()
            val isSeries = details.item.mediaType == MediaType.TV
            val type = if (isSeries) "series" else "movie"
            val id = if (isSeries && season != null && episode != null) "$imdb:$season:$episode" else imdb

            runCatching {
                api.getSubtitles(type, id).subtitles.mapNotNull { dto ->
                    val url = dto.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val lang = dto.lang?.takeIf { it.isNotBlank() } ?: "und"
                    SubtitleTrack(
                        id = dto.id ?: url,
                        label = languageLabel(lang),
                        languageCode = lang,
                        url = url,
                    )
                }
                    // One per language keeps the picker tidy (addons return many per language).
                    .distinctBy { it.languageCode }
                    .sortedBy { it.label }
            }.getOrDefault(emptyList())
        }

    private fun languageLabel(code: String): String =
        LANG_NAMES[code.lowercase()] ?: code.uppercase()

    private companion object {
        val LANG_NAMES = mapOf(
            "eng" to "English", "spa" to "Spanish", "fre" to "French", "fra" to "French",
            "ger" to "German", "deu" to "German", "ita" to "Italian", "por" to "Portuguese",
            "dut" to "Dutch", "nld" to "Dutch", "ara" to "Arabic", "rus" to "Russian",
            "hin" to "Hindi", "jpn" to "Japanese", "kor" to "Korean", "chi" to "Chinese",
            "zho" to "Chinese", "pol" to "Polish", "tur" to "Turkish", "swe" to "Swedish",
            "nor" to "Norwegian", "dan" to "Danish", "fin" to "Finnish", "ell" to "Greek",
            "heb" to "Hebrew", "ind" to "Indonesian", "tha" to "Thai", "vie" to "Vietnamese",
            "ron" to "Romanian", "rum" to "Romanian", "ukr" to "Ukrainian", "ces" to "Czech",
            "cze" to "Czech", "hun" to "Hungarian", "bul" to "Bulgarian", "hrv" to "Croatian",
        )
    }
}
