package com.nexastream.app.providers

import android.util.Base64
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.extractors.Extractor
import com.nexastream.app.models.Category
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Genre
import com.nexastream.app.models.Movie
import com.nexastream.app.models.People
import com.nexastream.app.models.Season
import com.nexastream.app.models.Show
import com.nexastream.app.models.TvShow
import com.nexastream.app.models.Video
import com.nexastream.app.utils.DnsResolver
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object PelisflixHdProvider : Provider {

    override val name = "PelisflixHD"
    override val baseUrl = "https://pelisflixhd.win"
    override val language = "es"
    override val logo = "https://s.pelisflixhd.win/cat/logo-mini.png"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", USER_AGENT)
                .build()
            chain.proceed(request)
        }
        .dns(DnsResolver.doh)
        .build()

    private interface PelisflixHdService {
        @GET
        suspend fun getPage(@Url url: String): Document
    }

    private val service = Retrofit.Builder()
        .baseUrl("$baseUrl/")
        .addConverterFactory(JsoupConverterFactory.create())
        .client(client)
        .build()
        .create(PelisflixHdService::class.java)

    override suspend fun getHome(): List<Category> {
        val document = service.getPage(baseUrl)

        return document.select("section.section-separator.container").mapNotNull { section ->
            val title = section.selectFirst("dt.section-title")?.text()?.trim() ?: return@mapNotNull null
            val shows = parseShowLinks(section.select("a[href*='/pelicula/'], a[href*='/serie/']"))
            if (shows.isEmpty()) null else Category(title, shows)
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            return getGenres()
        }

        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = buildPagedUrl("$baseUrl/busqueda/$encodedQuery", page)
        val document = service.getPage(searchUrl)

        return parseShowLinks(document.select("a[href*='/pelicula/'], a[href*='/serie/']"))
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val document = service.getPage(buildPagedUrl("$baseUrl/peliculas", page))
        return parseShowLinks(document.select("a[href*='/pelicula/']")).filterIsInstance<Movie>()
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val document = service.getPage(buildPagedUrl("$baseUrl/series", page))
        return parseShowLinks(document.select("a[href*='/serie/']")).filterIsInstance<TvShow>()
    }

    override suspend fun getMovie(id: String): Movie {
        val document = service.getPage(normalizeUrl(id))
        val info = document.selectFirst("article.backdrop-info") ?: return Movie(id = id)

        return Movie(
            id = normalizeUrl(id),
            title = info.selectFirst("h1 .itemprop, h1 [itemprop=name], h1")
                ?.text()
                ?.substringAfter("Ver Película")
                ?.trim()
                .orEmpty(),
            overview = info.selectFirst(".description p")?.text()?.trim() ?: "",
            released = info.selectFirst("[itemprop=datePublished]")?.text()?.trim() ?: "",
            runtime = parseRuntimeMinutes(info.selectFirst("[itemprop=duration]")?.text()),
            quality = document.selectFirst(".card-hover-meta-quality")?.text()?.trim() ?: "",
            poster = normalizeUrl(info.selectFirst("figure.poster img")?.attr("src") ?: ""),
            banner = extractBackdrop(document) ?: "",
            genres = info.select(".info-list a[href*='/genero/']").map {
                Genre(id = normalizeUrl(it.attr("href")), name = it.text().trim().trimEnd(','))
            },
            cast = info.select(".info-list a[href*='/buscar/']").map {
                People(id = normalizeUrl(it.attr("href")), name = it.text().trim().trimEnd(','))
            },
            recommendations = extractRecommendations(document)
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val document = service.getPage(normalizeUrl(id))
        val info = document.selectFirst("article.backdrop-info") ?: return TvShow(id = id)
        val seasons = linkedMapOf<String, Season>()

        document.select("a[href*='/temporada/']").forEach { link ->
            val href = normalizeUrl(link.attr("href"))
            val seasonNumber = Regex("""-([0-9]+)/?$""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""Temporada\s+(\d+)""", RegexOption.IGNORE_CASE)
                    .find(link.text())
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                ?: return@forEach

            seasons.putIfAbsent(
                href,
                Season(
                    id = href,
                    number = seasonNumber,
                    title = "Temporada $seasonNumber",
                    poster = normalizeUrl(info.selectFirst("figure.poster img")?.attr("src") ?: "")
                )
            )
        }

        return TvShow(
            id = normalizeUrl(id),
            title = info.selectFirst("h1 .itemprop, h1 [itemprop=name], h1")
                ?.text()
                ?.substringAfter("Ver Serie")
                ?.trim()
                .orEmpty(),
            overview = info.selectFirst(".description p")?.text()?.trim() ?: "",
            released = info.selectFirst("[itemprop=datePublished]")?.text()?.trim() ?: "",
            runtime = parseRuntimeMinutes(info.selectFirst("[itemprop=duration]")?.text()),
            quality = document.selectFirst(".card-hover-meta-quality")?.text()?.trim() ?: "",
            poster = normalizeUrl(info.selectFirst("figure.poster img")?.attr("src") ?: ""),
            banner = extractBackdrop(document) ?: "",
            genres = info.select(".info-list a[href*='/genero/']").map {
                Genre(id = normalizeUrl(it.attr("href")), name = it.text().trim().trimEnd(','))
            },
            cast = info.select(".info-list a[href*='/buscar/']").map {
                People(id = normalizeUrl(it.attr("href")), name = it.text().trim().trimEnd(','))
            },
            seasons = seasons.values.sortedBy { it.number },
            recommendations = extractRecommendations(document)
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val document = service.getPage(normalizeUrl(seasonId))

        return document.select("a[href*='/episodio/']").mapNotNull { link ->
            val spans = link.select("span")
            val code = spans.getOrNull(1)?.text()?.trim().orEmpty()
            val episodeNumber = Regex("""x(\d+)""", RegexOption.IGNORE_CASE).find(code)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return@mapNotNull null

            Episode(
                id = normalizeUrl(link.attr("href")),
                number = episodeNumber,
                title = spans.firstOrNull()?.text()?.trim() ?: "",
                poster = normalizeUrl(link.selectFirst("img")?.attr("src") ?: "")
            )
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val genreUrl = buildPagedUrl(normalizeUrl(id), page)
        val document = service.getPage(genreUrl)
        val name = document.selectFirst("dt.section-title, h1, h2")?.text()?.trim()
            ?: normalizeLabel(id.substringAfterLast('/'))

        return Genre(
            id = normalizeUrl(id),
            name = name,
            shows = parseShowLinks(document.select("a[href*='/pelicula/'], a[href*='/serie/']"))
        )
    }

    override suspend fun getVideo(server: Video.Server): Video {
        if (server.src.isNotEmpty()) {
            return Extractor.extract(server.src, server)
        }
        return Extractor.extract(server.id, server)
    }

    override suspend fun getPeople(id: String, page: Int): People {
        return People(id = id, name = "")
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        return emptyList()
    }

    private fun parseShowLinks(elements: List<Element>): List<Show> {
        return elements.mapNotNull { element ->
            val href = element.attr("href")
            val id = normalizeUrl(href)
            val title = element.selectFirst("h3, h2, .card-hover-title")?.text()?.trim()
                ?: element.selectFirst("img")?.attr("alt")?.trim()
                ?: return@mapNotNull null

            val poster = element.selectFirst("img")?.attr("src")?.let { normalizeUrl(it) }
            val quality = element.selectFirst(".card-hover-meta-quality")?.text()?.trim()

            when {
                href.contains("/pelicula/") -> Movie(
                    id = id,
                    title = title,
                    poster = poster,
                    quality = quality
                )
                href.contains("/serie/") -> TvShow(
                    id = id,
                    title = title,
                    poster = poster,
                    quality = quality
                )
                else -> null
            }
        }
    }

    private fun extractBackdrop(document: Document): String? {
        return document.selectFirst("article.backdrop-info")?.attr("data-bg")
            ?: document.selectFirst(".backdrop-info")?.attr("data-bg")
            ?: document.selectFirst(".backdrop")?.attr("data-bg")
    }

    private fun extractRecommendations(document: Document): List<Show> {
        return parseShowLinks(
            document.select("section:has(h3:contains(Recomendaciones), section:contains(similares)) a[href*='/pelicula/'], section:has(h3:contains(Recomendaciones), section:contains(similares)) a[href*='/serie/']")
        )
    }

    private fun buildPagedUrl(url: String, page: Int): String {
        return if (page > 1) {
            val base = url.removeSuffix("/")
            "$base/page/$page/"
        } else {
            "$url/"
        }
    }

    private fun normalizeUrl(url: String): String {
        return if (url.startsWith("http")) url else "$baseUrl${url.removePrefix("/")}"
    }

    private fun normalizeLabel(label: String): String {
        return label.replace("-", " ").replace("_", " ")
            .split(" ")
            .joinToString(" ") { it.capitalize() }
    }

    private fun parseRuntimeMinutes(duration: String?): Int? {
        if (duration == null) return null
        val minutes = Regex("""(\d+)\s*min""").find(duration)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return minutes
    }

    private fun getGenres(): List<AppAdapter.Item> {
        return emptyList()
    }
}