package com.company.callmonitor

import android.app.Application
import android.content.Intent
import android.os.Build
import com.company.callmonitor.callerlookup.CallerLookupWorker
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Cold-start recovery: if monitoring was left ON, restart the foreground service
 * and WorkManager schedules without requiring MainActivity to open.
 * Also registers the FCM token with the backend for push wake-ups.
 */
class CallMonitorApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Caller lookup jobs are independent of call-monitoring being toggled on.
        // Poll once on every process start to recover missed FCM or interrupted work.
        if (EnrollmentPrefs.isEnrolled(this)) {
            CallerLookupWorker.enqueuePending(this)
            CallerLookupWorker.schedulePeriodicPoll(this)
            registerFcmTokenForEnrollment(this)
        }

        // GPS duty is independent of call monitoring and must come back on any
        // process start — after an OEM kill the process is typically revived by
        // FCM or a worker, never by the driver opening the app, and until now
        // nothing in that path resumed tracking.
        if (EnrollmentPrefs.hasLocationTracking(this)) {
            com.company.callmonitor.location.LocationWatchdogWorker.schedule(this)
            Thread {
                try {
                    com.company.callmonitor.location.LocationDutyController
                        .ensureAlwaysActive(this, "cold_start")
                } catch (e: Exception) {
                    Logger.log(this, "📍 cold start restore failed — ${e.javaClass.simpleName}: ${e.message}")
                }
            }.start()
        }

        val prefs = getSharedPreferences("CallMonitorConfig", MODE_PRIVATE)
        if (!prefs.getBoolean("monitoringEnabled", false)) return

        val serverUrl = prefs.getString("serverUrl", "") ?: ""
        val deviceId = prefs.getString("deviceId", "") ?: ""
        if (serverUrl.isBlank() || deviceId.isBlank()) return

        try {
            val intent = Intent(this, CallMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Logger.log(this, "CallMonitorApp: could not start service on cold start — ${e.javaClass.simpleName}: ${e.message}")
            ServiceRestartScheduler.enqueueRestart(this)
        }
        try {
            CallSyncScheduler.schedule(this)
        } catch (e: Exception) {
            android.util.Log.e("CallMonitorApp", "CallSyncScheduler: ${e.message}")
        }

    }

    companion object {
        fun registerFcmTokenForEnrollment(context: android.content.Context) {
            val serverUrl = EnrollmentPrefs.getServerUrl(context)
            val apiKey = EnrollmentPrefs.getApiKey(context)
            val deviceId = EnrollmentPrefs.getDeviceId(context)
            if (serverUrl.isBlank() || deviceId.isBlank()) return

            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                Thread {
                    val success = ApiClient.registerFcmToken(
                        serverUrl,
                        apiKey,
                        deviceId,
                        token,
                        context
                    )
                    if (success) {
                        Logger.log(context, "✅ FCM token registered for device commands")
                    }
                }.start()
            }.addOnFailureListener { e ->
                Logger.log(context, "⚠️ FCM token retrieval failed: ${e.message}")
            }
        }
    }
}

