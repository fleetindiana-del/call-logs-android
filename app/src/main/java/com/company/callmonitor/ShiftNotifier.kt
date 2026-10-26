package com.company.callmonitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Makes the shift state impossible to be wrong about.
 *
 * While the shift is on there is a permanent, non-dismissable notification
 * naming the start time — the user can always see that they are being recorded,
 * and can always see when it began. Ending the shift replaces it with a
 * dismissable confirmation carrying both timestamps, which is the receipt for
 * the audit entry that just went to the server.
 */
object ShiftNotifier {

    private const val CHANNEL_ID = "ShiftStatusChannel"
    private const val ON_NOTIFICATION_ID = 8010
    private const val OFF_NOTIFICATION_ID = 8011

    fun showOn(context: Context, startedAt: Long) {
        val appCtx = context.applicationContext
        createChannel(appCtx)
        cancel(appCtx, OFF_NOTIFICATION_ID)

        val text = if (startedAt > 0L) {
            "Started at ${ShiftManager.formatTime(startedAt)}. Location and call logs are being recorded."
        } else {
            "Location and call logs are being recorded."
        }

        val notification = NotificationCompat.Builder(appCtx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_online)
            .setContentTitle("Shift ON")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // Sticky on purpose: being recorded is not something the user should
            // be able to swipe out of sight.
            .setOngoing(true)
            .setShowWhen(true)
            .setWhen(if (startedAt > 0L) startedAt else System.currentTimeMillis())
            .setContentIntent(homeIntent(appCtx))
            .build()

        notify(appCtx, ON_NOTIFICATION_ID, notification)
    }

    fun showOff(context: Context, startedAt: Long, endedAt: Long) {
        val appCtx = context.applicationContext
        createChannel(appCtx)
        cancel(appCtx, ON_NOTIFICATION_ID)

        val text = buildString {
            append("Ended at ${ShiftManager.formatTime(endedAt)}")
            if (startedAt > 0L) {
                append(" · started ${ShiftManager.formatTime(startedAt)}")
                append(" · ${ShiftManager.formatDuration(endedAt - startedAt)} worked")
            }
            append(". Nothing is being recorded now.")
        }

        val notification = NotificationCompat.Builder(appCtx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_invisible)
            .setContentTitle("Shift OFF")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(endedAt)
            .setContentIntent(homeIntent(appCtx))
            .build()

        notify(appCtx, OFF_NOTIFICATION_ID, notification)
    }

    /** Removes both notifications — used when there is no shift state to show. */
    fun clear(context: Context) {
        val appCtx = context.applicationContext
        cancel(appCtx, ON_NOTIFICATION_ID)
        cancel(appCtx, OFF_NOTIFICATION_ID)
    }

    /** Re-asserts the on-shift notification after a reboot or process restart. */
    fun sync(context: Context) {
        val appCtx = context.applicationContext
        if (ShiftManager.isOn(appCtx)) {
            showOn(appCtx, ShiftManager.startedAt(appCtx))
        } else {
            cancel(appCtx, ON_NOTIFICATION_ID)
        }
    }

    private fun homeIntent(context: Context): PendingIntent {
        val intent = Intent(context, LauncherActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, 3, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notify(context: Context, id: Int, notification: android.app.Notification) {
        try {
            context.getSystemService(NotificationManager::class.java)?.notify(id, notification)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "notify failed: ${e.message}")
        }
    }

    private fun cancel(context: Context, id: Int) {
        try {
            context.getSystemService(NotificationManager::class.java)?.cancel(id)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "cancel failed: ${e.message}")
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Shift Status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows whether your shift is on and what is being recorded"
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    private const val TAG = "ShiftNotifier"
}
