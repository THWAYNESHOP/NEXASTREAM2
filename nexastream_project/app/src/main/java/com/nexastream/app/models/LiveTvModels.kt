package com.nexastream.app.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
data class LiveStreamDescriptor(
    val url: String,
    val quality: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
    val label: String? = null,
)

@Serializable
data class LiveChannelDescriptor(
    val tvgId: String,
    val name: String,
    val logo: String? = null,
    val group: String? = null,
    val guideUrls: List<String> = emptyList(),
    val streams: List<LiveStreamDescriptor>,
)

@Entity(
    tableName = "epg_programs",
    indices = [
        Index(value = ["channelId", "startMillis"]),
        Index(value = ["endMillis"]),
        Index(value = ["sourceUrl"]),
    ],
)
data class EpgProgram(
    @PrimaryKey val programId: String,
    val channelId: String,
    val title: String,
    val description: String? = null,
    val category: String? = null,
    val icon: String? = null,
    val startMillis: Long,
    val endMillis: Long,
    val sourceUrl: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "live_stream_health",
    indices = [Index(value = ["channelId"])],
)
data class LiveStreamHealth(
    @PrimaryKey val streamKey: String,
    val channelId: String,
    val streamUrl: String,
    val lastSuccessMillis: Long? = null,
    val lastFailureMillis: Long? = null,
    val consecutiveFailures: Int = 0,
    val latencyMs: Long? = null,
    val lastError: String? = null,
)

@Entity(
    tableName = "program_reminders",
    indices = [Index(value = ["startMillis"])],
)
data class ProgramReminder(
    @PrimaryKey val programId: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val channelPayload: String,
    val createdAt: Long,
    val firedAt: Long? = null,
)

@Entity(
    tableName = "live_recordings",
    indices = [
        Index(value = ["channelId", "status"]),
        Index(value = ["startedAt"]),
    ],
)
data class LiveRecording(
    @PrimaryKey val recordingId: String,
    val channelId: String,
    val title: String,
    val filePath: String,
    val sourceUrl: String,
    val status: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val bytesWritten: Long = 0,
    val error: String? = null,
    val mimeType: String = "video/mp2t",
) {
    companion object {
        const val STATUS_RECORDING = "RECORDING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
    }
}

@Entity(
    tableName = "live_channel_preferences",
    indices = [
        Index(value = ["isFavorite"]),
        Index(value = ["customGroup"]),
        Index(value = ["lastWatchedAt"]),
    ],
)
data class LiveChannelPreference(
    @PrimaryKey val channelId: String,
    val channelPayload: String,
    val channelName: String,
    val isFavorite: Boolean = false,
    val customGroup: String? = null,
    val lastWatchedAt: Long? = null,
    val updatedAt: Long,
)

@Entity(
    tableName = "live_playback_diagnostics",
    indices = [
        Index(value = ["channelId", "timestamp"]),
        Index(value = ["timestamp"]),
    ],
)
data class LivePlaybackDiagnostic(
    @PrimaryKey(autoGenerate = true) val eventId: Long = 0,
    val channelId: String,
    val channelName: String,
    val streamKey: String,
    val host: String,
    val quality: String? = null,
    val event: String,
    val message: String? = null,
    val latencyMs: Long? = null,
    val timestamp: Long,
) {
    companion object {
        const val EVENT_READY = "READY"
        const val EVENT_FAILED = "FAILED"
    }
}

@Entity(
    tableName = "xmltv_channels",
    primaryKeys = ["sourceUrl", "xmlTvChannelId"],
    indices = [
        Index(value = ["displayName"]),
        Index(value = ["xmlTvChannelId"]),
    ],
)
data class XmlTvChannel(
    val sourceUrl: String,
    val xmlTvChannelId: String,
    val displayName: String,
    val icon: String? = null,
    val updatedAt: Long,
)

@Entity(
    tableName = "epg_channel_mappings",
    indices = [Index(value = ["sourceUrl", "xmlTvChannelId"])],
)
data class EpgChannelMapping(
    @PrimaryKey val channelId: String,
    val sourceUrl: String,
    val xmlTvChannelId: String,
    val updatedAt: Long,
)

data class LiveNowNext(
    val now: EpgProgram? = null,
    val next: EpgProgram? = null,
)

data class LiveChannelMetadata(
    val channelId: String,
    val quality: String? = null,
    val alternativeCount: Int = 1,
    val health: LiveHealthState = LiveHealthState.UNKNOWN,
    val nowNext: LiveNowNext = LiveNowNext(),
    val isFavorite: Boolean = false,
    val customGroup: String? = null,
)

enum class LiveHealthState {
    HEALTHY,
    DEGRADED,
    OFFLINE,
    UNKNOWN,
}
