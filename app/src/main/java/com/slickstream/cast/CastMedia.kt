package com.slickstream.cast

import okhttp3.HttpUrl

/** Cast needs an explicit container type; the addon's display title is only a last resort. */
internal fun castMimeType(url: HttpUrl, contentType: String?, title: String = ""): String {
    fun fromName(name: String): String? = when (name.substringAfterLast('.').lowercase()) {
        "m3u8" -> "application/x-mpegURL"
        "mpd" -> "application/dash+xml"
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "ts", "m2ts" -> "video/mp2t"
        else -> null
    }
    val type = contentType?.substringBefore(';')?.trim()?.lowercase()
    // Preserve adaptive formats even when a CDN labels a playlist as text/plain or video/mp4.
    val pathType = fromName(url.pathSegments.last())
    if (pathType == "application/x-mpegURL" || pathType == "application/dash+xml") return pathType
    if (type != null && (type.startsWith("video/") || isHls(type) || type == "application/dash+xml")) {
        return if (isHls(type)) "application/x-mpegURL" else type
    }
    return pathType ?: Regex("\\.(mkv|webm|mp4|m4v|avi|mov)(?:\\b|$)", RegexOption.IGNORE_CASE)
        .find(title)?.groupValues?.get(1)?.let { fromName("file.$it") } ?: "video/mp4"
}

internal fun isHls(mime: String): Boolean = mime.lowercase() in setOf(
    "application/x-mpegurl", "application/vnd.apple.mpegurl", "audio/mpegurl", "audio/x-mpegurl",
)

/** Rewrite variants, segments, subtitles, encryption keys and init maps through the same bridge. */
internal fun rewriteCastPlaylist(text: String, base: HttpUrl, route: (HttpUrl) -> String): String {
    fun rewrite(value: String): String = base.resolve(value)?.let(route) ?: value
    val uriAttribute = Regex("URI=\"([^\"]+)\"")
    return text.lineSequence().joinToString("\n") { line ->
        when {
            line.isBlank() -> line
            line.trimStart().startsWith('#') -> uriAttribute.replace(line) { match ->
                "URI=\"${rewrite(match.groupValues[1])}\""
            }
            else -> rewrite(line.trim())
        }
    }
}
