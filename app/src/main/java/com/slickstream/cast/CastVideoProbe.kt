package com.slickstream.cast

import android.net.Uri
import com.slickstream.data.vlc.VlcEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.videolan.libvlc.Media
import org.videolan.libvlc.interfaces.IMedia

/** Parse the authenticated bridge URL, never trust a release name or PLAYING status as codec proof. */
internal suspend fun probeCastVideo(engine: VlcEngine, url: String): CastVideoFormat? =
    withContext(Dispatchers.Main) {
        val media = Media(engine.libVlc, Uri.parse(url))
        try {
            val parsed = CompletableDeferred<Unit>()
            media.setEventListener { event ->
                if (event.type == IMedia.Event.ParsedChanged) parsed.complete(Unit)
            }
            if (!media.parseAsync(IMedia.Parse.ParseNetwork, 5_000)) return@withContext null
            withTimeoutOrNull(6_000) {
                parsed.await()
                val tracks = (0 until media.trackCount).mapNotNull { media.getTrack(it) as? IMedia.VideoTrack }
                // Multiple/unknown video tracks are converted rather than guessing which is selected.
                tracks.singleOrNull()?.let {
                    CastVideoFormat(fourccString(it.fourcc), it.profile, it.level,
                        it.width, it.height, it.frameRateNum, it.frameRateDen)
                }
            }
        } finally {
            media.setEventListener(null)
            media.release()
        }
    }

/** IMedia.codec is a translated display name; fourcc is the actual machine-readable codec. */
private fun fourccString(value: Int): String = buildString(4) {
    repeat(4) { append(((value ushr (it * 8)) and 0xff).toChar()) }
}
