package com.nexastream.app.live

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class EpgRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        if (LiveTvRepository.refreshConfiguredEpg(force = true)) Result.success() else Result.retry()
    }.getOrElse { Result.retry() }
}
