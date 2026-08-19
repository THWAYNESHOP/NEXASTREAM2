package com.nexastream.app.live

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nexastream.app.BuildConfig
import com.nexastream.app.R
import com.nexastream.app.activities.main.MainMobileActivity
import com.nexastream.app.activities.main.MainTvActivity
import com.nexastream.app.models.LiveRecording
import com.nexastream.app.models.Video
import com.nexastream.app.utils.NetworkClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class LiveDvrService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordingJob: Job? = null
    private val stopRequested = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRequested.set(true)
            ACTION_START -> if (recordingJob?.isActive != true) startRecording(intent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRequested.set(true)
        scope.cancel()
        super.onDestroy()
    }

    private fun startRecording(intent: Intent) {
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: return stopSelf()
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Live TV"
        val sourceUrl = intent.getStringExtra(EXTRA_SOURCE_URL) ?: return stopSelf()
        val headers = decodeHeaders(intent.getStringExtra(EXTRA_HEADERS))
        val recordingId = UUID.randomUUID().toString()
        val folder = File(getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "NexaStream DVR")
        if (!folder.exists()) folder.mkdirs()
        val safeTitle = title.replace("[^a-zA-Z0-9._ -]".toRegex(), "_").take(80).ifBlank { "Live TV" }
        val file = File(folder, "${safeTitle}_${System.currentTimeMillis()}.ts")
        stopRequested.set(false)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification(title, "Starting recording…"))

        recordingJob = scope.launch {
            var bytesWritten = 0L
            LiveTvRepository.saveRecording(
                LiveRecording(
                    recordingId = recordingId,
                    channelId = channelId,
                    title = title,
                    filePath = file.absolutePath,
                    sourceUrl = sourceUrl,
                    status = LiveRecording.STATUS_RECORDING,
                    startedAt = System.currentTimeMillis(),
                ),
            )
            runCatching {
                bytesWritten = recordHls(sourceUrl, headers, file, recordingId, title)
            }.onSuccess {
                LiveTvRepository.finishRecording(
                    recordingId,
                    LiveRecording.STATUS_COMPLETED,
                    bytesWritten,
                    null,
                )
            }.onFailure { error ->
                LiveTvRepository.finishRecording(
                    recordingId,
                    LiveRecording.STATUS_FAILED,
                    bytesWritten,
                    error.message?.take(300),
                )
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun recordHls(
        initialUrl: String,
        headers: Map<String, String>,
        output: File,
        recordingId: String,
        title: String,
    ): Long {
        var playlistUrl = initialUrl
        var playlist = fetchText(playlistUrl, headers)
        selectMasterVariant(playlistUrl, playlist)?.let { variant ->
            playlistUrl = variant
            playlist = fetchText(playlistUrl, headers)
        }
        if (!playlist.contains("#EXTM3U")) error("The selected stream is not HLS")
        if (playlist.contains("#EXT-X-MAP", ignoreCase = true)) {
            error("This fMP4 HLS stream cannot be exported safely; live timeshift remains available")
        }

        val seen = linkedSetOf<String>()
        var bytes = 0L
        FileOutputStream(output, false).use { fileOutput ->
            while (!stopRequested.get()) {
                playlist = fetchText(playlistUrl, headers)
                val keyLine = playlist.lineSequence().firstOrNull { it.startsWith("#EXT-X-KEY", true) }
                if (keyLine != null && !keyLine.contains("METHOD=NONE", true)) {
                    error("Encrypted HLS recording is not supported")
                }
                val segmentUrls = playlist.lineSequence()
                    .map(String::trim)
                    .filter { it.isNotBlank() && !it.startsWith('#') }
                    .mapNotNull { resolveUrl(playlistUrl, it) }
                    .toList()
                segmentUrls.forEach { segmentUrl ->
                    if (stopRequested.get() || !seen.add(segmentUrl)) return@forEach
                    val request = request(segmentUrl, headers)
                    NetworkClient.default.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error("Segment HTTP ${response.code}")
                        val body = response.body ?: error("Empty HLS segment")
                        body.byteStream().use { input -> bytes += input.copyTo(fileOutput) }
                    }
                    if (seen.size % 3 == 0) {
                        fileOutput.flush()
                        LiveTvRepository.updateRecordingBytes(recordingId, bytes)
                        updateNotification(title, bytes)
                    }
                }
                if (playlist.contains("#EXT-X-ENDLIST")) break
                val targetSeconds = Regex("#EXT-X-TARGETDURATION:(\\d+)")
                    .find(playlist)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toLongOrNull()
                    ?: 6L
                delay((targetSeconds * 500L).coerceIn(1_500L, 6_000L))
            }
            fileOutput.flush()
        }
        if (bytes == 0L) error("No HLS segments were recorded")
        return bytes
    }

    private fun fetchText(url: String, headers: Map<String, String>): String {
        NetworkClient.default.newCall(request(url, headers)).execute().use { response ->
            if (!response.isSuccessful) error("Playlist HTTP ${response.code}")
            return response.body?.string() ?: error("Empty HLS playlist")
        }
    }

    private fun request(url: String, headers: Map<String, String>): Request = Request.Builder()
        .url(url)
        .apply { headers.forEach { (name, value) -> header(name, value) } }
        .build()

    private fun selectMasterVariant(baseUrl: String, playlist: String): String? {
        val lines = playlist.lines()
        return lines.mapIndexedNotNull { index, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF", true)) return@mapIndexedNotNull null
            val bandwidth = Regex("BANDWIDTH=(\\d+)", RegexOption.IGNORE_CASE)
                .find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            val uri = lines.drop(index + 1).firstOrNull { it.isNotBlank() && !it.startsWith('#') }
                ?: return@mapIndexedNotNull null
            bandwidth to resolveUrl(baseUrl, uri)
        }.filter { it.second != null }.maxByOrNull { it.first }?.second
    }

    private fun resolveUrl(baseUrl: String, value: String): String? =
        value.toHttpUrlOrNull()?.toString() ?: baseUrl.toHttpUrlOrNull()?.resolve(value)?.toString()

    private fun notification(title: String, detail: String): android.app.Notification {
        val activityClass = if (BuildConfig.APP_LAYOUT == "tv") MainTvActivity::class.java else MainMobileActivity::class.java
        val contentIntent = PendingIntent.getActivity(
            this,
            42,
            Intent(this, activityClass),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            43,
            Intent(this, LiveDvrService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_menu_tv)
            .setContentTitle("Recording $title")
            .setContentText(detail)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification(title: String, bytes: Long) {
        val detail = String.format("%.1f MB saved", bytes / 1_048_576.0)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification(title, detail))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Live TV recordings", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun decodeHeaders(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val json = JSONObject(raw)
        return json.keys().asSequence().associateWith { json.optString(it) }
    }

    companion object {
        private const val ACTION_START = "com.nexastream.app.live.START_RECORDING"
        private const val ACTION_STOP = "com.nexastream.app.live.STOP_RECORDING"
        private const val EXTRA_CHANNEL_ID = "channel_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SOURCE_URL = "source_url"
        private const val EXTRA_HEADERS = "headers"
        private const val CHANNEL_ID = "live_dvr"
        private const val NOTIFICATION_ID = 2048

        fun start(context: Context, channelId: String, title: String, video: Video): Boolean {
            if (!video.source.contains(".m3u8", ignoreCase = true) &&
                video.type != androidx.media3.common.MimeTypes.APPLICATION_M3U8
            ) return false
            val headers = JSONObject().apply {
                video.headers.orEmpty().forEach { (name, value) -> put(name, value) }
            }
            ContextCompat.startForegroundService(
                context,
                Intent(context, LiveDvrService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_CHANNEL_ID, channelId)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_SOURCE_URL, video.source)
                    .putExtra(EXTRA_HEADERS, headers.toString()),
            )
            return true
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LiveDvrService::class.java).setAction(ACTION_STOP))
        }
    }
}
