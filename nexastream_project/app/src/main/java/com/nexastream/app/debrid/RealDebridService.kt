package com.nexastream.app.debrid

import retrofit2.http.*
import com.google.gson.annotations.SerializedName

interface RealDebridService {

    @GET("user")
    suspend fun getUser(@Header("Authorization") token: String): User

    @FormUrlEncoded
    @POST("unrestrict/link")
    suspend fun unrestrict(
        @Header("Authorization") token: String,
        @Field("link") link: String
    ): UnrestrictResponse

    data class User(
        val id: Int,
        val username: String,
        val email: String,
        val points: Int,
        val type: String,
        val premium: Int
    )

    data class UnrestrictResponse(
        val id: String,
        val filename: String,
        val mimeType: String,
        val filesize: Long,
        val link: String,
        val host: String,
        val chunks: Int,
        val crc: Int,
        val download: String,
        val streamable: Int
    )
}
