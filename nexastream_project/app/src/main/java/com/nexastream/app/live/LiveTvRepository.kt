package com.nexastream.app.live

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.nexastream.app.NexastreamApp
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.models.EpgProgram
import com.nexastream.app.models.EpgChannelMapping
import com.nexastream.app.models.LiveChannelDescriptor
import com.nexastream.app.models.LiveChannelMetadata
import com.nexastream.app.models.LiveChannelPreference
import com.nexastream.app.models.LiveHealthState
import com.nexastream.app.models.LiveNowNext
import com.nexastream.app.models.LivePlaybackDiagnostic
import com.nexastream.app.models.LiveRecording
import com.nexastream.app.models.LiveStreamDescriptor
import com.nexastream.app.models.LiveStreamHealth
import com.nexastream.app.models.ProgramReminder
import com.nexastream.app.models.XmlTvChannel
import com.nexastream.app.utils.NetworkClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.BufferedInputStream
import java.io.InputStream
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

object LiveTvRepository {
    const val PROVIDER_NAME = "IPTV-All World"
    private const val PREFS = "live_tv_epg"
    private const val PREF_CHANNELS = "channels"
    private const val PREF_GUIDES = "guides"
    private const val EPG_CACHE_MILLIS = 4 * 60 * 60 * 1000L
    private const val EPG_PAST_MILLIS = 6 * 60 * 60 * 1000L
    private const val EPG_FUTURE_MILLIS = 7 * 24 * 60 * 60 * 1000L
    private const val MAX_EPG_CHANNELS = 1_500

    private val appContext: Context
        get() = NexastreamApp.instance.applicationContext

    private val database: AppDatabase by lazy {
        AppDatabase.getInstanceForProvider(PROVIDER_NAME, appContext)
    }
    private val dao get() = database.liveTvDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()

    fun configureEpg(channelIds: Collection<String>, guideUrls: Collection<String>) {
        val cleanChannels = channelIds.asSequence().filter { it.isNotBlank() }.take(MAX_EPG_CHANNELS).toSet()
        val cleanGuides = guideUrls.filter { it.startsWith("http", ignoreCase = true) }.toSet()
        if (cleanChannels.isEmpty() || cleanGuides.isEmpty()) return

        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mergedChannels = (prefs.getStringSet(PREF_CHANNELS, emptySet()).orEmpty() + cleanChannels)
            .take(MAX_EPG_CHANNELS)
            .toSet()
        prefs.edit()
            .putStringSet(PREF_CHANNELS, mergedChannels)
            .putStringSet(PREF_GUIDES, cleanGuides)
            .apply()

        scope.launch { refreshEpg(mergedChannels, cleanGuides, force = false) }
        scheduleWorkers(appContext)
    }

    suspend fun refreshConfiguredEpg(force: Boolean): Boolean {
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val channels = prefs.getStringSet(PREF_CHANNELS, emptySet()).orEmpty()
        val guides = prefs.getStringSet(PREF_GUIDES, emptySet()).orEmpty()
        if (channels.isEmpty() || guides.isEmpty()) return true
        return refreshEpg(channels, guides, force)
    }

    suspend fun refreshEpg(
        channelIds: Collection<String>,
        guideUrls: Collection<String>,
        force: Boolean,
    ): Boolean = refreshMutex.withLock {
        val ids = channelIds.filter { it.isNotBlank() }.take(MAX_EPG_CHANNELS).toSet()
        if (ids.isEmpty()) return@withLock true
        val now = System.currentTimeMillis()
        val mappings = dao.getEpgMappings()
        var attempted = false
        var succeeded = false

        guideUrls.distinct().forEach { sourceUrl ->
            val lastUpdated = dao.getSourceUpdatedAt(sourceUrl) ?: 0L
            val hasChannelCatalog = dao.getXmlTvChannelCount(sourceUrl) > 0
            if (!force && hasChannelCatalog && now - lastUpdated < EPG_CACHE_MILLIS) {
                succeeded = true
                return@forEach
            }
            attempted = true
            runCatching {
                val sourceMappings = mappings.filter { it.sourceUrl == sourceUrl }
                val wantedXmlIds = ids + sourceMappings.map { it.xmlTvChannelId }
                val request = Request.Builder()
                    .url(sourceUrl)
                    .header("Accept", "application/xml,text/xml,application/gzip,*/*")
                    .build()
                NetworkClient.default.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("XMLTV HTTP ${response.code}")
                    val body = response.body ?: error("Empty XMLTV response")
                    openMaybeGzip(body.byteStream()).use { input ->
                        val document = XmlTvParser.parse(
                            input = input,
                            wantedChannelIds = wantedXmlIds,
                            sourceUrl = sourceUrl,
                            rangeStartMillis = now - EPG_PAST_MILLIS,
                            rangeEndMillis = now + EPG_FUTURE_MILLIS,
                            updatedAt = now,
                        )
                        val mappingsByXmlId = sourceMappings.groupBy { it.xmlTvChannelId }
                        val programs = document.programs.flatMap { programme ->
                            val targetIds = buildSet {
                                if (programme.channelId in ids) add(programme.channelId)
                                mappingsByXmlId[programme.channelId].orEmpty().forEach { add(it.channelId) }
                            }
                            targetIds.map { targetId ->
                                programme.copy(
                                    programId = LiveTvCodec.stableId(
                                        "$sourceUrl\u001f$targetId\u001f${programme.startMillis}\u001f${programme.title}",
                                    ),
                                    channelId = targetId,
                                )
                            }
                        }
                        dao.replaceXmlTvChannels(sourceUrl, document.channels)
                        dao.replaceProgramsForChannels(sourceUrl, ids.toList(), programs)
                    }
                }
            }.onSuccess {
                succeeded = true
            }
        }
        dao.deleteExpiredPrograms(now - EPG_PAST_MILLIS)
        !attempted || succeeded
    }

    suspend fun metadataForChannels(
        channels: List<LiveChannelDescriptor>,
        nowMillis: Long = System.currentTimeMillis(),
    ): Map<String, LiveChannelMetadata> {
        if (channels.isEmpty()) return emptyMap()
        val ids = channels.map { it.tvgId }.distinct()
        val programs = dao.getPrograms(ids, nowMillis - 60 * 60 * 1000L, nowMillis + 12 * 60 * 60 * 1000L)
            .groupBy { it.channelId }
        val health = dao.getHealthForChannels(ids).groupBy { it.channelId }
        val preferences = syncChannelCatalog(channels).associateBy { it.channelId }

        return channels.associate { channel ->
            val channelPrograms = programs[channel.tvgId].orEmpty()
            val current = channelPrograms.firstOrNull { it.startMillis <= nowMillis && it.endMillis > nowMillis }
            val next = channelPrograms.firstOrNull {
                it.startMillis >= (current?.endMillis ?: nowMillis) && it.programId != current?.programId
            }
            val channelHealth = health[channel.tvgId].orEmpty()
            val preference = preferences[channel.tvgId]
            channel.tvgId to LiveChannelMetadata(
                channelId = channel.tvgId,
                quality = channel.streams.firstNotNullOfOrNull { it.quality },
                alternativeCount = channel.streams.size,
                health = calculateHealth(channel.streams, channelHealth, nowMillis),
                nowNext = LiveNowNext(current, next),
                isFavorite = preference?.isFavorite == true,
                customGroup = preference?.customGroup,
            )
        }
    }

    private suspend fun syncChannelCatalog(
        channels: List<LiveChannelDescriptor>,
    ): List<LiveChannelPreference> {
        if (channels.isEmpty()) return emptyList()
        val existing = dao.getChannelPreferences(channels.map { it.tvgId }).associateBy { it.channelId }
        val now = System.currentTimeMillis()
        val updated = channels.map { channel ->
            val saved = existing[channel.tvgId]
            LiveChannelPreference(
                channelId = channel.tvgId,
                channelPayload = LiveTvCodec.encodeChannel(channel),
                channelName = channel.name,
                isFavorite = saved?.isFavorite == true,
                customGroup = saved?.customGroup,
                lastWatchedAt = saved?.lastWatchedAt,
                updatedAt = now,
            )
        }
        dao.upsertChannelPreferences(updated)
        return updated
    }

    fun observeChannelPreferences(): Flow<List<LiveChannelPreference>> = dao.observeChannelPreferences()

    suspend fun getSavedChannelPreferences(): List<LiveChannelPreference> =
        dao.getAllChannelPreferences()

    suspend fun getChannelPreferences(channelIds: List<String>): List<LiveChannelPreference> =
        if (channelIds.isEmpty()) emptyList() else dao.getChannelPreferences(channelIds)

    suspend fun setFavorite(
        channelId: String,
        channelPayload: String,
        channelName: String,
        favorite: Boolean,
    ) {
        ensurePreference(channelId, channelPayload, channelName)
        dao.setChannelFavorite(channelId, favorite, System.currentTimeMillis())
    }

    fun setFavoriteAsync(
        channelId: String,
        channelPayload: String,
        channelName: String,
        favorite: Boolean,
    ) {
        scope.launch { setFavorite(channelId, channelPayload, channelName, favorite) }
    }

    suspend fun setCustomGroup(
        channelId: String,
        channelPayload: String,
        channelName: String,
        customGroup: String?,
    ) {
        ensurePreference(channelId, channelPayload, channelName)
        dao.setChannelCustomGroup(
            channelId,
            customGroup?.trim()?.takeIf { it.isNotBlank() }?.take(60),
            System.currentTimeMillis(),
        )
    }

    fun setCustomGroupAsync(
        channelId: String,
        channelPayload: String,
        channelName: String,
        customGroup: String?,
    ) {
        scope.launch { setCustomGroup(channelId, channelPayload, channelName, customGroup) }
    }

    fun markChannelWatched(
        channelId: String,
        channelPayload: String,
        channelName: String,
    ) {
        scope.launch {
            ensurePreference(channelId, channelPayload, channelName)
            dao.markChannelWatched(channelId, System.currentTimeMillis())
        }
    }

    private suspend fun ensurePreference(channelId: String, payload: String, name: String) {
        if (dao.getChannelPreference(channelId) != null) return
        dao.upsertChannelPreferences(
            listOf(
                LiveChannelPreference(
                    channelId = channelId,
                    channelPayload = payload,
                    channelName = name,
                    updatedAt = System.currentTimeMillis(),
                ),
            ),
        )
    }

    suspend fun orderStreams(
        channelId: String,
        streams: List<LiveStreamDescriptor>,
    ): List<LiveStreamDescriptor> {
        val health = dao.getHealthForChannels(listOf(channelId)).associateBy { it.streamKey }
        return streams.sortedWith(
            compareBy<LiveStreamDescriptor> { stream ->
                val item = health[LiveTvCodec.streamKey(stream)]
                when {
                    item == null -> 1
                    item.consecutiveFailures >= 3 -> 3
                    item.consecutiveFailures > 0 -> 2
                    item.lastSuccessMillis != null -> 0
                    else -> 1
                }
            }.thenBy { health[LiveTvCodec.streamKey(it)]?.latencyMs ?: Long.MAX_VALUE },
        )
    }

    suspend fun recordPlaybackSuccess(serverId: String, latencyMs: Long?) {
        val payload = LiveTvCodec.decodeServer(serverId) ?: return
        val key = LiveTvCodec.streamKey(payload.stream)
        val previous = dao.getStreamHealth(key)
        val now = System.currentTimeMillis()
        dao.upsertStreamHealth(
            LiveStreamHealth(
                streamKey = key,
                channelId = payload.channelId,
                streamUrl = payload.stream.url,
                lastSuccessMillis = now,
                lastFailureMillis = previous?.lastFailureMillis,
                consecutiveFailures = 0,
                latencyMs = latencyMs,
                lastError = null,
            ),
        )
        dao.insertDiagnostic(
            LivePlaybackDiagnostic(
                channelId = payload.channelId,
                channelName = payload.channelName,
                streamKey = key,
                host = payload.stream.url.toHttpUrlOrNull()?.host ?: "unknown",
                quality = payload.stream.quality,
                event = LivePlaybackDiagnostic.EVENT_READY,
                latencyMs = latencyMs,
                timestamp = now,
            ),
        )
    }

    suspend fun recordPlaybackFailure(serverId: String, error: Throwable?) {
        val payload = LiveTvCodec.decodeServer(serverId) ?: return
        val key = LiveTvCodec.streamKey(payload.stream)
        val previous = dao.getStreamHealth(key)
        val now = System.currentTimeMillis()
        val safeMessage = sanitizeDiagnosticMessage(error?.message)
        dao.upsertStreamHealth(
            LiveStreamHealth(
                streamKey = key,
                channelId = payload.channelId,
                streamUrl = payload.stream.url,
                lastSuccessMillis = previous?.lastSuccessMillis,
                lastFailureMillis = now,
                consecutiveFailures = (previous?.consecutiveFailures ?: 0) + 1,
                latencyMs = previous?.latencyMs,
                lastError = safeMessage,
            ),
        )
        dao.insertDiagnostic(
            LivePlaybackDiagnostic(
                channelId = payload.channelId,
                channelName = payload.channelName,
                streamKey = key,
                host = payload.stream.url.toHttpUrlOrNull()?.host ?: "unknown",
                quality = payload.stream.quality,
                event = LivePlaybackDiagnostic.EVENT_FAILED,
                message = safeMessage,
                latencyMs = previous?.latencyMs,
                timestamp = now,
            ),
        )
        dao.deleteOldDiagnostics(now - 30L * 24 * 60 * 60 * 1000)
    }

    fun observeDiagnostics(limit: Int = 100): Flow<List<LivePlaybackDiagnostic>> =
        dao.observeDiagnostics(limit)

    suspend fun clearDiagnostics() = dao.clearDiagnostics()

    fun observeNowNext(channelId: String): Flow<LiveNowNext> = flow {
        while (true) {
            val now = System.currentTimeMillis()
            val upcoming = dao.getUpcomingPrograms(channelId, now)
            val current = upcoming.firstOrNull { it.startMillis <= now && it.endMillis > now }
            val next = upcoming.firstOrNull {
                it.programId != current?.programId && it.startMillis >= (current?.endMillis ?: now)
            }
            emit(LiveNowNext(current, next))
            val nextBoundary = listOfNotNull(current?.endMillis, next?.startMillis)
                .filter { it > now }
                .minOrNull()
            delay(((nextBoundary ?: (now + 30_000L)) - now).coerceIn(10_000L, 60_000L))
        }
    }.distinctUntilChanged()

    fun observePrograms(
        channelIds: List<String>,
        startMillis: Long,
        endMillis: Long,
    ): Flow<List<EpgProgram>> = if (channelIds.isEmpty()) {
        flowOf(emptyList())
    } else {
        dao.observePrograms(channelIds, startMillis, endMillis)
    }

    fun observeReminders(): Flow<List<ProgramReminder>> = dao.observeReminders()
    suspend fun getReminder(programId: String): ProgramReminder? = dao.getReminder(programId)
    suspend fun saveReminder(reminder: ProgramReminder) = dao.upsertReminder(reminder)
    suspend fun deleteReminder(programId: String) = dao.deleteReminder(programId)
    suspend fun markReminderFired(programId: String, firedAt: Long) = dao.markReminderFired(programId, firedAt)

    fun observeRecordings(): Flow<List<LiveRecording>> = dao.observeRecordings()
    fun observeActiveRecording(channelId: String): Flow<LiveRecording?> = dao.observeActiveRecording(channelId)
    suspend fun getRecording(recordingId: String): LiveRecording? = dao.getRecording(recordingId)
    suspend fun saveRecording(recording: LiveRecording) = dao.upsertRecording(recording)
    suspend fun updateRecordingBytes(recordingId: String, bytes: Long) = dao.updateRecordingBytes(recordingId, bytes)
    suspend fun finishRecording(id: String, status: String, bytes: Long, error: String?) =
        dao.finishRecording(id, status, System.currentTimeMillis(), bytes, error)

    fun observeEpgMappings(): Flow<List<EpgChannelMapping>> = dao.observeEpgMappings()

    suspend fun findEpgMappingCandidates(
        channelId: String,
        channelName: String,
        limit: Int = 30,
    ): List<XmlTvChannel> {
        val normalizedId = normalizeMatchText(channelId.substringBefore('@'))
        val normalizedName = normalizeMatchText(channelName.replace("\\([^)]*\\)".toRegex(), ""))
        return dao.getXmlTvChannels().asSequence()
            .map { candidate ->
                candidate to mappingScore(
                    normalizedId,
                    normalizedName,
                    normalizeMatchText(candidate.xmlTvChannelId.substringBefore('@')),
                    normalizeMatchText(candidate.displayName),
                )
            }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    suspend fun saveEpgMapping(channelId: String, candidate: XmlTvChannel) {
        dao.upsertEpgMapping(
            EpgChannelMapping(
                channelId = channelId,
                sourceUrl = candidate.sourceUrl,
                xmlTvChannelId = candidate.xmlTvChannelId,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteEpgMapping(channelId: String) = dao.deleteEpgMapping(channelId)

    private fun mappingScore(
        channelId: String,
        channelName: String,
        candidateId: String,
        candidateName: String,
    ): Double {
        if (channelId == candidateId) return 120.0
        if (channelName == candidateName) return 110.0
        var score = 0.0
        if (candidateName.contains(channelName) || channelName.contains(candidateName)) score += 45.0
        if (candidateId.contains(channelId) || channelId.contains(candidateId)) score += 35.0
        val wantedTokens = (channelName.split(' ') + channelId.split(' ')).filter { it.length > 1 }.toSet()
        val candidateTokens = (candidateName.split(' ') + candidateId.split(' ')).filter { it.length > 1 }.toSet()
        if (wantedTokens.isNotEmpty()) {
            score += 40.0 * wantedTokens.intersect(candidateTokens).size / wantedTokens.union(candidateTokens).size
        }
        return score
    }

    private fun normalizeMatchText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "")
        .lowercase()
        .replace("[^a-z0-9]+".toRegex(), " ")
        .trim()

    private fun calculateHealth(
        streams: List<LiveStreamDescriptor>,
        health: List<LiveStreamHealth>,
        nowMillis: Long,
    ): LiveHealthState {
        if (health.isEmpty()) return LiveHealthState.UNKNOWN
        val byKey = health.associateBy { it.streamKey }
        val states = streams.mapNotNull { stream -> byKey[LiveTvCodec.streamKey(stream)] }
        if (states.isEmpty()) return LiveHealthState.UNKNOWN
        val recentlyOffline = states.count {
            it.consecutiveFailures >= 3 && nowMillis - (it.lastFailureMillis ?: 0L) < 60 * 60 * 1000L
        }
        if (recentlyOffline == streams.size) return LiveHealthState.OFFLINE
        if (recentlyOffline > 0 || states.any { it.consecutiveFailures > 0 }) return LiveHealthState.DEGRADED
        return if (states.any { it.lastSuccessMillis != null }) LiveHealthState.HEALTHY else LiveHealthState.UNKNOWN
    }

    private fun sanitizeDiagnosticMessage(message: String?): String? = message
        ?.replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "[stream URL redacted]")
        ?.replace(Regex("(?i)(token|key|signature|auth)=([^&\\s]+)"), "\$1=[redacted]")
        ?.take(300)

    private fun openMaybeGzip(raw: InputStream): InputStream {
        val buffered = BufferedInputStream(raw)
        buffered.mark(2)
        val first = buffered.read()
        val second = buffered.read()
        buffered.reset()
        return if (first == 0x1f && second == 0x8b) GZIPInputStream(buffered) else buffered
    }

    private fun scheduleWorkers(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val initial = OneTimeWorkRequestBuilder<EpgRefreshWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "live_epg_initial",
            ExistingWorkPolicy.KEEP,
            initial,
        )
        val periodic = PeriodicWorkRequestBuilder<EpgRefreshWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "live_epg_periodic",
            ExistingPeriodicWorkPolicy.KEEP,
            periodic,
        )
    }
}
