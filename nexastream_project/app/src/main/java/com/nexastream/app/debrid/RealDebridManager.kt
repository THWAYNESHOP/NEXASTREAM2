package com.nexastream.app.debrid

import com.nexastream.app.utils.UserPreferences
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RealDebridManager {
    private const val BASE_URL = "https://api.real-debrid.com/rest/1.0/"

    private val service: RealDebridService by lazy {
        val client = OkHttpClient.Builder().build()
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(RealDebridService::class.java)
    }

    suspend fun getUser(): RealDebridService.User? {
        val token = UserPreferences.realDebridToken.takeIf { it.isNotBlank() } ?: return null
        return try {
            service.getUser("Bearer $token")
        } catch (e: Exception) {
            null
        }
    }

    suspend fun unrestrict(link: String): String? {
        val token = UserPreferences.realDebridToken.takeIf { it.isNotBlank() } ?: return null
        return try {
            val response = service.unrestrict("Bearer $token", link)
            response.download
        } catch (e: Exception) {
            null
        }
    }
}
