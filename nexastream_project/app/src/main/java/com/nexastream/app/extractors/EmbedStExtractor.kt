package com.nexastream.app.extractors

import com.nexastream.app.models.Video
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EmbedStExtractor : Extractor() {
    override val name: String = "EmbedSt"
    override val mainUrl: String = "https://embed.st"
    override val aliasUrls: List<String> = listOf("https://top-embed.com")

    private val client = OkHttpClient()

    override suspend fun extract(link: String): Video = withContext(Dispatchers.IO) {
        android.util.Log.d("EmbedStExtractor", "Extracting from: $link")
        val request = Request.Builder()
            .url(link)
            .header("Referer", "https://streamed.pk/")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .build()
        
        val response = client.newCall(request).execute()
        val html = response.body?.string() ?: throw Exception("Empty response from $link")
        val doc = Jsoup.parse(html)
        
        // Pattern 1: Direct source in scripts
        val scripts = doc.select("script")
        for (script in scripts) {
            val scriptContent = script.html()
            
            // Check for Clappr/Hls source pattern
            val sourceRegex = Regex("""source\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")
            sourceRegex.find(scriptContent)?.let {
                val url = it.groupValues[1]
                android.util.Log.d("EmbedStExtractor", "Found m3u8 source: $url")
                return@withContext Video(
                    source = url,
                    headers = mapOf("Referer" to "https://streamed.pk/")
                )
            }
            
            // Check for atob pattern (often used for obfuscation)
            val atobRegex = Regex("""atob\s*\(\s*["']([^"']+)["']\s*\)""")
            atobRegex.findAll(scriptContent).forEach { match ->
                try {
                    val decoded = String(Base64.decode(match.groupValues[1], Base64.DEFAULT))
                    if (decoded.contains(".m3u8")) {
                        android.util.Log.d("EmbedStExtractor", "Found decoded m3u8: $decoded")
                        return@withContext Video(
                            source = decoded,
                            headers = mapOf("Referer" to "https://streamed.pk/")
                        )
                    }
                } catch (ignored: Exception) {}
            }

            // Pattern for vars like hls_url = "..."
            val varRegex = Regex("""(?:var|let|const)\s+(?:hls_url|stream_url|url)\s*=\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")
            varRegex.find(scriptContent)?.let {
                val url = it.groupValues[1]
                android.util.Log.d("EmbedStExtractor", "Found var source: $url")
                return@withContext Video(
                    source = url,
                    headers = mapOf("Referer" to "https://streamed.pk/")
                )
            }
        }
        
        // Pattern 2: iframe redirection (sometimes they wrap another player)
        val iframes = doc.select("iframe")
        for (iframe in iframes) {
            val iframeSrc = iframe.attr("src")
            if (!iframeSrc.isNullOrBlank() && iframeSrc.startsWith("http")) {
                 if (!iframeSrc.contains("embed.st") && !iframeSrc.contains("top-embed.com")) {
                     android.util.Log.d("EmbedStExtractor", "Found sub-iframe: $iframeSrc")
                     return@withContext Extractor.extract(iframeSrc)
                 }
            }
        }

        // Pattern 3: Packed JS (eval(function(p,a,c,k,e,d)...))
        if (html.contains("eval(function(p,a,c,k,e,d)")) {
            val packedJS = Regex("(eval\\(function\\(p,a,c,k,e,d\\)(.|\\n)*?)</script>")
                .find(html)?.groupValues?.get(1)
            
            if (packedJS != null) {
                val unpacked = com.nexastream.app.utils.JsUnpacker(packedJS).unpack()
                if (unpacked != null) {
                    android.util.Log.d("EmbedStExtractor", "Unpacked JS found")
                    val m3u8Regex = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
                    m3u8Regex.find(unpacked)?.let {
                        val url = it.groupValues[1]
                        android.util.Log.d("EmbedStExtractor", "Found m3u8 in unpacked JS: $url")
                        return@withContext Video(
                            source = url,
                            headers = mapOf("Referer" to "https://streamed.pk/")
                        )
                    }
                }
            }
        }

        throw Exception("Could not find video source in $link")
    }
}
