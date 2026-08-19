package com.nexastream.app.providers

import android.util.Base64
import android.util.Log
import com.nexastream.app.NexastreamApp
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.models.*
import java.io.BufferedReader
import java.io.InputStreamReader

object LocalIptvProvider : IptvProvider {

    override val name = "IPTV DIMAN"
    override val baseUrl = ""
    override val logo = "https://i.ibb.co/W1d0CxF/Logo-IPTV-All-World.jpg"
    override val language = "en"

    private const val TAG = "LocalIptvProvider"

    private var cachedChannels: List<M3UChannel>? = null

    data class M3UChannel(
        val name: String,
        val url: String,
        val logo: String?,
        val group: String?,
        val userAgent: String? = null,
        val referer: String? = null
    )

    private fun createId(channel: M3UChannel): String {
        val rawId = "localiptv:${channel.url}|${channel.name}|${channel.logo ?: ""}|${channel.userAgent ?: ""}|${channel.referer ?: ""}"
        return "localiptv:" + Base64.encodeToString(rawId.toByteArray(), Base64.NO_WRAP)
    }

    fun decodeId(id: String): Triple<String, String, String> {
        return try {
            val raw = String(Base64.decode(id.removePrefix("localiptv:"), Base64.DEFAULT))
            val parts = raw.split("|")
            Triple(parts[0], parts[1], parts.getOrNull(2) ?: "")
        } catch (e: Exception) {
            Triple(id, "Unknown Channel", "")
        }
    }

    private fun getMetadataFromId(id: String): Map<String, String?> {
        return try {
            val raw = String(Base64.decode(id.removePrefix("localiptv:"), Base64.DEFAULT))
            val parts = raw.split("|")
            mapOf(
                "ua" to parts.getOrNull(3).takeIf { it?.isNotEmpty() == true },
                "referer" to parts.getOrNull(4).takeIf { it?.isNotEmpty() == true }
            )
        } catch (e: Exception) { emptyMap() }
    }

    private fun getAllChannels(): List<M3UChannel> {
        if (cachedChannels != null) return cachedChannels!!

        val allChannels = mutableListOf<M3UChannel>()
        val files = listOf("iptv_diman.m3u", "initial-playlist.m3u", "sports-playlist.m3u")
        
        files.forEach { fileName ->
            try {
                val inputStream = NexastreamApp.instance.assets.open(fileName)
                val reader = BufferedReader(InputStreamReader(inputStream))
                val content = reader.readText()
                allChannels.addAll(parseM3U(content))
                inputStream.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error reading $fileName: ${e.message}")
            }
        }

        cachedChannels = allChannels
        return allChannels
    }

    override suspend fun getHome(): List<Category> {
        val channels = getAllChannels()
        
        val sportsKeywords = listOf("sport", "espn", "fox", "sky", "bein", "nba", "nfl", "nhl", "mlb", "football", "cricket", "tennis", "basketball", "racing", "матч", "боец", "футбол")
        val movieKeywords = listOf("movie", "film", "cinema", "hbo", "star", "showtime", "кино", "сериал", "премьера", "хит")
        val newsKeywords = listOf("news", "cnn", "bbc", "fox news", "msnbc", "al jazeera", "новости", "известия", "рбк", "вести")
        val kidsKeywords = listOf("kids", "cartoon", "disney", "nickelodeon", "nick jr", "детский", "мульт", "карусель")
        val musicKeywords = listOf("music", "mtv", "vh1", "spotify", "apple music", "музыка", "bridge", "shanson")

        val categorized = mutableMapOf<String, MutableList<M3UChannel>>(
            "Live Sports" to mutableListOf(),
            "Movies & Series" to mutableListOf(),
            "News" to mutableListOf(),
            "Kids" to mutableListOf(),
            "Music" to mutableListOf(),
            "Regional & Others" to mutableListOf()
        )

        channels.forEach { channel ->
            val nameLower = channel.name.lowercase()
            val groupLower = channel.group?.lowercase() ?: ""
            
            when {
                sportsKeywords.any { nameLower.contains(it) || groupLower.contains(it) } -> categorized["Live Sports"]!!.add(channel)
                movieKeywords.any { nameLower.contains(it) || groupLower.contains(it) } -> categorized["Movies & Series"]!!.add(channel)
                newsKeywords.any { nameLower.contains(it) || groupLower.contains(it) } -> categorized["News"]!!.add(channel)
                kidsKeywords.any { nameLower.contains(it) || groupLower.contains(it) } -> categorized["Kids"]!!.add(channel)
                musicKeywords.any { nameLower.contains(it) || groupLower.contains(it) } -> categorized["Music"]!!.add(channel)
                else -> categorized["Regional & Others"]!!.add(channel)
            }
        }

        return categorized.mapNotNull { (groupName, list) ->
            if (list.isEmpty()) return@mapNotNull null
            Category(
                name = groupName,
                list = list.distinctBy { it.name }.take(40).map { channel ->
                    TvShow(
                        id = createId(channel),
                        title = channel.name,
                        poster = channel.logo ?: logo,
                        banner = channel.logo ?: logo,
                        providerName = "IPTV"
                    ).apply { 
                        itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM 
                    }
                }
            ).apply { 
                itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM 
            }
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (page > 1) return emptyList()
        val allChannels = getAllChannels()
        return allChannels.filter { it.name.contains(query, ignoreCase = true) }
            .distinctBy { it.name }
            .take(60)
            .map { channel ->
                TvShow(id = createId(channel), title = channel.name, poster = channel.logo ?: logo, providerName = "IPTV")
            }
    }

    override suspend fun getTvShow(id: String): TvShow {
        val (_, name, logoUrl) = decodeId(id)
        return TvShow(
            id = id,
            title = name,
            poster = logoUrl.ifEmpty { logo },
            banner = logoUrl.ifEmpty { logo },
            overview = "Local IPTV Stream: $name",
            seasons = listOf(Season(id = id, number = 1, title = "Live Stream")),
            providerName = "IPTV"
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        return listOf(Episode(id = seasonId, number = 1, title = "Play Stream", season = null))
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        return listOf(Video.Server(id = id, name = "Direct Stream"))
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val (url, _, _) = decodeId(server.id)
        val meta = getMetadataFromId(server.id)
        
        val headers = mutableMapOf<String, String>()
        meta["ua"]?.let { headers["User-Agent"] = it }
        meta["referer"]?.let { headers["Referer"] = it }
        
        // Default UA for ronaldo.tvfor.pro if not specified
        if (url.contains("ronaldo.tvfor.pro") && !headers.containsKey("User-Agent")) {
            headers["User-Agent"] = "Lavf/56.15.102"
        }

        return Video(source = url, headers = headers)
    }

    private fun parseM3U(m3uRaw: String): List<M3UChannel> {
        val channels = mutableListOf<M3UChannel>()
        val lines = m3uRaw.lines()

        var curName = ""
        var curLogo = ""
        var curGroup = ""
        var curUA: String? = null
        var curRef: String? = null

        for (line in lines) {
            val t = line.trim()
            if (t.startsWith("#EXTINF")) {
                curName = t.substringAfterLast(",").trim()
                curLogo = Regex("""tvg-logo="([^"]+)"""").find(t)?.groupValues?.get(1) ?: ""
                curGroup = Regex("""group-title="([^"]+)"""").find(t)?.groupValues?.get(1) ?: ""
                
                // Parse inline UA/Referer if present
                if (t.contains("http-user-agent=")) {
                    curUA = t.substringAfter("http-user-agent=").substringBefore(" ").removeSurrounding("\"")
                }
                if (t.contains("http-referrer=")) {
                    curRef = t.substringAfter("http-referrer=").substringBefore(" ").removeSurrounding("\"")
                }
            } else if (t.startsWith("#EXTGRP")) {
                curGroup = t.substringAfter(":").trim()
            } else if (t.startsWith("#EXTVLCOPT:")) {
                if (t.contains("http-user-agent=")) curUA = t.substringAfter("http-user-agent=").trim()
                if (t.contains("http-referrer=")) curRef = t.substringAfter("http-referrer=").trim()
            } else if (t.startsWith("http")) {
                val urlParts = t.split("|")
                val cleanUrl = urlParts[0].trim()
                
                // Handle |User-Agent=... or similar patterns in URL
                if (urlParts.size > 1) {
                    urlParts.drop(1).forEach { part ->
                        if (part.contains("User-Agent=", ignoreCase = true)) {
                            curUA = part.substringAfter("=").substringAfter("=").trim() // Handle double ==
                        }
                    }
                }

                if (curName.isNotEmpty()) {
                    channels.add(M3UChannel(curName, cleanUrl, curLogo, curGroup, curUA, curRef))
                    curName = ""; curLogo = ""; curGroup = ""; curUA = null; curRef = null
                }
            }
        }
        return channels
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()
    override suspend fun getTvShows(page: Int): List<TvShow> = emptyList()
    override suspend fun getMovie(id: String): Movie = Movie(id = id, title = "Live", poster = "")
    override suspend fun getGenre(id: String, page: Int): Genre = Genre(id, id, emptyList<Show>())
    override suspend fun getPeople(id: String, page: Int): People = People(id, id)
}
