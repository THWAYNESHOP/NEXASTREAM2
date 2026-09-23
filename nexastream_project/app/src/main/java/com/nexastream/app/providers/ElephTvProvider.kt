package com.nexastream.app.providers

import android.util.Log
import com.google.gson.annotations.SerializedName
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.models.Category
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Genre
import com.nexastream.app.models.Movie
import com.nexastream.app.models.People
import com.nexastream.app.models.SearchFilters
import com.nexastream.app.models.TvShow
import com.nexastream.app.models.Video
import com.nexastream.app.utils.NetworkClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

object ElephTvProvider : Provider, IptvProvider, ProviderPortalUrl {

    override val defaultPortalUrl: String = "https://qlouy.vnhtkrdga.com/"
    override val portalUrl: String = defaultPortalUrl

    private const val BACKUP_PORTAL_URL = "https://bckqe.kxneagytj.com/"
    private const val EPG_BASE_URL = "https://gjihn.qkymwrgac.com/"

    override val name: String = "Elephant TV"
    override val baseUrl: String = defaultPortalUrl
    override val logo: String = "https://www.elephmob.com/favicon.ico"
    override val language: String = "en"

    private val client = NetworkClient.systemDns.newBuilder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val service = Retrofit.Builder()
        .baseUrl(defaultPortalUrl)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(ElephService::class.java)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var cachedChannels: List<ElephChannel> = emptyList()
    private var lastFetchTime: Long = 0L
    private const val CACHE_EXPIRY = 5 * 60 * 1000L // 5 minutes

    data class ElephChannel(
        @SerializedName("id") val id: String? = null,
        @SerializedName("code") val code: String? = null,
        @SerializedName("name") val name: String = "",
        @SerializedName("image") val image: String? = null,
        @SerializedName("logo") val logo: String? = null,
        @SerializedName("category") val category: String? = null,
        @SerializedName("url") val url: String? = null,
        @SerializedName("stream_url") val streamUrl: String? = null
    )

    data class ElephChannelsResponse(
        @SerializedName("channels") val channels: List<ElephChannel> = emptyList(),
        @SerializedName("data") val data: List<ElephChannel> = emptyList(),
        @SerializedName("code") val code: Int? = null
    )

    interface ElephService {
        @GET("api/v1/live/channels")
        suspend fun getChannels(
            @Query("lang") lang: String = "en"
        ): ElephChannelsResponse
    }

    private suspend fun refreshCacheSilently() {
        runCatching {
            val resp = service.getChannels()
            val list = if (resp.channels.isNotEmpty()) resp.channels else resp.data
            if (list.isNotEmpty()) {
                cachedChannels = list
                lastFetchTime = System.currentTimeMillis()
                Log.d("ElephTvProvider", "Cache refreshed silently. Total channels: ${list.size}")
            }
        }.onFailure {
            Log.e("ElephTvProvider", "Silent cache refresh failed", it)
        }
    }

    override suspend fun getHome(): List<Category> {
        val now = System.currentTimeMillis()
        if (cachedChannels.isNotEmpty()) {
            if (now - lastFetchTime > CACHE_EXPIRY) {
                Log.d("ElephTvProvider", "Cache stale, triggering background refresh")
                scope.launch { refreshCacheSilently() }
            }
            return buildHomeCategories(cachedChannels)
        }

        return coroutineScope {
            val channels = runCatching {
                val resp = service.getChannels()
                if (resp.channels.isNotEmpty()) resp.channels else resp.data
            }.getOrElse {
                Log.e("ElephTvProvider", "Error fetching channels from primary portal", it)
                emptyList()
            }

            if (channels.isNotEmpty()) {
                cachedChannels = channels
                lastFetchTime = System.currentTimeMillis()
            }

            buildHomeCategories(channels)
        }
    }

    private suspend fun buildHomeCategories(channels: List<ElephChannel>): List<Category> {
        val categories = mutableListOf<Category>()

        if (channels.isNotEmpty()) {
            val allItems = channels.map { it.toTvShow() }

            // 1. All Channels
            categories.add(
                Category(
                    name = "ElephTV Live Channels",
                    list = allItems.take(40)
                ).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM }
            )

            // 2. Sports Channels Filter
            val sportsKeywords = listOf("Sport", "Sports", "ESPN", "Fox", "Football", "Soccer", "NBA", "F1", "UFC", "Racing", "Golf", "League", "Arena", "Tennis", "BeIN")
            val sportsItems = channels.filter { ch -> sportsKeywords.any { ch.name.contains(it, ignoreCase = true) } }.map { it.toTvShow() }
            if (sportsItems.isNotEmpty()) {
                categories.add(
                    Category(
                        name = "Live Sports Channels",
                        list = sportsItems
                    ).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM }
                )
            }

            // 3. News & Entertainment Filter
            val newsKeywords = listOf("News", "CNN", "BBC", "Fox News", "Sky", "NBC", "CBS", "ABC", "Bloomberg", "CNBC")
            val newsItems = channels.filter { ch -> newsKeywords.any { ch.name.contains(it, ignoreCase = true) } }.map { it.toTvShow() }
            if (newsItems.isNotEmpty()) {
                categories.add(
                    Category(
                        name = "News & Info",
                        list = newsItems
                    ).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM }
                )
            }
        }

        return categories
    }

    private suspend fun ElephChannel.toTvShow(): TvShow {
        val channelId = id ?: code ?: name.lowercase().replace(" ", "_")
        val channelLogo = logo ?: image ?: com.nexastream.app.utils.ChannelLogoRepository.getLogoUrl(name) ?: ""

        return TvShow(
            id = "eleph:$channelId",
            title = name,
            poster = channelLogo,
            banner = channelLogo,
            quality = "LIVE",
            providerName = "Elephant TV"
        ).apply {
            itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM
        }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        if (id.startsWith("eleph:")) {
            val channelId = id.removePrefix("eleph:")
            val channel = cachedChannels.find { (it.id ?: it.code ?: it.name.lowercase().replace(" ", "_")) == channelId }
            val streamUrl = channel?.url ?: channel?.streamUrl
            if (streamUrl != null) {
                return listOf(Video.Server(id = streamUrl, name = "ElephTV Live Stream"))
            }
        }
        return emptyList()
    }

    override suspend fun getVideo(server: Video.Server): Video = withContext(Dispatchers.IO) {
        val streamUrl = server.id
        Log.d("ElephTvProvider", "Resolving live video stream: $streamUrl")

        Video(
            source = streamUrl,
            headers = mapOf(
                "User-Agent" to NetworkClient.USER_AGENT,
                "Referer" to defaultPortalUrl,
                "Origin" to defaultPortalUrl.trimEnd('/')
            )
        )
    }

    override suspend fun search(query: String, page: Int, filters: SearchFilters?): List<AppAdapter.Item> {
        if (query.isBlank()) return emptyList()
        val channels = if (cachedChannels.isNotEmpty()) cachedChannels else getHome().flatMap { cat -> cat.list }.mapNotNull { item -> (item as? TvShow)?.let { ElephChannel(id = it.id, name = it.title, logo = it.poster) } }
        return channels.filter { it.name.contains(query, ignoreCase = true) }.map { it.toTvShow() }
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()
    override suspend fun getTvShows(page: Int): List<TvShow> = emptyList()
    override suspend fun getMovie(id: String): Movie = Movie(id = id, title = "Not Supported")

    override suspend fun getTvShow(id: String): TvShow {
        if (id.startsWith("eleph:")) {
            val channelId = id.removePrefix("eleph:")
            val channel = cachedChannels.find { (it.id ?: it.code ?: it.name.lowercase().replace(" ", "_")) == channelId }
            if (channel != null) {
                return channel.toTvShow()
            }
        }
        return TvShow(id = id, title = "Live Channel")
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = emptyList()

    override suspend fun getGenre(id: String, page: Int): Genre = coroutineScope {
        if (page > 1) {
            return@coroutineScope Genre(id = id, name = "Elephant TV", shows = emptyList())
        }

        val channels = if (cachedChannels.isNotEmpty()) cachedChannels else emptyList()
        val filtered = when (id) {
            "eleph_sports" -> {
                val keywords = listOf("Sport", "Sports", "ESPN", "Fox", "Football", "Soccer", "NBA", "F1", "UFC", "Tennis", "BeIN")
                channels.filter { ch -> keywords.any { ch.name.contains(it, ignoreCase = true) } }
            }
            "eleph_news" -> {
                val keywords = listOf("News", "CNN", "BBC", "Fox News", "Sky", "NBC", "CBS", "ABC")
                channels.filter { ch -> keywords.any { ch.name.contains(it, ignoreCase = true) } }
            }
            else -> channels
        }

        Genre(
            id = id,
            name = if (id == "eleph_sports") "Live Sports" else "Elephant TV Live",
            shows = filtered.map { async { it.toTvShow() } }.awaitAll()
        )
    }

    override suspend fun getPeople(id: String, page: Int): People = throw UnsupportedOperationException()
}
