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
import com.company.callmonitor.callerlookup.CallerLookupWorker
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class EnrollmentActivity : AppCompatActivity() {

    private lateinit var etEnrollmentCode: EditText
    private lateinit var etServerUrl: EditText
    private lateinit var btnEnroll: Button
    private lateinit var tvEnrollStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_enrollment)

        etEnrollmentCode = findViewById(R.id.etEnrollmentCode)
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

        btnEnroll.setOnClickListener {
            val code = etEnrollmentCode.text.toString().trim()
            val server = etServerUrl.text.toString().trim()
            if (code.isBlank()) {
                Toast.makeText(this, "Please enter an enrollment code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (server.isBlank()) {
                Toast.makeText(this, "Please enter the server URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            performEnrollment(code, server)
        }
    }

    private fun performEnrollment(code: String, server: String) {
        btnEnroll.isEnabled = false
        tvEnrollStatus.text = "Enrolling…"

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: "UNKNOWN"

        Thread {
            try {
                val url = URL("${server.trimEnd('/')}/api/enrollment/redeem")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000

                val body = JSONObject().apply {
                    put("code", code)
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
                        JSONObject(responseBody).optString("error", "Enrollment failed ($responseCode)")
                    } catch (_: Exception) {
                        "Enrollment failed ($responseCode)"
                    }
                    runOnUiThread {
                        tvEnrollStatus.text = msg
                        btnEnroll.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvEnrollStatus.text = "Error: ${e.message}"
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
                tvEnrollStatus.text = "Enrollment error: server returned no device token"
                btnEnroll.isEnabled = true
            }
            return
        }
        if (serverUrl.isBlank()) {
            runOnUiThread {
                tvEnrollStatus.text = "Enrollment error: missing server URL"
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
            capExpenseManagement = capExpenseManagement
        )
        // Seed the Contact Bank for this employee. Best-effort: if READ_CONTACTS has not
        // been granted yet, CallMonitorService's daily check picks it up once it is.
        if (capCallMonitoring) {
            val appCtx = applicationContext
            Thread { ContactSyncManager.syncNow(appCtx) }.start()
        }
        CallerLookupWorker.enqueuePending(this)
        CallerLookupWorker.schedulePeriodicPoll(this)
        CallMonitorApp.registerFcmTokenForEnrollment(this)

        // Best-effort: appear on fleet-map immediately after enroll.
        if (capLocationTracking) {
            try {
                com.company.callmonitor.location.LocationApiClient.registerDevice(
                    this,
                    trackingStatus = "ENROLLED",
                    gpsEnabled = false
                )
            } catch (_: Exception) {
            }
        }

        runOnUiThread {
            tvEnrollStatus.text = "Enrolled successfully!"
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
