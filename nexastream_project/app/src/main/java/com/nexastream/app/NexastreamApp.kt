package com.nexastream.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.media3.common.util.UnstableApi
import java.security.Security
import org.conscrypt.Conscrypt
import dagger.hilt.android.HiltAndroidApp
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.utils.AppLanguageManager
import com.nexastream.app.utils.ArtworkRepairScheduler
import com.nexastream.app.utils.CacheUtils
import com.nexastream.app.utils.DnsResolver
import com.nexastream.app.utils.IsrgRootTrustProvider
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.utils.DownloadManager as AppDownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class NexastreamApp : Application() {

    @OptIn(UnstableApi::class)
    @Inject
    lateinit var appDownloadManager: AppDownloadManager

    companion object {
        lateinit var instance: NexastreamApp
            private set

        @Volatile
        var currentActivity: Activity? = null
            private set
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun attachBaseContext(base: Context) {
        UserPreferences.setup(base)
        super.attachBaseContext(AppLanguageManager.wrap(base))
    }

    override fun onCreate() {
        UserPreferences.setup(this)
        super.onCreate()
        instance = this
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityResumed(activity: Activity) {
                currentActivity = activity
            }

            override fun onActivityPaused(activity: Activity) {
                if (currentActivity === activity) {
                    currentActivity = null
                }
            }

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                if (currentActivity === activity) {
                    currentActivity = null
                }
            }
        })

        // 0. Initialize Conscrypt for modern SSL on old Android
        Security.insertProviderAt(Conscrypt.newProvider(), 1)

        // 1. Install ISRG Root X1 globally for Let's Encrypt. On Android < 7 (API 24)
        // network_security_config.xml is not supported so the certificate must be injected manually.
        IsrgRootTrustProvider.install()

        DnsResolver.setDnsUrl(UserPreferences.dohProviderUrl)

        @OptIn(UnstableApi::class)
        Log.d("NexastreamApp", "DownloadManager initialized: ${appDownloadManager.hashCode()}")

        val appContext = applicationContext
        val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val threshold = if (isTv) 10L else 50L

        applicationScope.launch(Dispatchers.IO) {
            AppDatabase.setup(appContext)
            appDownloadManager.recoverDownloads()
            // SerienStreamProvider.init(appContext)
            // AniWorldProvider.initialize(appContext)
            ArtworkRepairScheduler.schedule(appContext, UserPreferences.currentProvider)
            CacheUtils.autoClearIfNeeded(appContext, thresholdMb = threshold)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            CacheUtils.clearAppCache(this)
        }
    }
}
