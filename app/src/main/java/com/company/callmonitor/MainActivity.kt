package com.company.callmonitor

import android.Manifest
import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.graphics.Color
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.company.callmonitor.callerlookup.CallerLookupActivity
import com.company.callmonitor.location.LocationDutyController
import com.company.callmonitor.location.LocationTrackingService

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var etServerUrl: EditText
    private lateinit var etDeviceId: EditText
    private lateinit var etEmployeeName: EditText
    private lateinit var etApiKey: EditText
    private lateinit var switchMonitoring: SwitchCompat
    private lateinit var switchLocationTracking: SwitchCompat
    private lateinit var tvStatus: TextView
    private lateinit var tvLocationStatus: TextView
    private lateinit var tvLogs: TextView
    private lateinit var btnRefreshLogs: Button
    private lateinit var btnClearLogs: Button
    private lateinit var btnSave: Button
    private lateinit var btnTest: Button
    private lateinit var btnCallerLookup: Button

    // Guards against re-entrant listener calls when we programmatically flip the switch.
    private var suppressToggleListener = false
    private var suppressLocationToggleListener = false

    /**
     * Runs [block] and swallows any exception, logging it instead of crashing.
     * Used to wrap side-effectful work triggered from the monitoring toggle so a
     * failure in one subsystem (e.g. OEM intent not available) can never crash the app.
     */
    private inline fun safely(tag: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            android.util.Log.e("MainActivity", "$tag failed: ${t.javaClass.simpleName}: ${t.message}", t)
            try {
                Logger.log(this, "⚠️ $tag failed: ${t.javaClass.simpleName}: ${t.message}")
            } catch (_: Throwable) { }
        }
    }

    // Device Admin activation result handler
    private val adminResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Logger.log(this, "✅ Device Admin successfully activated")
            refreshLogs()
        } else {
            Logger.log(this, "❌ Device Admin was rejected — app can be tampered with")
            refreshLogs()
            showAdminRequiredDialog()
        }
        refreshAppStatusLabel()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Mandatory OEM onboarding — must complete before anything else
        if (OemPowerSettingsHelper.isAggressivePowerOem() && !OemOnboardingActivity.isCompleted(this)) {
            startActivity(Intent(this, OemOnboardingActivity::class.java))
        }

        // Set the root view to black and make the actual layout invisible to hide the app content
        val contentView = findViewById<android.view.ViewGroup>(android.R.id.content)
        contentView.setBackgroundColor(android.graphics.Color.BLACK)
        contentView.getChildAt(0)?.visibility = android.view.View.INVISIBLE

        showPasswordDialog()

        prefs = getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)

        etServerUrl = findViewById(R.id.etServerUrl)
        etDeviceId = findViewById(R.id.etDeviceId)
        etEmployeeName = findViewById(R.id.etEmployeeName)
        etApiKey = findViewById(R.id.etApiKey)
        switchMonitoring = findViewById(R.id.switchMonitoring)
        switchLocationTracking = findViewById(R.id.switchLocationTracking)
        tvStatus = findViewById(R.id.tvStatus)
        tvLocationStatus = findViewById(R.id.tvLocationStatus)
        tvLogs = findViewById(R.id.tvLogs)
        btnRefreshLogs = findViewById(R.id.btnRefreshLogs)
        btnClearLogs = findViewById(R.id.btnClearLogs)
        btnSave = findViewById(R.id.btnSave)
        btnTest = findViewById(R.id.btnTest)
        btnCallerLookup = findViewById(R.id.btnCallerLookup)

        // Let the logs pane scroll: outer NestedScrollView must not steal vertical drags
        findViewById<ScrollView>(R.id.scrollLogs)?.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        loadConfig()
        refreshLogs()

        btnRefreshLogs.setOnClickListener {
            refreshLogs()
            Thread {
                DeviceLogSync.flushAll(applicationContext)
            }.start()
        }
        btnClearLogs.setOnClickListener {
            Logger.clearLogs(this)
            refreshLogs()
        }

        if (etDeviceId.text.toString().isEmpty()) {
            etDeviceId.setText(getDeviceIdentifier())
        }

        btnSave.setOnClickListener {
            saveConfig()
            Toast.makeText(this, "Configuration Saved", Toast.LENGTH_SHORT).show()
        }

        btnTest.setOnClickListener {
            testConnection()
        }

        btnCallerLookup.setOnClickListener {
            startActivity(Intent(this, CallerLookupActivity::class.java))
        }

        updateStatusText()
        setupLocationTrackingToggle()

        switchMonitoring.setOnCheckedChangeListener { _, isChecked ->
            if (suppressToggleListener) return@setOnCheckedChangeListener

            val serverUrl = etServerUrl.text.toString()
            val apiKey = etApiKey.text.toString()
            val deviceId = etDeviceId.text.toString()
            val employeeName = etEmployeeName.text.toString()
            val timestamp = System.currentTimeMillis()
            
            try {
                if (isChecked) {
                    if (checkAndRequestPermissions()) {
                        safely("requestBatteryOptimization") { requestBatteryOptimization() }
                        safely("requestExactAlarms") { requestExactAlarmsIfNeeded() }
                        safely("promptAggressiveOemKeepAlive") { promptAggressiveOemKeepAliveIfNeeded() }
                        safely("startMonitoringService") { startMonitoringService() }
                        safely("CallSyncScheduler.schedule") { CallSyncScheduler.schedule(this) }
                        safely("prefs.putMonitoringEnabled") {
                            prefs.edit().putBoolean("monitoringEnabled", true).apply()
                        }
                        safely("refreshAppStatusLabel") { refreshAppStatusLabel() }
                        if (serverUrl.isNotEmpty() && deviceId.isNotEmpty()) {
                            Logger.log(this, "🟢 Sending app active status ON to database...")
                            Thread {
                                try {
                                    val success = ApiClient.sendStatus(serverUrl, apiKey, deviceId, employeeName, "ON", timestamp, this)
                                    if (success) {
                                        Logger.log(this, "✅ App active status ON sent successfully")
                                    } else {
                                        Logger.log(this, "⏳ App active status ON could not be sent — saved offline, will retry automatically")
                                        val entry = ToggleLogEntry(deviceId, employeeName, "ON", timestamp)
                                        PendingStatusManager.savePendingStatus(this, entry)
                                    }
                                    runOnUiThread { refreshLogs() }
                                    syncDeviceContacts(serverUrl, apiKey, deviceId, employeeName)
                                } catch (e: Exception) {
                                    android.util.Log.e("MainActivity", "status ON thread: ${e.message}", e)
                                }
                            }.start()
                        } else {
                            Logger.log(this, "⚠️ Server URL or Device ID not configured — status not sent")
                        }
                    } else {
                        // Permissions not yet granted — reset the toggle without re-triggering this listener
                        suppressToggleListener = true
                        switchMonitoring.isChecked = false
                        suppressToggleListener = false
                    }
                } else {
                    safely("stopMonitoringService") { stopMonitoringService() }
                    safely("CallSyncScheduler.cancel") { CallSyncScheduler.cancel(this) }
                    safely("prefs.putMonitoringDisabled") {
                        prefs.edit().putBoolean("monitoringEnabled", false).apply()
                    }
                    safely("refreshAppStatusLabel") { refreshAppStatusLabel() }
                    if (serverUrl.isNotEmpty() && deviceId.isNotEmpty()) {
                        Logger.log(this, "🔴 Sending app active status OFF to database...")
                        Thread {
                            try {
                                val success = ApiClient.sendStatus(serverUrl, apiKey, deviceId, employeeName, "OFF", timestamp, this)
                                if (success) {
                                    Logger.log(this, "✅ App active status OFF sent successfully")
                                } else {
                                    Logger.log(this, "⏳ App active status OFF could not be sent — saved offline, will retry automatically")
                                    val entry = ToggleLogEntry(deviceId, employeeName, "OFF", timestamp)
                                    PendingStatusManager.savePendingStatus(this, entry)
                                }
                                runOnUiThread { refreshLogs() }
                            } catch (e: Exception) {
                                android.util.Log.e("MainActivity", "status OFF thread: ${e.message}", e)
                            }
                        }.start()
                    } else {
                        Logger.log(this, "⚠️ Server URL or Device ID not configured — status not sent")
                    }
                }
            } catch (t: Throwable) {
                // Absolute safety net — a crash here would bring down the whole app when the
                // user just taps a switch. Surface the failure but keep the app alive.
                android.util.Log.e("MainActivity", "Monitoring toggle failed: ${t.javaClass.simpleName}: ${t.message}", t)
                try {
                    Logger.log(this, "❌ Monitoring toggle failed: ${t.javaClass.simpleName}: ${t.message}")
                } catch (_: Throwable) { }
                try {
                    Toast.makeText(
                        this,
                        "Could not fully enable monitoring: ${t.javaClass.simpleName}. Check logs.",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (_: Throwable) { }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check onboarding whenever user returns to this activity
        if (OemPowerSettingsHelper.isAggressivePowerOem() && !OemOnboardingActivity.isCompleted(this)) {
            startActivity(Intent(this, OemOnboardingActivity::class.java))
        }
        refreshLogs()
        refreshLocationTrackingUi()
        checkForRevokedPermissions()
        // Starting FGS from onCreate breaks on Android 12+ (not foreground yet). Retry after we're visible.
        Handler(Looper.getMainLooper()).post {
            if (isFinishing || isDestroyed) return@post
            if (prefs.getBoolean("monitoringEnabled", false)) {
                if (!CallMonitorService.isRunning) {
                    ensureCallMonitorServiceRunning()
                }
                try {
                    CallSyncScheduler.schedule(this)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "CallSyncScheduler: ${e.message}")
                }
                // After force-stop or OEM kill, FCM cannot run until the user opens the app; enqueue
                // a one-time sync whenever the UI becomes visible so logs catch up immediately.
                try {
                    val req = OneTimeWorkRequestBuilder<CallSyncWorker>().apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        }
                    }.build()
                    WorkManager.getInstance(this).enqueueUniqueWork(
                        "resume-sync-after-open",
                        ExistingWorkPolicy.REPLACE,
                        req
                    )
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Resume sync work: ${e.message}")
                }
            }
            refreshAppStatusLabel()
        }
    }

    // ---------------------------------------------------------------- location

    /**
     * Admin switch for GPS tracking. Drives the same [LocationDutyController]
     * intent the website's start/end route commands use, so a later command from
     * the website still wins — this is a local override, not a separate mode.
     */
    private fun setupLocationTrackingToggle() {
        refreshLocationTrackingUi()

        switchLocationTracking.setOnCheckedChangeListener { _, isChecked ->
            if (suppressLocationToggleListener) return@setOnCheckedChangeListener

            if (isChecked) {
                if (!ensureLocationPermissions()) {
                    // Permission request is in flight — the switch flips back on
                    // its own once the user grants it.
                    setLocationSwitchChecked(false)
                    return@setOnCheckedChangeListener
                }
                applyLocationTracking(true, "admin_toggle")
            } else {
                applyLocationTracking(false, "admin_toggle")
            }
        }
    }

    /** Start/stop off the main thread — both paths hit the network and Room. */
    private fun applyLocationTracking(enable: Boolean, reason: String) {
        switchLocationTracking.isEnabled = false
        Thread {
            val ok = try {
                if (enable) {
                    LocationDutyController.startTracking(this, reason)
                } else {
                    LocationDutyController.stopTracking(this, reason)
                }
            } catch (t: Throwable) {
                android.util.Log.e("MainActivity", "location toggle failed: ${t.message}", t)
                safely("logLocationToggleFailure") {
                    Logger.log(this, "❌ Location toggle failed: ${t.javaClass.simpleName}: ${t.message}")
                }
                false
            }
            runOnUiThread {
                if (enable && !ok) {
                    Toast.makeText(
                        this,
                        "Could not start location tracking — check capability and permissions.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                refreshLocationTrackingUi()
                refreshLogs()
            }
        }.start()
    }

    private fun setLocationSwitchChecked(checked: Boolean) {
        suppressLocationToggleListener = true
        switchLocationTracking.isChecked = checked
        suppressLocationToggleListener = false
    }

    private fun refreshLocationTrackingUi() {
        if (!::switchLocationTracking.isInitialized) return

        val enrolled = EnrollmentPrefs.hasLocationTracking(this)
        val desired = LocationDutyController.isTrackingDesired(this)
        val permitted = LocationDutyController.hasLocationPermission(this)
        val running = LocationTrackingService.isRunning

        switchLocationTracking.isEnabled = enrolled
        setLocationSwitchChecked(enrolled && desired)

        val (text, color) = when {
            !enrolled ->
                "Location tracking is not enabled for this device" to "#757575"
            !desired ->
                "Status: Tracking OFF — no GPS points are being recorded" to "#757575"
            !permitted ->
                "Status: Tracking ON — waiting for location permission" to "#EF6C00"
            running ->
                "Status: Tracking ON — recording route" to "#2E7D32"
            else ->
                "Status: Tracking ON — starting service…" to "#EF6C00"
        }
        tvLocationStatus.text = text
        tvLocationStatus.setTextColor(Color.parseColor(color))
    }

    /**
     * @return true when location can be tracked right now; false when a permission
     * request was launched instead (tracking resumes in [onRequestPermissionsResult]).
     */
    private fun ensureLocationPermissions(): Boolean {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            try {
                ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_LOCATION_PERMS)
            } catch (e: Exception) {
                Logger.log(this, "⚠️ Could not request location permissions: ${e.message}")
            }
            return false
        }

        // Background access is what keeps the route recording once the screen is
        // off; ask for it, but do not block tracking on the answer.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            try {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    REQ_BG_LOCATION_PERMS
                )
            } catch (e: Exception) {
                Logger.log(this, "⚠️ Could not request background location: ${e.message}")
            }
        }
        return true
    }

    private fun checkForRevokedPermissions() {
        if (!prefs.getBoolean("monitoringEnabled", false)) return

        val criticalPermissions = mapOf(
            android.Manifest.permission.READ_CALL_LOG to "READ_CALL_LOG",
            android.Manifest.permission.READ_PHONE_STATE to "READ_PHONE_STATE",
            android.Manifest.permission.READ_CONTACTS to "READ_CONTACTS"
        )

        val revokedPermissions = criticalPermissions.filter { (permission, _) ->
            androidx.core.content.ContextCompat.checkSelfPermission(this, permission) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }.values.toList()

        if (revokedPermissions.isEmpty()) return

        val reason = "Permissions revoked: ${revokedPermissions.joinToString(", ")}"

        // Only alert once per hour to avoid spamming
        val lastAlert = prefs.getLong("lastPermissionAlertTime", 0L)
        val now = System.currentTimeMillis()
        if (now - lastAlert < 60 * 60 * 1000L) return

        Logger.log(this, "🚨 PERMISSION DENIED: $reason")
        Logger.log(this, "📤 Sending permission denied alert to database...")
        refreshLogs()

        val serverUrl = prefs.getString("serverUrl", "") ?: ""
        val apiKey = prefs.getString("apiKey", "") ?: ""
        val deviceId = prefs.getString("deviceId", "") ?: ""
        val employeeName = prefs.getString("employeeName", "") ?: ""

        if (serverUrl.isNotEmpty() && deviceId.isNotEmpty()) {
            Thread {
                val success = ApiClient.sendStatus(
                    serverUrl, apiKey, deviceId, employeeName,
                    "PERMISSION_DENIED", now, this, reason
                )
                if (success) {
                    Logger.log(this, "✅ Permission denied alert sent to database")
                    prefs.edit().putLong("lastPermissionAlertTime", now).apply()
                } else {
                    Logger.log(this, "⏳ Permission denied alert saved offline, will retry")
                    val entry = ToggleLogEntry(deviceId, employeeName, "PERMISSION_DENIED", now)
                    PendingStatusManager.savePendingStatus(this, entry)
                    prefs.edit().putLong("lastPermissionAlertTime", now).apply()
                }
                runOnUiThread { refreshLogs() }
            }.start()
        }
    }

    private fun showPasswordDialog() {
        val input = EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        input.hint = "Enter Password"
        input.setTextColor(android.graphics.Color.WHITE)
        input.setHintTextColor(android.graphics.Color.GRAY)

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Authentication Required")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Submit", null)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))

        dialog.setOnShowListener {
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.setOnClickListener {
                if (input.text.toString() == "indiana@132") {
                    val contentView = findViewById<android.view.ViewGroup>(android.R.id.content)
                    contentView.setBackgroundColor(android.graphics.Color.WHITE)
                    contentView.getChildAt(0)?.visibility = android.view.View.VISIBLE
                    dialog.dismiss()
                } else {
                    Toast.makeText(this, "Incorrect Password", Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    private fun refreshLogs() {
        if (::tvLogs.isInitialized) {
            tvLogs.text = Logger.getLogs(this)
        }
    }

    private fun loadConfig() {
        etServerUrl.setText(prefs.getString("serverUrl", ""))
        etDeviceId.setText(prefs.getString("deviceId", ""))
        etEmployeeName.setText(prefs.getString("employeeName", ""))
        etApiKey.setText(prefs.getString("apiKey", ""))
        switchMonitoring.isChecked = prefs.getBoolean("monitoringEnabled", false)
    }

    private fun saveConfig() {
        prefs.edit()
            .putString("serverUrl", etServerUrl.text.toString())
            .putString("deviceId", etDeviceId.text.toString())
            .putString("employeeName", etEmployeeName.text.toString())
            .putString("apiKey", etApiKey.text.toString())
            .putBoolean("monitoringEnabled", switchMonitoring.isChecked)
            .apply()
    }

    private fun getDeviceIdentifier(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
    }

    private fun checkAndRequestPermissions(): Boolean {
        val requiredPermissions = mutableListOf(
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.PROCESS_OUTGOING_CALLS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            requiredPermissions.add(Manifest.permission.FOREGROUND_SERVICE)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requiredPermissions.add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            requiredPermissions.add(Manifest.permission.FOREGROUND_SERVICE_PHONE_CALL)
            requiredPermissions.add(Manifest.permission.MANAGE_OWN_CALLS)
        }

        val missingPermissions = requiredPermissions.filter {
            try {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "checkSelfPermission($it) failed: ${e.message}")
                false
            }
        }

        if (missingPermissions.isNotEmpty()) {
            try {
                ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 100)
            } catch (e: Exception) {
                Logger.log(this, "⚠️ Could not request permissions: ${e.javaClass.simpleName}: ${e.message}")
            }
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION_PERMS) {
            val granted = grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (granted) {
                // ensureLocationPermissions() now passes, and also asks for
                // background access on its way through.
                if (ensureLocationPermissions()) applyLocationTracking(true, "admin_toggle")
            } else {
                Toast.makeText(
                    this,
                    "Location permission is required to track GPS",
                    Toast.LENGTH_LONG
                ).show()
                refreshLocationTrackingUi()
            }
            return
        }
        if (requestCode == REQ_BG_LOCATION_PERMS) {
            refreshLocationTrackingUi()
            return
        }
        if (requestCode == 100) {
            try {
                if (grantResults.isEmpty() || grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
                    try {
                        AlertDialog.Builder(this)
                            .setTitle("Permissions Required")
                            .setMessage("This app requires call monitoring permissions to function. Please enable them in Settings.")
                            .setPositiveButton("Settings") { _, _ ->
                                try {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    intent.data = Uri.fromParts("package", packageName, null)
                                    startActivity(intent)
                                } catch (e: Exception) {
                                    Logger.log(this, "⚠️ App details settings unavailable: ${e.message}")
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    } catch (e: Exception) {
                        Logger.log(this, "⚠️ Could not show permissions dialog: ${e.message}")
                    }
                    suppressToggleListener = true
                    switchMonitoring.isChecked = false
                    suppressToggleListener = false
                } else {
                    // Permissions granted — start monitoring directly without going through the listener
                    // (setting isChecked = true here would fire the listener and double-execute everything)
                    safely("requestBatteryOptimization") { requestBatteryOptimization() }
                    safely("requestExactAlarms") { requestExactAlarmsIfNeeded() }
                    safely("promptAggressiveOemKeepAlive") { promptAggressiveOemKeepAliveIfNeeded() }
                    safely("startMonitoringService") { startMonitoringService() }
                    safely("CallSyncScheduler.schedule") { CallSyncScheduler.schedule(this) }
                    safely("prefs.putMonitoringEnabled") {
                        prefs.edit().putBoolean("monitoringEnabled", true).apply()
                    }
                    suppressToggleListener = true
                    switchMonitoring.isChecked = true
                    suppressToggleListener = false
                    safely("refreshAppStatusLabel") { refreshAppStatusLabel() }
                    // The toggle listener bailed out when permissions were missing, so this
                    // is the only place the first contact sync can happen for a new employee.
                    safely("syncDeviceContacts") {
                        syncDeviceContacts(
                            etServerUrl.text.toString(),
                            etApiKey.text.toString(),
                            etDeviceId.text.toString(),
                            etEmployeeName.text.toString()
                        )
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("MainActivity", "onRequestPermissionsResult failed: ${t.message}", t)
            }
        }
    }

    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } catch (e: Exception) {
                    Logger.log(this, "⚠️ Battery optimisation settings unavailable: ${e.message}")
                }
            }
        }
    }

    /** Needed on Android 12+ so 8‑min idle wake alarms can use exact timing. */
    private fun requestExactAlarmsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(AlarmManager::class.java)
            if (!am.canScheduleExactAlarms()) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                } catch (e: Exception) {
                    Logger.log(this, "Exact alarm settings unavailable: ${e.message}")
                }
            }
        }
    }

    /**
     * Ensures OEM keep-alive settings are configured.
     * Now delegates to the mandatory [OemOnboardingActivity] instead of a dismissible dialog.
     */
    private fun promptAggressiveOemKeepAliveIfNeeded() {
        if (!OemPowerSettingsHelper.isAggressivePowerOem()) return
        if (OemOnboardingActivity.isCompleted(this)) return
        try {
            startActivity(Intent(this, OemOnboardingActivity::class.java))
        } catch (e: Exception) {
            Logger.log(this, "⚠️ OEM onboarding activity unavailable: ${e.message}")
        }
    }

    /**
     * Starts the foreground service only (no Device Admin). Used when restoring monitoring after launch.
     */
    private fun ensureCallMonitorServiceRunning() {
        try {
            val intent = Intent(this, CallMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Logger.log(this, "⚠️ Could not start CallMonitorService: ${e.javaClass.simpleName}: ${e.message}")
            ServiceRestartScheduler.enqueueRestart(this)
        }
    }

    private fun startMonitoringService() {
        ensureCallMonitorServiceRunning()
        // Request Device Admin so Force Stop is blocked after monitoring starts.
        // Any failure here (unresolved intent, missing DPM on weird ROMs) must not crash the app.
        try {
            requestDeviceAdmin()
        } catch (e: Exception) {
            Logger.log(this, "⚠️ Device Admin request failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun requestDeviceAdmin() {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        if (dpm == null) {
            Logger.log(this, "⚠️ DevicePolicyManager unavailable on this device — skipping admin request")
            return
        }
        val componentName = MyDeviceAdminReceiver.getComponentName(this)

        if (dpm.isAdminActive(componentName)) {
            Logger.log(this, "ℹ️ Device Admin already active")
            return
        }

        Logger.log(this, "📋 Requesting Device Admin activation...")
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Required to keep work call monitoring active on this device."
            )
        }
        try {
            adminResultLauncher.launch(intent)
        } catch (e: Exception) {
            // e.g. ActivityNotFoundException on ROMs that strip the device admin UI
            Logger.log(this, "⚠️ Could not launch Device Admin screen: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun showAdminRequiredDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("Device admin permission is required for this work phone to stay protected. Please contact your manager if you have questions.")
            .setPositiveButton("Try Again") { _, _ -> requestDeviceAdmin() }
            .setCancelable(false)
            .show()
    }

    private fun stopMonitoringService() {
        val intent = Intent(this, CallMonitorService::class.java)
        stopService(intent)
    }

    private fun isDeviceAdminActive(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(MyDeviceAdminReceiver.getComponentName(this))
    }

    /**
     * Status line(s) for monitoring + device admin. When monitoring is on but the user removed
     * device admin in Settings, show an explicit fix path. ADMIN_DISABLED is sent to the web API
     * (receiver / service / debounced ping when the user opens this screen).
     */
    private fun refreshAppStatusLabel() {
        if (!::tvStatus.isInitialized) return

        val monitoringOn =
            prefs.getBoolean("monitoringEnabled", false) || CallMonitorService.isRunning

        if (!monitoringOn) {
            tvStatus.text = "Status: Monitoring OFF"
            tvStatus.setTextColor(Color.parseColor("#757575"))
            return
        }

        val adminOk = isDeviceAdminActive()
        if (adminOk) {
            tvStatus.text =
                "Status: Monitoring ON\nDevice admin: active — work protection is on."
            tvStatus.setTextColor(Color.parseColor("#2E7D32"))
            prefs.edit().remove("lastAdminDisabledScreenNotifyAt").apply()
        } else {
            tvStatus.text =
                "Status: Monitoring ON\nDevice admin: NOT active — you turned it off in Settings. " +
                    "Re-enable: Settings → Security → Device admin apps → enable this app. " +
                    "Your administrator is notified when device admin is removed."
            tvStatus.setTextColor(Color.parseColor("#C62828"))
            notifyServerIfMonitoringOnButAdminOff()
        }
    }

    /** Re-report ADMIN_DISABLED at most once per hour while user keeps monitoring on without admin. */
    private fun notifyServerIfMonitoringOnButAdminOff() {
        val now = System.currentTimeMillis()
        val last = prefs.getLong("lastAdminDisabledScreenNotifyAt", 0L)
        if (now - last < 60 * 60 * 1000L) return

        val serverUrl = prefs.getString("serverUrl", "") ?: ""
        val deviceId = prefs.getString("deviceId", "") ?: ""
        if (serverUrl.isBlank() || deviceId.isBlank()) return

        prefs.edit().putLong("lastAdminDisabledScreenNotifyAt", now).apply()
        val apiKey = prefs.getString("apiKey", "") ?: ""
        val employeeName = prefs.getString("employeeName", "") ?: ""
        Thread {
            val ok = ApiClient.sendStatus(
                serverUrl, apiKey, deviceId, employeeName,
                "ADMIN_DISABLED", now, this@MainActivity,
                "Monitoring is ON but device admin is inactive (user opened the app — same as on-device warning)."
            )
            if (!ok) {
                val entry = ToggleLogEntry(deviceId, employeeName, "ADMIN_DISABLED", now)
                PendingStatusManager.savePendingStatus(this@MainActivity, entry)
            }
            runOnUiThread { refreshLogs() }
        }.start()
    }

    private fun updateStatusText() {
        val isEnabledInPrefs = prefs.getBoolean("monitoringEnabled", false)
        suppressToggleListener = true
        switchMonitoring.isChecked = CallMonitorService.isRunning || isEnabledInPrefs
        suppressToggleListener = false
        refreshAppStatusLabel()
    }

    private fun testConnection() {
        val url = etServerUrl.text.toString()
        val key = etApiKey.text.toString()
        Thread {
            val result = ApiClient.testConnection(url, key)
            runOnUiThread {
                if (result) {
                    Toast.makeText(this, "Connection Successful", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Connection Failed", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun syncDeviceContacts(serverUrl: String, apiKey: String, deviceId: String, employeeName: String) {
        Thread {
            ContactSyncManager.syncNow(this, serverUrl, apiKey, deviceId, employeeName)
            runOnUiThread { refreshLogs() }
        }.start()
    }

    companion object {
        private const val REQ_LOCATION_PERMS = 300
        private const val REQ_BG_LOCATION_PERMS = 301
    }
}