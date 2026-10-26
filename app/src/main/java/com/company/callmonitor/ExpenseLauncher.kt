package com.company.callmonitor

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ExpenseLauncher {

    fun open(context: Context) {
        val webBaseUrl = EnrollmentPrefs.getWebBaseUrl(context)
        if (webBaseUrl.isBlank()) {
            Toast.makeText(context, "Expense portal not configured. Please contact your administrator.", Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(context, "Opening your wallet…", Toast.LENGTH_SHORT).show()
        val appCtx = context.applicationContext
        Thread {
            // Sign the browser in as this handset's driver so the page shows the
            // advance that was allocated to them, not whoever last used Chrome.
            val ticket = requestTicket(appCtx, webBaseUrl)
            val url = if (ticket.isNullOrBlank()) {
                "${webBaseUrl.trimEnd('/')}/driver/add-expense"
            } else {
                "${webBaseUrl.trimEnd('/')}/api/driver/portal?ticket=${Uri.encode(ticket)}"
            }
            Handler(Looper.getMainLooper()).post {
                launch(context, url)
            }
        }.start()
    }

    private fun launch(context: Context, url: String) {
        try {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            customTabsIntent.launchUrl(context, Uri.parse(url))
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open expense portal: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestTicket(context: Context, webBaseUrl: String): String? {
        val token = EnrollmentPrefs.getDeviceToken(context)
        if (token.isBlank()) return null

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("${webBaseUrl.trimEnd('/')}/api/driver/portal-ticket").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 8_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            json.optString("ticket", "").ifBlank { null }
        } catch (e: Exception) {
            Logger.log(context, "💳 portal ticket failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
