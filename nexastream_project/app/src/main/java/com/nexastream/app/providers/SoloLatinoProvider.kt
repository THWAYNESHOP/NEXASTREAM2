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
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object SoloLatinoProvider : Provider {

    override val name = "SoloLatino"
    override val baseUrl = "https://sololatino.net"
    override val language = "es"
    override val logo = "$baseUrl/images/logo.png"

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
                .build()
            chain.proceed(request)
        }
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .dns(DnsResolver.doh)
        .build()

    private val service = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(JsoupConverterFactory.create())
        .client(client)
        .build()
        .create(SoloLatinoService::class.java)

    private interface SoloLatinoService {
        @GET
        suspend fun getPage(@Url url: String): Document
    }

    override suspend fun getHome(): List<Category> {
        val categories = mutableListOf<Category>()
        val mainDoc = service.getPage(baseUrl)
        
        // Featured/Banner
        val bannerShows = parseBannerShows(mainDoc).take(12)
        if (bannerShows.isNotEmpty()) {
            categories.add(Category(Category.FEATURED, bannerShows))
        }
        
        // Sections from the home page
        val sections = mainDoc.select("section")
        for (section in sections) {
            val title = section.selectFirst(".section-title")?.text() ?: continue
            val shows = parseMixed(section)
            if (shows.isNotEmpty()) {
                categories.add(Category(title, shows.take(12)))
            }
        }

        return categories
    }

    private fun parseMixed(element: Element): List<Show> {
        val cards = element.select("div.card")
        return cards.mapNotNull { card ->
            val linkElement = card.selectFirst("a") ?: card.parents().firstOrNull { it.tagName() == "a" }
            val href = linkElement?.attr("href") ?: return@mapNotNull null
            val absoluteUrl = if (href.startsWith("http")) href else "$baseUrl$href"
            
            val imgElement = card.selectFirst("img.card__poster")
            val poster = imgElement?.attr("src") ?: ""
            
            val titleElement = card.selectFirst(".card__title")
            val title = titleElement?.text() ?: ""
            
            val year = card.selectFirst(".card__year")?.text()
            val isMovie = card.selectFirst(".badge-movie") != null || absoluteUrl.contains("/pelicula/")

            if (isMovie) {
                Movie(
                    id = absoluteUrl,
                    title = title,
                    released = year,
                    poster = poster
                )
            } else {
                TvShow(
                    id = absoluteUrl,
                    title = title,
                    released = year,
                    poster = poster
                )
            }
        }
    }

    private fun parseBannerShows(document: Document): List<Show> {
        return document.select(".hero__slide").mapNotNull { slide ->
            val linkElement = slide.selectFirst("a.btn-accent") ?: return@mapNotNull null
            val href = linkElement.attr("href")
            val absoluteUrl = if (href.startsWith("http")) href else "$baseUrl$href"
            
            val posterElement = slide.selectFirst(".hero__bg")
            val style = posterElement?.attr("style") ?: ""
            val bannerUrl = if (style.contains("url('")) {
                style.substringAfter("url('").substringBefore("')")
            } else ""
            
            val title = slide.selectFirst(".hero__logo-img")?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: slide.selectFirst(".hero__content p.font-display")?.text()?.trim() 
                ?: ""
            
            val year = slide.selectFirst("div.flex.items-center span")?.text()?.takeIf { it.matches(Regex("""\d{4}""")) }
            
            val overview = slide.selectFirst(".text-sm.leading-relaxed.line-clamp-4")?.text()?.trim()

            if (absoluteUrl.contains("/pelicula/")) {
                Movie(
                    id = absoluteUrl,
                    title = title,
                    banner = bannerUrl,
                    overview = overview,
                    released = year
                )
            } else {
                TvShow(
                    id = absoluteUrl,
                    title = title,
                    banner = bannerUrl,
                    overview = overview,
                    released = year
                )
            }
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            return emptyList()
        }

        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl/search?q=$encodedQuery"
        val document = service.getPage(url)
        return parseMixed(document)
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val url = if (page == 1) "$baseUrl/peliculas" else "$baseUrl/peliculas?page=$page"
        val document = service.getPage(url)
        return parseMixed(document).filterIsInstance<Movie>()
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val url = if (page == 1) "$baseUrl/series" else "$baseUrl/series?page=$page"
        val document = service.getPage(url)
        return parseMixed(document).filterIsInstance<TvShow>()
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val url = if (page == 1) "$baseUrl/genero/$id" else "$baseUrl/genero/$id?page=$page"
        val document = service.getPage(url)
        val shows = parseMixed(document).filterIsInstance<Show>()
        val genreName = id.replace("-", " ").replaceFirstChar { it.uppercase() }
        return Genre(id = id, name = genreName, shows = shows)
    }

    override suspend fun getMovie(id: String): Movie {
        val url = if (id.startsWith("http")) id else "$baseUrl/pelicula/$id"
        val document = service.getPage(url)
        val info = document.selectFirst("article") ?: return Movie(id = id)

        return Movie(
            id = id,
            title = info.selectFirst("h1")?.text()?.trim() ?: "",
            overview = info.selectFirst(".description")?.text()?.trim(),
            poster = info.selectFirst("img.poster")?.attr("src"),
            banner = info.selectFirst(".hero__bg")?.attr("style")?.let { 
                it.substringAfter("url('").substringBefore("')") 
            },
            genres = info.select("a[href*='/genero/']").map {
                Genre(id = it.attr("href"), name = it.text().trim())
            },
            cast = info.select("a[href*='/buscar/']").map {
                People(id = it.attr("href"), name = it.text().trim())
            }
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val url = if (id.startsWith("http")) id else "$baseUrl/serie/$id"
        val document = service.getPage(url)
        val info = document.selectFirst("article") ?: return TvShow(id = id)
        val seasons = linkedMapOf<String, Season>()

        document.select("div.seasons a").forEach { link ->
            val href = link.attr("href")
            val seasonNumber = Regex("""season-(\d+)""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return@forEach

            seasons.putIfAbsent(
                href,
                Season(
                    id = href,
                    number = seasonNumber,
                    title = "Temporada $seasonNumber",
                    poster = info.selectFirst("img.poster")?.attr("src")
                )
            )
        }

        return TvShow(
            id = id,
            title = info.selectFirst("h1")?.text()?.trim() ?: "",
            overview = info.selectFirst(".description")?.text()?.trim(),
            poster = info.selectFirst("img.poster")?.attr("src"),
            banner = info.selectFirst(".hero__bg")?.attr("style")?.let { 
                it.substringAfter("url('").substringBefore("')") 
            },
            genres = info.select("a[href*='/genero/']").map {
                Genre(id = it.attr("href"), name = it.text().trim())
            },
            cast = info.select("a[href*='/buscar/']").map {
                People(id = it.attr("href"), name = it.text().trim())
            },
            seasons = seasons.values.sortedBy { it.number }
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val url = if (seasonId.startsWith("http")) seasonId else "$baseUrl/$seasonId"
        val document = service.getPage(url)

        return document.select("div.episodes a").mapNotNull { link ->
            val episodeNumber = Regex("""episodio-(\d+)""").find(link.attr("href"))?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return@mapNotNull null

            Episode(
                id = link.attr("href"),
                number = episodeNumber,
                title = link.selectFirst("span")?.text()?.trim(),
                poster = link.selectFirst("img")?.attr("src")
            )
        }
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
}