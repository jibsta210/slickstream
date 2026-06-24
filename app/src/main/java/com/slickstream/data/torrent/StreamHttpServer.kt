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

        // One eMMC read of [chunkSize] services many of NanoHTTPD's small read() calls, so the
        // ensureRange()/advanceReadHead() native round-trips fire ~16-64x less often. On a no-NCQ
        // TV eMMC, a few large sequential reads beat thousands of tiny random-ish ones — this is a
        // big part of stopping the disk-contention freeze. 256 KB on low-power is heap-safe.
        private val chunkSize = if (engine.isLowPower) 256 * 1024 else 1024 * 1024
        private val chunk = ByteArray(chunkSize)
        private var chunkStart = -1L
        private var chunkLen = 0

        // Fail fast on low-power TV: a stalled read that would otherwise pin a NanoHTTPD worker for
        // up to 60s (and hold the native handle) becomes a quick recoverable retry. 8s is safe —
        // head/moov fuel arrives in <1s under the 8 MB/s cap.
        private val readWaitMs = if (engine.isLowPower) 8_000L else READ_WAIT_TIMEOUT_MS
        private val flushWaitMs = if (engine.isLowPower) 8_000L else FLUSH_WAIT_BUDGET_MS

        private fun raf(): RandomAccessFile =
            raf ?: RandomAccessFile(file, "r").also { raf = it }

        override fun read(): Int {
            val single = ByteArray(1)
            val n = read(single, 0, 1)
            return if (n <= 0) -1 else single[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position > end) return -1
            // Refill the look-ahead chunk when the read position leaves the current window.
            if (position < chunkStart || position >= chunkStart + chunkLen) {
                fillChunk()
                if (chunkLen <= 0) return -1
            }
            val inChunk = (position - chunkStart).toInt()
            val toCopy = minOf(len, chunkLen - inChunk)
            System.arraycopy(chunk, inChunk, b, off, toCopy)
            position += toCopy
            return toCopy
        }

        /** Ensure + flush-retry one [chunkSize] window starting at the current read position. */
        private fun fillChunk() {
            val fillStart = position
            val fillEnd = minOf(fillStart + chunkSize - 1, end)
            val want = (fillEnd - fillStart + 1).toInt()

            // Keep the engine's look-ahead band hot at the ACTUAL read position (not a buffer-fill
            // position) so head prioritisation tracks where the player really is.
            engine.advanceReadHead(infoHash, fillStart)
            val ready = runBlocking {
                engine.ensureRange(infoHash, fillStart, fillEnd, readWaitMs)
            }
            if (!ready) throw IOException("range $fillStart-$fillEnd not available")

            // Pieces report present, but libtorrent flips the bitfield on hash-pass *before* its
            // write cache is flushed to disk, so the RAF can briefly read short. Retry against a hard
            // wall-clock deadline, re-seeking the SAME handle (reopening does NOT surface unflushed
            // data — that just storms open()/close()/seek() syscalls at flash blocks libtorrent is
            // mid-write on). Only reopen on a real IOException.
            val deadlineNanos = System.nanoTime() + flushWaitMs * 1_000_000L
            var backoff = 10L
            var filled = 0
            while (filled < want) {
                val n = try {
                    raf().apply { seek(fillStart + filled) }.read(chunk, filled, want - filled)
                } catch (e: IOException) {
                    Log.w(TAG, "read error, reopening", e)
                    raf?.close(); raf = null
                    -1
                }
                if (n > 0) {
                    filled += n
                    continue
                }
                if (System.nanoTime() >= deadlineNanos) {
                    if (filled > 0) break // serve what flushed; the next read refills the remainder
                    throw IOException("backing file not flushed for $fillStart")
                }
                Thread.sleep(backoff)
                backoff = (backoff * 2).coerceAtMost(200L)
            }
            chunkStart = fillStart
            chunkLen = filled
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
