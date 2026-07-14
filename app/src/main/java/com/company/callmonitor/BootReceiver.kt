package com.company.callmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val prefs = context.getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
            if (prefs.getBoolean("monitoringEnabled", false)) {
                val serviceIntent = Intent(context, CallMonitorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                CallSyncScheduler.schedule(context)
                ServiceRestartScheduler.enqueueDelayedRestart(context, 90, TimeUnit.SECONDS)
            }
        }
    }
}
