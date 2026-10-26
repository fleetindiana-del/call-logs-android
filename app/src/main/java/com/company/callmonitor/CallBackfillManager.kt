package com.company.callmonitor

import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat

object CallBackfillManager {

    /**
     * Saves the timestamp of the most recently synced call so we know
     * where to resume from after a restart.
     */
    fun markLastSyncedTimestamp(context: Context, timestamp: Long) {
        context.getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
            .edit()
            .putLong("lastSyncedCallTimestamp", timestamp)
            .apply()
    }

    /**
     * Called every time the service starts (reboot, update, force-stop, etc.).
     * Reads the phone's call log from the last synced timestamp onward and
     * pushes any missed calls to the server.
     */
    fun backfillMissedCalls(context: Context) {
        val prefs = context.getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("monitoringEnabled", false)) return

        val serverUrl = prefs.getString("serverUrl", "") ?: ""
        val apiKey = prefs.getString("apiKey", "") ?: ""
        val deviceId = prefs.getString("deviceId", "") ?: ""
        val employeeName = prefs.getString("employeeName", "") ?: ""

        if (serverUrl.isEmpty() || deviceId.isEmpty()) return

        // Default: look back 24 hours if we've never synced before
        val lastSynced = prefs.getLong(
            "lastSyncedCallTimestamp",
            System.currentTimeMillis() - (24 * 60 * 60 * 1000L)
        )

        // Never reach back past the oldest shift we know about: everything before
        // it is off-shift by definition and must not be collected.
        val since = maxOf(lastSynced, ShiftManager.earliestWindowStart(context))
        if (since == Long.MAX_VALUE) {
            Logger.log(context, "🔍 Backfill: no shift on record — nothing to collect")
            return
        }

        Logger.log(context, "🔍 Backfill: checking for missed calls since ${java.util.Date(since)}")

        // getCallLogSince drops anything that falls outside a shift window, so a
        // call placed after clocking off is skipped even when it sits inside the
        // resume range.
        val missedCalls = getCallLogSince(context, deviceId, employeeName, since)

        if (missedCalls.isEmpty()) {
            Logger.log(context, "✅ Backfill: no missed calls found")
            return
        }

        Logger.log(context, "📋 Backfill: found ${missedCalls.size} call(s) — syncing now...")

        val syncedCalls = mutableListOf<CallLogEntry>()
        val failedCalls  = mutableListOf<CallLogEntry>()

        for (call in missedCalls) {
            val result = ApiClient.syncCall(serverUrl, apiKey, call, context)
            when (result) {
                SyncResult.SUCCESS           -> {
                    syncedCalls.add(call)
                    markLastSyncedTimestamp(context, call.timestamp)
                    PeriodicSyncManager.markCallAsSynced(context, call)
                }
                SyncResult.PERMANENT_FAILURE -> { /* 4xx — skip, don't queue */ }
                SyncResult.TRANSIENT_FAILURE -> failedCalls.add(call)
            }
        }

        if (syncedCalls.isNotEmpty()) {
            Logger.log(context, "✅ Backfill: synced ${syncedCalls.size} call(s)")
        }
        if (failedCalls.isNotEmpty()) {
            Logger.log(context, "⏳ Backfill: ${failedCalls.size} call(s) failed (transient) — saving to pending queue")
            for (call in failedCalls) {
                PendingCallsManager.savePendingCall(context, call)
            }
        }
    }

    private fun getCallLogSince(
        context: Context,
        deviceId: String,
        employeeName: String,
        sinceTimestamp: Long
    ): List<CallLogEntry> {
        val calls = mutableListOf<CallLogEntry>()

        if (ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_CALL_LOG
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Logger.log(context, "❌ Backfill: READ_CALL_LOG permission not granted")
            return calls
        }

        var skippedOffShift = 0

        try {
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.CACHED_NAME
                ),
                "${CallLog.Calls.DATE} > ?",
                arrayOf(sinceTimestamp.toString()),
                "${CallLog.Calls.DATE} ASC"   // Oldest first so timestamps are saved in order
            ) ?: return calls

            cursor.use {
                while (it.moveToNext()) {
                    val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                        ?.takeIf { n -> n.isNotBlank() } ?: "UNKNOWN"
                    val typeInt = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                    val date = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))

                    // The shift gate, applied per call rather than per sweep: a
                    // phone that was dead through yesterday's shift still gets
                    // those calls, while calls made after clocking off are never
                    // collected.
                    if (!ShiftManager.wasOnShift(context, date)) {
                        skippedOffShift++
                        continue
                    }

                    val duration = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                    val name = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME))
                        ?.takeIf { n -> n.isNotBlank() } ?: "Unknown"

                    // Map Android CallLog type integer → string used by this app
                    val callType = when (typeInt) {
                        CallLog.Calls.INCOMING_TYPE -> "INCOMING"
                        CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                        CallLog.Calls.MISSED_TYPE   -> "MISSED"
                        else -> null  // voicemail, rejected, blocked — skip
                    } ?: continue

                    calls.add(
                        CallLogEntry(
                            phoneNumber = number,
                            callType = callType,
                            duration = duration,
                            timestamp = date,
                            deviceId = deviceId,
                            employeeName = employeeName,
                            contactName = name
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Logger.log(context, "❌ Backfill: error reading call log — ${e.message}")
        }

        if (skippedOffShift > 0) {
            Logger.log(context, "🚫 Backfill: skipped $skippedOffShift off-shift call(s)")
        }

        return calls
    }
}
