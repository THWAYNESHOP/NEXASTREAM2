package com.nexastream.app.trakt

import retrofit2.http.*
import com.google.gson.annotations.SerializedName

interface TraktService {

    @POST("oauth/device/code")
    suspend fun generateDeviceCode(@Body request: DeviceCodeRequest): DeviceCodeResponse

    @POST("oauth/device/token")
    suspend fun getDeviceToken(@Body request: DeviceTokenRequest): DeviceTokenResponse

    @POST("sync/history")
    suspend fun addToHistory(
        @Header("Authorization") token: String,
        @Header("trakt-api-key") clientId: String,
        @Body request: HistoryRequest
    ): HistoryResponse

    data class DeviceCodeRequest(
        @SerializedName("client_id") val clientId: String
    )

    data class DeviceCodeResponse(
        @SerializedName("device_code") val deviceCode: String,
        @SerializedName("user_code") val userCode: String,
        @SerializedName("verification_url") val verificationUrl: String,
        @SerializedName("expires_in") val expiresIn: Int,
        @SerializedName("interval") val interval: Int
    )

    data class DeviceTokenRequest(
        val code: String,
        @SerializedName("client_id") val clientId: String,
        @SerializedName("client_secret") val clientSecret: String
    )

    data class DeviceTokenResponse(
        @SerializedName("access_token") val accessToken: String,
        @SerializedName("token_type") val tokenType: String,
        @SerializedName("expires_in") val expiresIn: Int,
        @SerializedName("refresh_token") val refreshToken: String,
        val scope: String,
        @SerializedName("created_at") val createdAt: Long
    )

    data class HistoryRequest(
        val movies: List<Movie>? = null,
        val episodes: List<Episode>? = null
    ) {
        data class Movie(val ids: Ids)
        data class Episode(val ids: Ids)
        data class Ids(val tmdb: Int? = null, val imdb: String? = null)
    }

    data class HistoryResponse(
        val added: Added,
        val not_found: NotFound
    ) {
        data class Added(val movies: Int, val episodes: Int)
        data class NotFound(val movies: List<HistoryRequest.Movie>, val episodes: List<HistoryRequest.Episode>)
    }
}
