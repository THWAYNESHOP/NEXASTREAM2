package com.nexastream.app.models

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class Download(
    @PrimaryKey val id: String, // Can be movie id or episode id
    val title: String,
    val poster: String?,
    @ColumnInfo(name = "filePath") val cacheKey: String,
    val url: String,
    val status: Status = Status.QUEUED,
    val progress: Int = 0,
    @ColumnInfo(defaultValue = "0") val downloadedSize: Long = 0,
    val totalSize: Long = 0,
    @ColumnInfo(defaultValue = "0") val downloadSpeed: Long = 0,
    val etaSeconds: Long? = null,
    val quality: String? = null,
    val headers: Map<String, String>? = null,
    val mimeType: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    enum class Status {
        QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED
    }

    val storageType: StorageType
        get() = when {
            isHls -> StorageType.HLS_CACHE
            isDash -> StorageType.DASH_CACHE
            isDirectFile -> StorageType.DIRECT_FILE_CACHE
            else -> StorageType.MEDIA_CACHE
        }

    val isPlayableOffline: Boolean
        get() = url.isNotBlank() && status == Status.COMPLETED

    private val isHls: Boolean
        get() {
            val lowerMime = mimeType?.lowercase().orEmpty()
            val lowerUrl = url.lowercase()
            return lowerMime == "application/x-mpegurl" ||
                lowerMime == "application/vnd.apple.mpegurl" ||
                lowerMime.contains("mpegurl") ||
                lowerUrl.contains(".m3u8")
        }

    private val isDash: Boolean
        get() {
            val lowerMime = mimeType?.lowercase().orEmpty()
            val lowerUrl = url.lowercase()
            return lowerMime == "application/dash+xml" || lowerUrl.contains(".mpd")
        }

    private val isDirectFile: Boolean
        get() {
            val lowerMime = mimeType?.lowercase().orEmpty()
            val cleanUrl = url.substringBefore('?').lowercase()
            return lowerMime.startsWith("video/") ||
                cleanUrl.endsWith(".mp4") ||
                cleanUrl.endsWith(".mkv") ||
                cleanUrl.endsWith(".webm")
        }

    enum class StorageType {
        DIRECT_FILE_CACHE,
        HLS_CACHE,
        DASH_CACHE,
        MEDIA_CACHE
    }
}
