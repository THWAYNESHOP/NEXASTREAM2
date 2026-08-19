package com.nexastream.app.live

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nexastream.app.BuildConfig
import com.nexastream.app.R
import com.nexastream.app.activities.main.MainMobileActivity
import com.nexastream.app.activities.main.MainTvActivity
import com.nexastream.app.models.EpgProgram
import com.nexastream.app.models.ProgramReminder
import java.util.concurrent.TimeUnit

class ProgramReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val programId = inputData.getString(KEY_PROGRAM_ID) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: "Programme starting"
        val channel = inputData.getString(KEY_CHANNEL) ?: "Live TV"
        createChannel()

        val activityClass = if (BuildConfig.APP_LAYOUT == "tv") {
            MainTvActivity::class.java
        } else {
            MainMobileActivity::class.java
        }
        val intent = Intent(applicationContext, activityClass).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            programId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_menu_tv)
            .setContentTitle("$title is starting")
            .setContentText(channel)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(programId.hashCode(), notification)
        LiveTvRepository.markReminderFired(programId, System.currentTimeMillis())
        return Result.success()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Programme reminders",
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    companion object {
        const val KEY_PROGRAM_ID = "program_id"
        const val KEY_TITLE = "title"
        const val KEY_CHANNEL = "channel"
        private const val CHANNEL_ID = "live_program_reminders"
    }
}

object ProgramReminderScheduler {
    private const val REMINDER_LEAD_MILLIS = 5 * 60 * 1000L

    suspend fun toggle(
        context: Context,
        program: EpgProgram,
        channelName: String,
        channelPayload: String,
    ): Boolean {
        val existing = LiveTvRepository.getReminder(program.programId)
        return if (existing != null) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(program.programId))
            LiveTvRepository.deleteReminder(program.programId)
            false
        } else {
            val reminder = ProgramReminder(
                programId = program.programId,
                channelId = program.channelId,
                channelName = channelName,
                title = program.title,
                startMillis = program.startMillis,
                endMillis = program.endMillis,
                channelPayload = channelPayload,
                createdAt = System.currentTimeMillis(),
            )
            LiveTvRepository.saveReminder(reminder)
            val delayMillis = (program.startMillis - REMINDER_LEAD_MILLIS - System.currentTimeMillis())
                .coerceAtLeast(0L)
            val data = Data.Builder()
                .putString(ProgramReminderWorker.KEY_PROGRAM_ID, program.programId)
                .putString(ProgramReminderWorker.KEY_TITLE, program.title)
                .putString(ProgramReminderWorker.KEY_CHANNEL, channelName)
                .build()
            val request = OneTimeWorkRequestBuilder<ProgramReminderWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .setInputData(data)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(program.programId),
                ExistingWorkPolicy.REPLACE,
                request,
            )
            true
        }
    }

    private fun workName(programId: String) = "program_reminder_$programId"
}
