package com.nexastream.app.providers

import com.nexastream.app.utils.TMDb3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeenRomanceRecommendationTest {

    private val provider = TmdbProvider("en")

    private fun createMovie(
        id: Int = 100,
        title: String = "Teen Love Story",
        overview: String = "A high school teenager falls in love with her childhood best friend.",
        genresIds: List<Int> = listOf(10749), // Romance
        releaseDate: String? = "2023-06-15",
        popularity: Float = 100f,
        voteCount: Int = 500,
        voteAverage: Float = 7.8f
    ) = TMDb3.Movie(
        id = id,
        title = title,
        originalTitle = title,
        overview = overview,
        genresIds = genresIds,
        releaseDate = releaseDate,
        popularity = popularity,
        voteCount = voteCount,
        voteAverage = voteAverage,
        posterPath = "/poster.jpg",
        backdropPath = "/backdrop.jpg",
        adult = false,
        video = false,
        originalLanguage = "en"
    )

    private fun createTvShow(
        id: Int = 200,
        name: String = "High School Romance Series",
        overview: String = "Two high school students navigate a secret relationship and a love triangle.",
        genresIds: List<Int> = listOf(10749),
        firstAirDate: String? = "2022-04-10",
        popularity: Float = 150f,
        voteCount: Int = 800,
        voteAverage: Float = 8.1f
    ) = TMDb3.Tv(
        id = id,
        name = name,
        originalName = name,
        overview = overview,
        genresIds = genresIds,
        firstAirDate = firstAirDate,
        popularity = popularity,
        voteCount = voteCount,
        voteAverage = voteAverage,
        posterPath = "/tv_poster.jpg",
        backdropPath = "/tv_backdrop.jpg",
        originCountry = emptyList(),
        originalLanguage = "en"
    )

    @Test
    fun `animation genre 16 is strictly excluded for movies and tv`() {
        val animatedMovie = createMovie(genresIds = listOf(16, 10749), overview = "High school teen romance in high school")
        val animatedTv = createTvShow(genresIds = listOf(16, 10749), overview = "High school teen romance in high school")

        assertNull("Animated movie must be excluded", provider.evaluateTeenRomanceCandidate(animatedMovie))
        assertNull("Animated TV show must be excluded", provider.evaluateTeenRomanceCandidate(animatedTv))
    }

    @Test
    fun `release dates are correctly parsed for movies and tv`() {
        val movie = createMovie(releaseDate = "2024-01-01", overview = "High school teen romance love story")
        val tv = createTvShow(firstAirDate = "2023-05-10", overview = "High school teen romance love story")

        val movieCand = provider.evaluateTeenRomanceCandidate(movie)
        val tvCand = provider.evaluateTeenRomanceCandidate(tv)

        assertNotNull(movieCand)
        assertNotNull(tvCand)
    }

    @Test
    fun `dual evidence is required - rejects generic non-teen romance and non-romance summer titles`() {
        // Adult corporate romance with no teen/YA evidence
        val adultRomance = createMovie(
            title = "Corporate Affair",
            overview = "Two corporate executives fall in love at work during a business trip.",
            genresIds = listOf(10749)
        )
        assertNull("Adult romance without teen signals must be rejected", provider.evaluateTeenRomanceCandidate(adultRomance))

        // Generic summer kayaking movie with no romance evidence
        val kayakingMovie = createMovie(
            title = "Extreme Kayaking",
            overview = "Two high school athletes go white water kayaking during summer vacation.",
            genresIds = listOf(12) // Adventure, no Romance
        )
        assertNull("Non-romance title must be rejected even if it contains 'high school' or 'summer'", provider.evaluateTeenRomanceCandidate(kayakingMovie))

        // Valid Teen Romance title passing dual evidence
        val validTeenRomance = createMovie(
            title = "Summer Crush",
            overview = "A high school teenager meets her crush at summer camp and they fall in love.",
            genresIds = listOf(10749)
        )
        assertNotNull("Valid teen romance passing dual evidence must be accepted", provider.evaluateTeenRomanceCandidate(validTeenRomance))
    }

    @Test
    fun `classic title exception accepts acclaimed pre-2012 teen romances but rejects generic pre-2012 non-teen movies`() {
        // Acclaimed pre-2012 teen romance classic (10 Things I Hate About You style)
        val classicTeenRomance = createMovie(
            id = 101,
            title = "10 Things I Hate About You",
            overview = "A high school teenager tries to get her sister a boyfriend before prom in an enemies to lovers story.",
            genresIds = listOf(10749),
            releaseDate = "1999-03-31",
            voteCount = 7000,
            voteAverage = 7.6f
        )
        assertNotNull("Acclaimed pre-2012 teen romance must pass classic exception", provider.evaluateTeenRomanceCandidate(classicTeenRomance))

        // High vote pre-2012 non-teen movie (The Godfather style)
        val classicMafiaMovie = createMovie(
            id = 102,
            title = "The Crime Boss",
            overview = "An aging mafia patriarch transfers control of his clandestine empire to his reluctant son.",
            genresIds = listOf(80, 18),
            releaseDate = "1972-03-14",
            voteCount = 18000,
            voteAverage = 8.7f
        )
        assertNull("Generic pre-2012 movie without teen romance evidence must be rejected regardless of rating", provider.evaluateTeenRomanceCandidate(classicMafiaMovie))
    }

    @Test
    fun `trope score deduplicates repeated keywords in same trope category`() {
        val repetitiveOverviewMovie = createMovie(
            overview = "A teenager enters a fake date, fake dating, pretend relationship, pretend dating with her classmate."
        )
        val candidate = provider.evaluateTeenRomanceCandidate(repetitiveOverviewMovie)
        assertNotNull(candidate)
        assertEquals("Fake dating trope category should count once (weight 12)", 12, candidate!!.tropeScore)
    }

    @Test
    fun `vibe priority matrix ranks core YA romance higher than dark gritty thriller`() {
        val coreYARomance = createMovie(
            id = 1,
            title = "Love at First Sight",
            overview = "A sweet high school romance about a teenage girl and her crush at prom."
        )
        val darkGrittyYARomance = createMovie(
            id = 2,
            title = "Neon Shadows",
            overview = "A dark gritty mystery about high school teenagers investigating a murder in neon lit alleys."
        )

        val coreCand = provider.evaluateTeenRomanceCandidate(coreYARomance)
        val darkCand = provider.evaluateTeenRomanceCandidate(darkGrittyYARomance)

        assertNotNull(coreCand)
        assertNotNull(darkCand)

        assertEquals("Core YA Romance matrix priority must be 1 (primary)", 1, coreCand!!.matrixPriority)
        assertEquals("Dark gritty mystery matrix priority must be 4", 4, darkCand!!.matrixPriority)
        assertTrue("Priority 1 must rank before Priority 4", coreCand.matrixPriority < darkCand.matrixPriority)
    }

    @Test
    fun `missing metadata handles null dates gracefully without crash`() {
        val missingDateMovie = createMovie(
            releaseDate = null,
            overview = "A high school teenager falls in love with her childhood best friend."
        )
        val candidate = provider.evaluateTeenRomanceCandidate(missingDateMovie)
        assertNotNull("Missing release date should be handled safely", candidate)
    }

    @Test
    fun `the summer i turned pretty style overview passes dual evidence`() {
        val summerITurnedPretty = createTvShow(
            id = 126138,
            name = "The Summer I Turned Pretty",
            overview = "A drama focused on a girl who catches the eye of two brothers while on summer vacation.",
            genresIds = listOf(10749, 18)
        )
        val candidate = provider.evaluateTeenRomanceCandidate(summerITurnedPretty)
        assertNotNull("The Summer I Turned Pretty style overview must be accepted", candidate)
    }

    @Test
    fun `user benchmark titles evaluate cleanly with high trope and composite scores`() {
        val walterBoys = createTvShow(
            id = 199001,
            name = "My Life with the Walter Boys",
            overview = "A teenage girl's life is turned upside down when she moves in with a big family in rural Colorado.",
            genresIds = listOf(10749, 18)
        )
        val xoKitty = createTvShow(
            id = 154825,
            name = "XO, Kitty",
            overview = "Teen matchmaker Kitty Song Covey reunites with her long-distance boyfriend at a boarding school in Seoul.",
            genresIds = listOf(10749, 35)
        )
        val euphoria = createTvShow(
            id = 85552,
            name = "Euphoria",
            overview = "A look at life for a group of high school students as they navigate love, friendships, and young adult drama.",
            genresIds = listOf(10749, 18)
        )
        val heartbreakHigh = createTvShow(
            id = 110292,
            name = "Heartbreak High",
            overview = "High school teenagers in Australia navigate love, heartbreaks, and relationships.",
            genresIds = listOf(10749, 18)
        )
        val heatedRivalry = createTvShow(
            id = 300001,
            name = "Heated Rivalry",
            overview = "Two pro hockey players share a secret relationship and a heated rivalry on and off the ice.",
            genresIds = listOf(10749)
        )

        val cand1 = provider.evaluateTeenRomanceCandidate(walterBoys)
        val cand2 = provider.evaluateTeenRomanceCandidate(xoKitty)
        val cand3 = provider.evaluateTeenRomanceCandidate(euphoria)
        val cand4 = provider.evaluateTeenRomanceCandidate(heartbreakHigh)
        val cand5 = provider.evaluateTeenRomanceCandidate(heatedRivalry)

        assertNotNull("Walter Boys must evaluate cleanly", cand1)
        assertNotNull("XO Kitty must evaluate cleanly", cand2)
        assertNotNull("Euphoria must evaluate cleanly", cand3)
        assertNotNull("Heartbreak High must evaluate cleanly", cand4)
        assertNotNull("Heated Rivalry must evaluate cleanly", cand5)

        assertTrue("Walter Boys composite score should be high", cand1!!.compositeScore >= 30.0)
        assertTrue("XO Kitty composite score should be high", cand2!!.compositeScore >= 30.0)
        assertTrue("Heated Rivalry trope score should reflect rivalry", cand5!!.tropeScore >= 10)
    }
}
