package com.company.callmonitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.company.callmonitor.callerlookup.CallerLookupWorker
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Handles high-priority FCM **data** messages from the backend (e.g. stale sync).
 *
 * **Force-stop:** If the user force-stops the app (App info → Force stop), Android
 * **does not deliver FCM** until the user opens the app again. This is OS policy, not a bug.
 * After reopening, [MainActivity] enqueues a sync so data can catch up.
 *
 * When not force-stopped, delivery can wake the process in the background; no activity is launched.
 *
 * Actions:
 *   - "sync_now"  → enqueues a one-time expedited [CallSyncWorker] immediately
 *   - "check_settings" → shows a notification prompting the user to re-check battery settings
 *
 * Also handles token refresh: re-registers the new FCM token with the backend.
 */
class CallMonitorFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val CHANNEL_ID = "FcmWakeChannel"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Logger.log(applicationContext, "🔑 FCM token refreshed — registering with backend")
        registerTokenWithBackend(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val action = message.data["action"] ?: ""
        Logger.log(applicationContext, "📩 FCM message received — action: $action")

        when (action) {
            "sync_now" -> {
                // Trigger an immediate one-time sync (process already running for this callback)
                enqueueImmediateSync()

                // If repeated failures, also show a notification
                val prefs = applicationContext.getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
                val failures = prefs.getInt("callSyncConsecutiveFailureCount", 0)
                if (failures >= 3) {
                    showBatterySettingsNotification()
                }
            }
            "check_settings" -> {
                showBatterySettingsNotification()
            }
            "location_upload", "location_latest" -> handleLocationFcm(action, message.data)
            "location_live_mode" -> handleLocationFcm(action, message.data)
            "location_stop_live_mode" -> handleLocationFcm(action, message.data)
            "location_start_tracking", "location_stop_tracking" -> handleLocationFcm(action, message.data)
            "caller_lookup_start", "caller_lookup_resume" -> {
                val jobId = message.data["jobId"].orEmpty()
                if (jobId.isNotBlank()) {
                    CallerLookupWorker.enqueue(applicationContext, jobId)
                    Logger.log(applicationContext, "🔎 FCM: enqueued Android caller lookup $jobId")
                } else {
                    CallerLookupWorker.enqueuePending(applicationContext)
                }
            }
            "caller_lookup_control" -> {
                val jobId = message.data["jobId"].orEmpty()
                if (jobId.isNotBlank()) {
                    // Replace wakes/cancels a possibly blocked worker so it immediately
                    // fetches and acknowledges pause/stop from the backend.
                    CallerLookupWorker.enqueue(
                        applicationContext,
                        jobId,
                        ExistingWorkPolicy.REPLACE
                    )
                } else {
                    CallerLookupWorker.enqueuePending(applicationContext)
                }
            }
        }
    }

    private fun enqueueImmediateSync() {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("monitoringEnabled", false)) return

        val builder = OneTimeWorkRequestBuilder<CallSyncWorker>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        WorkManager.getInstance(ctx).enqueue(builder.build())
        Logger.log(ctx, "⚡ FCM: enqueued immediate CallSyncWorker")
    }

    private fun showBatterySettingsNotification() {
        val ctx = applicationContext
        createNotificationChannel(ctx)

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Call logs not syncing")
            .setContentText("Please check battery settings — your phone may be killing this app in the background.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Your call logs haven't been uploaded recently. " +
                        "Please open the app and ensure AutoStart and Unrestricted Battery are enabled " +
                        "in your phone settings."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm?.notify(9001, notification)
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sync Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when call log sync is failing"
            }
            val nm = context.getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun handleLocationFcm(action: String, data: Map<String, String>) {
        when (action) {
            "location_upload", "location_latest" -> {
                com.company.callmonitor.location.LocationUploadWorker.enqueue(applicationContext)
                Logger.log(applicationContext, "📍 FCM: enqueued LocationUploadWorker (action=$action)")
            }
            "location_live_mode" -> {
                applicationContext.getSharedPreferences("location_prefs", android.content.Context.MODE_PRIVATE)
                    .edit().putBoolean("liveMode", true).apply()
                // Live denser sampling only helps if the service is already recording.
                com.company.callmonitor.location.LocationDutyController.startTracking(
                    applicationContext,
                    "fcm_live_mode"
                )
                // Re-subscribe now; otherwise a parked device keeps its 20s rate
                // until the next fix lands.
                com.company.callmonitor.location.LocationTrackingService
                    .applyLiveMode(applicationContext)
                Logger.log(applicationContext, "📍 FCM: live mode enabled")
            }
            "location_stop_live_mode" -> {
                applicationContext.getSharedPreferences("location_prefs", android.content.Context.MODE_PRIVATE)
                    .edit().putBoolean("liveMode", false).apply()
                com.company.callmonitor.location.LocationTrackingService
                    .applyLiveMode(applicationContext)
                Logger.log(applicationContext, "📍 FCM: live mode disabled")
            }
            "location_start_tracking" -> {
                val ok = com.company.callmonitor.location.LocationDutyController.startTracking(
                    applicationContext,
                    "fcm_start"
                )
                Logger.log(applicationContext, "📍 FCM: start tracking ok=$ok")
                com.company.callmonitor.location.LocationUploadWorker.enqueue(applicationContext)
            }
            "location_stop_tracking" -> {
                com.company.callmonitor.location.LocationDutyController.stopTracking(
                    applicationContext,
                    "fcm_stop"
                )
                Logger.log(applicationContext, "📍 FCM: stop tracking")
            }
        }
    }

    private fun registerTokenWithBackend(token: String) {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
        val serverUrl = prefs.getString("serverUrl", "") ?: ""
        val apiKey = prefs.getString("apiKey", "") ?: ""
        val deviceId = prefs.getString("deviceId", "") ?: ""

        if (serverUrl.isBlank() || deviceId.isBlank()) return

        Thread {
            val success = ApiClient.registerFcmToken(serverUrl, apiKey, deviceId, token, ctx)
            if (success) {
                Logger.log(ctx, "✅ FCM token registered with backend")
            } else {
                Logger.log(ctx, "❌ Failed to register FCM token — will retry on next refresh")
            }
        }.start()
    }
}
