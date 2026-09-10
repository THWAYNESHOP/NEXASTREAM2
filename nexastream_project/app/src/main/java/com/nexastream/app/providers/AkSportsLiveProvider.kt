package com.nexastream.app.providers

import android.util.Log
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.models.Category
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Genre
import com.nexastream.app.models.Movie
import com.nexastream.app.models.People
import com.nexastream.app.models.SportMatch
import com.nexastream.app.models.SportStream
import com.nexastream.app.models.TvShow
import com.nexastream.app.models.Video
import com.nexastream.app.utils.SportsSecurityUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object AkSportsLiveProvider : IptvProvider {
    private var activeHost: String = "https://streamed.st/api"
    private const val API_BASE = "https://streamed.st/api"
    private val FALLBACK_HOSTS = listOf(
        "https://v3.streamed.su/api",
        "https://v2.streamed.su/api",
        "https://streamed.pk/api",
        "https://stream.pk/api",
        "https://streamed.is/api"
    )
    private const val SERVER_NAME_PREFIX = "AK Sports - "
    
    private val client = com.nexastream.app.utils.NetworkClient.compatibleTrustAll.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()
    private val sourceCache = ConcurrentHashMap<String, List<SportMatch.MatchSource>>()

    override val baseUrl: String get() = activeHost
    override val name: String = "AK Sports Live"
    override val logo: String = ""
    override val language: String = "en"

    override suspend fun getHome(): List<Category> {
        val liveMatches = getLiveMatches()
        val upcomingMatches = getUpcomingMatches()
        
        return listOf(
            Category(
                name = "Live Sports",
                list = liveMatches
            ).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM },
            Category(
                name = "Upcoming Matches",
                list = upcomingMatches
            ).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM }
        )
    }

    suspend fun getLiveMatches(): List<SportMatch> = withContext(Dispatchers.IO) {
        try {
            val body = fetchWithFallback("/matches/live")
            if (body.isBlank()) return@withContext emptyList()
            parseMatches(JSONArray(body), "LIVE").also(::cacheMatchSources)
        } catch (e: Exception) {
            Log.e("AkSportsLiveProvider", "Error fetching live matches", e)
            emptyList()
        }
    }

    suspend fun getUpcomingMatches(): List<SportMatch> = withContext(Dispatchers.IO) {
        try {
            val body = fetchWithFallback("/matches/all")
            if (body.isBlank()) return@withContext emptyList()
            val now = System.currentTimeMillis()
            parseMatches(JSONArray(body), "UPCOMING")
                .also(::cacheMatchSources)
                .filter { (it.date ?: 0) > now }
        } catch (e: Exception) {
            Log.e("AkSportsLiveProvider", "Error fetching upcoming matches", e)
            emptyList()
        }
    }

    private fun fetchWithFallback(path: String): String {
        val hosts = (listOf(activeHost, API_BASE) + FALLBACK_HOSTS).distinct()
        for (host in hosts) {
            // Try twice for each host before giving up
            repeat(2) { attempt ->
                try {
                    Log.d("AkSportsLiveProvider", "Fetching: $host$path (Attempt ${attempt + 1})")
                    val result = executeRequest("$host$path")
                    if (result.isNotBlank()) {
                        if (activeHost != host) {
                            Log.i("AkSportsLiveProvider", "Switched active host to: $host")
                            activeHost = host
                        }
                        return result
                    }
                } catch (e: Exception) {
                    Log.w("AkSportsLiveProvider", "API failed for host $host: $path (Attempt ${attempt + 1}) -> ${e.message}")
                }
            }
        }
        Log.e("AkSportsLiveProvider", "All API sources failed for $path")
        return ""
    }

    private fun executeRequest(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()
        return client.newBuilder()
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
            .newCall(request)
            .execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Request failed with HTTP ${response.code}")
            }
            response.body?.string().orEmpty()
        }
    }

    private suspend fun parseMatches(jsonArray: JSONArray, status: String): List<SportMatch> = coroutineScope {
        val deferredMatches = (0 until jsonArray.length()).map { i ->
            async {
                val obj = jsonArray.getJSONObject(i)
                val matchId = obj.optString("id")
                val title = obj.optString("title")
                val teams = obj.optJSONObject("teams")
                val homeTeamObj = teams?.optJSONObject("home")
                val awayTeamObj = teams?.optJSONObject("away")
                val homeTeam = homeTeamObj?.optString("name") ?: title.split(" vs ").firstOrNull() ?: title
                val awayTeam = awayTeamObj?.optString("name") ?: title.split(" vs ").lastOrNull() ?: "Opponent"
                
                val sourcesArray = obj.optJSONArray("sources")
                val sources = mutableListOf<SportMatch.MatchSource>()
                if (sourcesArray != null && sourcesArray.length() > 0) {
                    for (j in 0 until sourcesArray.length()) {
                        val sObj = sourcesArray.getJSONObject(j)
                        val source = sObj.optString("source")
                        val sourceId = sObj.optString("id")
                        if (source.isNotBlank() && sourceId.isNotBlank()) {
                            sources.add(SportMatch.MatchSource(source, sourceId))
                        }
                    }
                } else {
                    sources.add(SportMatch.MatchSource("alpha", matchId))
                    sources.add(SportMatch.MatchSource("beta", matchId))
                    sources.add(SportMatch.MatchSource("delta", matchId))
                    sources.add(SportMatch.MatchSource("gamma", matchId))
                }

                val apiPoster = obj.optString("poster").ifBlank { obj.optString("eventLogo") }
                val poster = when {
                    apiPoster.isNotBlank() -> apiPoster
                    matchId.isNotBlank() -> "/api/images/poster/$matchId"
                    else -> {
                        val homeBadge = homeTeamObj?.optString("badge") ?: homeTeamObj?.optString("teamAFlag")
                        val awayBadge = awayTeamObj?.optString("badge") ?: awayTeamObj?.optString("teamBFlag")
                        if (!homeBadge.isNullOrBlank() && !awayBadge.isNullOrBlank()) {
                            "/api/images/poster/$homeBadge/$awayBadge"
                        } else if (!homeBadge.isNullOrBlank()) {
                            "/admin/flags/$homeBadge.png"
                        } else null
                    }
                }

                val logo = com.nexastream.app.utils.ChannelLogoRepository.getLogoUrl(homeTeam)
                    ?: com.nexastream.app.utils.ChannelLogoRepository.getLogoUrl(awayTeam)
                    ?: com.nexastream.app.utils.ChannelLogoRepository.getLogoUrl(title)
                    ?: normalizePosterUrl(poster)

                SportMatch(
                    id = matchId,
                    title = title,
                    homeTeam = homeTeam,
                    awayTeam = awayTeam,
                    league = obj.optString("category").uppercase(),
                    status = status,
                    time = if (status == "LIVE") "Live" else "Upcoming",
                    score = "vs",
                    sport = obj.optString("category"),
                    poster = logo,
                    date = obj.optLong("date"),
                    sources = sources
                ).apply { itemType = AppAdapter.Type.SPORT_MATCH_ITEM }
            }
        }
        deferredMatches.awaitAll()
    }

    private fun cacheMatchSources(matches: List<SportMatch>) {
        matches.forEach { match ->
            if (match.id.isNotBlank() && match.sources.isNotEmpty()) {
                sourceCache[match.id] = match.sources
            }
        }
    }

    private fun normalizePosterUrl(poster: String?): String? {
        if (poster.isNullOrBlank()) return null
        
        var cleanPath = poster
        if (poster.startsWith("http")) {
            cleanPath = when {
                poster.contains("/api/") -> poster.substringAfter("/api/")
                poster.contains("api/") -> poster.substringAfter("api/")
                else -> try { java.net.URI(poster).path } catch(e: Exception) { poster }
            }
        }
        
        cleanPath = cleanPath.removePrefix("/api/").removePrefix("api/").removePrefix("/")
        if (cleanPath.isBlank()) return null

        // ALWAYS use streamed.st for images as v3.streamed.su is frequently unreachable
        val baseImageUrl = "https://streamed.st"
        val imageUrl = "$baseImageUrl/api/$cleanPath"
        val webpUrl = if (imageUrl.endsWith(".webp")) imageUrl else "$imageUrl.webp"
        
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        
        return com.nexastream.app.utils.ArtworkRequestHeaders.withHeaders(
            webpUrl,
            referer = "$baseImageUrl/",
            origin = baseImageUrl,
            userAgent = userAgent
        ) ?: webpUrl
    }

    suspend fun getStreams(source: String, id: String): List<SportStream> = withContext(Dispatchers.IO) {
        Log.e("AkSportsLiveProvider", "getStreams: source=$source, id=$id")
        try {
            val body = fetchWithFallback("/stream/$source/$id")
            if (body.isBlank()) {
                Log.e("AkSportsLiveProvider", "getStreams: Received empty body for $source/$id")
                return@withContext emptyList()
            }
            // Log.e("AkSportsLiveProvider", "RAW Stream Response: $body")
            
            // Replicate Lx5/d.smali logic: Try JSON, then try custom decryption
            var processedBody = body
            var jsonObject: JSONObject? = try { JSONObject(body) } catch (e: Exception) { null }
            var jsonArray: JSONArray? = try { JSONArray(body) } catch (e: Exception) { null }
            
            if (jsonObject == null && jsonArray == null) {
                val decoded = com.nexastream.app.utils.SportsSecurityUtils.decodeObfuscatedString(body)
                if (decoded.contains("{")) {
                    processedBody = decoded
                    jsonObject = try { JSONObject(decoded) } catch (e: Exception) { null }
                    jsonArray = try { JSONArray(decoded) } catch (e: Exception) { null }
                }
            }

            val streamsArray = when {
                jsonObject != null -> {
                    val linkKey = jsonObject.optString("link_key", "playback_url")
                    if (jsonObject.has("streams")) jsonObject.optJSONArray("streams") 
                    else if (jsonObject.has(linkKey) || jsonObject.has("playback_url") || jsonObject.has("url")) {
                         JSONArray().apply { put(jsonObject as Any) }
                    } else null
                }
                jsonArray != null -> jsonArray
                else -> null
            }

            // Respect dynamic salt from server and sync with security utils
            val salt = jsonObject?.optString("default_string")?.takeIf { it.isNotBlank() } ?: "9HY(#b1q6"
            com.nexastream.app.utils.SportsSecurityUtils.activeSalt = salt

            Log.d("AkSportsLiveProvider", "Processed Body (start): ${processedBody.take(100)}")
            Log.d("AkSportsLiveProvider", "Streams Array Found: ${streamsArray != null}, Length: ${streamsArray?.length() ?: 0}")

            // A direct URL is already playable. We check if it needs a security token.
            if (streamsArray == null && processedBody.startsWith("http")) {
                if (processedBody.contains("127.0.0.1")) return@withContext emptyList()
                
                var finalUrl = processedBody
                if (processedBody.contains(".m3u8") && !processedBody.contains("token=")) {
                    val domainPart = com.nexastream.app.utils.SportsSecurityUtils.getDomainSegment(processedBody)
                    val token = com.nexastream.app.utils.SportsSecurityUtils.generateToken(domainPart)
                    finalUrl = if (processedBody.contains("?")) "$processedBody&${token.removePrefix("?")}" else "$processedBody$token"
                }

                return@withContext listOf(
                    SportStream(
                        id = "$source-$id-1",
                        streamNo = 1,
                        language = "Unknown",
                        hd = true,
                        embedUrl = finalUrl,
                        source = source,
                        thumbnail = null
                    )
                )
            }

            if (streamsArray == null) {
                Log.e("AkSportsLiveProvider", "getStreams: streamsArray is null")
                return@withContext emptyList()
            }

            val streams = mutableListOf<SportStream>()
            Log.e("AkSportsLiveProvider", "getStreams: processing ${streamsArray.length()} items")
            for (i in 0 until streamsArray.length()) {
                val obj = streamsArray.getJSONObject(i)
                val linkKey = obj.optString("link_key", "playback_url")
                var embedUrl = obj.optString("embedUrl")
                    .ifBlank { obj.optString(linkKey) }
                    .ifBlank { obj.optString("playback_url") }
                
                if (embedUrl.isBlank()) {
                    Log.w("AkSportsLiveProvider", "getStreams: item $i has blank embedUrl")
                    continue
                }
                
                val type = obj.optString("type", "ls")
                
                // Handle obfuscated 'sp' or 'json' types within the array
                if (type == "sp" || type == "json") {
                    val decoded = com.nexastream.app.utils.SportsSecurityUtils.decodeObfuscatedString(embedUrl)
                    if (decoded.startsWith("http")) embedUrl = decoded
                }

                streams.add(
                    SportStream(
                        id = obj.optString("id").ifBlank { "$source-$id-${i + 1}" },
                        streamNo = obj.optInt("streamNo", i + 1),
                        language = obj.optString("language").ifBlank { "Unknown" },
                        hd = obj.optBoolean("hd"),
                        embedUrl = embedUrl,
                        source = obj.optString("source").ifBlank { source },
                        thumbnail = obj.optString("thumbnail").takeIf(String::isNotBlank),
                        healthScore = obj.optInt("healthScore").takeIf { obj.has("healthScore") }
                    )
                )
            }

            Log.e("AkSportsLiveProvider", "getStreams: returning ${streams.size} streams")
            streams
        } catch (e: Exception) {
            Log.e("AkSportsLiveProvider", "Error fetching streams", e)
            emptyList()
        }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val request = try { SportsPlaybackId.decode(id) } catch(e: Exception) { SportsPlaybackRequest(id, emptyList()) }
        val liveMatches = getLiveMatches()
        var liveMatch = liveMatches.find { it.id == request.matchId }
        
        // FALLBACK: If matchId not found (maybe changed or stale), search by title/teams
        if (liveMatch == null && videoType is Video.Type.Movie) {
            val targetTitle = normalizeTeam(videoType.title)
            liveMatch = liveMatches.find { match ->
                val currentTitle = normalizeTeam(match.title)
                currentTitle == targetTitle || currentTitle.contains(targetTitle) || targetTitle.contains(currentTitle)
            }
            if (liveMatch != null) {
                Log.i("AkSportsLiveProvider", "Match found by title fallback: ${liveMatch.title} (ID: ${liveMatch.id})")
            }
        }

        Log.e("AkSportsLiveProvider", "getServers START: matchId=${request.matchId}, sourcesInId=${request.sources.size}")
        
        val servers = mutableListOf<Video.Server>()
        
        // 1. Try to get sources (from ID payload, then Cache, then refreshing Home)
        val sources = request.sources.ifEmpty {
            val cached = sourceCache[request.matchId]
            if (cached != null) {
                Log.e("AkSportsLiveProvider", "Found ${cached.size} sources in Cache for ${request.matchId}")
                cached
            } else {
                Log.e("AkSportsLiveProvider", "Cache miss for ${request.matchId}, trying live refresh")
                liveMatch?.sources ?: emptyList()
            }
        }

        val resolvedSources = sources.ifEmpty {
            Log.e("AkSportsLiveProvider", "No sources found anywhere, using defaults for ${request.matchId}")
            listOf(
                SportMatch.MatchSource(source = "alpha", id = liveMatch?.id ?: request.matchId),
                SportMatch.MatchSource(source = "beta", id = liveMatch?.id ?: request.matchId)
            )
        }

        try {
            Log.e("AkSportsLiveProvider", "Calling loadSportServers with ${resolvedSources.size} sources")
            servers.addAll(loadSportServers(resolvedSources, ::getStreams))
        } catch (e: Exception) {
            Log.e("AkSportsLiveProvider", "Error in loadSportServers: ${e.message}")
        }

        // 2. ALWAYS add direct embed fallbacks
        // Extract numeric ID from slug if possible (e.g. man-city-123 -> 123)
        val numericId = request.matchId.substringAfterLast("-")
        Log.e("AkSportsLiveProvider", "Adding mandatory direct embeds. matchId=${request.matchId}, numericId=$numericId")
        
        val isNumericId = numericId.all { it.isDigit() }
        val idsToTry = if (isNumericId) listOf(request.matchId, numericId).distinct() else listOf(request.matchId)
        val hostsToTry = listOf(
            "https://streamed.st",
            "https://streamed.is",
            "https://embed.st",
            "https://v3.streamed.su",
            "https://v2.streamed.su",
            "https://streamed.pk",
            "https://stream.pk"
        )
        val mirrorTypes = listOf("alpha", "beta", "delta", "gamma")
        
        hostsToTry.forEach { host ->
            mirrorTypes.forEach { type ->
                idsToTry.forEach { matchId ->
                    val url = "$host/embed/$type/$matchId/1"
                    if (servers.none { it.id == url }) {
                        val hostLabel = host.substringAfter("//").substringBefore(".")
                        val mirrorName = "Mirror ${servers.size + 1} ($type) [$hostLabel]"
                        servers.add(Video.Server(id = url, name = mirrorName, src = url))
                    }
                }
            }
        }

        // 3. Provider-Cross Matching Fallback
        liveMatch?.let { match ->
            if (!match.homeTeam.isNullOrBlank() && !match.awayTeam.isNullOrBlank()) {
                try {
                    Log.d("AkSportsLiveProvider", "Searching cross-provider mirrors for: ${match.homeTeam} vs ${match.awayTeam}")
                    val crossMirrors = withContext(Dispatchers.IO) {
                        kotlinx.coroutines.withTimeoutOrNull(8000) {
                            findCrossProviderMirrors(match.homeTeam, match.awayTeam)
                        } ?: emptyList()
                    }
                    servers.addAll(crossMirrors)
                } catch (e: Exception) {
                    Log.e("AkSportsLiveProvider", "Cross-provider matching failed", e)
                }
            }
        }
        
        Log.e("AkSportsLiveProvider", "getServers total resolved: ${servers.size}")
        return servers.distinctBy { it.id }
    }

    private suspend fun findCrossProviderMirrors(home: String, away: String): List<Video.Server> = coroutineScope {
        val nHome = normalizeTeam(home)
        val nAway = normalizeTeam(away)
        
        val pelotaLibreDeferred = async {
            runCatching {
                PelotaLibreTvHdProvider.getHome()
                    .find { it.name == "Agenda Deportiva" }
                    ?.list?.filterIsInstance<TvShow>()
                    ?.firstOrNull { item ->
                        val title = normalizeTeam(item.title)
                        title.contains(nHome) && title.contains(nAway)
                    }?.let { match ->
                        PelotaLibreTvHdProvider.getServers(match.id, Video.Type.Movie(match.id, match.title, "", "", null))
                            .map { it.copy(name = "Pelota Libre - ${it.name}") }
                    }
            }.getOrNull() ?: emptyList()
        }

        val tvLibreDeferred = async {
            runCatching {
                TvLibrefutbolProvider.getHome()
                    .flatMap { it.list }
                    .filterIsInstance<TvShow>()
                    .firstOrNull { item ->
                        val title = normalizeTeam(item.title)
                        title.contains(nHome) && title.contains(nAway)
                    }?.let { match ->
                        TvLibrefutbolProvider.getServers(match.id, Video.Type.Movie(match.id, match.title, "", "", null))
                            .map { it.copy(name = "TV Libre - ${it.name}") }
                    }
            }.getOrNull() ?: emptyList()
        }

        (pelotaLibreDeferred.await() + tvLibreDeferred.await())
    }

    private fun normalizeTeam(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]"), "")

    suspend fun getServersForTeams(home: String, away: String): List<Video.Server> = withContext(Dispatchers.IO) {
        val nHome = normalizeTeam(home)
        val nAway = normalizeTeam(away)
        
        val match = getLiveMatches().find { 
            val title = normalizeTeam(it.title)
            title.contains(nHome) && title.contains(nAway)
        }
        
        if (match != null) {
            getServers(playbackId(match), Video.Type.Movie(match.id, match.title, "", "", null))
        } else emptyList()
    }

    fun playbackId(match: SportMatch): String = SportsPlaybackId.encode(match)

    fun ownsPlaybackId(id: String): Boolean =
        SportsPlaybackId.isEncoded(id) ||
            SportsPlaybackId.isLegacy(id) ||
            id.startsWith("match-") ||
            id.startsWith("ppv-") ||
            // Support for match slugs like "manchester-city-vs-bournemouth-2494006"
            id.matches(Regex("^[a-z0-9-]+-[0-9]+$"))

    fun canResolvePlaybackId(id: String): Boolean =
        ownsPlaybackId(id) || sourceCache.containsKey(id)

    fun ownsServer(server: Video.Server): Boolean {
        val id = server.id.lowercase()
        val name = server.name.lowercase()
        return name.contains(SERVER_NAME_PREFIX.lowercase()) ||
                id.contains("embed.st") ||
                id.contains("streamed.st") ||
                id.contains("streamed.su") ||
                id.contains("streamed.is") ||
                id.contains("streamed.pk") ||
                id.contains("stream.pk") ||
                id.contains("top-embed.com") ||
                id.contains("crichd") ||
                id.contains("exposestrat.com") ||
                id.contains("zohanayaan.com") ||
                name.contains("ak sports") ||
                name.contains("match!")
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val url = server.src.ifBlank { server.id }
        Log.i("AkSportsLiveProvider", "getVideo Request: $url")
        
        return try {
            val video = com.nexastream.app.extractors.Extractor.extract(url, server)
            Log.i("AkSportsLiveProvider", "Extraction Success: ${video.source}")
            video
        } catch (e: Exception) {
            Log.e("AkSportsLiveProvider", "Extraction failed for $url, trying fallback", e)
            if (url.contains(".m3u8") || url.contains("token=") || url.contains(".php")) {
                val headers = mutableMapOf<String, String>()

                // Set Referer based on URL
                if (url.contains("crichd")) {
                    headers["Referer"] = "https://crichd.online/"
                } else if (url.contains("embed.st") || url.contains("streamed")) {
                    headers["Referer"] = "https://streamed.st/"
                } else {
                    // Default referer - try to extract from URL
                    try {
                        val uri = java.net.URI(url)
                        val referer = "${uri.scheme}://${uri.host}/"
                        headers["Referer"] = referer
                    } catch (ex: Exception) {
                        headers["Referer"] = "https://cdnlivetv.tv/" // fallback
                    }
                }

                // Set Origin header (important for CORS and stream validation)
                val referer = headers["Referer"]
                if (referer != null) {
                    try {
                        val uri = java.net.URI(referer)
                        headers["Origin"] = "${uri.scheme}://${uri.host}"
                    } catch (ex: Exception) {
                        headers["Origin"] = "https://crichd.online" // fallback
                    }
                } else {
                    headers["Origin"] = "https://crichd.online"
                }

                // Set User-Agent for consistency
                headers["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

                Video(
                    source = url,
                    headers = headers,
                    maintainToken = true
                )
            } else {
                throw e
            }
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        return emptyList()
    }
    override suspend fun getMovies(page: Int): List<Movie> {
        return emptyList()
    }
    override suspend fun getTvShows(page: Int): List<TvShow> {
        return emptyList()
    }
    override suspend fun getMovie(id: String): Movie = throw UnsupportedOperationException()
    override suspend fun getTvShow(id: String): TvShow = throw UnsupportedOperationException()
    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        return emptyList()
    }
    override suspend fun getGenre(id: String, page: Int): Genre = throw UnsupportedOperationException()
    override suspend fun getPeople(id: String, page: Int): People = throw UnsupportedOperationException()

    internal suspend fun loadSportServers(
        sources: List<SportMatch.MatchSource>,
        streamLoader: suspend (source: String, id: String) -> List<SportStream>,
    ): List<Video.Server> = coroutineScope {
        val deferredMirrors = sources
            .filter { it.source.isNotBlank() && it.id.isNotBlank() }
            .distinctBy { it.source to it.id }
            .map { source ->
                async {
                    try {
                        kotlinx.coroutines.withTimeout(30000) {
                            streamLoader(source.source, source.id)
                        }
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }

        val discovered = deferredMirrors.awaitAll().flatten()
        Log.e("AkSportsLiveProvider", "loadSportServers: found ${discovered.size} streams across sources")

        val servers = discovered
            .filter { it.embedUrl.isNotBlank() }
            .distinctBy { it.embedUrl }
            .map { stream ->
                Video.Server(
                    id = stream.embedUrl,
                    name = "AK Sports - ${stream.source} - ${stream.language} ${if (stream.hd) "HD" else "SD"}",
                    src = stream.embedUrl,
                )
            }
        Log.e("AkSportsLiveProvider", "loadSportServers: returning ${servers.size} unique servers")
        servers
    }
}

