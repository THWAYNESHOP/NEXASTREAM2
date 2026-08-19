package com.nexastream.app.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import com.nexastream.app.utils.DownloadManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class DownloadNotificationActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var downloadManager: DownloadManager

    override fun onReceive(context: Context, intent: Intent) {
        val downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID) ?: return

        when (intent.action) {
            ACTION_PAUSE -> downloadManager.pauseDownload(downloadId)
            ACTION_RESUME -> downloadManager.resumeDownload(downloadId)
            ACTION_RETRY -> downloadManager.retryDownload(downloadId)
            ACTION_CANCEL -> downloadManager.deleteDownload(downloadId)
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.nexastream.app.download.action.PAUSE"
        const val ACTION_RESUME = "com.nexastream.app.download.action.RESUME"
        const val ACTION_RETRY = "com.nexastream.app.download.action.RETRY"
        const val ACTION_CANCEL = "com.nexastream.app.download.action.CANCEL"
        const val EXTRA_DOWNLOAD_ID = "download_id"
    }
}
