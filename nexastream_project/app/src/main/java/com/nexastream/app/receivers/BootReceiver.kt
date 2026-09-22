package com.nexastream.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nexastream.app.activities.main.MainTvActivity
import com.nexastream.app.utils.UserPreferences

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (UserPreferences.autoStartOnBoot) {
                val launchIntent = Intent(context, MainTvActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(launchIntent)
            }
        }
    }
}
