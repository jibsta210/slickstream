package com.slickstream.cast

import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class CastStreamServerTest {
    private val client = OkHttpClient()
    private val servers = mutableListOf<NanoHTTPD>()

    @After fun cleanup() {
        servers.forEach { it.stop() }
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }

    private fun origin(handler: (NanoHTTPD.IHTTPSession) -> NanoHTTPD.Response): String {
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                val response = handler(session)
                if (session.method != Method.HEAD) return response
                // NanoHTTPD 2.3.1 does not suppress a fixed-length HEAD body itself. Return an
                // empty stream with the advertised GET length, as a conforming origin does.
                return newFixedLengthResponse(response.status, response.mimeType,
                    ByteArray(0).inputStream(), response.data.available().toLong()).apply {
                    listOf("Location", "Content-Range", "Accept-Ranges").forEach { header ->
                        response.getHeader(header)?.let { addHeader(header, it) }
                    }
                    response.close()
                }
            }
        }
        servers += server
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        return "http://127.0.0.1:${server.listeningPort}"
    }

    private fun bridge(headers: Map<String, String> = emptyMap()): CastStreamServer =
        CastStreamServer("127.0.0.1", headers).also { servers += it }

    @Test fun `receiver gets ranged video with required headers and CORS without leaking credentials`() {
        val received = CopyOnWriteArrayList<Map<String, String>>()
        val base = origin { session ->
            received += session.headers
            assertEquals("https://watch.example/", session.headers["referer"])
            assertEquals("secret", session.headers["authorization"])
            if (session.headers["range"] == "bytes=2-5") {
                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.PARTIAL_CONTENT, "video/mp4", "2345")
                    .apply { addHeader("Content-Range", "bytes 2-5/10"); addHeader("Accept-Ranges", "bytes") }
            } else NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "video/mp4", "0123456789")
        }
        val media = bridge(mapOf("Referer" to "https://watch.example/", "Authorization" to "secret"))
            .prepare("$base/movie?token=private", "Movie")
        assertEquals("video/mp4", media.mimeType)
        assertFalse(media.url.contains("private"))
        client.newCall(Request.Builder().url(media.url).header("Range", "bytes=2-5").build()).execute().use {
            assertEquals(206, it.code)
            assertEquals("2345", it.body!!.string())
            assertEquals("bytes 2-5/10", it.header("Content-Range"))
            assertEquals("*", it.header("Access-Control-Allow-Origin"))
        }
        client.newCall(Request.Builder().url(media.url).head().build()).execute().use {
            assertEquals("10", it.header("Content-Length"))
            assertEquals("", it.body!!.string())
        }
        val before = received.size
        client.newCall(Request.Builder().url(media.url).method("OPTIONS", null).build()).execute().use {
            assertEquals(204, it.code)
            assertTrue(it.header("Access-Control-Allow-Headers")!!.contains("Range"))
        }
        assertEquals(before, received.size)
    }

    @Test fun `redirected protected HLS rewrites and serves variants segments keys and init maps`() {
        val requested = CopyOnWriteArrayList<String>()
        val base = origin { session ->
            requested += session.uri
            if (session.headers["referer"] != "https://watch.example/") {
                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN, "text/plain", "Forbidden")
            } else when (session.uri) {
                "/start" -> NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.REDIRECT, "text/plain", "")
                    .apply { addHeader("Location", "/final/master.m3u8?token=secret") }
                "/final/master.m3u8" -> NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK,
                    "application/vnd.apple.mpegurl", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nchild.m3u8\n")
                "/final/child.m3u8" -> NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK,
                    "text/plain", "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:4,\nseg.ts\n")
                else -> NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/octet-stream", "abc")
            }
        }
        val media = bridge(mapOf("Referer" to "https://watch.example/")).prepare("$base/start", "Movie")
        assertEquals("application/x-mpegURL", media.mimeType)
        fun get(url: String) = client.newCall(Request.Builder().url(url).build()).execute().use {
            assertEquals(200, it.code)
            it.body!!.string()
        }
        val master = get(media.url)
        val childUrl = master.lines().first { it.startsWith("http") }
        assertFalse(childUrl.contains("secret"))
        val child = get(childUrl)
        val keyUrl = Regex("URI=\"([^\"]+)\"").findAll(child).map { it.groupValues[1] }.toList()
        keyUrl.forEach { assertEquals("abc", get(it)) }
        assertEquals("abc", get(child.lines().first { it.startsWith("http") }))
        assertTrue(requested.containsAll(listOf("/final/child.m3u8", "/final/key", "/final/init.mp4", "/final/seg.ts")))
    }

    @Test fun `expired routes and unknown URLs cannot access upstream`() {
        var requests = 0
        val base = origin {
            requests++
            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "video/mp4", "video")
        }
        val server = bridge()
        val media = server.prepare("$base/movie.mp4", "Movie")
        val before = requests
        client.newCall(Request.Builder().url(media.url.substringBeforeLast('/') + "/unknown?url=$base/secret").build())
            .execute().use { assertEquals(404, it.code) }
        assertEquals(before, requests)
        server.stop()
        assertTrue(runCatching { client.newCall(Request.Builder().url(media.url).build()).execute() }.isFailure)
    }

    @Test fun `upstream range rejection retains status and content range`() {
        val base = origin {
            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "Range")
                .apply { addHeader("Content-Range", "bytes */10") }
        }
        val media = bridge().prepare("$base/movie.mp4", "Movie")
        client.newCall(Request.Builder().url(media.url).header("Range", "bytes=99-").build()).execute().use {
            assertEquals(416, it.code)
            assertEquals("bytes */10", it.header("Content-Range"))
        }
    }
}
