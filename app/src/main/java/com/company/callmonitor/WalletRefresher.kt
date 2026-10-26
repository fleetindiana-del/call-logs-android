package com.company.callmonitor

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import java.util.Locale

/**
 * The balance an admin allocated on the website, read with this handset's device
 * token. That token is what ties the phone to the driver who was given the money.
 */
object WalletRefresher {

    data class Wallet(val balance: Double, val name: String)

    fun formatInr(amount: Double): String {
        val fmt = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
        fmt.minimumFractionDigits = 2
        fmt.maximumFractionDigits = 2
        return fmt.format(amount)
    }

    fun fetch(context: Context): Wallet? {
        val token = EnrollmentPrefs.getDeviceToken(context)
        if (token.isBlank()) return null

        // The website is the ledger the admin just wrote. The fleet server is the
        // fallback the phone already uses for everything else, and it reads the
        // same wallet.
        val webBaseUrl = EnrollmentPrefs.getWebBaseUrl(context)
        if (webBaseUrl.isNotBlank()) {
            fetchAt(context, "${webBaseUrl.trimEnd('/')}/api/driver/device-wallet", token)?.let { return it }
        }
        val serverUrl = EnrollmentPrefs.getServerUrl(context)
        if (serverUrl.isBlank()) return null
        return fetchAt(context, "${serverUrl.trimEnd('/')}/api/driver/wallet", token)
    }

    private fun fetchAt(context: Context, url: String, token: String): Wallet? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Authorization", "Bearer $token")
            }
            if (conn.responseCode !in 200..299) {
                Logger.log(context, "💳 wallet refresh refused: HTTP ${conn.responseCode} $url")
                return null
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            if (!json.has("walletBalance")) return null
            Wallet(
                balance = json.optDouble("walletBalance", 0.0),
                name = json.optString("name", "")
            )
        } catch (e: Exception) {
            Logger.log(context, "💳 wallet refresh failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
