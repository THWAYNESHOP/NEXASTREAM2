package com.nexastream.app.providers

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
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

object FilmPalastProvider : Provider {

    override val name = "Filmpalast"
    override val baseUrl = "https://filmpalast.to/"
    override val language = "de"
    override val logo = "$baseUrl/themes/downloadarchive/images/logo.png"

    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .dns(DnsResolver.doh)
        .build()

    private val service = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(JsoupConverterFactory.create())
        .client(client)
        .build()
        .create(FilmPalastService::class.java)

    private interface FilmPalastService {
        @GET
        suspend fun getPage(@Url url: String): Document
    }

    override suspend fun getHome(): List<Category> {
        val document = service.getPage(baseUrl)
        val categories = mutableListOf<Category>()

        document.select("div#content article").forEach { article ->
            val title = article.selectFirst("h2 a")?.text() ?: return@forEach
            val shows = parseArticle(article)
            if (shows.isNotEmpty()) {
                categories.add(Category(title, shows))
            }
        }

        return categories
    }

    private fun parseArticle(article: org.jsoup.nodes.Element): List<Show> {
        val href = article.selectFirst("h2 a")?.attr("href") ?: ""
        val id = href.substringAfterLast("/")
        val title = article.selectFirst("h2 a")?.text() ?: ""
        val posterSrc = article.selectFirst("a img")?.attr("src") ?: ""
        val fullPosterUrl = if (posterSrc.startsWith("/")) "$baseUrl$posterSrc" else posterSrc

        return listOf(Movie(id = id, title = title, poster = fullPosterUrl))
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            return emptyList()
        }

        val url = "$baseUrl/?s=$query"
        val document = service.getPage(url)
        return document.select("div#content article").mapNotNull { article ->
            val href = article.selectFirst("h2 a")?.attr("href") ?: return@mapNotNull null
            val id = href.substringAfterLast("/")
            val title = article.selectFirst("h2 a")?.text() ?: ""
            val posterSrc = article.selectFirst("a img")?.attr("src") ?: ""
            val fullPosterUrl = if (posterSrc.startsWith("/")) "$baseUrl$posterSrc" else posterSrc

            Movie(id = id, title = title, poster = fullPosterUrl)
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val url = if (page == 1) "$baseUrl/filme/" else "$baseUrl/filme/page/$page"
        val document = service.getPage(url)
        return document.select("div#content article").mapNotNull { article ->
            val href = article.selectFirst("h2 a")?.attr("href") ?: return@mapNotNull null
            val id = href.substringAfterLast("/")
            val title = article.selectFirst("h2 a")?.text() ?: ""
            val posterSrc = article.selectFirst("a img")?.attr("src") ?: ""
            val fullPosterUrl = if (posterSrc.startsWith("/")) "$baseUrl$posterSrc" else posterSrc

            Movie(id = id, title = title, poster = fullPosterUrl)
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return emptyList()
    }

    override suspend fun getMovie(id: String): Movie {
        val url = if (id.startsWith("http")) id else "$baseUrl/$id"
        val document = service.getPage(url)
        val info = document.selectFirst("div.moviedescription") ?: return Movie(id = id)

        return Movie(
            id = id,
            title = document.selectFirst("h1")?.text()?.trim() ?: "",
            overview = info.text()?.trim() ?: "",
            poster = document.selectFirst("img")?.attr("src")?.let { if (it.startsWith("http")) it else "$baseUrl$it" },
            banner = "",
            genres = emptyList(),
            cast = emptyList()
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        return TvShow(id = id)
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        return emptyList()
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        return Genre(id = id, name = "", shows = emptyList())
    }

    override suspend fun getPeople(id: String, page: Int): People {
        return People(id = id, name = "")
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        return emptyList()
    }

    override suspend fun getVideo(server: Video.Server): Video {
        if (server.src.isNotEmpty()) {
            return Extractor.extract(server.src, server)
        }
        return Extractor.extract(server.id, server)
    }
}