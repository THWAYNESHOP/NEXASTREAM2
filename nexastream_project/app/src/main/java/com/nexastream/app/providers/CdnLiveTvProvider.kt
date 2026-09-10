package com.nexastream.app.providers

import android.util.Log
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.models.*
import com.nexastream.app.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import kotlin.text.Charsets

object CdnLiveTvProvider : Provider {
    private const val BASE_URL = "https://api.cdnlivetv.tv/api/v1/"
    private const val USER = "cdnlivetv"
    private const val PLAN = "free"

    override val name: String = "CDN Live TV"
    override val baseUrl: String = BASE_URL
    override val logo: String = "https://cdnlivetv.tv/assets/img/logo.png"
    override val language: String = "en"

    private val client = NetworkClient.systemDns.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val service = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(Service::class.java)

    interface Service {
        @GET("channels/")
        suspend fun getChannels(
            @Query("user") user: String = USER,
            @Query("plan") plan: String = PLAN
        ): CDNChannelsResponse

        @GET("events/sports/")
        suspend fun getAllSports(
            @Query("user") user: String = USER,
            @Query("plan") plan: String = PLAN
        ): Map<String, CDNSportsData>

        @GET("events/sports/soccer/")
        suspend fun getSoccer(
            @Query("user") user: String = USER,
            @Query("plan") plan: String = PLAN
        ): Map<String, CDNSportsData>

        @GET("events/sports/nba/")
        suspend fun getNBA(
            @Query("user") user: String = USER,
            @Query("plan") plan: String = PLAN
        ): Map<String, CDNSportsData>

        @GET("events/sports/nhl/")
        suspend fun getNHL(
            @Query("user") user: String = USER,
            @Query("plan") plan: String = PLAN
        ): Map<String, CDNSportsData>

        @GET("events/sports/nfl/")
        suspend fun getNFL(
            @Query("user") user: String = USER,
            @Query("plan") plan: String = PLAN
        ): Map<String, CDNSportsData>
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        Log.d("CdnLiveTv", "Fetching CDN Home data...")
        val channelsDeferred = async { 
            runCatching { 
                val resp = service.getChannels()
                Log.d("CdnLiveTv", "Channels fetched: ${resp.channels.size}")
                resp.channels 
            }.getOrElse { 
                Log.e("CdnLiveTv", "Error fetching channels", it)
                emptyList() 
            }
        }
        val sportsDeferred = async {
            runCatching { 
                val resp = service.getAllSports()
                Log.d("CdnLiveTv", "Sports data fetched keys: ${resp.keys}")
                resp // Keep the whole map to process all categories
            }.getOrElse { 
                Log.e("CdnLiveTv", "Error fetching sports", it)
                emptyMap() 
            }
        }

        val channels = channelsDeferred.await()
        val allSportsMap = sportsDeferred.await()

        val categories = mutableListOf<Category>()

        // 1. Sports Events (Dynamic Categories)
        allSportsMap.forEach { (catName, data) ->
            if (catName.contains("total", ignoreCase = true)) return@forEach
            
            val matches = mutableListOf<CDNSportEvent>()
            matches.addAll(data.soccer.orEmpty())
            matches.addAll(data.nba.orEmpty())
            matches.addAll(data.nhl.orEmpty())
            matches.addAll(data.nfl.orEmpty())
            
            // In case the API adds new fields not yet in our GSON model but in the map
            // We can't easily extract them without reflection or raw JSON, 
            // but let's at least process what we have.
            
            val liveMatches = matches.filter { 
                it.status.contains("live", ignoreCase = true) || 
                it.status.contains("soon", ignoreCase = true) ||
                it.status.contains("NS", ignoreCase = true)
            }
            
            if (liveMatches.isNotEmpty()) {
                val displayName = if (catName == "cdn-live-tv") "Live Sports (CDN)" else catName
                val sportItems = liveMatches.map { async { it.toSportMatch() } }.awaitAll()
                categories.add(
                    Category(
                        name = displayName,
                        list = sportItems
                    ).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM }
                )
            }
        }

        // 2. Featured Channels
        if (channels.isNotEmpty()) {
            val tvShowItems = channels.take(30).map { async { it.toTvShow() } }.awaitAll()
            categories.add(
                Category(
                    name = "CDN Live Channels",
                    list = tvShowItems
                ).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM }
            )
        }

        categories
    }

    private suspend fun CDNChannel.toTvShow() = TvShow(
        id = "cdn:$code",
        title = name,
        poster = com.nexastream.app.utils.ChannelLogoRepository.getLogoUrl(name) ?: com.nexastream.app.utils.ArtworkRequestHeaders.run {
            val urlWithParams = appendQueryParams(image, mapOf("user" to USER, "plan" to PLAN))
            withHeaders(
                urlWithParams,
                referer = "https://cdnlivetv.tv/",
                origin = "https://cdnlivetv.tv",
                userAgent = NetworkClient.USER_AGENT
            )
        } ?: image,
        banner = image,
        quality = "LIVE",
        providerName = "CDN Live TV"
    ).apply { 
        itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM 
    }

    private suspend fun CDNSportEvent.toSportMatch() = SportMatch(
        id = "cdn_match:$gameID",
        title = "$homeTeam vs $awayTeam",
        homeTeam = homeTeam,
        awayTeam = awayTeam,
        league = tournament,
        status = when {
            status.contains("live", ignoreCase = true) -> "LIVE"
            status.contains("soon", ignoreCase = true) || status.contains("NS", ignoreCase = true) -> "UPCOMING"
            else -> status.uppercase()
        },
        time = time,
        score = "vs",
        sport = tournament,
        poster = com.nexastream.app.utils.ChannelLogoRepository.getLogoUrl(homeTeam) 
            ?: com.nexastream.app.utils.ChannelLogoRepository.getLogoUrl(tournament)
            ?: com.nexastream.app.utils.ArtworkRequestHeaders.run {
                val img = if (homeTeamIMG.isNotEmpty()) homeTeamIMG else countryIMG
                val urlWithParams = appendQueryParams(img, mapOf("user" to USER, "plan" to PLAN))
                withHeaders(
                    urlWithParams,
                    referer = "https://cdnlivetv.tv/",
                    origin = "https://cdnlivetv.tv",
                    userAgent = NetworkClient.USER_AGENT
                )
            } ?: (if (homeTeamIMG.isNotEmpty()) homeTeamIMG else countryIMG),
        date = null
    ).apply { 
        itemType = AppAdapter.Type.SPORT_MATCH_ITEM 
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        if (id.startsWith("cdn:")) {
            val code = id.removePrefix("cdn:")
            val channels = runCatching { service.getChannels().channels }.getOrNull() ?: return emptyList()
            val channel = channels.find { it.code == code } ?: return emptyList()
            return listOf(Video.Server(id = channel.url, name = "CDN Direct"))
        }

        if (id.startsWith("cdn_match:")) {
            val gameId = id.removePrefix("cdn_match:")
            val sportsData = runCatching { service.getAllSports().values }.getOrNull() ?: return emptyList()
            
            val allEvents = sportsData.flatMap { data ->
                (data.soccer.orEmpty() + data.nba.orEmpty() + data.nhl.orEmpty() + data.nfl.orEmpty())
            }
            
            var event = allEvents.find { it.gameID == gameId }
            
            // FALLBACK: If gameID not found (maybe changed), search by title
            if (event == null && videoType is Video.Type.Movie) {
                val targetTitle = videoType.title.lowercase().replace(" vs ", " ").replace(" ", "")
                event = allEvents.find { 
                    val currentTitle = "${it.homeTeam}${it.awayTeam}".lowercase().replace(" ", "")
                    currentTitle == targetTitle || currentTitle.contains(targetTitle) || targetTitle.contains(currentTitle)
                }
            }
            
            return event?.channels?.map { channel ->
                Video.Server(id = channel.url, name = "Mirror: ${channel.channelName}")
            } ?: emptyList()
        }

        return emptyList()
    }

    override suspend fun getVideo(server: Video.Server): Video = withContext(Dispatchers.IO) {
        val url = server.id
        Log.e("CdnLiveTv", "getVideo(url=$url)")
        
        if (url.contains("/player/") || url.contains("/channels/player/")) {
            try {
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("Referer", "https://cdnlivetv.tv/")
                    .header("User-Agent", NetworkClient.USER_AGENT)
                    .build()
                
                val response = client.newCall(request).execute()
                val html = response.body?.string().orEmpty()
                Log.e("CdnLiveTv", "Fetched player HTML, length: ${html.length}")
                
                // Log in chunks to see all scripts
                html.chunked(3000).forEachIndexed { index, chunk ->
                    Log.e("CdnLiveTv", "HTML Chunk $index: $chunk")
                }
                
                // Try to find m3u8 in the HTML (greedy search)
                val m3u8Regex = Regex("""["'](https?[:\\]+[^"']+\.m3u8[^"']*)["']""")
                val fileRegex = Regex("""file\s*[:\s]+["']([^"']+)["']""")
                val sourceRegex = Regex("""source\s*[:\s]+["']([^"']+)["']""")
                
                var streamUrl = m3u8Regex.find(html)?.groupValues?.get(1)
                    ?: fileRegex.find(html)?.groupValues?.get(1)
                    ?: sourceRegex.find(html)?.groupValues?.get(1)
                
                // 2. Try atob patterns
                if (streamUrl == null) {
                    Regex("""atob\s*\(\s*["']([^"']+)["']\s*\)""").findAll(html).forEach { match ->
                        try {
                            val decoded = String(android.util.Base64.decode(match.groupValues[1], android.util.Base64.DEFAULT))
                            if (decoded.contains(".m3u8")) {
                                streamUrl = decoded
                                Log.e("CdnLiveTv", "Found decoded m3u8: $streamUrl")
                            }
                        } catch (e: Exception) {}
                    }
                }

                // 3. Try iframe redirection
                if (streamUrl == null) {
                    val doc = org.jsoup.Jsoup.parse(html)
                    doc.select("iframe").forEach { iframe ->
                        val src = iframe.attr("src")
                        if (src.isNotEmpty() && src.startsWith("http")) {
                            Log.e("CdnLiveTv", "Found iframe: $src")
                        }
                    }
                }

                if (streamUrl == null && html.contains("eval(function(p,a,c,k,e,d)")) {
                    Log.e("CdnLiveTv", "Packed JS detected, attempting to unpack...")
                    val unpacked = com.nexastream.app.utils.JsUnpacker(html).unpack()
                    if (unpacked != null) {
                        streamUrl = m3u8Regex.find(unpacked)?.groupValues?.get(1)
                            ?: fileRegex.find(unpacked)?.groupValues?.get(1)
                    }
                }

                // 5. Try to extract stream URL from custom obfuscation (CDN Live TV specific)
                if (streamUrl == null) {
                    streamUrl = extractObfuscatedStreamUrl(html)
                    if (streamUrl != null) {
                        Log.e("CdnLiveTv", "Found obfuscated stream URL: $streamUrl")
                    }
                }

                val currentUrl = streamUrl
                if (currentUrl != null) {
                    val finalStreamUrl = try {
                        currentUrl.replace("\\/", "/")
                            .let { if (it.startsWith("//")) "https:$it" else it }
                    } catch (e: Exception) {
                        Log.e("CdnLiveTv", "Error formatting stream URL: $currentUrl", e)
                        null
                    }
                    
                    if (finalStreamUrl != null) {
                        Log.e("CdnLiveTv", "Extracted stream Success: $finalStreamUrl")
                        return@withContext Video(
                            source = finalStreamUrl,
                            headers = mapOf(
                                "Referer" to "https://cdnlivetv.tv/",
                                "Origin" to "https://cdnlivetv.tv",
                                "User-Agent" to NetworkClient.USER_AGENT
                            )
                        )
                    }
                }
                
                Log.e("CdnLiveTv", "Stream not found or invalid in player HTML. Body length: ${html.length}")
            } catch (e: Exception) {
                Log.e("CdnLiveTv", "Error extracting stream from $url", e)
            }
        }
        Video(source = url)
    }

    /**
     * Extracts stream URL from CDN Live TV's specific obfuscation pattern.
     * The pattern involves:
     * 1. Multiple variables with base64-encoded string parts
     * 2. A function with a randomized name (e.g., ZBeEXEZVzJ or EuDBXDBAHg) that decodes base64
     * 3. Concatenation of decoded parts to form the final URL
     */
    private fun extractObfuscatedStreamUrl(html: String): String? {
        try {
            // 1. Detect the decoding function name dynamically
            // Look for: function NAME(s){s=s.replace(/-/g,'+').replace(/_/g,'/') ... }
            val funcNamePattern = Regex("""function\s+([a-zA-Z0-9_]+)\s*\(\s*[a-z]\s*\)\s*\{\s*[a-z]\s*=\s*[a-z]\.replace\(""")
            val funcNameMatch = funcNamePattern.find(html) ?: return null
            val funcName = funcNameMatch.groupValues[1]
            Log.d("CdnLiveTv", "Detected obfuscation function: $funcName")

            // 2. Extract all variable assignments: var xxx='yyy' or var xxx = "yyy"
            val varAssignments = mutableMapOf<String, String>()
            val varPattern = Regex("""var\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*=\s*["']([^"']*)["']""")
            for (match in varPattern.findAll(html)) {
                val varName = match.groupValues[1]
                val varValue = match.groupValues[2]
                varAssignments[varName] = varValue
            }

            // 3. Look for URL construction pattern using the detected function name: 
            // var RJcIbpAgvVfE=funcName(...)+funcName(...)+...
            val urlPattern = Regex("""var\s+[a-zA-Z_][a-zA-Z0-9_]*\s*=\s*((?:$funcName\s*\(\s*[a-zA-Z_][a-zA-Z0-9_]*\s*\)\s*(?:\+\s*)?)+)""")
            val urlMatch = urlPattern.find(html) ?: return null

            // Extract the sequence of function calls
            val callSequence = urlMatch.groupValues[1]

            // Split by '+' to get individual function calls
            val functionCalls = callSequence.split("+").map { it.trim() }

            // 4. Process each function call: funcName(variableName)
            val decodedParts = StringBuilder()
            val callPattern = Regex("""$funcName\s*\(\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*\)""")
            
            for (call in functionCalls) {
                val varMatch = callPattern.find(call)
                if (varMatch != null) {
                    val varName = varMatch.groupValues[1]
                    val varValue = varAssignments[varName]
                    if (varValue != null) {
                        decodedParts.append(obfuscatedBase64Decode(varValue))
                    }
                }
            }

            return decodedParts.toString().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e("CdnLiveTv", "Error extracting obfuscated stream URL", e)
            return null
        }
    }

    /**
     * Implements the randomized decoding logic (formerly EuDBXDBAHg):
     * function NAME(s){s=s.replace(/-/g,'+').replace(/_/g,'/');while(s.length%4)s+='=';try{return decodeURIComponent(escape(atob(s)))}catch(e){return atob(s)}}
     */
    private fun obfuscatedBase64Decode(input: String): String {
        return try {
            // Convert URL-safe base64 to standard base64
            var s = input.replace("-", "+").replace("_", "/")

            // Add padding if needed
            while (s.length % 4 != 0) {
                s += "="
            }

            // Decode base64
            val decodedBytes = android.util.Base64.decode(s, android.util.Base64.DEFAULT)

            // Standard JS UTF-8 decode trick
            String(decodedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            try {
                // Fallback: direct decode as ISO-8859-1 (standard atob behavior)
                val s = input.replace("-", "+").replace("_", "/")
                var padded = s
                while (padded.length % 4 != 0) padded += "="
                val decodedBytes = android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
                String(decodedBytes, Charsets.ISO_8859_1)
            } catch (e2: Exception) {
                input
            }
        }
    }

    // Stubs
    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> = emptyList()
    override suspend fun getMovies(page: Int): List<Movie> = emptyList()
    override suspend fun getTvShows(page: Int): List<TvShow> = emptyList()
    override suspend fun getMovie(id: String): Movie = throw UnsupportedOperationException()
    override suspend fun getTvShow(id: String): TvShow {
        if (id.startsWith("cdn:")) {
            val code = id.removePrefix("cdn:")
            val channels = runCatching { service.getChannels().channels }.getOrNull() ?: throw Exception("Not found")
            val channel = channels.find { it.code == code } ?: throw Exception("Not found")
            return channel.toTvShow()
        }
        throw UnsupportedOperationException()
    }
    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = emptyList()
    override suspend fun getGenre(id: String, page: Int): Genre = coroutineScope {
        if (id == "cdn_all_channels") {
            val channels = runCatching { service.getChannels().channels }.getOrElse { 
                Log.e("CdnLiveTv", "getGenre failed to fetch channels")
                emptyList() 
            }
            Log.d("CdnLiveTv", "getGenre returning ${channels.size} channels")
            return@coroutineScope Genre(
                id = id,
                name = "CDN Live TV",
                shows = channels.map { async { it.toTvShow() } }.awaitAll()
            )
        }
        throw UnsupportedOperationException()
    }

    override suspend fun getPeople(id: String, page: Int): People = throw UnsupportedOperationException()
}
