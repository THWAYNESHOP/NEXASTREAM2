package com.nexastream.app.extractors

import com.nexastream.app.models.Video
import com.nexastream.app.utils.JsUnpacker
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CrichdExtractor : Extractor() {
    override val name: String = "Crichd"
    override val mainUrl: String = "https://crichd.online"
    override val aliasUrls: List<String> = listOf("https://cdn.crichd.com", "https://crichd.tv", "https://crichd.vip")

    private val client = OkHttpClient()

    override suspend fun extract(link: String): Video = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(link)
            .header("Referer", "https://crichd.online/")
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
                headers = mapOf("Referer" to "https://crichd.online/", "Origin" to "https://crichd.online")
            )
        }

        // 2. Packed JS
        if (html.contains("eval(function(p,a,c,k,e,d)")) {
            val unpacked = JsUnpacker(html).unpack()
            if (unpacked != null) {
                m3u8Regex.find(unpacked)?.let {
                    return@withContext Video(
                        source = it.groupValues[1],
                        headers = mapOf("Referer" to "https://crichd.online/", "Origin" to "https://crichd.online")
                    )
                }
            }
        }

        // 3. Iframe redirection
        val iframe = doc.selectFirst("iframe")
        val iframeSrc = iframe?.attr("src")
        if (!iframeSrc.isNullOrBlank() && iframeSrc.startsWith("http") && !iframeSrc.contains("crichd")) {
            return@withContext Extractor.extract(iframeSrc)
        }

        throw Exception("Could not find video source in $link")
    }
}
