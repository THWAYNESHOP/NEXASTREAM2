package com.nexastream.app.providers

import com.nexastream.app.models.SportMatch
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal data class SportsPlaybackRequest(
    val matchId: String,
    val sources: List<SportMatch.MatchSource>,
)

/**
 * Keeps the match id and every source-specific id together while the match is passed through
 * Navigation Safe Args. Components are URL encoded so upstream ids cannot collide with the
 * separators used by this small wire format.
 */
internal object SportsPlaybackId {
    private const val PREFIX = "sports:v1:"

    fun encode(match: SportMatch): String {
        val encodedSources = match.sources
            .filter { it.source.isNotBlank() && it.id.isNotBlank() }
            .distinctBy { it.source to it.id }
            .joinToString(",") { source ->
                "${encodeComponent(source.source)}|${encodeComponent(source.id)}"
            }

        return "$PREFIX${encodeComponent(match.id)};$encodedSources"
    }

    fun decode(value: String): SportsPlaybackRequest {
        if (isEncoded(value)) {
            val payload = value.removePrefix(PREFIX)
            val parts = payload.split(';', limit = 2)
            val sources = parts.getOrNull(1)
                .orEmpty()
                .split(',')
                .mapNotNull(::decodeSource)

            return SportsPlaybackRequest(
                matchId = decodeComponent(parts.firstOrNull().orEmpty()),
                sources = sources,
            )
        }

        // Compatibility with the original Home integration, which passed only "source|id".
        decodeSource(value)?.let { source ->
            return SportsPlaybackRequest(matchId = source.id, sources = listOf(source))
        }

        return SportsPlaybackRequest(matchId = value, sources = emptyList())
    }

    fun isEncoded(value: String): Boolean = value.startsWith(PREFIX)

    fun isLegacy(value: String): Boolean = !isEncoded(value) && decodeSource(value) != null

    private fun decodeSource(value: String): SportMatch.MatchSource? {
        val separator = value.indexOf('|')
        if (separator <= 0 || separator >= value.lastIndex) return null

        val source = decodeComponent(value.substring(0, separator))
        val id = decodeComponent(value.substring(separator + 1))
        if (source.isBlank() || id.isBlank()) return null
        return SportMatch.MatchSource(source = source, id = id)
    }

    private fun encodeComponent(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun decodeComponent(value: String): String = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrDefault(value)
}
