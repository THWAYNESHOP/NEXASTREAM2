package com.nexastream.app.live

import android.util.Base64
import com.nexastream.app.models.LiveChannelDescriptor
import com.nexastream.app.models.LiveStreamDescriptor
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

object LiveTvCodec {
    private const val CHANNEL_PREFIX = "iptv2:"
    private const val STREAM_PREFIX = "iptvs2:"
    const val RECORDING_PREFIX = "live-recording:"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    data class ServerPayload(
        val channelId: String,
        val channelName: String,
        val stream: LiveStreamDescriptor,
    )

    fun encodeChannel(channel: LiveChannelDescriptor): String =
        CHANNEL_PREFIX + encode(json.encodeToString(channel))

    fun decodeChannel(id: String): LiveChannelDescriptor? {
        if (!id.startsWith(CHANNEL_PREFIX)) return null
        return runCatching {
            json.decodeFromString<LiveChannelDescriptor>(decode(id.removePrefix(CHANNEL_PREFIX)))
        }.getOrNull()
    }

    fun encodeServer(
        channelId: String,
        channelName: String,
        stream: LiveStreamDescriptor,
    ): String = STREAM_PREFIX + encode(
        json.encodeToString(ServerPayload(channelId, channelName, stream)),
    )

    fun decodeServer(id: String): ServerPayload? {
        if (!id.startsWith(STREAM_PREFIX)) return null
        return runCatching {
            json.decodeFromString<ServerPayload>(decode(id.removePrefix(STREAM_PREFIX)))
        }.getOrNull()
    }

    fun streamKey(stream: LiveStreamDescriptor): String = sha256(
        listOf(stream.url, stream.userAgent.orEmpty(), stream.referrer.orEmpty()).joinToString("\u001f"),
    )

    fun stableId(value: String): String = sha256(value)

    private fun encode(value: String): String = Base64.encodeToString(
        value.toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

    private fun decode(value: String): String = String(
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
        Charsets.UTF_8,
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
