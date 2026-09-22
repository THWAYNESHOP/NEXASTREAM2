package com.nexastream.app.extractors

import com.nexastream.app.models.Video
import okhttp3.Request
import org.jsoup.Jsoup
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EmbedStExtractor : Extractor() {
    override val name: String = "EmbedSt"
    override val mainUrl: String = "https://embed.st"
    override val aliasUrls: List<String> = listOf(
        "top-embed.com",
        "streamed.st",
        "streamed.pk",
        "streamed.is",
        "v3.streamed.su",
        "streamed.su",
        "strmd.link",
        "streampk.org",
        "stream.pk"
    )

    private val client = com.nexastream.app.utils.NetworkClient.compatibleTrustAll.newBuilder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override suspend fun extract(link: String): Video = withContext(Dispatchers.IO) {
        android.util.Log.d("EmbedStExtractor", "Extracting from: $link")
        
        // Network Master Referer logic: if it's any "streamed" mirror, use the main portal as referer
        val isStreamedNetwork = aliasUrls.any { it.contains("streamed") || it.contains("stream.pk") } && link.contains("stream")
        val referer = if (isStreamedNetwork) "https://streamed.st/" else {
            try {
                val uri = java.net.URI(link)
                "${uri.scheme}://${uri.host}/"
            } catch (_: Exception) {
                "https://embed.st/"
            }
        }
        
        val request = Request.Builder()
            .url(link)
            .header("Referer", referer)
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
            val sourceRegex = Regex("""source\s*[:\s]+["'](https?://[^"']+\.m3u8[^"']*)["']""")
            sourceRegex.find(scriptContent)?.let {
                val url = it.groupValues[1]
                android.util.Log.d("EmbedStExtractor", "Found m3u8 source: $url")
                return@withContext Video(
                    source = url,
                    headers = mapOf(
                        "Referer" to referer,
                        "Origin" to referer.trimEnd('/')
                    ),
                    maintainToken = true
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
                            headers = mapOf(
                                "Referer" to referer,
                                "Origin" to referer.trimEnd('/')
                            ),
                            maintainToken = true
                        )
                    }
                } catch (ignored: Exception) {}
            }

            // Pattern for vars like hls_url = "..." or file: "..."
            val genericRegex = Regex("""(?:var|let|const|file|url)\s*[:=]\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")
            genericRegex.find(scriptContent)?.let {
                val url = it.groupValues[1]
                android.util.Log.d("EmbedStExtractor", "Found generic source: $url")
                return@withContext Video(
                    source = url,
                    headers = mapOf(
                        "Referer" to referer,
                        "Origin" to referer.trimEnd('/')
                    ),
                    maintainToken = true
                )
            }
        }
        
        // Pattern 2: iframe redirection (sometimes they wrap another player)
        val iframes = doc.select("iframe")
        for (iframe in iframes) {
            val iframeSrc = iframe.attr("src")
            if (!iframeSrc.isNullOrBlank() && iframeSrc.startsWith("http")) {
                 val isAlias = aliasUrls.any { iframeSrc.contains(it.substringAfter("://")) }
                 if (!iframeSrc.contains("embed.st") && !isAlias) {
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
                            headers = mapOf(
                                "Referer" to referer,
                                "Origin" to referer.trimEnd('/')
                            ),
                            maintainToken = true
                        )
                    }
                }
            }
        }

        // Pattern 4: window.__SVELTEKIT_DATA__ or script data-sveltekit-fetched
        // Modern sites store JSON props here.
        if (html.contains("sveltekit")) {
            val svelteDataRegex = Regex("""<script[^>]*>\s*(?:window\.)?__SVELTEKIT_DATA__\s*=\s*(\{.*?\});?\s*</script>""", RegexOption.DOT_MATCHES_ALL)
            svelteDataRegex.find(html)?.let { match ->
                val json = match.groupValues[1]
                val m3u8Regex = Regex("""(https?[:\\]+[^"']+\.m3u8[^"']*)""")
                m3u8Regex.find(json)?.let { m ->
                    val url = m.groupValues[1].replace("\\/", "/").replace("\\u002F", "/")
                    android.util.Log.d("EmbedStExtractor", "Found m3u8 in SvelteKit data: $url")
                    return@withContext Video(
                        source = url,
                        headers = mapOf(
                            "Referer" to referer,
                            "Origin" to referer.trimEnd('/')
                        ),
                        maintainToken = true
                    )
                }
            }
        }

        // Pattern 5: Generic script-based JSON extraction
        scripts.forEach { script ->
            val content = script.html()
            if (content.contains(".m3u8")) {
                val m3u8Regex = Regex("""(https?[:\\]+[^"']+\.m3u8[^"']*)""")
                m3u8Regex.find(content)?.let { m ->
                    val url = m.groupValues[1].replace("\\/", "/").replace("\\u002F", "/")
                    android.util.Log.d("EmbedStExtractor", "Found m3u8 in generic script: $url")
                    return@withContext Video(
                        source = url,
                        headers = mapOf(
                            "Referer" to referer,
                            "Origin" to referer.trimEnd('/')
                        ),
                        maintainToken = true
                    )
                }
            }
        }

        throw Exception("Could not find video source in $link")
    }
}
