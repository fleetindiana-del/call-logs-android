package com.company.callmonitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/**
 * Chained alarms (~8 min) while monitoring is enabled. Wakes from App Standby / Doze
 * on many devices so the foreground service can stay active and sync.
 */
object MonitoringAlarmScheduler {

    private const val REQUEST_CODE = 0x4D4F4E41 // "MONA"
    private const val INTERVAL_MS = 8 * 60_000L

    /** First alarm after [initialDelayMs]; receiver chains further alarms. */
    fun startAlarmChain(context: Context, initialDelayMs: Long = 60_000L) {
        val appCtx = context.applicationContext
        cancelAlarm(appCtx)
        scheduleAlarmAt(
            appCtx,
            triggerElapsedRealtime = SystemClock.elapsedRealtime() + initialDelayMs
        )
    }

    /**
     * Start the chain only if it is not already armed. Used by GPS duty, which
     * calls in far more often than call monitoring does and must not keep
     * pushing the next wake-up out by restarting the chain each time.
     */
    fun ensureAlarmChain(context: Context) {
        val appCtx = context.applicationContext
        val intent = Intent(appCtx, MonitoringAlarmReceiver::class.java)
        val flags = PendingIntent.FLAG_NO_CREATE or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val existing = PendingIntent.getBroadcast(appCtx, REQUEST_CODE, intent, flags)
        if (existing == null) startAlarmChain(appCtx)
    }

    fun scheduleNextAlarm(context: Context) {
        val appCtx = context.applicationContext
        scheduleAlarmAt(
            appCtx,
            triggerElapsedRealtime = SystemClock.elapsedRealtime() + INTERVAL_MS
        )
    }

    private fun scheduleAlarmAt(context: Context, triggerElapsedRealtime: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmPendingIntent(context)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerElapsedRealtime,
                    pi
                )
            } else {
                @Suppress("DEPRECATION")
                am.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerElapsedRealtime,
                    pi
                )
            }
        } catch (e: SecurityException) {
            try {
                am.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerElapsedRealtime,
                    pi
                )
            } catch (e2: Exception) {
                android.util.Log.e("MonitoringAlarm", "schedule failed: ${e2.message}")
            }
        } catch (e: Exception) {
            android.util.Log.e("MonitoringAlarm", "schedule failed: ${e.message}")
        }
    }

    fun cancelAlarm(context: Context) {
        val appCtx = context.applicationContext
        val am = appCtx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(alarmPendingIntent(appCtx))
    }

    private fun alarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MonitoringAlarmReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}
