package com.nexastream.app.providers

import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.models.Category
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Genre
import com.nexastream.app.models.Movie
import com.nexastream.app.models.People
import com.nexastream.app.models.SportMatch
import com.nexastream.app.models.TvShow
import com.nexastream.app.models.Video
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

object NexaHomeProvider : Provider {
    override val baseUrl: String = ""
    override val name: String = "HOME"
    override val logo: String = ""
    override val language: String = "en"

    private val tmdb = TmdbProvider("en")

    override suspend fun getHome(): List<Category> = coroutineScope {
        val tmdbHomeDeferred = async { runCatching { tmdb.getHome() }.getOrElse { emptyList() } }
        val liveSportsDeferred = async { runCatching { AkSportsLiveProvider.getLiveMatches() }.getOrElse { emptyList() } }
        val cdnHomeDeferred = async { runCatching { CdnLiveTvProvider.getHome() }.getOrElse { emptyList() } }
        val localIptvDeferred = async { runCatching { LocalIptvProvider.getHome() }.getOrElse { emptyList() } }
        val upcomingSportsDeferred = async { runCatching { AkSportsLiveProvider.getUpcomingMatches() }.getOrElse { emptyList() } }
        
        // Fetch specific genres for Netflix-style categories
        val animationDeferred = async { runCatching { tmdb.getGenre("16") }.getOrNull() }
        val actionDeferred = async { runCatching { tmdb.getGenre("28") }.getOrNull() }
        val comedyDeferred = async { runCatching { tmdb.getGenre("35") }.getOrNull() }

        val tmdbHome = tmdbHomeDeferred.await()
        val liveSports = liveSportsDeferred.await()
        val cdnHome = cdnHomeDeferred.await()
        val localIptv = localIptvDeferred.await()
        val upcomingSports = upcomingSportsDeferred.await()
        val animationGenre = animationDeferred.await()
        val actionGenre = actionDeferred.await()
        val comedyGenre = comedyDeferred.await()

        val categories = mutableListOf<Category>()

        // 1. Hero Banner (from TMDB Featured)
        tmdbHome.find { it.name == Category.FEATURED }?.let {
            categories.add(it)
        }

        // 2. Live Sports (Real-time Events)
        if (liveSports.isNotEmpty()) {
            categories.add(
                Category(
                    name = "Live Sports",
                    list = liveSports.map { match ->
                        match.copy(id = AkSportsLiveProvider.playbackId(match)).apply {
                            itemType = AppAdapter.Type.SPORT_MATCH_ITEM
                        }
                    }
                ).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM }
            )
        }

        // 3. CDN Live TV & Global Sports
        categories.addAll(cdnHome)

        // 4. Local IPTV Categories (Prominent rows)
        localIptv.find { it.name == "Live Sports" }?.let { categories.add(it) }
        localIptv.find { it.name == "Movies & Series" }?.let { categories.add(it) }

        // 5. Trending (from TMDB)
        tmdbHome.find { (it.name == "Trending") || (it.name == "Di tendenza") }?.let {
            categories.add(it)
        }

        // 6. Static Sports Rows (Permanent Lineup)
        val sportGroups = listOf("Sky Sports", "TNT Sports", "Match!", "Bein Sports", "US Sports", "Global TV")
        sportGroups.forEach { group ->
            val list = HomeIptvChannels.getTvShows(group)
            if (list.isNotEmpty()) {
                categories.add(
                    Category(
                        name = group,
                        list = list
                    ).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM }
                )
            }
        }

        // 7. Other Local IPTV Categories
        localIptv.filter { it.name !in listOf("Live Sports", "Movies & Series") }.forEach { categories.add(it) }

        // 8. Upcoming Matches
        if (upcomingSports.isNotEmpty()) {
             categories.add(
                Category(
                    name = "Upcoming Sports",
                    list = upcomingSports.take(20).map { match ->
                        match.copy(id = AkSportsLiveProvider.playbackId(match)).apply {
                            itemType = AppAdapter.Type.SPORT_MATCH_ITEM
                        }
                    }
                ).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM }
            )
        }

        // 7. Movies & Series Categories
        animationGenre?.shows?.takeIf { it.isNotEmpty() }?.let {
            categories.add(Category(name = "Animation", list = it).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM })
        }
        actionGenre?.shows?.takeIf { it.isNotEmpty() }?.let {
            categories.add(Category(name = "Action & Adventure", list = it).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM })
        }
        comedyGenre?.shows?.takeIf { it.isNotEmpty() }?.let {
            categories.add(Category(name = "Comedy", list = it).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM })
        }

        // Popular on Platforms
        tmdbHome.find { it.name.contains("Netflix") }?.let { categories.add(it) }
        tmdbHome.find { it.name.contains("Disney+") }?.let { categories.add(it) }

        // Exclusion filter
        val filteredCategories = categories.map { category ->
            category.copy(
                list = category.list.filter { item ->
                    val title = when (item) {
                        is Movie -> item.title
                        is TvShow -> item.title
                        is SportMatch -> item.title
                        else -> ""
                    }
                    !title.contains("Kenyan", ignoreCase = true) && !title.contains("Setanta", ignoreCase = true)
                }
            ).apply { 
                // SAFETY: Ensure itemType is initialized
                this.itemType = try { category.itemType } catch(_: Exception) { AppAdapter.Type.CATEGORY_MOBILE_ITEM }
                this.selectedIndex = category.selectedIndex
                this.itemSpacing = category.itemSpacing
            }
        }

        filteredCategories
    }

    override suspend fun getTvShow(id: String): TvShow {
        android.util.Log.e("NexaHomeProvider", "getTvShow(id=$id)")
        if (id.startsWith("cdn:")) {
            return CdnLiveTvProvider.getTvShow(id)
        }
        if (id.startsWith("cdn_match:")) {
            // Return a placeholder for CDN matches to avoid TMDB crash
            return TvShow(
                id = id, 
                title = "Live Sports Match", 
                providerName = "CDN Live TV", 
                quality = "LIVE",
                poster = "https://cdnlivetv.tv/assets/img/logo.png"
            ).apply { itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM }
        }
        if (id.startsWith("localiptv:")) {
            return LocalIptvProvider.getTvShow(id)
        }
        if (AkSportsLiveProvider.ownsPlaybackId(id)) {
            // Return a placeholder for AK Sports matches
            return TvShow(
                id = id, 
                title = "Live Sports Match", 
                providerName = "AK Sports Live", 
                quality = "LIVE",
                poster = "https://i.ibb.co/W1d0CxF/Logo-IPTV-All-World.jpg"
            ).apply { itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM }
        }
        // Handle static IPTV channels
        val staticChannel = HomeIptvChannels.channels.find { it.id == id }
        if (staticChannel != null) {
            return TvShow(
                id = id, 
                title = staticChannel.name, 
                poster = staticChannel.logo ?: "https://i.ibb.co/W1d0CxF/Logo-IPTV-All-World.jpg",
                banner = "https://i.ibb.co/W1d0CxF/Logo-IPTV-All-World.jpg",
                providerName = name,
                quality = "LIVE"
            ).apply {
                itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM
            }
        }
        return tmdb.getTvShow(id)
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        if (id.startsWith("localiptv:")) {
            return LocalIptvProvider.getServers(id, videoType)
        }
        if (id.startsWith("cdn:") || id.startsWith("cdn_match:")) {
            return CdnLiveTvProvider.getServers(id, videoType)
        }

        val staticChannel = HomeIptvChannels.channels.find { it.id == id }
        if (staticChannel != null) {
            val servers = mutableListOf<Video.Server>()
            
            // Mirror logic for UK sports
            val mirrorId = when {
                staticChannel.id.startsWith("sky-arena") -> "skyarena"
                staticChannel.id.startsWith("sky-action") -> "skyaction"
                staticChannel.id.startsWith("sky-mix") -> "skymix"
                staticChannel.id.startsWith("sky-golf") -> "skygolf"
                staticChannel.id.startsWith("sky-cricket") -> "skycricket"
                staticChannel.id.startsWith("sky-f1") -> "skyf1"
                staticChannel.id.startsWith("sky-football") -> "skyfootball"
                staticChannel.id.startsWith("sky-main-event") -> "skymainevent"
                staticChannel.id.startsWith("sky-news") -> "skynews"
                staticChannel.id.startsWith("sky-racing") -> "skyracing"
                staticChannel.id.startsWith("tnt-1") -> "tnt1"
                staticChannel.id.startsWith("tnt-2") -> "tnt2"
                staticChannel.id.startsWith("tnt-3") -> "tnt3"
                staticChannel.id.startsWith("tnt-4") -> "tnt4"
                staticChannel.id.startsWith("eurosport-1") -> "euro1"
                staticChannel.id.startsWith("eurosport-2") -> "euro2"
                staticChannel.id.startsWith("espn") -> "espn"
                else -> null
            }
            
            if (mirrorId != null) {
                servers.add(Video.Server(id = "https://crichd.online/embed.php?id=$mirrorId", name = "Mirror 1 (CricHD)"))
            }

            servers.add(Video.Server(id = staticChannel.url, name = "Main Server (IPTV)"))
            return servers
        }

        return when {
            AkSportsLiveProvider.ownsPlaybackId(id) -> AkSportsLiveProvider.getServers(id, videoType)
            else -> tmdb.getServers(id, videoType)
        }
    }

    override suspend fun getVideo(server: Video.Server): Video = withContext(Dispatchers.IO) {
        if (server.id.startsWith("localiptv:")) {
            return@withContext LocalIptvProvider.getVideo(server)
        }
        if (server.name.contains("CDN")) {
            return@withContext CdnLiveTvProvider.getVideo(server)
        }

        if (server.id.startsWith("http://ronaldo.tvfor.pro")) {
            val channel = HomeIptvChannels.channels.find { it.url == server.id }
            val cleanUrl = server.id.substringBefore("|")
            val userAgent = channel?.userAgent ?: "Lavf/56.15.102"
            
            try {
                // Manually resolve redirect to get the stable IP link and capture the token properly
                val request = okhttp3.Request.Builder()
                    .url(cleanUrl)
                    .header("User-Agent", userAgent)
                    .header("Referer", "http://ronaldo.tvfor.pro/")
                    .build()
                
                // Use a client that follows redirects
                val response = com.nexastream.app.utils.NetworkClient.noRedirects.newBuilder()
                    .followRedirects(false) // We want to see the redirect target
                    .build()
                    .newCall(request)
                    .execute()
                
                val location = response.header("Location")
                val cookies = response.headers("Set-Cookie")
                response.close()

                if (!location.isNullOrBlank()) {
                    android.util.Log.e("NexaHomeProvider", "Resolved redirect: $location")
                    android.util.Log.e("NexaHomeProvider", "Captured cookies: $cookies")
                    
                    val uri = android.net.Uri.parse(location)
                    val token = uri.getQueryParameter("token")
                    if (token != null) {
                        com.nexastream.app.extractors.TokenManager.latestQuery = "token=$token"
                    }

                    return@withContext Video(
                        source = location,
                        headers = mapOf(
                            "User-Agent" to userAgent,
                            "Referer" to "http://ronaldo.tvfor.pro/",
                            "Origin" to "http://ronaldo.tvfor.pro",
                            "Accept" to "*/*",
                            "Accept-Encoding" to "identity",
                            "Icy-MetaData" to "1",
                            "Connection" to "keep-alive"
                        ),
                        maintainToken = true
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("NexaHomeProvider", "Error resolving ronaldo redirect", e)
            }

            return@withContext Video(
                source = cleanUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "http://ronaldo.tvfor.pro/",
                    "Origin" to "http://ronaldo.tvfor.pro",
                    "Accept" to "*/*",
                    "Accept-Encoding" to "identity",
                    "Icy-MetaData" to "1",
                    "Connection" to "keep-alive"
                ),
                maintainToken = true
            )
        }

        return@withContext when {
            server.id.contains("crichd.online") -> {
                if (server.id.contains(".m3u8")) {
                    Video(source = server.id, headers = mapOf("Referer" to "https://crichd.online/"))
                } else {
                    com.nexastream.app.extractors.Extractor.extract(server.id, server)
                }
            }
            server.id.startsWith("http://ronaldo.tvfor.pro") -> {
                val channel = HomeIptvChannels.channels.find { it.url == server.id }
                val cleanUrl = server.id.substringBefore("|")
                
                // Use a more neutral set of headers
                val headers = mutableMapOf<String, String>()
                headers["User-Agent"] = channel?.userAgent ?: "Lavf/56.15.102"
                headers["Referer"] = "http://ronaldo.tvfor.pro/"
                headers["Accept"] = "*/*"
                headers["Connection"] = "keep-alive"
                headers["Icy-MetaData"] = "1"

                Video(
                    source = cleanUrl,
                    headers = headers,
                    maintainToken = true
                )
            }
            AkSportsLiveProvider.ownsServer(server) -> {
                AkSportsLiveProvider.getVideo(server)
            }
            else -> {
                tmdb.getVideo(server)
            }
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> = tmdb.search(query, page)
    override suspend fun getMovies(page: Int): List<Movie> = tmdb.getMovies(page)
    override suspend fun getTvShows(page: Int): List<TvShow> = tmdb.getTvShows(page)
    override suspend fun getMovie(id: String): Movie = tmdb.getMovie(id)
    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = tmdb.getEpisodesBySeason(seasonId)
    override suspend fun getGenre(id: String, page: Int): Genre = when {
        id == "cdn_all_channels" -> CdnLiveTvProvider.getGenre(id, page)
        else -> tmdb.getGenre(id, page)
    }

    override suspend fun getPeople(id: String, page: Int): People = tmdb.getPeople(id, page)
}
