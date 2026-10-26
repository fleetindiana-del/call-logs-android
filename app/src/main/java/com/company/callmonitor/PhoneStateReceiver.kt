package com.company.callmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

class PhoneStateReceiver : BroadcastReceiver() {

    companion object {
        private var lastState = TelephonyManager.CALL_STATE_IDLE
        private var callStartTime: Long = 0
        private var callStartDate: Long = 0
        private var isIncoming: Boolean = false
        private var savedNumber: String? = null

        /** Called on every service (re)start to avoid stale state from a prior session. */
        fun resetState() {
            lastState = TelephonyManager.CALL_STATE_IDLE
            callStartTime = 0L
            callStartDate = 0L
            isIncoming = false
            savedNumber = null
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        Logger.log(context, "PhoneStateRcv: Received intent ${intent.action}")
        
        if (intent.action == Intent.ACTION_NEW_OUTGOING_CALL) {
            val phoneNum = intent.extras?.getString(Intent.EXTRA_PHONE_NUMBER)
            Logger.log(context, "📞 PhoneStateRcv: Outgoing call to ${phoneNum ?: "unknown"}")
            if (!phoneNum.isNullOrEmpty()) {
                savedNumber = phoneNum
            }
            isIncoming = false
            callStartDate = System.currentTimeMillis()
        } else if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val stateStr = intent.extras?.getString(TelephonyManager.EXTRA_STATE)
            val number = intent.extras?.getString(TelephonyManager.EXTRA_INCOMING_NUMBER)
            

            
            var state = 0
            when (stateStr) {
                TelephonyManager.EXTRA_STATE_IDLE -> state = TelephonyManager.CALL_STATE_IDLE
                TelephonyManager.EXTRA_STATE_OFFHOOK -> state = TelephonyManager.CALL_STATE_OFFHOOK
                TelephonyManager.EXTRA_STATE_RINGING -> state = TelephonyManager.CALL_STATE_RINGING
            }

            onCallStateChanged(context, state, number)
        }
    }

    private fun onCallStateChanged(context: Context, state: Int, number: String?) {
        if (!number.isNullOrEmpty()) {
            savedNumber = number
        }

        if (lastState == state) {
            return
        }
        
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                isIncoming = true
                callStartTime = System.currentTimeMillis()
                callStartDate = callStartTime
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (lastState != TelephonyManager.CALL_STATE_RINGING) {
                    isIncoming = false
                    callStartTime = System.currentTimeMillis()
                    callStartDate = callStartTime
                } else {
                    isIncoming = true
                    callStartTime = System.currentTimeMillis()
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                val numToLog = savedNumber
                Logger.log(context, "📞 PhoneStateRcv: Call ended → logging call")
                
                if (lastState == TelephonyManager.CALL_STATE_RINGING) {
                    handleCall(context, numToLog, "MISSED", 0L, callStartDate)
                } else if (isIncoming) {
                    val duration = (System.currentTimeMillis() - callStartTime) / 1000
                    handleCall(context, numToLog, "INCOMING", duration, callStartDate)
                } else {
                    val duration = (System.currentTimeMillis() - callStartTime) / 1000
                    handleCall(context, numToLog, "OUTGOING", duration, callStartDate)
                }
                
                // Clear state for the next call
                savedNumber = null
            }
        }
        lastState = state
    }

    private fun handleCall(context: Context, number: String?, type: String, durationInSeconds: Long, callDate: Long) {
        val initialNumber = number ?: "UNKNOWN"
        
        Logger.log(context, "📋 Processing $type call — number: $initialNumber")

        val prefs = context.getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("monitoringEnabled", false)) {
            Logger.log(context, "PhoneStateRcv: Monitoring is disabled, ignoring call.")
            return
        }

        // The shift gate. Off shift the call is not recorded at all — not queued,
        // not uploaded later. The after-the-fact sweeps
        // (CallBackfillManager/PeriodicSyncManager) apply the same window test,
        // so this call stays off the server permanently.
        if (!ShiftManager.mayCollectNow(context)) {
            Logger.log(context, "PhoneStateRcv: Shift is OFF, call not recorded.")
            return
        }

        val serverUrl = prefs.getString("serverUrl", "") ?: ""
        val apiKey = prefs.getString("apiKey", "") ?: ""
        val deviceId = prefs.getString("deviceId", "") ?: ""
        val employeeName = prefs.getString("employeeName", "") ?: ""

        if (serverUrl.isBlank() || deviceId.isBlank()) {
            Logger.log(context, "⚠️ Config incomplete (serverUrl or deviceId empty) — call will be queued and retried once configured")
            // We don't have a full entry yet (contact lookup hasn't run), so just log and return.
            // The periodic sync / backfill will pick these up once configured.
            return
        }

        Thread {
            var finalNumber = initialNumber
            var finalDuration = durationInSeconds
            var finalTimestamp = callDate
            var contactName = "Unknown"
            
            // Try up to 3 times to find the log (OS might delay writing it)
            var attempts = 0
            var foundLog = false
            
            while (attempts < 3 && !foundLog) {
                try {
                    Thread.sleep(3000) // Wait 3 seconds per attempt
                } catch (e: Exception) {}
                
                try {
                    val cursor = context.contentResolver.query(
                        android.provider.CallLog.Calls.CONTENT_URI,
                        arrayOf(
                            android.provider.CallLog.Calls.NUMBER,
                            android.provider.CallLog.Calls.CACHED_NAME,
                            android.provider.CallLog.Calls.DURATION,
                            android.provider.CallLog.Calls.DATE
                        ),
                        null,
                        null,
                        android.provider.CallLog.Calls.DATE + " DESC LIMIT 1"
                    )

                    if (cursor != null && cursor.moveToFirst()) {
                        val dbNumber = cursor.getString(0)
                        val dbName = cursor.getString(1)
                        val dbDuration = cursor.getLong(2)
                        val dbDate = cursor.getLong(3)

                        // CallLog date is usually the start time. 
                        // Check if this log entry belongs to the current call (not an older call).
                        if (dbDate >= callDate - 15000) {
                            if (!dbNumber.isNullOrBlank() && dbNumber != "UNKNOWN") {
                                finalNumber = dbNumber
                            }
                            foundLog = true
                            
                            if (!dbName.isNullOrBlank()) {
                                contactName = dbName
                            }
                            finalDuration = dbDuration
                            finalTimestamp = dbDate
                        }
                        cursor.close()
                    }
                } catch (e: Exception) {
                    Logger.log(context, "⚠️ Call log not yet written by OS (attempt $attempts/3) — retrying...")
                }
                attempts++
            }

            // Fallback for contact name if CallLog CACHED_NAME is empty
            if (contactName == "Unknown" && finalNumber != "UNKNOWN") {
                try {
                    val uri = android.net.Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(finalNumber))
                    val cursor = context.contentResolver.query(uri, arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                    if (cursor != null && cursor.moveToFirst()) {
                        val name = cursor.getString(0)
                        if (!name.isNullOrBlank()) contactName = name
                        cursor.close()
                    }
                } catch (e: Exception) {}
            }

            val entry = CallLogEntry(
                phoneNumber = finalNumber,
                callType = type,
                duration = finalDuration,
                timestamp = finalTimestamp,
                deviceId = deviceId,
                employeeName = employeeName,
                contactName = contactName
            )
            
            Logger.log(context, "📤 Uploading call: $contactName ($finalNumber) · $type · ${finalDuration}s")
            Logger.log(context, "   ↳ deviceId=\"$deviceId\" employeeName=\"$employeeName\" callType=\"$type\"")
            when (ApiClient.syncCall(serverUrl, apiKey, entry, context)) {
                SyncResult.SUCCESS -> {
                    Logger.log(context, "✅ Successfully saved to database")
                    CallBackfillManager.markLastSyncedTimestamp(context, entry.timestamp)
                    PeriodicSyncManager.markCallAsSynced(context, entry)
                }
                SyncResult.PERMANENT_FAILURE -> {
                    Logger.log(context, "🚫 Server rejected call (4xx) — check in-app log above for HTTP code & body")
                }
                SyncResult.TRANSIENT_FAILURE -> {
                    Logger.log(context, "❌ Upload failed (network/server error) — $contactName ($finalNumber) · saved to pending queue, will retry automatically")
                    PendingCallsManager.savePendingCall(context, entry)
                }
            }
        }.start()
    }
}
