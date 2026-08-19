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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

object SportsProvider : Provider {
    private const val API_BASE = "https://streamed.pk/api"
    private const val SERVER_NAME_PREFIX = "Live Sports - "
    private val client = OkHttpClient()
    private val sourceCache = ConcurrentHashMap<String, List<SportMatch.MatchSource>>()

    override val baseUrl: String = API_BASE
    override val name: String = "Sports"
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
            val request = Request.Builder()
                .url("$API_BASE/matches/live")
                .header("Accept", "application/json")
                .build()
            val body = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Live matches request failed with HTTP ${response.code}")
                }
                response.body?.string().orEmpty()
            }
            if (body.isBlank()) return@withContext emptyList()
            parseMatches(JSONArray(body), "LIVE").also(::cacheMatchSources)
        } catch (e: Exception) {
            Log.e("SportsProvider", "Error fetching live matches", e)
            emptyList()
        }
    }

    suspend fun getUpcomingMatches(): List<SportMatch> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$API_BASE/matches/all")
                .header("Accept", "application/json")
                .build()
            val body = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("All matches request failed with HTTP ${response.code}")
                }
                response.body?.string().orEmpty()
            }
            if (body.isBlank()) return@withContext emptyList()
            val now = System.currentTimeMillis()
            parseMatches(JSONArray(body), "UPCOMING")
                .also(::cacheMatchSources)
                .filter { (it.date ?: 0) > now }
        } catch (e: Exception) {
            Log.e("SportsProvider", "Error fetching upcoming matches", e)
            emptyList()
        }
    }

    private fun parseMatches(jsonArray: JSONArray, status: String): List<SportMatch> {
        val matches = mutableListOf<SportMatch>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val title = obj.optString("title")
            val teams = obj.optJSONObject("teams")
            val homeTeam = teams?.optJSONObject("home")?.optString("name") ?: title.split(" vs ").firstOrNull() ?: title
            val awayTeam = teams?.optJSONObject("away")?.optString("name") ?: title.split(" vs ").lastOrNull() ?: "Opponent"
            
            val sourcesArray = obj.optJSONArray("sources")
            val sources = mutableListOf<SportMatch.MatchSource>()
            if (sourcesArray != null) {
                for (j in 0 until sourcesArray.length()) {
                    val sObj = sourcesArray.getJSONObject(j)
                    val source = sObj.optString("source")
                    val sourceId = sObj.optString("id")
                    if (source.isNotBlank() && sourceId.isNotBlank()) {
                        sources.add(SportMatch.MatchSource(source, sourceId))
                    }
                }
            }

            matches.add(
                SportMatch(
                    id = obj.getString("id"),
                    title = title,
                    homeTeam = homeTeam,
                    awayTeam = awayTeam,
                    league = obj.optString("category").uppercase(),
                    status = status,
                    time = if (status == "LIVE") "Live" else "Upcoming",
                    score = "vs",
                    sport = obj.optString("category"),
                    poster = normalizePosterUrl(obj.optString("poster")),
                    date = obj.optLong("date"),
                    sources = sources
                ).apply { itemType = AppAdapter.Type.SPORT_MATCH_ITEM }
            )
        }
        return matches
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
        if (poster.startsWith("http")) return poster
        if (poster.startsWith("//")) return "https:$poster"
        
        // Remove duplicate /api prefix if present in the poster path
        val cleanPath = if (poster.startsWith("/api/")) poster.substring(4) else if (poster.startsWith("api/")) poster.substring(3) else if (poster.startsWith("/")) poster else "/$poster"
        return "$API_BASE$cleanPath"
    }

    suspend fun getStreams(source: String, id: String): List<SportStream> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$API_BASE/stream/$source/$id")
                .header("Accept", "application/json")
                .build()
            val body = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Stream request for $source/$id failed with HTTP ${response.code}")
                }
                response.body?.string().orEmpty()
            }
            if (body.isBlank()) return@withContext emptyList()
            val jsonArray = JSONArray(body)
            val streams = mutableListOf<SportStream>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val embedUrl = obj.optString("embedUrl")
                if (embedUrl.isBlank()) continue
                streams.add(
                    SportStream(
                        id = obj.optString("id").ifBlank { "$source-$id-${i + 1}" },
                        streamNo = obj.optInt("streamNo", i + 1),
                        language = obj.optString("language").ifBlank { "Unknown language" },
                        hd = obj.optBoolean("hd"),
                        embedUrl = embedUrl,
                        source = obj.optString("source").ifBlank { source },
                        thumbnail = obj.optString("thumbnail").takeIf(String::isNotBlank),
                        healthScore = obj.optInt("healthScore").takeIf { obj.has("healthScore") }
                    )
                )
            }
            streams
        } catch (e: Exception) {
            Log.e("SportsProvider", "Error fetching streams", e)
            emptyList()
        }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val request = SportsPlaybackId.decode(id)
        val sources = request.sources.ifEmpty {
            sourceCache[request.matchId]
                ?: getLiveMatches().firstOrNull { it.id == request.matchId }?.sources
                ?: emptyList()
        }

        // The alpha fallback keeps old raw match ids working if the match metadata is no longer
        // returned by the live endpoint. New ids always carry their exact sources.
        val resolvedSources = sources.ifEmpty {
            listOf(SportMatch.MatchSource(source = "alpha", id = request.matchId))
        }

        return loadSportServers(resolvedSources, ::getStreams)
    }

    fun playbackId(match: SportMatch): String = SportsPlaybackId.encode(match)

    fun ownsPlaybackId(id: String): Boolean =
        SportsPlaybackId.isEncoded(id) ||
            SportsPlaybackId.isLegacy(id) ||
            id.startsWith("match-") ||
            id.startsWith("ppv-")

    fun ownsServer(server: Video.Server): Boolean =
        server.name.startsWith(SERVER_NAME_PREFIX) ||
            server.id.contains("embed.st") ||
            server.id.contains("top-embed.com")

    override suspend fun getVideo(server: Video.Server): Video {
        val url = server.src.ifBlank { server.id }
        return try {
            com.nexastream.app.extractors.Extractor.extract(url, server)
        } catch (e: Exception) {
            // If no extractor found, treat it as direct HLS/Embed if it looks like one
            if (url.contains(".m3u8") || url.contains(".mpd")) {
                Video(source = url)
            } else {
                throw e
            }
        }
    }

    // Provider Interface Stubs
    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> = emptyList()
    override suspend fun getMovies(page: Int): List<Movie> = emptyList()
    override suspend fun getTvShows(page: Int): List<TvShow> = emptyList()
    override suspend fun getMovie(id: String): Movie = throw UnsupportedOperationException()
    override suspend fun getTvShow(id: String): TvShow = throw UnsupportedOperationException()
    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = emptyList()
    override suspend fun getGenre(id: String, page: Int): Genre = throw UnsupportedOperationException()
    override suspend fun getPeople(id: String, page: Int): People = throw UnsupportedOperationException()
}

internal suspend fun loadSportServers(
    sources: List<SportMatch.MatchSource>,
    streamLoader: suspend (source: String, id: String) -> List<SportStream>,
): List<Video.Server> = coroutineScope {
    sources
        .filter { it.source.isNotBlank() && it.id.isNotBlank() }
        .distinctBy { it.source to it.id }
        .map { source ->
            async { streamLoader(source.source, source.id) }
        }
        .awaitAll()
        .flatten()
        .filter { it.embedUrl.isNotBlank() }
        .distinctBy { it.embedUrl }
        .map { stream ->
            Video.Server(
                id = stream.embedUrl,
                name = "Live Sports - ${stream.source} - ${stream.language} ${if (stream.hd) "HD" else "SD"}",
                src = stream.embedUrl,
            )
        }
}
