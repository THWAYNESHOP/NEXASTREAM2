package com.nexastream.app.trakt

import com.nexastream.app.utils.UserPreferences
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object TraktManager {
    private const val BASE_URL = "https://api.trakt.tv/"
    private const val CLIENT_ID = "YOUR_TRAKT_CLIENT_ID" // Placeholder
    private const val CLIENT_SECRET = "YOUR_TRAKT_CLIENT_SECRET" // Placeholder

    private val service: TraktService by lazy {
        val client = OkHttpClient.Builder().build()
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TraktService::class.java)
    }

    suspend fun generateDeviceCode(): TraktService.DeviceCodeResponse {
        return service.generateDeviceCode(TraktService.DeviceCodeRequest(CLIENT_ID))
    }

    suspend fun pollForToken(deviceCode: String): TraktService.DeviceTokenResponse? {
        return try {
            service.getDeviceToken(TraktService.DeviceTokenRequest(deviceCode, CLIENT_ID, CLIENT_SECRET))
        } catch (e: Exception) {
            null
        }
    }

    suspend fun syncMovieToHistory(tmdbId: Int) {
        val token = UserPreferences.traktToken.takeIf { it.isNotBlank() } ?: return
        try {
            service.addToHistory(
                "Bearer $token",
                CLIENT_ID,
                TraktService.HistoryRequest(
                    movies = listOf(TraktService.HistoryRequest.Movie(TraktService.HistoryRequest.Ids(tmdb = tmdbId)))
                )
            )
        } catch (e: Exception) {
            // Log error
        }
    }

    suspend fun syncEpisodeToHistory(tmdbId: Int) {
        val token = UserPreferences.traktToken.takeIf { it.isNotBlank() } ?: return
        try {
            service.addToHistory(
                "Bearer $token",
                CLIENT_ID,
                TraktService.HistoryRequest(
                    episodes = listOf(TraktService.HistoryRequest.Episode(TraktService.HistoryRequest.Ids(tmdb = tmdbId)))
                )
            )
        } catch (e: Exception) {
            // Log error
        }
    }
}
