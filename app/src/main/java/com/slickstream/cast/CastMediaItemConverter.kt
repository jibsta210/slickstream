package com.slickstream.cast

import androidx.annotation.OptIn
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaQueueItem

/** VLC's receiver messages have no Media3 custom JSON. CastPlayer must still track the session. */
@OptIn(UnstableApi::class)
internal class CastMediaItemConverter : MediaItemConverter {
    private val delegate = DefaultMediaItemConverter()

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem = delegate.toMediaQueueItem(mediaItem)

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val info = mediaQueueItem.media ?: return MediaItem.EMPTY
        if (info.customData?.has("mediaItem") == true) {
            runCatching { delegate.toMediaItem(mediaQueueItem) }.getOrNull()?.let { return it }
        }
        val title = info.metadata?.getString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE)
        return MediaItem.Builder()
            .setUri(info.contentId)
            .setMimeType(info.contentType)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
    }
}
