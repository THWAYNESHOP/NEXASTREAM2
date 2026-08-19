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

object CdnLiveTvProvider : Provider {
    private const val BASE_URL = "https://api.cdnlivetv.tv/api/v1/"
    private const val USER = "cdnlivetv"
    private const val PLAN = "free"

    override val name: String = "CDN Live TV"
    override val baseUrl: String = BASE_URL
    override val logo: String = "https://cdnlivetv.tv/assets/img/logo.png"
    override val language: String = "en"

    private val client = OkHttpClient.Builder()
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
                resp.values.firstOrNull()
            }.getOrElse { 
                Log.e("CdnLiveTv", "Error fetching sports", it)
                null 
            }
        }

        val channels = channelsDeferred.await()
        val sportsData = sportsDeferred.await()

        val categories = mutableListOf<Category>()

        // 1. Sports Events
        sportsData?.let { data ->
            val allEvents = (data.soccer.orEmpty() + data.nba.orEmpty() + data.nhl.orEmpty() + data.nfl.orEmpty())
                .filter { it.status.contains("live", ignoreCase = true) || it.status.contains("soon", ignoreCase = true) }
            
            if (allEvents.isNotEmpty()) {
                categories.add(
                    Category(
                        name = "Live Sports (CDN)",
                        list = allEvents.map { it.toSportMatch() }
                    ).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM }
                )
            }
        }

        // 2. Featured Channels
        if (channels.isNotEmpty()) {
            categories.add(
                Category(
                    name = "CDN Live Channels",
                    list = channels.take(20).map { it.toTvShow() }
                ).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM }
            )
            
            // Group by Country if needed, but let's keep it simple for Home
        }

        categories
    }

    private fun CDNChannel.toTvShow() = TvShow(
        id = "cdn:$code",
        title = name,
        poster = image,
        banner = image,
        quality = "LIVE",
        providerName = "CDN Live TV"
    ).apply { 
        itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM 
    }

    private fun CDNSportEvent.toSportMatch() = SportMatch(
        id = "cdn_match:$gameID",
        title = "$homeTeam vs $awayTeam",
        homeTeam = homeTeam,
        awayTeam = awayTeam,
        league = tournament,
        status = if (status.contains("live", ignoreCase = true)) "LIVE" else "UPCOMING",
        time = time,
        score = "vs",
        sport = tournament,
        poster = if (homeTeamIMG.isNotEmpty()) homeTeamIMG else countryIMG,
        date = null // API doesn't seem to give a unix timestamp
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
            val sportsData = runCatching { service.getAllSports().values.firstOrNull() }.getOrNull() ?: return emptyList()
            val allEvents = (sportsData.soccer.orEmpty() + sportsData.nba.orEmpty() + sportsData.nhl.orEmpty() + sportsData.nfl.orEmpty())
            val event = allEvents.find { it.gameID == gameId } ?: return emptyList()
            
            return event.channels.map { channel ->
                Video.Server(id = channel.url, name = "Mirror: ${channel.channelName}")
            }
        }

        return emptyList()
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return Video(source = server.id)
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
    override suspend fun getGenre(id: String, page: Int): Genre = throw UnsupportedOperationException()
    override suspend fun getPeople(id: String, page: Int): People = throw UnsupportedOperationException()
}
