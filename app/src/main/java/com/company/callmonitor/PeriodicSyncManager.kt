package com.company.callmonitor

import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Calendar

/**
 * Runs every 15 minutes to scan the phone's native call log for today + yesterday,
 * filters out calls already synced (dedup by timestamp|number|name), and sends
 * only new calls to the server.
 *
 * Fingerprint format: "<timestamp>|<phoneNumber>|<contactName>"
 * Stored in a flat file — one fingerprint per line — and pruned to the
 * current today+yesterday window on every run to keep the file small.
 */
object PeriodicSyncManager {

    private const val FINGERPRINTS_FILE = "synced_call_fingerprints.txt"

    fun sync(context: Context) {
        val prefs = context.getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("monitoringEnabled", false)) return

        val serverUrl = prefs.getString("serverUrl", "") ?: ""
        val apiKey    = prefs.getString("apiKey",    "") ?: ""
        val deviceId  = prefs.getString("deviceId",  "") ?: ""
        val employeeName = prefs.getString("employeeName", "") ?: ""

        if (serverUrl.isEmpty() || deviceId.isEmpty()) return

        // ── Window: start of yesterday 00:00:00 → now ─────────────────────────
        // Clamped to the oldest shift on record; readCallLog then drops anything
        // inside the window that falls outside a shift, so off-shift calls are
        // never collected even though the sweep itself still runs.
        val windowStart = maxOf(startOfYesterday(), ShiftManager.earliestWindowStart(context))
        val windowEnd   = System.currentTimeMillis()

        if (windowStart == Long.MAX_VALUE || windowStart > windowEnd) {
            Logger.log(context, "🔄 Periodic sync: no shift on record — nothing to collect")
            return
        }

        Logger.log(context, "🔄 Periodic sync: scanning call log since ${java.util.Date(windowStart)}")

        if (ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_CALL_LOG
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Logger.log(context, "❌ Periodic sync: READ_CALL_LOG permission not granted")
            return
        }

        // ── Read phone call log for the window ────────────────────────────────
        val callsInWindow = readCallLog(context, deviceId, employeeName, windowStart, windowEnd)

        if (callsInWindow.isEmpty()) {
            Logger.log(context, "✅ Periodic sync: no calls in window")
            return
        }

        // ── Load already-synced fingerprints, pruned to current window ────────
        val syncedFingerprints = loadFingerprints(context, windowStart)

        // ── Filter: keep only calls we have NOT synced yet ────────────────────
        val newCalls = callsInWindow.filter { call ->
            !isAlreadySynced(call, syncedFingerprints)
        }

        if (newCalls.isEmpty()) {
            Logger.log(context, "✅ Periodic sync: all ${callsInWindow.size} call(s) already synced — nothing to do")
            return
        }

        Logger.log(context, "📤 Periodic sync: ${newCalls.size} new call(s) to sync (${callsInWindow.size - newCalls.size} duplicate(s) skipped)")

        // ── Send new calls ────────────────────────────────────────────────────
        val justSynced   = mutableListOf<CallLogEntry>()
        val justFailed   = mutableListOf<CallLogEntry>()

        for (call in newCalls) {
            when (ApiClient.syncCall(serverUrl, apiKey, call, context)) {
                SyncResult.SUCCESS           -> {
                    justSynced.add(call)
                    CallBackfillManager.markLastSyncedTimestamp(context, call.timestamp)
                }
                SyncResult.PERMANENT_FAILURE -> { /* 4xx: server rejected — don't queue */ }
                SyncResult.TRANSIENT_FAILURE -> justFailed.add(call)
            }
        }

        // ── Persist fingerprints for successfully sent calls ──────────────────
        if (justSynced.isNotEmpty()) {
            val newFingerprints = syncedFingerprints + justSynced.map { fingerprint(it) }.toSet()
            saveFingerprints(context, newFingerprints, windowStart)
            Logger.log(context, "✅ Periodic sync: synced ${justSynced.size} call(s)")
        }

        // ── Failed calls go to the pending queue as usual ─────────────────────
        if (justFailed.isNotEmpty()) {
            Logger.log(context, "⏳ Periodic sync: ${justFailed.size} call(s) failed — saving to pending queue")
            for (call in justFailed) {
                PendingCallsManager.savePendingCall(context, call)
            }
        }
    }


    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Unique key for a call: timestamp + number + contact name */
    private fun fingerprint(entry: CallLogEntry): String =
        "${entry.timestamp}|${entry.phoneNumber}|${entry.contactName}"

    private fun isAlreadySynced(call: CallLogEntry, syncedFingerprints: Set<String>): Boolean {
        // We do a fuzzy match on the timestamp because PhoneStateReceiver might have used
        // its own tracked start time instead of the actual OS dbDate if it was slow to write.
        for (fp in syncedFingerprints) {
            val parts = fp.split('|')
            if (parts.size >= 3) {
                val fpTs = parts[0].toLongOrNull() ?: continue
                val fpNumber = parts[1]
                
                // If number matches, and timestamp is within 90 seconds, consider it synced
                if (fpNumber == call.phoneNumber && Math.abs(fpTs - call.timestamp) < 90000) {
                    return true
                }
            }
        }
        return false
    }

    /** Called by PhoneStateReceiver to prevent double-syncing */
    fun markCallAsSynced(context: Context, entry: CallLogEntry) {
        val windowStart = startOfYesterday()
        val synced = loadFingerprints(context, windowStart)
        synced.add(fingerprint(entry))
        saveFingerprints(context, synced, windowStart)
    }

    /** Midnight (00:00:00.000) of the previous calendar day */
    private fun startOfYesterday(): Long {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** Read call log in [from, to] range. Returns oldest-first list. */
    private fun readCallLog(
        context: Context,
        deviceId: String,
        employeeName: String,
        from: Long,
        to: Long
    ): List<CallLogEntry> {
        val calls = mutableListOf<CallLogEntry>()

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
                "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.DATE} <= ?",
                arrayOf(from.toString(), to.toString()),
                "${CallLog.Calls.DATE} ASC"
            ) ?: return calls

            cursor.use {
                while (it.moveToNext()) {
                    val number   = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                        ?.takeIf { n -> n.isNotBlank() } ?: "UNKNOWN"
                    val typeInt  = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                    val date     = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))

                    // Off-shift calls are not this app's business, whichever
                    // sweep happens to find them first.
                    if (!ShiftManager.wasOnShift(context, date)) continue

                    val duration = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                    val name     = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME))
                        ?.takeIf { n -> n.isNotBlank() } ?: "Unknown"

                    val callType = when (typeInt) {
                        CallLog.Calls.INCOMING_TYPE -> "INCOMING"
                        CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                        CallLog.Calls.MISSED_TYPE   -> "MISSED"
                        else                        -> "UNKNOWN"
                    }

                    calls.add(
                        CallLogEntry(
                            phoneNumber  = number,
                            callType     = callType,
                            duration     = duration,
                            timestamp    = date,
                            deviceId     = deviceId,
                            employeeName = employeeName,
                            contactName  = name
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Logger.log(context, "❌ Periodic sync: error reading call log — ${e.message}")
        }

        return calls
    }

    /**
     * Load stored fingerprints from file. Prunes any fingerprint whose
     * embedded timestamp falls outside the current window (i.e., older
     * than yesterday) so the file never grows unbounded.
     */
    private fun loadFingerprints(context: Context, windowStart: Long): MutableSet<String> {
        val file = File(context.filesDir, FINGERPRINTS_FILE)
        if (!file.exists()) return mutableSetOf()

        return try {
            file.readLines()
                .filter { line ->
                    // fingerprint format: "timestamp|number|name"
                    val ts = line.substringBefore('|').toLongOrNull() ?: return@filter false
                    ts >= windowStart   // prune anything older than the window
                }
                .toMutableSet()
        } catch (e: Exception) {
            mutableSetOf()
        }
    }

    /**
     * Persist the full set of fingerprints. Prunes entries older than the
     * window before writing so the file stays bounded in size.
     */
    private fun saveFingerprints(context: Context, fingerprints: Set<String>, windowStart: Long) {
        val file = File(context.filesDir, FINGERPRINTS_FILE)
        try {
            val pruned = fingerprints.filter { line ->
                val ts = line.substringBefore('|').toLongOrNull() ?: return@filter false
                ts >= windowStart
            }
            file.writeText(pruned.joinToString("\n"))
        } catch (e: Exception) {
            Logger.log(context, "❌ Periodic sync: could not save fingerprints — ${e.message}")
        }
    }
}
