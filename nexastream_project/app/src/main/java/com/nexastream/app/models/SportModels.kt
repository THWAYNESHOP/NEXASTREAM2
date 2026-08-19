package com.nexastream.app.models

import com.nexastream.app.adapters.AppAdapter
import java.io.Serializable

data class SportMatch(
    val id: String,
    val title: String,
    val homeTeam: String,
    val awayTeam: String,
    val league: String,
    val status: String,
    val time: String,
    val score: String,
    val sport: String,
    val poster: String? = null,
    val date: Long? = null,
    val sources: List<MatchSource> = emptyList()
) : AppAdapter.Item, Serializable {
    override lateinit var itemType: AppAdapter.Type

    data class MatchSource(
        val source: String,
        val id: String
    ) : Serializable
}

data class SportStream(
    val id: String,
    val streamNo: Int,
    val language: String,
    val hd: Boolean,
    val embedUrl: String,
    val source: String,
    val thumbnail: String? = null,
    val healthScore: Int? = null
) : Serializable
