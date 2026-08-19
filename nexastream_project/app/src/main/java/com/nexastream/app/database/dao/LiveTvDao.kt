package com.nexastream.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.nexastream.app.models.EpgProgram
import com.nexastream.app.models.EpgChannelMapping
import com.nexastream.app.models.LiveChannelPreference
import com.nexastream.app.models.LivePlaybackDiagnostic
import com.nexastream.app.models.LiveRecording
import com.nexastream.app.models.LiveStreamHealth
import com.nexastream.app.models.ProgramReminder
import com.nexastream.app.models.XmlTvChannel
import kotlinx.coroutines.flow.Flow

@Dao
interface LiveTvDao {

    @Query(
        "SELECT * FROM epg_programs WHERE channelId IN (:channelIds) " +
            "AND endMillis > :startMillis AND startMillis < :endMillis " +
            "ORDER BY channelId, startMillis",
    )
    fun observePrograms(
        channelIds: List<String>,
        startMillis: Long,
        endMillis: Long,
    ): Flow<List<EpgProgram>>

    @Query(
        "SELECT * FROM epg_programs WHERE channelId IN (:channelIds) " +
            "AND endMillis > :startMillis AND startMillis < :endMillis " +
            "ORDER BY channelId, startMillis",
    )
    suspend fun getPrograms(
        channelIds: List<String>,
        startMillis: Long,
        endMillis: Long,
    ): List<EpgProgram>

    @Query(
        "SELECT * FROM epg_programs WHERE channelId = :channelId AND endMillis > :nowMillis " +
            "ORDER BY startMillis LIMIT 3",
    )
    suspend fun getUpcomingPrograms(channelId: String, nowMillis: Long): List<EpgProgram>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrograms(programs: List<EpgProgram>)

    @Query("DELETE FROM epg_programs WHERE sourceUrl = :sourceUrl")
    suspend fun deleteProgramsForSource(sourceUrl: String)

    @Query("DELETE FROM epg_programs WHERE sourceUrl = :sourceUrl AND channelId IN (:channelIds)")
    suspend fun deleteProgramsForChannels(sourceUrl: String, channelIds: List<String>)

    @Transaction
    suspend fun replaceProgramsForSource(sourceUrl: String, programs: List<EpgProgram>) {
        deleteProgramsForSource(sourceUrl)
        if (programs.isNotEmpty()) insertPrograms(programs)
    }

    @Transaction
    suspend fun replaceProgramsForChannels(
        sourceUrl: String,
        channelIds: List<String>,
        programs: List<EpgProgram>,
    ) {
        if (channelIds.isNotEmpty()) deleteProgramsForChannels(sourceUrl, channelIds)
        if (programs.isNotEmpty()) insertPrograms(programs)
    }

    @Query("DELETE FROM epg_programs WHERE endMillis < :beforeMillis")
    suspend fun deleteExpiredPrograms(beforeMillis: Long)

    @Query("SELECT MAX(updatedAt) FROM epg_programs WHERE sourceUrl = :sourceUrl")
    suspend fun getSourceUpdatedAt(sourceUrl: String): Long?

    @Query("SELECT * FROM live_stream_health WHERE channelId IN (:channelIds)")
    suspend fun getHealthForChannels(channelIds: List<String>): List<LiveStreamHealth>

    @Query("SELECT * FROM live_stream_health WHERE streamKey = :streamKey LIMIT 1")
    suspend fun getStreamHealth(streamKey: String): LiveStreamHealth?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStreamHealth(health: LiveStreamHealth)

    @Query("SELECT * FROM program_reminders ORDER BY startMillis")
    fun observeReminders(): Flow<List<ProgramReminder>>

    @Query("SELECT * FROM program_reminders WHERE programId = :programId LIMIT 1")
    suspend fun getReminder(programId: String): ProgramReminder?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReminder(reminder: ProgramReminder)

    @Query("DELETE FROM program_reminders WHERE programId = :programId")
    suspend fun deleteReminder(programId: String)

    @Query("UPDATE program_reminders SET firedAt = :firedAt WHERE programId = :programId")
    suspend fun markReminderFired(programId: String, firedAt: Long)

    @Query("SELECT * FROM live_recordings ORDER BY startedAt DESC")
    fun observeRecordings(): Flow<List<LiveRecording>>

    @Query("SELECT * FROM live_recordings WHERE recordingId = :recordingId LIMIT 1")
    suspend fun getRecording(recordingId: String): LiveRecording?

    @Query(
        "SELECT * FROM live_recordings WHERE channelId = :channelId AND status = 'RECORDING' " +
            "ORDER BY startedAt DESC LIMIT 1",
    )
    fun observeActiveRecording(channelId: String): Flow<LiveRecording?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecording(recording: LiveRecording)

    @Query(
        "UPDATE live_recordings SET status = :status, endedAt = :endedAt, " +
            "bytesWritten = :bytesWritten, error = :error WHERE recordingId = :recordingId",
    )
    suspend fun finishRecording(
        recordingId: String,
        status: String,
        endedAt: Long,
        bytesWritten: Long,
        error: String?,
    )

    @Query("UPDATE live_recordings SET bytesWritten = :bytesWritten WHERE recordingId = :recordingId")
    suspend fun updateRecordingBytes(recordingId: String, bytesWritten: Long)

    @Query("SELECT * FROM live_channel_preferences ORDER BY isFavorite DESC, customGroup, channelName")
    fun observeChannelPreferences(): Flow<List<LiveChannelPreference>>

    @Query("SELECT * FROM live_channel_preferences ORDER BY isFavorite DESC, customGroup, channelName")
    suspend fun getAllChannelPreferences(): List<LiveChannelPreference>

    @Query("SELECT * FROM live_channel_preferences WHERE channelId IN (:channelIds)")
    suspend fun getChannelPreferences(channelIds: List<String>): List<LiveChannelPreference>

    @Query("SELECT * FROM live_channel_preferences WHERE channelId = :channelId LIMIT 1")
    suspend fun getChannelPreference(channelId: String): LiveChannelPreference?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChannelPreferences(preferences: List<LiveChannelPreference>)

    @Query(
        "UPDATE live_channel_preferences SET isFavorite = :favorite, updatedAt = :updatedAt " +
            "WHERE channelId = :channelId",
    )
    suspend fun setChannelFavorite(channelId: String, favorite: Boolean, updatedAt: Long)

    @Query(
        "UPDATE live_channel_preferences SET customGroup = :customGroup, updatedAt = :updatedAt " +
            "WHERE channelId = :channelId",
    )
    suspend fun setChannelCustomGroup(channelId: String, customGroup: String?, updatedAt: Long)

    @Query(
        "UPDATE live_channel_preferences SET lastWatchedAt = :watchedAt, updatedAt = :watchedAt " +
            "WHERE channelId = :channelId",
    )
    suspend fun markChannelWatched(channelId: String, watchedAt: Long)

    @Insert
    suspend fun insertDiagnostic(event: LivePlaybackDiagnostic)

    @Query("SELECT * FROM live_playback_diagnostics ORDER BY timestamp DESC LIMIT :limit")
    fun observeDiagnostics(limit: Int = 100): Flow<List<LivePlaybackDiagnostic>>

    @Query("DELETE FROM live_playback_diagnostics")
    suspend fun clearDiagnostics()

    @Query("DELETE FROM live_playback_diagnostics WHERE timestamp < :beforeMillis")
    suspend fun deleteOldDiagnostics(beforeMillis: Long)

    @Query("SELECT * FROM xmltv_channels ORDER BY displayName LIMIT :limit")
    suspend fun getXmlTvChannels(limit: Int = 10_000): List<XmlTvChannel>

    @Query("SELECT COUNT(*) FROM xmltv_channels WHERE sourceUrl = :sourceUrl")
    suspend fun getXmlTvChannelCount(sourceUrl: String): Int

    @Query("DELETE FROM xmltv_channels WHERE sourceUrl = :sourceUrl")
    suspend fun deleteXmlTvChannelsForSource(sourceUrl: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertXmlTvChannels(channels: List<XmlTvChannel>)

    @Transaction
    suspend fun replaceXmlTvChannels(sourceUrl: String, channels: List<XmlTvChannel>) {
        deleteXmlTvChannelsForSource(sourceUrl)
        if (channels.isNotEmpty()) insertXmlTvChannels(channels)
    }

    @Query("SELECT * FROM epg_channel_mappings ORDER BY channelId")
    fun observeEpgMappings(): Flow<List<EpgChannelMapping>>

    @Query("SELECT * FROM epg_channel_mappings")
    suspend fun getEpgMappings(): List<EpgChannelMapping>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEpgMapping(mapping: EpgChannelMapping)

    @Query("DELETE FROM epg_channel_mappings WHERE channelId = :channelId")
    suspend fun deleteEpgMapping(channelId: String)
}
