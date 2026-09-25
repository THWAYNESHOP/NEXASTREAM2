package com.nexastream.app.extractors

import com.nexastream.app.models.Video
import com.nexastream.app.utils.WebSniffer

/**
 * Universal Sniffer Extractor.
 * Serves as a general-purpose fallback extractor using [WebSniffer] to capture
 * media streams (.m3u8, .mp4, .mpd) from arbitrary video embed URLs.
 */
class SnifferExtractor : Extractor() {

    override val name = "WebSniffer"
    override val mainUrl = "https://sniffer.generic"

    private val sniffer = WebSniffer()

    override suspend fun extract(link: String): Video {
        return sniffer.sniffToVideo(link)
            ?: throw Exception("WebSniffer failed to intercept a playable video stream from: $link")
    }

    suspend fun extract(
        link: String,
        customHeaders: Map<String, String> = emptyMap(),
        customUserAgent: String? = null,
        timeoutMs: Long = 25000L
    ): Video {
        return sniffer.sniffToVideo(
            targetUrl = link,
            customHeaders = customHeaders,
            customUserAgent = customUserAgent,
            timeoutMs = timeoutMs
        ) ?: throw Exception("WebSniffer failed to intercept a playable video stream from: $link")
    }
}
