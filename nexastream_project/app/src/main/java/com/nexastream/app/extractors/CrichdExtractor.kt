package com.nexastream.app.extractors

import com.nexastream.app.models.Video
import com.nexastream.app.utils.JsUnpacker
import okhttp3.Request
import org.jsoup.Jsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CrichdExtractor : Extractor() {
    override val name: String = "Crichd"
    override val mainUrl: String = "https://crichd.online"
    override val aliasUrls: List<String> = listOf("https://cdn.crichd.com", "https://crichd.tv", "https://crichd.vip")

    private val client = com.nexastream.app.utils.NetworkClient.compatibleTrustAll.newBuilder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override suspend fun extract(link: String): Video = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(link)
            .header("Referer", "https://crichd.online/")
            .header("Origin", "https://crichd.online")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .build()

        val response = client.newCall(request).execute()
        val html = response.body?.string() ?: throw Exception("Empty response from $link")
        val doc = Jsoup.parse(html)

        // 1. Direct HLS link in script
        val m3u8Regex = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
        m3u8Regex.find(html)?.let {
            return@withContext Video(
                source = it.groupValues[1],
                headers = mapOf("Referer" to "https://crichd.online/", "Origin" to "https://crichd.online"),
                maintainToken = true
            )
        }

        // 2. Packed JS
        if (html.contains("eval(function(p,a,c,k,e,d)")) {
            val unpacked = JsUnpacker(html).unpack()
            if (unpacked != null) {
                m3u8Regex.find(unpacked)?.let {
                    return@withContext Video(
                        source = it.groupValues[1],
                        headers = mapOf("Referer" to "https://crichd.online/", "Origin" to "https://crichd.online"),
                        maintainToken = true
                    )
                }
            }
        }

        // 3. Iframe redirection - extract with proper headers
        val iframe = doc.selectFirst("iframe")
        val iframeSrc = iframe?.attr("src")
        if (!iframeSrc.isNullOrBlank() && iframeSrc.startsWith("http") && !iframeSrc.contains("crichd")) {
            val iframeRequest = Request.Builder()
                .url(iframeSrc)
                .header("Referer", "https://crichd.online/")
                .header("Origin", "https://crichd.online")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .build()

            val iframeResponse = client.newCall(iframeRequest).execute()
            val iframeHtml = iframeResponse.body?.string() ?: throw Exception("Empty response from iframe $iframeSrc")

            // Look for m3u8 in iframe content
            m3u8Regex.find(iframeHtml)?.let {
                return@withContext Video(
                    source = it.groupValues[1],
                    headers = mapOf("Referer" to "https://crichd.online/", "Origin" to "https://crichd.online"),
                    maintainToken = true
                )
            }

            // Also check for packed JS in iframe
            if (iframeHtml.contains("eval(function(p,a,c,k,e,d)")) {
                val unpacked = JsUnpacker(iframeHtml).unpack()
                if (unpacked != null) {
                    m3u8Regex.find(unpacked)?.let {
                        return@withContext Video(
                            source = it.groupValues[1],
                            headers = mapOf("Referer" to "https://crichd.online/", "Origin" to "https://crichd.online"),
                            maintainToken = true
                        )
                    }
                }
            }
        }

        throw Exception("Could not find video source in $link")
    }
}
