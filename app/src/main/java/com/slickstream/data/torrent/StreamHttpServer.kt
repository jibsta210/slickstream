package com.slickstream.data.torrent

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Local HTTP bridge that serves a (possibly still-downloading) torrent file to ExoPlayer
 * over 127.0.0.1 with full HTTP Range support.
 *
 * URL shape: http://127.0.0.1:<port>/<infoHash>
 *
 * When the player requests a byte range that isn't on disk yet, the server raises the
 * priority of the covering pieces via [TorrentEngine.ensureRange] and blocks briefly until
 * they are available rather than failing the request.
 */
class StreamHttpServer(
    private val engine: TorrentEngine,
    // hostname = null binds all interfaces (0.0.0.0) so a Chromecast on the LAN can reach the
    // stream; local playback still uses the 127.0.0.1 baseUrl below.
) : NanoHTTPD(null as String?, 0 /* ephemeral port */) {

    /** The local base URL, valid only after [start]. */
    val baseUrl: String
        get() = "http://$LOOPBACK:$listeningPort"

    fun urlFor(infoHash: String): String = "$baseUrl/$infoHash"

    override fun serve(session: IHTTPSession): Response {
        return try {
            handle(session)
        } catch (t: Throwable) {
            Log.e(TAG, "serve failed", t)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "stream error")
        }
    }

    private fun handle(session: IHTTPSession): Response {
        val infoHash = session.uri.trimStart('/').substringBefore('/').lowercase()
        if (infoHash.isBlank()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "no info hash")
        }

        val path = engine.filePath(infoHash)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "unknown torrent")
        val file = File(path)
        val totalLength = engine.fileLength(infoHash)
        if (totalLength <= 0) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "file not ready")
        }

        val mime = mimeOf(file.name)
        val rangeHeader = session.headers["range"]

        // HEAD request: just advertise capabilities + length.
        if (session.method == Method.HEAD) {
            val empty: InputStream = ByteArray(0).inputStream()
            return newFixedLengthResponse(Response.Status.OK, mime, empty, 0L).apply {
                addCommonHeaders(this, totalLength)
            }
        }

        return if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            servePartial(infoHash, file, totalLength, mime, rangeHeader)
        } else {
            serveFull(infoHash, file, totalLength, mime)
        }
    }

    private fun serveFull(infoHash: String, file: File, totalLength: Long, mime: String): Response {
        // Ensure at least the head is present before we start streaming from 0.
        blockForRange(infoHash, 0, (HEAD_SERVE_BYTES - 1).coerceAtMost(totalLength - 1))
        val stream = TorrentFileInputStream(engine, infoHash, file, 0, totalLength - 1)
        val resp = newFixedLengthResponse(Response.Status.OK, mime, stream, totalLength)
        addCommonHeaders(resp, totalLength)
        return resp
    }

    private fun servePartial(
        infoHash: String,
        file: File,
        totalLength: Long,
        mime: String,
        rangeHeader: String,
    ): Response {
        val (start, endRaw) = parseRange(rangeHeader, totalLength)
            ?: return rangeNotSatisfiable(totalLength)
        val end = endRaw.coerceAtMost(totalLength - 1)
        if (start > end || start < 0) return rangeNotSatisfiable(totalLength)

        val contentLength = end - start + 1
        // Make sure the first chunk of the requested window is on disk before we respond.
        blockForRange(infoHash, start, (start + HEAD_SERVE_BYTES - 1).coerceAtMost(end))

        val stream = TorrentFileInputStream(engine, infoHash, file, start, end)
        val resp = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, stream, contentLength)
        addCommonHeaders(resp, totalLength)
        resp.addHeader("Content-Range", "bytes $start-$end/$totalLength")
        return resp
    }

    private fun blockForRange(infoHash: String, start: Long, endInclusive: Long) {
        runBlocking {
            engine.ensureRange(infoHash, start, endInclusive, RANGE_BLOCK_TIMEOUT_MS)
        }
    }

    private fun rangeNotSatisfiable(totalLength: Long): Response {
        val resp = newFixedLengthResponse(
            Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "range not satisfiable",
        )
        resp.addHeader("Content-Range", "bytes */$totalLength")
        resp.addHeader("Accept-Ranges", "bytes")
        return resp
    }

    private fun addCommonHeaders(resp: Response, @Suppress("UNUSED_PARAMETER") totalLength: Long) {
        // NanoHTTPD sets Content-Length itself from the fixed response length; we only add
        // the streaming-related headers here.
        resp.addHeader("Accept-Ranges", "bytes")
        // Loopback only; no caching across runs.
        resp.addHeader("Cache-Control", "no-store")
    }

    /** Parse a single-range "bytes=start-end" header. Returns (start, end) inclusive. */
    private fun parseRange(header: String, totalLength: Long): Pair<Long, Long>? {
        val spec = header.removePrefix("bytes=").substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startStr = spec.substring(0, dash).trim()
        val endStr = spec.substring(dash + 1).trim()

        return when {
            startStr.isEmpty() -> {
                // Suffix range: last N bytes.
                val n = endStr.toLongOrNull() ?: return null
                val start = (totalLength - n).coerceAtLeast(0)
                start to (totalLength - 1)
            }
            endStr.isEmpty() -> {
                val start = startStr.toLongOrNull() ?: return null
                start to (totalLength - 1)
            }
            else -> {
                val start = startStr.toLongOrNull() ?: return null
                val end = endStr.toLongOrNull() ?: return null
                start to end
            }
        }
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "ts" -> "video/mp2t"
        "flv" -> "video/x-flv"
        else -> "video/mp4"
    }

    /**
     * Streams a byte window [start, end] out of the torrent file, pulling pieces on demand.
     * Before each read crosses into not-yet-downloaded territory, it asks the engine to
     * fetch + wait for that slice so the player never reads garbage / short data.
     */
    private class TorrentFileInputStream(
        private val engine: TorrentEngine,
        private val infoHash: String,
        private val file: File,
        private val start: Long,
        private val end: Long,
    ) : InputStream() {

        private var position = start
        private var raf: RandomAccessFile? = null

        private fun raf(): RandomAccessFile {
            return raf ?: RandomAccessFile(file, "r").also { raf = it }
        }

        override fun read(): Int {
            val single = ByteArray(1)
            val n = read(single, 0, 1)
            return if (n <= 0) -1 else single[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position > end) return -1
            val remaining = end - position + 1
            val toRead = minOf(len.toLong(), remaining).toInt()
            if (toRead <= 0) return -1

            // Ensure the bytes we're about to read are downloaded.
            val rangeEnd = position + toRead - 1
            // Tell the engine where the player (or Chromecast) is reading so it keeps the
            // sliding look-ahead band hot ahead of this position.
            engine.advanceReadHead(infoHash, position)
            val ready = runBlocking {
                engine.ensureRange(infoHash, position, rangeEnd, READ_WAIT_TIMEOUT_MS)
            }
            if (!ready) {
                // Couldn't get the data in time — surface as a transient I/O error so the
                // player retries rather than treating it as EOF.
                throw IOException("range $position-$rangeEnd not available")
            }

            // Pieces report present, but libtorrent flips the bitfield on hash-pass *before* its
            // write cache is flushed to disk, so the RAF can briefly read 0 bytes. Retry against a
            // hard wall-clock deadline — NO recursion (constant stack, can't StackOverflow) so the
            // NanoHTTPD worker thread can never hang indefinitely.
            val deadlineNanos = System.nanoTime() + FLUSH_WAIT_BUDGET_MS * 1_000_000L
            var backoff = 10L
            while (true) {
                val n = try {
                    raf().apply { seek(position) }.read(b, off, toRead)
                } catch (e: IOException) {
                    Log.w(TAG, "read error, reopening", e)
                    raf?.close(); raf = null
                    -1
                }
                if (n > 0) {
                    position += n
                    return n
                }
                if (System.nanoTime() >= deadlineNanos) {
                    throw IOException("backing file not flushed for $position-$rangeEnd")
                }
                // Drop the stale handle, back off, and re-seek so we observe freshly flushed data.
                raf?.close(); raf = null
                Thread.sleep(backoff)
                backoff = (backoff * 2).coerceAtMost(200L)
            }
        }

        override fun available(): Int {
            val remaining = end - position + 1
            return remaining.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        }

        override fun close() {
            runCatching { raf?.close() }
            raf = null
        }

        companion object {
            private const val TAG = "TorrentFileInputStream"

            /** Per-read wait budget while streaming further into the file. */
            private const val READ_WAIT_TIMEOUT_MS = 30_000L

            /** Max time to wait for libtorrent to flush a hashed-but-uncached piece to disk. */
            private const val FLUSH_WAIT_BUDGET_MS = 30_000L
        }
    }

    companion object {
        private const val TAG = "StreamHttpServer"
        private const val LOOPBACK = "127.0.0.1"

        /** Bytes to confirm present before responding to a (re)seek. */
        private const val HEAD_SERVE_BYTES = 512L * 1024L

        /** How long to block a Range response while waiting on the head slice. */
        private const val RANGE_BLOCK_TIMEOUT_MS = 30_000L
    }
}
