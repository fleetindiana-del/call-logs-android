package com.company.callmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.Calendar

class CallMonitorService : Service() {

    companion object {
        var isRunning = false
    }

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val permissionExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val syncExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    /** Keeps CPU from sleeping while monitoring (reduces “idle” throttling on some OEMs). */
    private var monitoringWakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        // On newer Android versions, starting a foreground service with restricted FGS types
        // (e.g. PHONE_CALL) can throw at runtime depending on eligibility/role/policy.
        // If that happens, fall back to a basic foreground service notification so the app
        // doesn't crash when the user enables monitoring.
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    1,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Logger.log(
                this@CallMonitorService,
                "⚠️ startForeground(types) failed (${e.javaClass.simpleName}): ${e.message} — falling back to basic foreground"
            )
            try {
                startForeground(1, notification)
            } catch (e2: Exception) {
                Logger.log(
                    this@CallMonitorService,
                    "❌ startForeground fallback failed (${e2.javaClass.simpleName}): ${e2.message}"
                )
                // If we cannot become a foreground service, stop to avoid an ANR/crash loop.
                stopSelf()
                return
            }
        }

        acquireMonitoringWakeLock()

        // Reset stale call-state from a previous session so the receiver
        // doesn't get stuck thinking a call is still in progress.
        PhoneStateReceiver.resetState()

        // Backfill any calls missed while the service was not running
        // (covers reboots, updates, force-stops, FCM wakeups, etc.)
        Thread {
            CallBackfillManager.backfillMissedCalls(this)
        }.start()

        executor.scheduleAtFixedRate({
            try {
                retryPendingCalls()
                retryPendingStatuses()
                retryPendingContacts()
                ContactSyncManager.syncIfDue(this@CallMonitorService)
                DeviceLogSync.flushAll(this@CallMonitorService)
                checkDailyStatus()
                checkPermissions()
                checkAdminStatus()
                sendHeartbeat()
            } catch (e: Exception) {
                Logger.log(this@CallMonitorService, "❌ Scheduled task error (5 min): ${e.message}")
            }
        }, 5, 5, TimeUnit.MINUTES)

        // Fast permission check — runs every 30 seconds so revocations are caught almost immediately
        permissionExecutor.scheduleAtFixedRate({
            try {
                checkPermissions()
            } catch (e: Exception) {
                Logger.log(this@CallMonitorService, "❌ Permission check error: ${e.message}")
            }
        }, 30, 30, TimeUnit.SECONDS)

        // Periodic call-log poll: reads today + yesterday, deduplicates, syncs new calls
        // Initial delay of 1 minute lets the startup backfill finish first
        syncExecutor.scheduleAtFixedRate({
            try {
                PeriodicSyncManager.sync(this@CallMonitorService)
            } catch (e: Exception) {
                Logger.log(this@CallMonitorService, "❌ Periodic sync error: ${e.message}")
            }
        }, 1, 15, TimeUnit.MINUTES)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        executor.shutdown()
        permissionExecutor.shutdown()
        syncExecutor.shutdown()
        val prefs = getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
        if (prefs.getBoolean("monitoringEnabled", false)) {
            val restartIntent = Intent(applicationContext, CallMonitorService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(restartIntent)
                } else {
                    startService(restartIntent)
                }
            } catch (e: Exception) {
                Logger.log(
                    applicationContext,
                    "⚠️ Immediate service restart failed (${e.javaClass.simpleName}) — scheduling WorkManager restart"
                )
                ServiceRestartScheduler.enqueueRestart(applicationContext)
            }
        }
        releaseMonitoringWakeLock()
        super.onDestroy()
    }

    private fun acquireMonitoringWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            monitoringWakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CallMonitor::MonitoringPartial"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Logger.log(this, "⚠️ WakeLock not acquired: ${e.message}")
        }
    }

    private fun releaseMonitoringWakeLock() {
        try {
            monitoringWakeLock?.let { wl ->
                if (wl.isHeld) wl.release()
            }
        } catch (_: Exception) {
        }
        monitoringWakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "CallMonitorChannel",
                "Call Monitor Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, "CallMonitorChannel")
            .setContentTitle("App Active")
            .setContentText("Your app is active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun retryPendingCalls() {
        val prefs = getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
        val serverUrl = prefs.getString("serverUrl", "") ?: ""
        val apiKey = prefs.getString("apiKey", "") ?: ""

        val pendingCalls = PendingCallsManager.getPendingCalls(this)
        if (pendingCalls.isEmpty()) return

        Logger.log(this, "🔁 Retrying ${pendingCalls.size} pending call(s)...")

        val succeeded        = mutableListOf<CallLogEntry>()
        val permanentlyFailed = mutableListOf<CallLogEntry>()  // 4xx — drop, never retry
        val transientlyFailed = mutableListOf<CallLogEntry>()  // 5xx/network — keep for next retry

        for (call in pendingCalls) {
            when (ApiClient.syncCall(serverUrl, apiKey, call, this)) {
                SyncResult.SUCCESS            -> succeeded.add(call)
                SyncResult.PERMANENT_FAILURE  -> permanentlyFailed.add(call)
                SyncResult.TRANSIENT_FAILURE  -> transientlyFailed.add(call)
            }
        }

        // Clear both successes and permanent failures — no point keeping 4xx in the queue
        val toRemove = succeeded + permanentlyFailed
        if (toRemove.isNotEmpty()) {
            PendingCallsManager.clearSyncedCalls(this, toRemove)
        }

        when {
            succeeded.isNotEmpty() && transientlyFailed.isEmpty() && permanentlyFailed.isEmpty() ->
                Logger.log(this, "✅ All ${succeeded.size} pending call(s) synced successfully")

            succeeded.isNotEmpty() ->
                Logger.log(this, "✅ Synced ${succeeded.size} | ⚠️ Dropped ${permanentlyFailed.size} (server rejected, 4xx) | ⏳ ${transientlyFailed.size} will retry next cycle")

            permanentlyFailed.isNotEmpty() && transientlyFailed.isEmpty() ->
                Logger.log(this, "⚠️ All ${permanentlyFailed.size} pending call(s) rejected by server (4xx) — removed from queue")

            transientlyFailed.isNotEmpty() ->
                Logger.log(this, "❌ ${transientlyFailed.size} call(s) still failing (network/server error) — will retry in 5 min")

            else ->
                Logger.log(this, "ℹ️ No pending calls processed")
        }

        // Update the backfill cursor if any were successfully synced
        succeeded.maxOfOrNull { it.timestamp }?.let { latest ->
            CallBackfillManager.markLastSyncedTimestamp(this, latest)
        }
    }


    private fun retryPendingStatuses() {
        val prefs = getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
        val serverUrl = prefs.getString("serverUrl", "") ?: ""
        val apiKey = prefs.getString("apiKey", "") ?: ""

        val pendingStatuses = PendingStatusManager.getPendingStatuses(this)
        if (pendingStatuses.isEmpty()) return

        Logger.log(this, "🔁 Retrying ${pendingStatuses.size} pending status event(s)...")
        val syncedStatuses = mutableListOf<ToggleLogEntry>()
        val failedStatuses  = mutableListOf<ToggleLogEntry>()

        for (status in pendingStatuses) {
            val success = ApiClient.sendStatus(
                serverUrl, apiKey,
                status.deviceId, status.employeeName,
                status.status, status.timestamp,
                this,
                status.reason
            )
            if (success) syncedStatuses.add(status) else failedStatuses.add(status)
        }

        if (syncedStatuses.isNotEmpty()) {
            Logger.log(this, "✅ Synced ${syncedStatuses.size} status event(s) successfully")
            PendingStatusManager.clearSyncedStatuses(this, syncedStatuses)
        }
        if (failedStatuses.isNotEmpty()) {
            Logger.log(this, "❌ ${failedStatuses.size} status event(s) still failing — will retry in 5 min (events: ${failedStatuses.joinToString { it.status }})")
        }
    }

    private fun retryPendingContacts() {
        val prefs = getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
        val serverUrl = prefs.getString("serverUrl", "") ?: ""
        val apiKey = prefs.getString("apiKey", "") ?: ""

        val pendingContacts = PendingContactsManager.getPendingContacts(this)
        if (pendingContacts.isEmpty()) return

        Logger.log(this, "🔁 Retrying ${pendingContacts.size} pending contact(s) in batches...")
        val syncedContacts  = mutableListOf<ContactEntry>()
        val failedContacts  = mutableListOf<ContactEntry>()
        val batchSize = 200
        val batches = pendingContacts.chunked(batchSize)

        for ((index, batch) in batches.withIndex()) {
            val success = ApiClient.syncContacts(serverUrl, apiKey, batch, this)
            if (success) {
                syncedContacts.addAll(batch)
            } else {
                failedContacts.addAll(batch)
                Logger.log(this, "❌ Contacts batch ${index + 1}/${batches.size} failed (${batch.size} contacts) — will retry next cycle")
            }
        }

        if (syncedContacts.isNotEmpty()) {
            Logger.log(this, "✅ Synced ${syncedContacts.size} contact(s) successfully")
            PendingContactsManager.clearSyncedContacts(this, syncedContacts)
        }
        if (failedContacts.isNotEmpty() && syncedContacts.isEmpty()) {
            Logger.log(this, "❌ All ${failedContacts.size} pending contact(s) still failing — will retry in 5 min")
        }
    }

    private fun checkDailyStatus() {
        val prefs = getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("monitoringEnabled", false)) return

        val serverUrl = prefs.getString("serverUrl", "") ?: ""
        val apiKey = prefs.getString("apiKey", "") ?: ""
        val deviceId = prefs.getString("deviceId", "") ?: ""
        val employeeName = prefs.getString("employeeName", "") ?: ""
        
        if (serverUrl.isEmpty() || deviceId.isEmpty()) return

        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentMinute = calendar.get(Calendar.MINUTE)
        
        // Check if it's 8:30 AM or later
        if (currentHour > 8 || (currentHour == 8 && currentMinute >= 30)) {
            val todayString = "${calendar.get(Calendar.YEAR)}-${calendar.get(Calendar.MONTH)}-${calendar.get(Calendar.DAY_OF_MONTH)}"
            val lastDailyOnDate = prefs.getString("lastDailyOnDate", "")
            
            if (todayString != lastDailyOnDate) {
                Logger.log(this, "8:30 AM Trigger: Sending daily ON status")
                val timestamp = System.currentTimeMillis()
                val success = ApiClient.sendStatus(serverUrl, apiKey, deviceId, employeeName, "ON", timestamp)
                
                if (success) {
                    prefs.edit().putString("lastDailyOnDate", todayString).apply()
                } else {
                    Logger.log(this, "Daily ON status failed, saving to pending statuses")
                    val entry = ToggleLogEntry(deviceId, employeeName, "ON", timestamp)
                    PendingStatusManager.savePendingStatus(this, entry)
                    // Still mark it as done for today so we don't spam the pending queue every 5 mins
                    prefs.edit().putString("lastDailyOnDate", todayString).apply()
                }
            }
        }
    }
    private fun checkPermissions() {
        val prefs = getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("monitoringEnabled", false)) return
        
        val serverUrl = prefs.getString("serverUrl", "") ?: ""
        val apiKey = prefs.getString("apiKey", "") ?: ""
        val deviceId = prefs.getString("deviceId", "") ?: ""
        val employeeName = prefs.getString("employeeName", "") ?: ""
        if (serverUrl.isEmpty() || deviceId.isEmpty()) return

        val criticalPermissions = mapOf(
            android.Manifest.permission.READ_CALL_LOG to "READ_CALL_LOG",
            android.Manifest.permission.READ_PHONE_STATE to "READ_PHONE_STATE",
            android.Manifest.permission.READ_CONTACTS to "READ_CONTACTS"
        )

        val currentlyGranted = criticalPermissions.filter { (permission, _) ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }.values.toSet()

        val currentlyRevoked = criticalPermissions.values.toSet() - currentlyGranted

        // Load previous state
        val previouslyRevokedStr = prefs.getString("previouslyRevokedPermissions", "") ?: ""
        val previouslyRevoked = if (previouslyRevokedStr.isEmpty()) emptySet()
                                else previouslyRevokedStr.split(",").toSet()

        val now = System.currentTimeMillis()

        // Detect newly revoked permissions (were granted, now denied)
        val newlyRevoked = currentlyRevoked - previouslyRevoked
        if (newlyRevoked.isNotEmpty()) {
            val reason = "Permissions revoked: ${newlyRevoked.joinToString(", ")}"
            Logger.log(this, "🚨 PERMISSION DENIED: $reason")
            Logger.log(this, "📤 Sending permission denied alert to database...")
            val success = ApiClient.sendStatus(serverUrl, apiKey, deviceId, employeeName, "PERMISSION_DENIED", now, this, reason)
            if (success) {
                Logger.log(this, "✅ Permission denied alert sent to database")
            } else {
                Logger.log(this, "⏳ Could not send alert — saving offline, will retry")
                PendingStatusManager.savePendingStatus(this, ToggleLogEntry(deviceId, employeeName, "PERMISSION_DENIED", now))
            }
        }

        // Detect restored permissions (were denied, now granted again)
        val newlyRestored = previouslyRevoked - currentlyRevoked
        if (newlyRestored.isNotEmpty()) {
            val reason = "Permissions restored: ${newlyRestored.joinToString(", ")}"
            Logger.log(this, "✅ PERMISSION RESTORED: $reason")
            Logger.log(this, "📤 Sending permission restored alert to database...")
            val success = ApiClient.sendStatus(serverUrl, apiKey, deviceId, employeeName, "PERMISSION_RESTORED", now, this, reason)
            if (success) {
                Logger.log(this, "✅ Permission restored alert sent to database")
            } else {
                Logger.log(this, "⏳ Could not send alert — saving offline, will retry")
                PendingStatusManager.savePendingStatus(this, ToggleLogEntry(deviceId, employeeName, "PERMISSION_RESTORED", now))
            }
        }

        // Always save the current revoked set for next comparison
        prefs.edit().putString("previouslyRevokedPermissions", currentlyRevoked.joinToString(",")).apply()
    }

    private fun checkAdminStatus() {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = MyDeviceAdminReceiver.getComponentName(this)

        val prefs = getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
        val wasAdminActive = prefs.getBoolean("adminWasActive", false)
        val isAdminActive = dpm.isAdminActive(componentName)

        // Persist current state for the next comparison
        prefs.edit().putBoolean("adminWasActive", isAdminActive).apply()

        if (wasAdminActive && !isAdminActive) {
            val serverUrl = prefs.getString("serverUrl", "") ?: ""
            val apiKey = prefs.getString("apiKey", "") ?: ""
            val deviceId = prefs.getString("deviceId", "") ?: ""
            val employeeName = prefs.getString("employeeName", "") ?: ""
            Logger.log(this, "🚨 Admin status changed: was active, now INACTIVE — alerting server")
            ApiClient.sendStatus(
                serverUrl, apiKey, deviceId, employeeName,
                "ADMIN_DISABLED", System.currentTimeMillis(), this,
                "Device admin was removed while the app was running (Settings → Device admin)."
            )
        }

        if (!wasAdminActive && isAdminActive) {
            val serverUrl = prefs.getString("serverUrl", "") ?: ""
            val apiKey = prefs.getString("apiKey", "") ?: ""
            val deviceId = prefs.getString("deviceId", "") ?: ""
            val employeeName = prefs.getString("employeeName", "") ?: ""
            Logger.log(this, "✅ Admin status changed: now ACTIVE — notifying server")
            ApiClient.sendStatus(
                serverUrl, apiKey, deviceId, employeeName,
                "ADMIN_ENABLED", System.currentTimeMillis(), this,
                "Device admin is active again (detected by monitoring service)."
            )
        }

        if (!isAdminActive) {
            Logger.log(this, "⚠️ Device Admin not active — app can be tampered with")
        }
    }

    private fun sendHeartbeat() {
        // Placeholder — extend later for periodic heartbeat pings
    }
}

