package com.nexastream.app.live

import com.nexastream.app.models.EpgProgram
import com.nexastream.app.models.XmlTvChannel
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object XmlTvParser {
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    fun parse(
        input: InputStream,
        wantedChannelIds: Set<String>,
        sourceUrl: String,
        rangeStartMillis: Long,
        rangeEndMillis: Long,
        updatedAt: Long = System.currentTimeMillis(),
    ): XmlTvDocument {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(input, null)
        }
        val programs = ArrayList<EpgProgram>()
        val channels = ArrayList<XmlTvChannel>()
        var insideChannel = false
        var catalogId: String? = null
        var catalogName: String? = null
        var catalogIcon: String? = null
        var event = parser.eventType
        var channelId: String? = null
        var startMillis: Long? = null
        var endMillis: Long? = null
        var title: String? = null
        var description: String? = null
        var category: String? = null
        var icon: String? = null
        var wanted = false

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> {
                        insideChannel = true
                        catalogId = parser.getAttributeValue(null, "id")?.trim()
                        catalogName = null
                        catalogIcon = null
                    }

                    "display-name" -> if (insideChannel && catalogName == null) {
                        catalogName = parser.nextText().trim()
                    }

                    "programme" -> {
                        channelId = parser.getAttributeValue(null, "channel")?.trim()
                        wanted = channelId in wantedChannelIds
                        startMillis = if (wanted) parseXmlTvTime(parser.getAttributeValue(null, "start")) else null
                        endMillis = if (wanted) parseXmlTvTime(parser.getAttributeValue(null, "stop")) else null
                        title = null
                        description = null
                        category = null
                        icon = null
                    }

                    "title" -> if (wanted && title == null) title = parser.nextText().trim()
                    "desc" -> if (wanted && description == null) description = parser.nextText().trim()
                    "category" -> if (wanted && category == null) category = parser.nextText().trim()
                    "icon" -> if (insideChannel) {
                        if (catalogIcon == null) catalogIcon = parser.getAttributeValue(null, "src")
                    } else if (wanted && icon == null) {
                        icon = parser.getAttributeValue(null, "src")
                    }
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "channel" -> {
                        val id = catalogId
                        if (!id.isNullOrBlank()) {
                            channels += XmlTvChannel(
                                sourceUrl = sourceUrl,
                                xmlTvChannelId = id,
                                displayName = catalogName?.takeIf { it.isNotBlank() } ?: id,
                                icon = catalogIcon?.takeIf { it.isNotBlank() },
                                updatedAt = updatedAt,
                            )
                        }
                        insideChannel = false
                    }

                    "programme" -> if (wanted) {
                        val id = channelId
                        val start = startMillis
                        val end = endMillis
                        if (
                            id != null && start != null && end != null && end > start &&
                            end > rangeStartMillis && start < rangeEndMillis
                        ) {
                            val safeTitle = title?.takeIf { it.isNotBlank() } ?: "Programme"
                            programs += EpgProgram(
                                programId = LiveTvCodec.stableId("$sourceUrl\u001f$id\u001f$start\u001f$safeTitle"),
                                channelId = id,
                                title = safeTitle,
                                description = description?.takeIf { it.isNotBlank() },
                                category = category?.takeIf { it.isNotBlank() },
                                icon = icon?.takeIf { it.isNotBlank() },
                                startMillis = start,
                                endMillis = end,
                                sourceUrl = sourceUrl,
                                updatedAt = updatedAt,
                            )
                        }
                        wanted = false
                    }
                }
            }
            event = parser.next()
        }
        return XmlTvDocument(programs = programs, channels = channels)
    }

    fun parseXmlTvTime(value: String?): Long? {
        val raw = value?.trim().orEmpty()
        if (raw.length < 14) return null
        return runCatching {
            val local = LocalDateTime.parse(raw.take(14), dateFormatter)
            val suffix = raw.drop(14).trim()
            val offset = Regex("([+-])(\\d{2}):?(\\d{2})").find(suffix)?.let { match ->
                val sign = if (match.groupValues[1] == "-") -1 else 1
                ZoneOffset.ofHoursMinutes(
                    sign * match.groupValues[2].toInt(),
                    sign * match.groupValues[3].toInt(),
                )
            }
            if (offset != null) {
                local.toInstant(offset).toEpochMilli()
            } else {
                local.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
        }.getOrNull()
    }
}

data class XmlTvDocument(
    val programs: List<EpgProgram>,
    val channels: List<XmlTvChannel>,
)
