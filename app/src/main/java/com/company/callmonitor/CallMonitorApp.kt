package com.company.callmonitor

import android.app.Application
import android.content.Intent
import android.os.Build
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Cold-start recovery: if monitoring was left ON, restart the foreground service
 * and WorkManager schedules without requiring MainActivity to open.
 * Also registers the FCM token with the backend for push wake-ups.
 */
class CallMonitorApp : Application() {

    override fun onCreate() {
        super.onCreate()
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

        // Register FCM token with backend for push wake-ups
        val apiKey = prefs.getString("apiKey", "") ?: ""
        registerFcmToken(serverUrl, apiKey, deviceId)
    }

    private fun registerFcmToken(serverUrl: String, apiKey: String, deviceId: String) {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            Thread {
                val success = ApiClient.registerFcmToken(serverUrl, apiKey, deviceId, token, this)
                if (success) {
                    Logger.log(this, "✅ FCM token registered on cold start")
                }
            }.start()
        }.addOnFailureListener { e ->
            Logger.log(this, "⚠️ FCM token retrieval failed: ${e.message}")
        }
    }
}

