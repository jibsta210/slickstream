package com.slickstream.cast

import fi.iki.elonen.NanoHTTPD
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FilterInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Session-scoped bridge for the default Cast receiver, which cannot send the addon's headers.
 * Only registered media URLs are served, behind unguessable paths. Upstream credentials never
 * appear in the receiver URL. HLS children use the same headers and CORS policy as the playlist.
 */
internal class CastStreamServer(
    private val address: String,
    private val headers: Map<String, String>,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build(),
) : NanoHTTPD(address, 0) {
    private val routes = ConcurrentHashMap<String, HttpUrl>()
    private val paths = ConcurrentHashMap<HttpUrl, String>()
    private val token = UUID.randomUUID().toString()

    init {
        val handlers = ConcurrentHashMap.newKeySet<ClientHandler>()
        val executor = ThreadPoolExecutor(8, 8, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(16),
            { task -> Thread(task, "cast-http").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
        setAsyncRunner(object : AsyncRunner {
            override fun exec(code: ClientHandler) {
                handlers += code
                try { executor.execute(code) } catch (_: RejectedExecutionException) {
                    handlers -= code
                    code.close()
                }
            }
            override fun closed(code: ClientHandler) { handlers -= code }
            override fun closeAll() {
                handlers.toList().forEach { runCatching { it.close() } }
                handlers.clear()
                executor.shutdownNow()
            }
        })
    }

    data class Media(val url: String, val mimeType: String)

    /** Called on an IO dispatcher; HEAD is metadata only and must not consume torrent pieces. */
    fun prepare(url: String, title: String): Media {
        val upstream = url.toHttpUrl()
        val mime = runCatching {
            client.newCall(request(upstream).head().build()).execute().use { response ->
                if (response.isSuccessful) castMimeType(response.request.url, response.header("Content-Type"), title)
                else castMimeType(upstream, null, title)
            }
        }.getOrElse { castMimeType(upstream, null, title) }
        // DASH manifests contain nested URL templates. Do not silently send an unrewritten manifest.
        require(mime != "application/dash+xml") { "This stream can't be cast yet. Choose an MP4 or HLS stream." }
        start(SOCKET_READ_TIMEOUT, true)
        return Media(route(upstream), mime)
    }

    private fun route(url: HttpUrl): String {
        val path = paths.computeIfAbsent(url) {
            "/$token/${UUID.randomUUID()}".also { routes[it] = url }
        }
        return "http://$address:$listeningPort$path"
    }

    private fun request(url: HttpUrl): Request.Builder = Request.Builder().url(url).apply {
        headers.forEach { (key, value) ->
            if (key.lowercase() !in setOf("host", "connection", "content-length", "range", "accept-encoding")) {
                header(key, value)
            }
        }
        header("Accept-Encoding", "identity")
    }

    override fun serve(session: IHTTPSession): Response {
        val response = try {
            val url = routes[session.uri]
            when {
                url == null -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
                session.method == Method.OPTIONS -> newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")
                session.method != Method.GET && session.method != Method.HEAD ->
                    newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method not allowed")
                else -> relay(session, url)
            }
        } catch (_: Exception) {
            // URLs and exception messages can contain account credentials; never echo them to the LAN.
            newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "Stream unavailable")
        }
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        response.addHeader("Access-Control-Allow-Private-Network", "true")
        response.addHeader("Access-Control-Allow-Headers", "Range, Content-Type")
        response.addHeader("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges")
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private fun relay(session: IHTTPSession, url: HttpUrl): Response {
        val request = request(url).apply {
            if (session.method == Method.HEAD) head()
            session.headers["range"]?.let { header("Range", it) }
        }.build()
        val upstream = client.newCall(request).execute()
        try {
            val body = upstream.body
            val mime = upstream.header("Content-Type") ?: castMimeType(url, null)
            val status = object : Response.IStatus {
                override fun getRequestStatus() = upstream.code
                override fun getDescription() = "${upstream.code} ${upstream.message}"
            }
            if (session.method == Method.HEAD || body == null) {
                return newFixedLengthResponse(status, mime, ByteArray(0).inputStream(),
                    upstream.header("Content-Length")?.toLongOrNull()?.coerceAtLeast(0) ?: 0).apply {
                    copyRangeHeaders(upstream, this)
                    upstream.close()
                }
            }
            val isPlaylist = upstream.isSuccessful && (isHls(mime.substringBefore(';')) ||
                upstream.request.url.encodedPath.endsWith(".m3u8", true) ||
                (body.source().request(7) && body.source().buffer.clone().readUtf8(7) == "#EXTM3U"))
            if (isPlaylist) {
                val source = body.source()
                require(!source.request(MAX_PLAYLIST_BYTES + 1)) { "Playlist too large" }
                val text = source.readUtf8()
                val playlist = rewriteCastPlaylist(text, upstream.request.url, ::route)
                upstream.close()
                return newFixedLengthResponse(Response.Status.OK, "application/x-mpegURL", playlist)
            }
            val input = object : FilterInputStream(body.byteStream()) {
                override fun close() { try { super.close() } finally { upstream.close() } }
            }
            return (if (body.contentLength() >= 0) newFixedLengthResponse(status, mime, input, body.contentLength())
                else newChunkedResponse(status, mime, input)).apply { copyRangeHeaders(upstream, this) }
        } catch (e: Exception) {
            upstream.close()
            throw e
        }
    }

    private fun copyRangeHeaders(upstream: okhttp3.Response, response: Response) {
        listOf("Content-Range", "Accept-Ranges").forEach { name ->
            upstream.header(name)?.let { response.addHeader(name, it) }
        }
    }

    override fun stop() {
        client.dispatcher.cancelAll()
        super.stop()
        routes.clear()
        paths.clear()
        client.connectionPool.evictAll()
    }

    private companion object {
        const val MAX_PLAYLIST_BYTES = 2L * 1024 * 1024
    }
}
