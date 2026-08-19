package com.nexastream.app.utils

import androidx.media3.common.MimeTypes
import com.nexastream.app.models.Video
import java.util.Locale

object DownloadQualityFormatter {

    fun title(server: Video.Server): String {
        return listOfNotNull(
            resolution(server).takeIf { it != UNKNOWN },
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
            resolution(server).takeIf { it != UNKNOWN },
            sourceName(server).takeIf { it.isNotBlank() },
            format(video?.type, video?.source ?: server.src)
        ).ifEmpty {
            listOf(server.name.ifBlank { "Unknown quality" })
        }.joinToString(" - ")
    }

    private fun resolution(server: Video.Server): String {
        val text = "${server.name} ${server.src} ${server.video?.source.orEmpty()}".lowercase(Locale.US)
        val numericResolution = Regex("""(?<!\d)(2160|1440|1080|720|576|540|480|360|240)p?(?!\d)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)

        return when {
            numericResolution != null -> "${numericResolution}p"
            text.contains("4k") || text.contains("uhd") -> "2160p"
            text.contains("full hd") || text.contains("fhd") -> "1080p"
            text.contains("hd") -> "720p"
            text.contains("sd") -> "480p"
            text.contains("cam") -> "CAM"
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
