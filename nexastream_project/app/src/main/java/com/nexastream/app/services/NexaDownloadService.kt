package com.nexastream.app.services

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.scheduler.Scheduler
import com.nexastream.app.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class NexaDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_channel_name,
    0
) {

    @Inject
    lateinit var injectedDownloadManager: DownloadManager

    @Inject
    lateinit var notificationHelper: DownloadNotificationHelper

    companion object {
        private const val FOREGROUND_NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "download_channel"
    }

    override fun getDownloadManager(): DownloadManager {
        android.util.Log.d("NexaDownloadService", "getDownloadManager called")
        return injectedDownloadManager
    }

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int
    ): Notification {
        val baseNotification = notificationHelper.buildProgressNotification(
            this,
            R.drawable.ic_download,
            null,
            null,
            downloads,
            notMetRequirements
        )
        val actionDownload = downloads.firstOrNull { it.state != Download.STATE_REMOVING }
            ?: return baseNotification

        val builder = Notification.Builder.recoverBuilder(this, baseNotification)
        when (actionDownload.state) {
            Download.STATE_DOWNLOADING,
            Download.STATE_QUEUED,
            Download.STATE_RESTARTING -> builder.addAction(
                downloadNotificationAction(
                    R.drawable.exo_styled_controls_pause,
                    getString(R.string.download_action_pause),
                    actionDownload.request.id,
                    DownloadNotificationActionReceiver.ACTION_PAUSE
                )
            )

            Download.STATE_STOPPED -> builder.addAction(
                downloadNotificationAction(
                    R.drawable.exo_styled_controls_play,
                    getString(R.string.download_action_resume),
                    actionDownload.request.id,
                    DownloadNotificationActionReceiver.ACTION_RESUME
                )
            )

            Download.STATE_FAILED -> builder.addAction(
                downloadNotificationAction(
                    R.drawable.ic_refresh,
                    getString(R.string.download_action_retry),
                    actionDownload.request.id,
                    DownloadNotificationActionReceiver.ACTION_RETRY
                )
            )
        }

        return builder
            .addAction(
                downloadNotificationAction(
                    R.drawable.ic_settings_close,
                    getString(R.string.download_action_cancel),
                    actionDownload.request.id,
                    DownloadNotificationActionReceiver.ACTION_CANCEL
                )
            )
            .build()
    }

    private fun downloadNotificationAction(
        icon: Int,
        title: String,
        downloadId: String,
        action: String
    ): Notification.Action {
        return Notification.Action.Builder(
            Icon.createWithResource(this, icon),
            title,
            downloadActionPendingIntent(this, downloadId, action)
        ).build()
    }

    private fun downloadActionPendingIntent(
        context: Context,
        downloadId: String,
        action: String
    ): PendingIntent {
        val intent = Intent(context, DownloadNotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(DownloadNotificationActionReceiver.EXTRA_DOWNLOAD_ID, downloadId)
        }
        val requestCode = 31 * action.hashCode() + downloadId.hashCode()

        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
