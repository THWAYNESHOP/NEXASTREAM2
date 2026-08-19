package com.nexastream.app.providers

import com.nexastream.app.models.SportMatch
import com.nexastream.app.models.SportStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SportsPlaybackIdTest {

    @Test
    fun `playback id preserves the match and every source`() {
        val match = sportMatch(
            id = "event + 42",
            sources = listOf(
                SportMatch.MatchSource(source = "alpha", id = "alpha|42"),
                SportMatch.MatchSource(source = "admin/live", id = "event,42"),
            ),
        )

        val decoded = SportsPlaybackId.decode(SportsPlaybackId.encode(match))

        assertEquals(match.id, decoded.matchId)
        assertEquals(match.sources, decoded.sources)
    }

    @Test
    fun `legacy source id remains supported`() {
        val decoded = SportsPlaybackId.decode("admin|ppv-event-1")

        assertEquals("ppv-event-1", decoded.matchId)
        assertEquals(
            listOf(SportMatch.MatchSource(source = "admin", id = "ppv-event-1")),
            decoded.sources,
        )
    }

    @Test
    fun `empty first source does not hide streams from another source`() = runBlocking {
        val sources = listOf(
            SportMatch.MatchSource(source = "alpha", id = "alpha-event"),
            SportMatch.MatchSource(source = "admin", id = "admin-event"),
        )

        val servers = loadSportServers(sources) { source, _ ->
            if (source == "alpha") emptyList()
            else listOf(
                SportStream(
                    id = "stream-1",
                    streamNo = 1,
                    language = "English",
                    hd = true,
                    embedUrl = "https://embed.st/embed/admin/admin-event/1",
                    source = source,
                ),
            )
        }

        assertEquals(1, servers.size)
        assertEquals("https://embed.st/embed/admin/admin-event/1", servers.single().id)
    }

    private fun sportMatch(
        id: String,
        sources: List<SportMatch.MatchSource>,
    ) = SportMatch(
        id = id,
        title = "Home vs Away",
        homeTeam = "Home",
        awayTeam = "Away",
        league = "TEST",
        status = "LIVE",
        time = "Live",
        score = "vs",
        sport = "test",
        sources = sources,
    )
}
