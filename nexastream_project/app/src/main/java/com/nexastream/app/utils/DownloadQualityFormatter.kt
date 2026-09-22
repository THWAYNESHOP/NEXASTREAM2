package com.nexastream.app.utils

import androidx.media3.common.MimeTypes
import com.nexastream.app.models.Video
import java.util.Locale

object DownloadQualityFormatter {

    fun title(server: Video.Server): String {
        return listOfNotNull(
            resolution(server, server.video).takeIf { it != UNKNOWN },
            sourceName(server).takeIf { it.isNotBlank() }
        ).ifEmpty {
            listOf(server.name.ifBlank { "Unknown source" })
        }.joinToString(" - ")
    }

    fun details(server: Video.Server): String {
        val video = server.video
        return listOfNotNull(
            fileSize(server),
            format(video?.type, video?.source ?: server.src),
            supportType(video?.source ?: server.src, video?.type)
        ).distinct().joinToString(" - ")
    }

    fun qualityLabel(server: Video.Server, video: Video? = server.video): String {
        return listOfNotNull(
            resolution(server, video).takeIf { it != UNKNOWN },
            sourceName(server).takeIf { it.isNotBlank() },
            format(video?.type, video?.source ?: server.src)
        ).ifEmpty {
            listOf(server.name.ifBlank { "Unknown quality" })
        }.joinToString(" - ")
    }

    private fun resolution(server: Video.Server, video: Video? = server.video): String {
        val targetVideo = video ?: server.video
        val sourceUrl = targetVideo?.source.orEmpty()
        if (sourceUrl.startsWith("data:application/vnd.apple.mpegurl;base64,")) {
            try {
                val base64Data = sourceUrl.substringAfter("base64,")
                val decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                val manifestContent = String(decodedBytes, Charsets.UTF_8)
                
                val resolutions = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
                    .findAll(manifestContent)
                    .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                    .toList()
                
                if (resolutions.isNotEmpty()) {
                    val maxResolution = resolutions.maxOrNull()
                    if (maxResolution != null) {
                        return "${maxResolution}p"
                    }
                }
            } catch (e: Exception) {
                // Fallback on failure
            }
        }

        // Exclude the raw base64 data stream from being scanned by text regex matching
        val safeSourceText = if (sourceUrl.startsWith("data:")) "" else sourceUrl
        val text = "${server.name} ${server.src} $safeSourceText".lowercase(Locale.US)
        val numericResolution = Regex("""(?<!\d)(2160|1440|1080|720|576|540|480|360|240)p?(?!\d)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)

        return when {
            numericResolution != null -> "${numericResolution}p"
            Regex("""\b(4k|uhd)\b""").containsMatchIn(text) -> "2160p"
            Regex("""\b(full hd|fhd)\b""").containsMatchIn(text) -> "1080p"
            Regex("""\bhd\b""").containsMatchIn(text) -> "720p"
            Regex("""\bsd\b""").containsMatchIn(text) -> "480p"
            Regex("""\bcam\b""").containsMatchIn(text) -> "CAM"
            else -> UNKNOWN
        }
    }

    private fun sourceName(server: Video.Server): String {
        val name = server.name
            .replace(Regex("""(?i)\b(2160p|1440p|1080p|720p|576p|540p|480p|360p|240p|4k|uhd|fhd|hd|sd|cam)\b"""), "")
            .replace(Regex("""(?i)\b(\d+(?:\.\d+)?\s*(gb|mb|kb))\b"""), "")
            .replace(Regex("""\s*[-|/]\s*"""), " ")
            .trim()

        return name.ifBlank { server.id.ifBlank { "Source" } }
    }

    private fun fileSize(server: Video.Server): String? {
        val text = server.name
        return Regex("""(?i)\b\d+(?:\.\d+)?\s*(gb|mb|kb)\b""")
            .find(text)
            ?.value
            ?.uppercase(Locale.US)
    }

    private fun format(mimeType: String?, url: String): String {
        val lowerMime = mimeType?.lowercase(Locale.US).orEmpty()
        val lowerUrl = url.lowercase(Locale.US)

        return when {
            lowerMime == MimeTypes.APPLICATION_M3U8 || lowerMime.contains("mpegurl") || lowerUrl.contains(".m3u8") -> "HLS"
            lowerMime == MimeTypes.APPLICATION_MPD || lowerUrl.contains(".mpd") -> "DASH"
            lowerMime == MimeTypes.VIDEO_MP4 || lowerUrl.substringBefore('?').endsWith(".mp4") -> "MP4"
            lowerUrl.substringBefore('?').endsWith(".mkv") -> "MKV"
            lowerUrl.substringBefore('?').endsWith(".webm") -> "WEBM"
            lowerMime.startsWith("video/") -> lowerMime.removePrefix("video/").uppercase(Locale.US)
            else -> "Unknown format"
        }
    }

    private fun supportType(url: String, mimeType: String?): String {
        val format = format(mimeType, url)
        return when (format) {
            "HLS", "DASH" -> "Segmented media"
            "MP4", "MKV", "WEBM" -> "Single media resource"
            else -> "Offline support unknown"
        }
    }

    private const val UNKNOWN = "Unknown"
}
