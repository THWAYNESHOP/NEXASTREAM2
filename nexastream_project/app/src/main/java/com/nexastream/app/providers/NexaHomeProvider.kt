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

object NexaHomeProvider : Provider {
    override val baseUrl: String = ""
    override val name: String = "HOME"
    override val logo: String = ""
    override val language: String = "en"

    private val tmdb = TmdbProvider("en")

    override suspend fun getHome(): List<Category> = coroutineScope {
        val tmdbHomeDeferred = async { tmdb.getHome() }
        val liveSportsDeferred = async { SportsProvider.getLiveMatches() }
        val cdnHomeDeferred = async { CdnLiveTvProvider.getHome() }
        val localIptvDeferred = async { LocalIptvProvider.getHome() }
        val upcomingSportsDeferred = async { SportsProvider.getUpcomingMatches() }
        
        // Fetch specific genres for Netflix-style categories
        val animationDeferred = async { tmdb.getGenre("16") } // Animation
        val actionDeferred = async { tmdb.getGenre("28") } // Action
        val comedyDeferred = async { tmdb.getGenre("35") } // Comedy

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
                        match.copy(id = SportsProvider.playbackId(match)).apply {
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
        val sportGroups = listOf("Sky Sports", "TNT Sports", "Setanta", "Match!", "Bein Sports", "US Sports", "Global TV")
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
                        match.copy(id = SportsProvider.playbackId(match)).apply {
                            itemType = AppAdapter.Type.SPORT_MATCH_ITEM
                        }
                    }
                ).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM }
            )
        }

        // 7. Movies & Series Categories
        if (animationGenre.shows.isNotEmpty()) {
            categories.add(Category(name = "Animation", list = animationGenre.shows).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM })
        }
        if (actionGenre.shows.isNotEmpty()) {
            categories.add(Category(name = "Action & Adventure", list = actionGenre.shows).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM })
        }
        if (comedyGenre.shows.isNotEmpty()) {
            categories.add(Category(name = "Comedy", list = comedyGenre.shows).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM })
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
                    !title.contains("Kenyan", ignoreCase = true)
                }
            ).apply { 
                this.itemType = category.itemType
                this.selectedIndex = category.selectedIndex
                this.itemSpacing = category.itemSpacing
            }
        }

        filteredCategories
    }

    override suspend fun getTvShow(id: String): TvShow {
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
            SportsProvider.ownsPlaybackId(id) -> SportsProvider.getServers(id, videoType)
            else -> tmdb.getServers(id, videoType)
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        if (server.id.startsWith("localiptv:")) {
            return LocalIptvProvider.getVideo(server)
        }
        if (server.name.contains("CDN")) {
            return CdnLiveTvProvider.getVideo(server)
        }

        return when {
            server.id.contains("crichd.online") -> {
                if (server.id.contains(".m3u8")) {
                    Video(source = server.id, headers = mapOf("Referer" to "https://crichd.online/"))
                } else {
                    com.nexastream.app.extractors.Extractor.extract(server.id, server)
                }
            }
            server.id.startsWith("http://ronaldo.tvfor.pro") -> {
                val channel = HomeIptvChannels.channels.find { it.url == server.id }
                Video(
                    source = server.id,
                    headers = channel?.userAgent?.let { mapOf("User-Agent" to it) } ?: mapOf("User-Agent" to "Lavf/56.15.102")
                )
            }
            SportsProvider.ownsServer(server) -> {
                SportsProvider.getVideo(server)
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
    override suspend fun getGenre(id: String, page: Int): Genre = tmdb.getGenre(id, page)
    override suspend fun getPeople(id: String, page: Int): People = tmdb.getPeople(id, page)
}
