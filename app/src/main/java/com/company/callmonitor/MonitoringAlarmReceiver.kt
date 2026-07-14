package com.company.callmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Fires on an interval while monitoring is ON. Wakes the device from light idle,
 * ensures [CallMonitorService] is running, and reschedules the next alarm.
 */
class MonitoringAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("monitoringEnabled", false)) {
            MonitoringAlarmScheduler.cancelAlarm(appCtx)
            return
        }

        MonitoringAlarmScheduler.scheduleNextAlarm(appCtx)

        val serverUrl = prefs.getString("serverUrl", "") ?: ""
        val deviceId = prefs.getString("deviceId", "") ?: ""
        if (serverUrl.isBlank() || deviceId.isBlank()) return

        if (!CallMonitorService.isRunning) {
            try {
                val serviceIntent = Intent(appCtx, CallMonitorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appCtx.startForegroundService(serviceIntent)
                } else {
                    appCtx.startService(serviceIntent)
                }
                Logger.log(appCtx, "MonitoringAlarm: restarted CallMonitorService (idle wake)")
            } catch (e: Exception) {
                Logger.log(appCtx, "MonitoringAlarm: start failed — ${e.javaClass.simpleName}: ${e.message}")
                ServiceRestartScheduler.enqueueRestart(appCtx)
            }
        }
    }
}
