package com.nexastream.app.utils

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaTypeUtilsTest {

    @Test
    fun `infers adaptive stream types with query parameters`() {
        assertEquals(
            MimeTypes.APPLICATION_M3U8,
            inferVideoMimeType("https://example.com/live/channel.m3u8?token=abc"),
        )
        assertEquals(
            MimeTypes.APPLICATION_MPD,
            inferVideoMimeType("https://example.com/live/manifest.mpd?token=abc"),
        )
    }

    @Test
    fun `infers common direct media types`() {
        assertEquals(MimeTypes.VIDEO_MP4, inferVideoMimeType("https://example.com/video.MP4"))
        assertEquals(MimeTypes.VIDEO_MP2T, inferVideoMimeType("https://example.com/live.ts"))
        assertEquals(MimeTypes.AUDIO_MPEG, inferVideoMimeType("https://example.com/radio.mp3"))
    }

    @Test
    fun `uses HLS for opaque IPTV endpoints`() {
        assertEquals(
            MimeTypes.APPLICATION_M3U8,
            inferVideoMimeType("https://example.com/play?channel=world-news"),
        )
    }
}
