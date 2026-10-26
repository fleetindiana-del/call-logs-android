package com.company.callmonitor

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Device sign-in.
 *
 * Was an admin-issued, single-use enrollment code. That meant every
 * re-enrollment — a reinstall, a factory reset, cleared app data, a keystore the
 * phone could no longer decrypt — stranded the driver until an admin minted a
 * fresh code. Credentials are re-usable, so the driver now recovers unaided,
 * which is the entire reason for the change.
 *
 * The server keeps `/api/enrollment/redeem` for devices already enrolled that
 * way; nothing here breaks them.
 */
class EnrollmentActivity : AppCompatActivity() {

    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var etServerUrl: EditText
    private lateinit var btnEnroll: Button
    private lateinit var tvEnrollStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_enrollment)

        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        etServerUrl = findViewById(R.id.etServerUrl)
        btnEnroll = findViewById(R.id.btnEnroll)
        tvEnrollStatus = findViewById(R.id.tvEnrollStatus)

        // Pre-fill server URL from CallMonitorConfig (admin may have set it) or strings.xml default
        val savedServer = getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
            .getString("serverUrl", "")
        val defaultServer = if (savedServer.isNullOrBlank())
            getString(R.string.enrollment_server_url)
        else
            savedServer
        etServerUrl.setText(defaultServer)

        val advanced = findViewById<android.view.View>(R.id.layoutAdvanced)
        findViewById<TextView>(R.id.tvToggleAdvanced).setOnClickListener {
            advanced.visibility =
                if (advanced.visibility == android.view.View.VISIBLE) android.view.View.GONE
                else android.view.View.VISIBLE
        }

        btnEnroll.setOnClickListener { submit() }

        // Enter on the password field signs in, so the driver never has to find
        // the button behind the keyboard.
        etPassword.setOnEditorActionListener { _, _, _ ->
            submit()
            true
        }
    }

    private fun submit() {
        val username = etUsername.text.toString().trim()
        // Not trimmed: a trailing space may genuinely be part of a password, and
        // silently altering it produces an "incorrect password" no one can explain.
        val password = etPassword.text.toString()
        val server = etServerUrl.text.toString().trim()

        if (username.isBlank()) {
            Toast.makeText(this, "Please enter your username", Toast.LENGTH_SHORT).show()
            return
        }
        if (password.isBlank()) {
            Toast.makeText(this, "Please enter your password", Toast.LENGTH_SHORT).show()
            return
        }
        if (server.isBlank()) {
            Toast.makeText(this, "Please enter the server URL", Toast.LENGTH_SHORT).show()
            return
        }
        performLogin(username, password, server)
    }

    private fun performLogin(username: String, password: String, server: String) {
        btnEnroll.isEnabled = false
        tvEnrollStatus.setTextColor(android.graphics.Color.parseColor("#555555"))
        tvEnrollStatus.text = "Signing in…"

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: "UNKNOWN"

        Thread {
            try {
                val url = URL("${server.trimEnd('/')}/api/enrollment/login")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000

                val body = JSONObject().apply {
                    put("username", username)
                    put("password", password)
                    put("deviceId", deviceId)
                }.toString()

                OutputStreamWriter(conn.outputStream).use { it.write(body) }

                val responseCode = conn.responseCode
                val responseBody = if (responseCode in 200..299)
                    conn.inputStream.bufferedReader().readText()
                else
                    conn.errorStream?.bufferedReader()?.readText() ?: ""

                conn.disconnect()

                if (responseCode in 200..299) {
                    val json = JSONObject(responseBody)
                    handleSuccess(json, server, deviceId)
                } else {
                    val msg = try {
                        JSONObject(responseBody).optString("error", "Sign-in failed ($responseCode)")
                    } catch (_: Exception) {
                        // A 404 here almost always means the backend predates the
                        // login endpoint, which is worth saying rather than
                        // leaving the driver staring at a bare status code.
                        if (responseCode == 404)
                            "This server does not support sign-in yet — ask your administrator to update it."
                        else "Sign-in failed ($responseCode)"
                    }
                    runOnUiThread {
                        tvEnrollStatus.setTextColor(android.graphics.Color.parseColor("#C62828"))
                        tvEnrollStatus.text = msg
                        btnEnroll.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvEnrollStatus.setTextColor(android.graphics.Color.parseColor("#C62828"))
                    tvEnrollStatus.text = "Could not reach the server: ${e.message}"
                    btnEnroll.isEnabled = true
                }
            }
        }.start()
    }

    private fun handleSuccess(json: JSONObject, server: String, deviceId: String) {
        val employeeId = json.optString("employeeId", "")
        val employeeName = json.optString("employeeName", "")
        val role = json.optString("role", "employee")
        val deviceToken = json.optString("deviceToken", "")
        // optString returns "" when the key exists as empty — that must not wipe the URL the user typed.
        val serverUrl = json.optString("serverUrl", "")
            .ifBlank { server }
            .trimEnd('/')
        val apiKey = json.optString("apiKey", "")
        val webBaseUrl = json.optString("webBaseUrl", "")
        val actualDeviceId = json.optString("deviceId", deviceId)

        if (deviceToken.isBlank()) {
            runOnUiThread {
                tvEnrollStatus.setTextColor(android.graphics.Color.parseColor("#C62828"))
                tvEnrollStatus.text = "Sign-in error: server returned no device token"
                btnEnroll.isEnabled = true
            }
            return
        }
        if (serverUrl.isBlank()) {
            runOnUiThread {
                tvEnrollStatus.setTextColor(android.graphics.Color.parseColor("#C62828"))
                tvEnrollStatus.text = "Sign-in error: missing server URL"
                btnEnroll.isEnabled = true
            }
            return
        }

        val vehicle = json.optJSONObject("vehicle")
        val vehicleId = vehicle?.optString("id", "") ?: ""
        val vehicleRegistration = vehicle?.optString("registration", "") ?: ""

        val caps = json.optJSONObject("capabilities")
        val capCallMonitoring = caps?.optBoolean("callMonitoring", false) ?: false
        val capLocationTracking = caps?.optBoolean("locationTracking", false) ?: false
        val capExpenseManagement = caps?.optBoolean("expenseManagement", false) ?: false
        // Older servers don't send `shifts`; there, scheduled shifts rode on location tracking.
        val capShifts = if (caps?.has("shifts") == true) caps.optBoolean("shifts", false) else capLocationTracking

        EnrollmentPrefs.saveEnrollment(
            this,
            employeeId = employeeId,
            employeeName = employeeName,
            role = role,
            deviceToken = deviceToken,
            deviceId = actualDeviceId,
            serverUrl = serverUrl,
            apiKey = apiKey,
            webBaseUrl = webBaseUrl,
            vehicleId = vehicleId,
            vehicleRegistration = vehicleRegistration,
            capCallMonitoring = capCallMonitoring,
            capLocationTracking = capLocationTracking,
            capExpenseManagement = capExpenseManagement,
            capShifts = capShifts
        )
        // Seed the Contact Bank for this employee. Best-effort: if READ_CONTACTS has not
        // been granted yet, CallMonitorService's daily check picks it up once it is.
        if (capCallMonitoring) {
            val appCtx = applicationContext
            Thread { ContactSyncManager.syncNow(appCtx) }.start()
        }
        CallMonitorApp.registerFcmTokenForEnrollment(this)

        // Best-effort: appear on fleet-map immediately after enroll.
        if (capLocationTracking) {
            try {
                com.company.callmonitor.location.LocationApiClient.registerDevice(
                    this,
                    trackingStatus = "ENROLLED",
                    gpsEnabled = com.company.callmonitor.location.LocationEnvironment
                        .isLocationEnabled(this),
                    permissionStatus = com.company.callmonitor.location.LocationEnvironment
                        .permissionStatus(this)
                )
            } catch (_: Exception) {
            }
        }

        runOnUiThread {
            tvEnrollStatus.setTextColor(android.graphics.Color.parseColor("#2E7D32"))
            tvEnrollStatus.text = "Signed in — setting up…"
            navigateHome(role)
        }
    }

    private fun navigateHome(role: String) {
        val intent = when (role.lowercase()) {
            "driver" -> Intent(this, DriverHomeActivity::class.java)
            else -> Intent(this, EmployeeHomeActivity::class.java)
        }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
