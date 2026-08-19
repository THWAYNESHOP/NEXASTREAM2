package com.nexastream.app.utils

import androidx.media3.common.MimeTypes
import java.util.Locale

/**
 * Resolves a Cast-compatible MIME type from a media URL.
 *
 * Media3's Cast converter requires a non-null MIME type, while the local player can infer one
 * by inspecting the stream. Most opaque provider URLs in NexaStream are HLS endpoints, so HLS is
 * the safest fallback when the URL has no useful extension.
 */
fun inferVideoMimeType(
    source: String,
    fallback: String = MimeTypes.APPLICATION_M3U8,
): String {
    val normalized = source.trim().lowercase(Locale.ROOT).substringBefore('#')
    val path = normalized.substringBefore('?')

    return when {
        normalized.startsWith("data:application/dash+xml") -> MimeTypes.APPLICATION_MPD
        normalized.startsWith("data:application/vnd.apple.mpegurl") ||
            normalized.startsWith("data:application/x-mpegurl") -> MimeTypes.APPLICATION_M3U8
        path.endsWith(".m3u8") || path.endsWith(".m3u") -> MimeTypes.APPLICATION_M3U8
        path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
        path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mov") -> MimeTypes.VIDEO_MP4
        path.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
        path.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
        path.endsWith(".ts") || path.endsWith(".m2ts") -> MimeTypes.VIDEO_MP2T
        path.endsWith(".mpeg") || path.endsWith(".mpg") -> MimeTypes.VIDEO_MPEG
        path.endsWith(".mp3") -> MimeTypes.AUDIO_MPEG
        path.endsWith(".aac") -> MimeTypes.AUDIO_AAC
        path.endsWith(".m4a") -> MimeTypes.AUDIO_MP4
        else -> fallback
    }
}
