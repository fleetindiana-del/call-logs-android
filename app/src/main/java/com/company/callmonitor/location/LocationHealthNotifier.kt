package com.company.callmonitor.location

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat

/**
 * Android gives no app the ability to switch Location back on by itself — the
 * master toggle is the user's. What it does allow is noticing the moment it goes
 * off and making that impossible to miss, which is the closest thing to "make
 * sure location does not get turned off" that the platform permits.
 *
 * Paired with [LocationProviderChangedReceiver], which restarts recording the
 * instant the switch comes back, so the driver only ever has to tap once.
 */
object LocationHealthNotifier {

    private const val CHANNEL_ID = "LocationHealthChannel"
    private const val NOTIFICATION_ID = 8002

    fun update(context: Context) {
        val reason = LocationEnvironment.blockedReason(context)
        if (reason == null) clear(context) else show(context, reason)
    }

    fun show(context: Context, reason: String) {
        val appCtx = context.applicationContext
        createChannel(appCtx)

        // Straight to the Location settings screen when that is what is off,
        // otherwise to this app's permission page.
        val settingsIntent = if (!LocationEnvironment.hasForegroundPermission(appCtx)) {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", appCtx.packageName, null))
        } else {
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val pendingIntent = PendingIntent.getActivity(
            appCtx, 1, settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appCtx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Route tracking is paused")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Sticky on purpose: swiping it away must not make the problem look
            // solved while the route is still not being recorded.
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            appCtx.getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "notify failed: ${e.message}")
        }
    }

    fun clear(context: Context) {
        try {
            context.applicationContext.getSystemService(NotificationManager::class.java)
                ?.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "cancel failed: ${e.message}")
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Tracking Problems",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Warns when GPS tracking has stopped working"
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    private const val TAG = "LocationHealthNotifier"
}
