package com.nexastream.app.models

import com.google.gson.annotations.SerializedName

data class CDNChannel(
    @SerializedName("name") val name: String,
    @SerializedName("code") val code: String,
    @SerializedName("url") val url: String,
    @SerializedName("image") val image: String,
    @SerializedName("status") val status: String,
    @SerializedName("viewers") val viewers: Int
)

data class CDNChannelsResponse(
    @SerializedName("total_channels") val totalChannels: Int,
    @SerializedName("channels") val channels: List<CDNChannel>
)

data class CDNEventChannel(
    @SerializedName("channel_name") val channelName: String,
    @SerializedName("channel_code") val channelCode: String,
    @SerializedName("viewers") val viewers: String,
    @SerializedName("url") val url: String,
    @SerializedName("image") val image: String
)

data class CDNSportEvent(
    @SerializedName("gameID") val gameID: String,
    @SerializedName("homeTeam") val homeTeam: String,
    @SerializedName("awayTeam") val awayTeam: String,
    @SerializedName("homeTeamIMG") val homeTeamIMG: String,
    @SerializedName("awayTeamIMG") val awayTeamIMG: String,
    @SerializedName("time") val time: String,
    @SerializedName("tournament") val tournament: String,
    @SerializedName("country") val country: String,
    @SerializedName("countryIMG") val countryIMG: String,
    @SerializedName("status") val status: String,
    @SerializedName("start") val start: String,
    @SerializedName("end") val end: String,
    @SerializedName("channels") val channels: List<CDNEventChannel>
)

data class CDNSportsData(
    @SerializedName("Soccer") val soccer: List<CDNSportEvent>? = null,
    @SerializedName("NBA") val nba: List<CDNSportEvent>? = null,
    @SerializedName("NHL") val nhl: List<CDNSportEvent>? = null,
    @SerializedName("NFL") val nfl: List<CDNSportEvent>? = null,
    @SerializedName("total_events") val totalEvents: Int = 0
)
