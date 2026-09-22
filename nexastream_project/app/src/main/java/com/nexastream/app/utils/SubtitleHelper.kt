package com.nexastream.app.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.regex.Pattern

object SubtitleHelper {
    data class SubtitleCue(
        val startTimeMs: Long,
        val endTimeMs: Long,
        val text: String
    )

    private var currentCues = listOf<SubtitleCue>()

    suspend fun downloadAndParse(url: String) {
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                val response = NetworkClient.default.newCall(request).execute()
                val content = response.body?.string() ?: return@withContext
                currentCues = if (url.endsWith(".vtt", ignoreCase = true) || content.startsWith("WEBVTT")) {
                    parseVtt(content)
                } else {
                    parseSrt(content)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                currentCues = emptyList()
            }
        }
    }

    private fun parseSrt(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        // Split by double newline to separate blocks
        val blocks = content.split(Regex("(\\r\\n\\r\\n|\\n\\n)"))
        
        val timePattern = Pattern.compile("(\\d{2}:\\d{2}:\\d{2}[,. ]\\d{3}) --> (\\d{2}:\\d{2}:\\d{2}[,. ]\\d{3})")

        for (block in blocks) {
            val lines = block.trim().lines()
            if (lines.size < 2) continue
            
            // The time line is usually the second line, but sometimes the first if there's no index
            val timeLine = if (timePattern.matcher(lines[0]).find()) lines[0] else if (lines.size > 1 && timePattern.matcher(lines[1]).find()) lines[1] else null
            
            if (timeLine != null) {
                val matcher = timePattern.matcher(timeLine)
                if (matcher.find()) {
                    val startTime = parseTimeToMs(matcher.group(1))
                    val endTime = parseTimeToMs(matcher.group(2))
                    val text = lines.subList(lines.indexOf(timeLine) + 1, lines.size).joinToString("\n")
                    if (text.isNotBlank()) {
                        cues.add(SubtitleCue(startTime, endTime, text))
                    }
                }
            }
        }
        return cues
    }
    
    private fun parseVtt(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val timePattern = Pattern.compile("(\\d{2}:)?(\\d{2}:\\d{2}\\.\\d{3}) --> (\\d{2}:)?(\\d{2}:\\d{2}\\.\\d{3})")
        
        val blocks = content.split(Regex("(\\r\\n\\r\\n|\\n\\n)"))
        for (block in blocks) {
            val lines = block.trim().lines()
            if (lines.isEmpty() || lines[0].startsWith("WEBVTT")) continue
            
            val timeLine = lines.find { timePattern.matcher(it).find() }
            if (timeLine != null) {
                val matcher = timePattern.matcher(timeLine)
                if (matcher.find()) {
                    val startTime = parseVttTimeToMs(matcher.group(1), matcher.group(2))
                    val endTime = parseVttTimeToMs(matcher.group(3), matcher.group(4))
                    val text = lines.subList(lines.indexOf(timeLine) + 1, lines.size).joinToString("\n")
                    if (text.isNotBlank()) {
                        cues.add(SubtitleCue(startTime, endTime, text))
                    }
                }
            }
        }
        return cues
    }

    private fun parseTimeToMs(timeStr: String): Long {
        val cleaned = timeStr.replace(',', '.').replace(' ', '.')
        val parts = cleaned.split(':')
        val secParts = parts[2].split('.')
        
        val hours = parts[0].toLong()
        val minutes = parts[1].toLong()
        val seconds = secParts[0].toLong()
        val ms = secParts[1].toLong()
        
        return (hours * 3600 + minutes * 60 + seconds) * 1000 + ms
    }
    
    private fun parseVttTimeToMs(hoursPart: String?, timePart: String): Long {
        val parts = timePart.split(':')
        val secParts = parts[1].split('.')
        
        val hours = hoursPart?.removeSuffix(":")?.toLong() ?: 0L
        val minutes = parts[0].toLong()
        val seconds = secParts[0].toLong()
        val ms = secParts[1].toLong()
        
        return (hours * 3600 + minutes * 60 + seconds) * 1000 + ms
    }

    fun getCueAt(timeMs: Long): String? {
        return currentCues.find { timeMs in it.startTimeMs..it.endTimeMs }?.text
    }

    fun clear() {
        currentCues = emptyList()
    }
}
