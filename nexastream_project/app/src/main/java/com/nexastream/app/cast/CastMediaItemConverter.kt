package com.nexastream.app.cast

import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaQueueItem
import com.nexastream.app.utils.inferVideoMimeType

/** Adds the MIME type required by Media3 Cast when a provider did not declare one. */
@UnstableApi
class CastMediaItemConverter(
    private val delegate: MediaItemConverter = DefaultMediaItemConverter(),
) : MediaItemConverter {

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val localConfiguration = mediaItem.localConfiguration
        val castMediaItem = if (localConfiguration?.mimeType.isNullOrBlank() && localConfiguration != null) {
            mediaItem.buildUpon()
                .setMimeType(inferVideoMimeType(localConfiguration.uri.toString()))
                .build()
        } else {
            mediaItem
        }

        return delegate.toMediaQueueItem(castMediaItem)
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem =
        delegate.toMediaItem(mediaQueueItem)
}
