package com.nexastream.app.providers

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.live.LiveTvCodec
import com.nexastream.app.live.LiveTvRepository
import com.nexastream.app.models.Category
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Genre
import com.nexastream.app.models.LiveChannelDescriptor
import com.nexastream.app.models.LiveRecording
import com.nexastream.app.models.LiveStreamDescriptor
import com.nexastream.app.models.Movie
import com.nexastream.app.models.People
import com.nexastream.app.models.Season
import com.nexastream.app.models.TvShow
import com.nexastream.app.models.Video
import com.nexastream.app.utils.inferVideoMimeType
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.TimeUnit

object IptvOrgProvider : IptvProvider {

    override val name = LiveTvRepository.PROVIDER_NAME
    override val baseUrl = "https://iptv-org.github.io/iptv"
    override val logo = "https://i.ibb.co/W1d0CxF/Logo-IPTV-All-World.jpg"
    override val language = "en"

    private const val TAG = "IptvOrgProvider"
    private const val CACHE_DURATION = 30 * 60 * 1000L
    private const val RECORDING_SERVER_PREFIX = "live-recording-server:"

    private val OFFICIAL_CATEGORIES = listOf(
        "Animation", "Auto", "Business", "Classic", "Comedy", "Cooking", "Culture",
        "Documentary", "Education", "Entertainment", "Family", "General", "Interactive",
        "Kids", "Legislative", "Lifestyle", "Movies", "Music", "News", "Outdoor",
        "Public", "Relax", "Religious", "Science", "Series", "Shop", "Sports",
        "Travel", "Weather", "Undefined",
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            private val cookieStore = mutableMapOf<String, List<Cookie>>()
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore[url.host] = cookies
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> = cookieStore[url.host].orEmpty()
        })
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36",
                    )
                    .build(),
            )
        }
        .build()

    private var cachedChannels: List<LiveChannelDescriptor>? = null
    private var cachedGuideUrls: List<String> = emptyList()
    private var lastFetchTime: Long = 0L

    private data class PendingChannel(
        val tvgId: String,
        val name: String,
        val logo: String?,
        val group: String?,
        val stream: LiveStreamDescriptor,
    )

    private fun getAllChannels(): List<LiveChannelDescriptor> {
        val now = System.currentTimeMillis()
        cachedChannels?.takeIf { now - lastFetchTime < CACHE_DURATION }?.let { return it }

        return runCatching {
            val request = Request.Builder().url("$baseUrl/index.m3u").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Playlist HTTP ${response.code}")
                val source = response.body?.source() ?: error("Empty playlist")
                val rows = mutableListOf<PendingChannel>()
                val guideUrls = linkedSetOf<String>()
                var currentName = ""
                var currentTvgId = ""
                var currentLogo: String? = null
                var currentGroup: String? = null
                var currentQuality: String? = null
                var currentLabel: String? = null
                var currentUserAgent: String? = null
                var currentReferrer: String? = null

                source.use {
                    while (!it.exhausted()) {
                        val line = it.readUtf8Line()?.trim() ?: break
                        when {
                            line.startsWith("#EXTM3U", ignoreCase = true) -> {
                                listOf("x-tvg-url", "url-tvg").forEach { key ->
                                    attribute(line, key)
                                        ?.split(',')
                                        ?.map(String::trim)
                                        ?.filter { value -> value.startsWith("http", ignoreCase = true) }
                                        ?.let(guideUrls::addAll)
                                }
                            }

                            line.startsWith("#EXTINF", ignoreCase = true) -> {
                                currentName = line.substringAfterLast(',').trim()
                                currentTvgId = attribute(line, "tvg-id").orEmpty().trim()
                                currentLogo = attribute(line, "tvg-logo")?.takeIf(String::isNotBlank)
                                currentGroup = attribute(line, "group-title")?.takeIf(String::isNotBlank)
                                currentQuality = attribute(line, "quality")?.takeIf(String::isNotBlank)
                                    ?: extractQuality(currentName)
                                currentLabel = attribute(line, "label")?.takeIf(String::isNotBlank)
                                currentUserAgent = attribute(line, "http-user-agent")?.takeIf(String::isNotBlank)
                                currentReferrer = (
                                    attribute(line, "http-referrer")
                                        ?: attribute(line, "http-referer")
                                        ?: attribute(line, "referrer")
                                    )?.takeIf(String::isNotBlank)
                            }

                            line.startsWith("#EXTVLCOPT:http-user-agent=", ignoreCase = true) -> {
                                currentUserAgent = line.substringAfter('=').trim().takeIf(String::isNotBlank)
                            }

                            line.startsWith("#EXTVLCOPT:http-referrer=", ignoreCase = true) ||
                                line.startsWith("#EXTVLCOPT:http-referer=", ignoreCase = true) -> {
                                currentReferrer = line.substringAfter('=').trim().takeIf(String::isNotBlank)
                            }

                            line.startsWith("http", ignoreCase = true) && currentName.isNotBlank() -> {
                                val (streamUrl, inlineHeaders) = splitInlineHeaders(line)
                                val stableTvgId = currentTvgId.ifBlank {
                                    "name:${currentName.lowercase(Locale.ROOT).replace("[^a-z0-9]+".toRegex(), "-").trim('-')}"
                                }
                                rows += PendingChannel(
                                    tvgId = stableTvgId,
                                    name = currentName,
                                    logo = currentLogo,
                                    group = currentGroup,
                                    stream = LiveStreamDescriptor(
                                        url = streamUrl,
                                        quality = currentQuality,
                                        userAgent = currentUserAgent ?: inlineHeaders["user-agent"],
                                        referrer = currentReferrer
                                            ?: inlineHeaders["referer"]
                                            ?: inlineHeaders["referrer"],
                                        label = currentLabel,
                                    ),
                                )
                                currentName = ""
                                currentTvgId = ""
                                currentLogo = null
                                currentGroup = null
                                currentQuality = null
                                currentLabel = null
                                currentUserAgent = null
                                currentReferrer = null
                            }
                        }
                    }
                }

                val channels = rows.groupBy { it.tvgId }.map { (tvgId, alternatives) ->
                    val primary = alternatives.first()
                    LiveChannelDescriptor(
                        tvgId = tvgId,
                        name = primary.name,
                        logo = alternatives.firstNotNullOfOrNull { it.logo },
                        group = alternatives.firstNotNullOfOrNull { it.group },
                        guideUrls = guideUrls.toList(),
                        streams = alternatives.map { it.stream }.distinctBy {
                            listOf(it.url, it.userAgent, it.referrer).joinToString("\u001f")
                        },
                    )
                }
                cachedGuideUrls = guideUrls.toList()
                cachedChannels = channels
                lastFetchTime = now
                LiveTvRepository.configureEpg(channels.map { it.tvgId }, guideUrls)
                channels
            }
        }.onFailure {
            Log.e(TAG, "Unable to load IPTV-org playlist", it)
        }.getOrElse { cachedChannels.orEmpty() }
    }

    override suspend fun getHome(): List<Category> {
        val channels = getAllChannels()
        val homeGroups = listOf("Animation", "Comedy", "Series", "Entertainment", "News", "Movies", "Sports")
        val channelsById = channels.associateBy { it.tvgId }
        val preferences = LiveTvRepository.getSavedChannelPreferences()
        val channelCategories = mutableListOf<Category>()

        val favorites = preferences.filter { it.isFavorite }.mapNotNull { channelsById[it.channelId] }
        if (favorites.isNotEmpty()) {
            channelCategories += Category("★ Favorite channels", buildTvShows(favorites.take(40)))
        }
        preferences
            .filter { !it.customGroup.isNullOrBlank() }
            .groupBy { it.customGroup!!.trim() }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
            .forEach { (group, savedChannels) ->
                val grouped = savedChannels.mapNotNull { channelsById[it.channelId] }
                if (grouped.isNotEmpty()) channelCategories += Category("My group · $group", buildTvShows(grouped.take(40)))
            }
        val recent = preferences
            .filter { it.lastWatchedAt != null }
            .sortedByDescending { it.lastWatchedAt }
            .mapNotNull { channelsById[it.channelId] }
            .take(25)
        if (recent.isNotEmpty()) {
            channelCategories += Category("Recently watched", buildTvShows(recent))
        }

        channelCategories += channels
            .filter { channel ->
                channel.group?.let { group -> homeGroups.any { group.contains(it, ignoreCase = true) } } == true
            }
            .groupBy { channel ->
                homeGroups.firstOrNull { channel.group?.contains(it, ignoreCase = true) == true } ?: "Other"
            }
            .map { (groupName, channelList) ->
                Category(groupName, buildTvShows(channelList.take(25)))
            }
            .sortedBy { it.name }
        channelCategories += Category(
            name = "Support and help",
            list = listOf(getInfoItem("creador-info"), getInfoItem("apoyo-nando")),
        )
        return channelCategories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (page > 1) return emptyList()
        val results = mutableListOf<AppAdapter.Item>()
        OFFICIAL_CATEGORIES
            .filter { it.contains(query, ignoreCase = true) }
            .sorted()
            .forEach { results += Genre(id = it, name = "Category: $it", shows = emptyList()) }
        results += buildTvShows(
            getAllChannels().filter {
                it.name.contains(query, ignoreCase = true) || it.tvgId.contains(query, ignoreCase = true)
            }.take(80),
        )
        return results
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val channels = getAllChannels().filter { channel ->
            channel.group?.split(';')?.any { it.trim().equals(id, ignoreCase = true) } == true
        }
        val pageSize = 40
        return Genre(
            id = id,
            name = id,
            shows = buildTvShows(channels.drop((page - 1) * pageSize).take(pageSize)),
        )
    }

    override suspend fun getPeople(id: String, page: Int): People =
        throw UnsupportedOperationException("People are not available for live channels")

    override suspend fun getTvShow(id: String): TvShow {
        if (id == "creador-info" || id == "apoyo-nando") return getInfoItem(id)
        val channel = decodeChannel(id)
        return buildTvShows(listOf(channel)).first().apply {
            overview = buildString {
                append("Channel: ${channel.name}\nTVG ID: ${channel.tvgId}\nSource: IPTV-org")
                if (channel.streams.size > 1) append("\n${channel.streams.size} automatic failover sources")
            }
        }
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        if (seasonId == "creador-info" || seasonId == "apoyo-nando") return emptyList()
        return listOf(Episode(id = seasonId, number = 1, title = "Watch live", season = null))
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        if (id == "creador-info" || id == "apoyo-nando") return emptyList()
        if (id.startsWith(LiveTvCodec.RECORDING_PREFIX)) {
            val recordingId = id.removePrefix(LiveTvCodec.RECORDING_PREFIX)
            val recording = LiveTvRepository.getRecording(recordingId) ?: return emptyList()
            return listOf(
                Video.Server(
                    id = "$RECORDING_SERVER_PREFIX$recordingId",
                    name = "DVR recording",
                    src = recording.filePath,
                ),
            )
        }

        val channel = decodeChannel(id)
        val streams = LiveTvRepository.orderStreams(channel.tvgId, channel.streams)
        return streams.mapIndexed { index, stream ->
            val detail = listOfNotNull(stream.quality, stream.label).distinct().joinToString(" · ")
            Video.Server(
                id = LiveTvCodec.encodeServer(channel.tvgId, channel.name, stream),
                name = detail.ifBlank { "IPTV source ${index + 1}" },
            )
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        if (server.id.startsWith(RECORDING_SERVER_PREFIX)) {
            return Video(
                source = Uri.fromFile(java.io.File(server.src)).toString(),
                type = inferVideoMimeType(server.src),
            )
        }
        val payload = LiveTvCodec.decodeServer(server.id)
            ?: return legacyVideo(server.id)
        val headers = buildMap {
            payload.stream.userAgent?.takeIf(String::isNotBlank)?.let { put("User-Agent", it) }
            payload.stream.referrer?.takeIf(String::isNotBlank)?.let { put("Referer", it) }
        }.takeIf { it.isNotEmpty() }
        Log.d(TAG, "Play ${payload.channelId} from ${payload.stream.url}")
        return Video(
            source = payload.stream.url,
            subtitles = emptyList(),
            headers = headers,
            type = inferVideoMimeType(payload.stream.url),
        )
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()
    override suspend fun getMovie(id: String): Movie = Movie(id = id, title = "Live", poster = "")

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val channels = getAllChannels()
        val pageSize = 50
        val start = (page - 1) * pageSize
        return if (start >= channels.size) emptyList() else buildTvShows(channels.drop(start).take(pageSize))
    }

    suspend fun getGuideChannels(limit: Int = 600): List<TvShow> {
        val channels = getAllChannels().take(limit)
        LiveTvRepository.configureEpg(channels.map { it.tvgId }, cachedGuideUrls)
        return buildTvShows(channels)
    }

    suspend fun refreshEpg(force: Boolean): Boolean {
        val channels = getAllChannels().take(1_500)
        return LiveTvRepository.refreshEpg(channels.map { it.tvgId }, cachedGuideUrls, force)
    }

    fun channelIdFromEncodedId(id: String): String? = decodeChannelOrNull(id)?.tvgId

    fun descriptorFromEncodedId(id: String): LiveChannelDescriptor? = decodeChannelOrNull(id)

    private suspend fun buildTvShows(channels: List<LiveChannelDescriptor>): List<TvShow> {
        val metadata = LiveTvRepository.metadataForChannels(channels)
        return channels.map { channel ->
            TvShow(
                id = LiveTvCodec.encodeChannel(channel),
                title = channel.name,
                poster = channel.logo.orEmpty(),
                banner = channel.logo.orEmpty(),
                quality = channel.streams.firstNotNullOfOrNull { it.quality },
                providerName = name,
                seasons = listOf(Season(id = LiveTvCodec.encodeChannel(channel), number = 1, title = "Live")),
                isFavorite = metadata[channel.tvgId]?.isFavorite == true,
            ).also { it.liveMetadata = metadata[channel.tvgId] }
        }
    }

    private fun decodeChannel(id: String): LiveChannelDescriptor = decodeChannelOrNull(id)
        ?: LiveChannelDescriptor(
            tvgId = "legacy:${LiveTvCodec.stableId(id).take(16)}",
            name = "Unknown channel",
            streams = listOf(LiveStreamDescriptor(url = id)),
        )

    private fun decodeChannelOrNull(id: String): LiveChannelDescriptor? {
        LiveTvCodec.decodeChannel(id)?.let { return it }
        return runCatching {
            val decoded = String(Base64.decode(id, Base64.DEFAULT), Charsets.UTF_8)
            val parts = decoded.split('|')
            val url = parts.firstOrNull()?.takeIf { it.startsWith("http", ignoreCase = true) }
                ?: return@runCatching null
            val channelName = parts.getOrNull(1).orEmpty().ifBlank { "Legacy channel" }
            LiveChannelDescriptor(
                tvgId = "legacy:${LiveTvCodec.stableId(url).take(16)}",
                name = channelName,
                logo = parts.getOrNull(2)?.takeIf(String::isNotBlank),
                streams = listOf(
                    LiveStreamDescriptor(
                        url = url,
                        quality = extractQuality(channelName),
                        userAgent = parts.getOrNull(3)?.takeIf(String::isNotBlank),
                    ),
                ),
            )
        }.getOrNull()
    }

    private fun legacyVideo(id: String): Video {
        val channel = decodeChannel(id)
        val stream = channel.streams.first()
        return Video(
            source = stream.url,
            headers = buildMap {
                stream.userAgent?.let { put("User-Agent", it) }
                stream.referrer?.let { put("Referer", it) }
            }.takeIf { it.isNotEmpty() },
            type = inferVideoMimeType(stream.url),
        )
    }

    private fun getInfoItem(id: String): TvShow {
        val isReport = id == "creador-info"
        return TvShow(
            id = id,
            title = if (isReport) "Report problems" else "Support the provider",
            poster = if (isReport) {
                "https://i.ibb.co/dsknGBHT/Imagen-de-Whats-App-2025-09-06-a-las-19-00-50-e8e5bcaa.jpg"
            } else {
                "https://i.ibb.co/B5gKLkqS/nuevo-formato-2-K-202604112205.jpg"
            },
            overview = if (isReport) {
                "Report unavailable channels or provider errors in the official support group."
            } else {
                "Support the provider and help keep its servers online."
            },
            seasons = emptyList(),
        )
    }

    private fun attribute(line: String, name: String): String? =
        Regex("(?:^|\\s)${Regex.escape(name)}=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
            .find(line)
            ?.groupValues
            ?.getOrNull(1)

    private fun extractQuality(name: String): String? =
        Regex("\\((2160p|1440p|1080p|720p|576p|480p|360p|270p|4K|UHD|FHD|HD|SD)\\)", RegexOption.IGNORE_CASE)
            .find(name)
            ?.groupValues
            ?.getOrNull(1)
            ?.uppercase(Locale.ROOT)

    private fun splitInlineHeaders(line: String): Pair<String, Map<String, String>> {
        val url = line.substringBefore('|').trim()
        val rawHeaders = line.substringAfter('|', "")
        if (rawHeaders.isBlank()) return url to emptyMap()
        val headers = rawHeaders.split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=', "").trim().lowercase(Locale.ROOT)
            if (key.isBlank()) return@mapNotNull null
            val value = runCatching {
                URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            }.getOrDefault(pair.substringAfter('=', ""))
            key to value
        }.toMap()
        return url to headers
    }
}
