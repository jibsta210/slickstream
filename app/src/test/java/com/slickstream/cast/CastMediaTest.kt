package com.slickstream.cast

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class CastMediaTest {
    @Test fun `HLS URL overrides misleading CDN MIME and release title`() {
        assertEquals("application/x-mpegURL", castMimeType(
            "https://cdn.example/stream.M3U8?token=secret.mp4".toHttpUrl(), "video/mp4", "Film.mkv"))
    }

    @Test fun `opaque torrent URL uses the actual server container`() {
        assertEquals("video/x-matroska", castMimeType(
            "http://127.0.0.1:4567/abcdef".toHttpUrl(), "video/x-matroska", "1080p WEB-DL"))
    }

    @Test fun `opaque HLS URL uses HTTP MIME without parameters`() {
        assertEquals("application/x-mpegURL", castMimeType(
            "https://cdn.example/play?id=3".toHttpUrl(), "application/vnd.apple.mpegurl; charset=UTF-8"))
    }

    @Test fun `URL container is used when HEAD is unavailable`() {
        assertEquals("video/webm", castMimeType("https://cdn.example/movie.webm?token=abc".toHttpUrl(), null))
    }

    @Test fun `all HLS URI forms resolve against the redirected playlist location`() {
        val found = mutableListOf<String>()
        val playlist = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=SUBTITLES,URI="subs/en.m3u8"
            #EXT-X-KEY:METHOD=AES-128,URI="../key?token=abc"
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:4,
            segment.ts?token=def
            https://other.example/variant.m3u8
        """.trimIndent()
        val rewritten = rewriteCastPlaylist(playlist, "https://cdn.example/final/playlist.m3u8".toHttpUrl()) {
            found += it.toString()
            "http://phone/${found.size}"
        }
        assertEquals(listOf("https://cdn.example/final/subs/en.m3u8", "https://cdn.example/key?token=abc",
            "https://cdn.example/final/init.mp4", "https://cdn.example/final/segment.ts?token=def",
            "https://other.example/variant.m3u8"), found)
        assertTrue(rewritten.contains("URI=\"http://phone/2\""))
        assertFalse(rewritten.contains("token="))
        assertTrue(rewritten.contains("#EXTINF:4,"))
    }
}
