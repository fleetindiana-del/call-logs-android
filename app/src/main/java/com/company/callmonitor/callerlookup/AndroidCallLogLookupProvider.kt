package com.company.callmonitor.callerlookup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * Looks up caller names from everything the phone already knows:
 *  1. Contacts (PhoneLookup + CommonDataKinds.Phone, digit-normalized)
 *  2. Call log CACHED_NAME (digit-normalized variants)
 *
 * Also supports harvesting every named contact / call-log entry that belongs
 * to a given series prefix — that is how we actually get people's names for a batch.
 */
class AndroidCallLogLookupProvider(
    private val context: Context
) : PhoneLookupProvider {
    override val id = "android-call-log-cache"
    override val label = "Android Contacts + CallLog.CACHED_NAME"

    override fun lookup(phoneNumber: String): PhoneLookupOutcome {
        val started = System.currentTimeMillis()
        val normalized = normalizeDigits(phoneNumber)
        return try {
            if (!hasContactsPermission() && !hasCallLogPermission()) {
                return PhoneLookupOutcome(
                    phoneNumber = normalized.ifBlank { phoneNumber },
                    status = "error",
                    durationMs = System.currentTimeMillis() - started,
                    error = "Missing READ_CONTACTS and READ_CALL_LOG permissions",
                    providerId = id,
                    providerLabel = label
                )
            }

            val contactName = lookupContact(normalized)
            if (!contactName.isNullOrBlank()) {
                return found(normalized, contactName, "contacts", started)
            }

            val cachedName = lookupCachedCallLogName(normalized)
            if (!cachedName.isNullOrBlank()) {
                return found(normalized, cachedName, "call_log_cached_name", started)
            }

            PhoneLookupOutcome(
                phoneNumber = normalized,
                status = "not_found",
                durationMs = System.currentTimeMillis() - started,
                providerId = id,
                providerLabel = label
            )
        } catch (security: SecurityException) {
            PhoneLookupOutcome(
                phoneNumber = normalized.ifBlank { phoneNumber },
                status = "error",
                durationMs = System.currentTimeMillis() - started,
                error = "Missing Android contact/call-log permission: ${security.message}",
                providerId = id,
                providerLabel = label
            )
        } catch (error: Exception) {
            PhoneLookupOutcome(
                phoneNumber = normalized.ifBlank { phoneNumber },
                status = "error",
                durationMs = System.currentTimeMillis() - started,
                error = error.message ?: error.javaClass.simpleName,
                providerId = id,
                providerLabel = label
            )
        }
    }

    /**
     * Returns every known named number on this device that belongs to [prefix]
     * (contacts + call-log cache). Keys are 10-digit Indian mobiles.
     */
    fun harvestNamedNumbersInSeries(prefix: String): Map<String, Pair<String, String>> {
        val out = LinkedHashMap<String, Pair<String, String>>()
        if (prefix.isBlank()) return out

        if (hasContactsPermission()) {
            try {
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        val raw = if (numberIdx >= 0) cursor.getString(numberIdx) else null
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                        val digits = normalizeDigits(raw)
                        if (digits.length == 10 && digits.startsWith(prefix) && !name.isNullOrBlank()) {
                            out.putIfAbsent(digits, name.trim() to "contacts")
                        }
                    }
                }
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        }

        if (hasCallLogPermission()) {
            try {
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME),
                    "${CallLog.Calls.CACHED_NAME} IS NOT NULL AND ${CallLog.Calls.CACHED_NAME} != ''",
                    null,
                    "${CallLog.Calls.DATE} DESC"
                )?.use { cursor ->
                    val numberIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                    val nameIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                    while (cursor.moveToNext()) {
                        val raw = if (numberIdx >= 0) cursor.getString(numberIdx) else null
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                        val digits = normalizeDigits(raw)
                        if (digits.length == 10 && digits.startsWith(prefix) && !name.isNullOrBlank()) {
                            out.putIfAbsent(digits, name.trim() to "call_log_cached_name")
                        }
                    }
                }
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        }

        return out
    }

    private fun found(
        phoneNumber: String,
        name: String,
        source: String,
        started: Long
    ) = PhoneLookupOutcome(
        phoneNumber = phoneNumber,
        status = "found",
        callerName = name.trim(),
        metadataJson = JSONObject().put("source", source).toString(),
        durationMs = System.currentTimeMillis() - started,
        providerId = id,
        providerLabel = label
    )

    private fun lookupContact(phoneNumber: String): String? {
        if (!hasContactsPermission() || phoneNumber.isBlank()) return null

        for (candidate in lookupVariants(phoneNumber)) {
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(candidate)
            )
            try {
                context.contentResolver.query(
                    uri,
                    arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                        if (idx >= 0) {
                            val name = cursor.getString(idx)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // Fallback: scan CommonDataKinds.Phone for last-10-digit match
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val raw = if (numberIdx >= 0) cursor.getString(numberIdx) else null
                    val digits = normalizeDigits(raw)
                    if (digits == phoneNumber || digits.endsWith(phoneNumber) || phoneNumber.endsWith(digits.takeLast(10))) {
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun lookupCachedCallLogName(phoneNumber: String): String? {
        if (!hasCallLogPermission() || phoneNumber.isBlank()) return null

        val variants = lookupVariants(phoneNumber)
        val placeholders = variants.joinToString(",") { "?" }
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER),
                "${CallLog.Calls.NUMBER} IN ($placeholders)",
                variants,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                while (cursor.moveToNext()) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (_: Exception) {
        }

        // Digits-normalized scan of recent call log (handles weird formatting)
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME),
                "${CallLog.Calls.CACHED_NAME} IS NOT NULL AND ${CallLog.Calls.CACHED_NAME} != ''",
                null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val numberIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                var scanned = 0
                while (cursor.moveToNext() && scanned < 3000) {
                    scanned++
                    val raw = if (numberIdx >= 0) cursor.getString(numberIdx) else null
                    val digits = normalizeDigits(raw)
                    if (digits == phoneNumber) {
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasCallLogPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        fun normalizeDigits(raw: String?): String {
            var digits = (raw ?: "").replace(Regex("\\D"), "")
            if (digits.length == 12 && digits.startsWith("91")) digits = digits.substring(2)
            if (digits.length == 11 && digits.startsWith("0")) digits = digits.substring(1)
            if (digits.length > 10) digits = digits.takeLast(10)
            return digits
        }

        fun lookupVariants(phoneNumber: String): Array<String> {
            val n = normalizeDigits(phoneNumber)
            if (n.isBlank()) return arrayOf(phoneNumber)
            return arrayOf(n, "+91$n", "91$n", "0$n", phoneNumber).distinct().toTypedArray()
        }
    }
}

object PhoneLookupProviderRegistry {
    fun get(context: Context, providerId: String): PhoneLookupProvider =
        when (providerId) {
            "android-call-log-cache" -> AndroidCallLogLookupProvider(context)
            else -> AndroidCallLogLookupProvider(context)
        }
}
