package com.company.callmonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.company.callmonitor.location.FleetLocationDatabase
import com.company.callmonitor.location.LocationDutyController
import com.company.callmonitor.location.LocationEnableRequest
import com.company.callmonitor.location.LocationEnvironment
import com.company.callmonitor.location.LocationUploadWorker
import com.company.callmonitor.callerlookup.CallerLookupActivity

/**
 * Driver home — no Start/End Duty button. Tracking stays active when location
 * capability is enrolled; the website starts/ends sessions via FCM.
 */
class DriverHomeActivity : AppCompatActivity() {

    private lateinit var tvDriverName: TextView
    private lateinit var tvVehicleReg: TextView
    private lateinit var tvTrackingStatus: TextView
    private lateinit var tvPendingPoints: TextView
    private lateinit var btnAddExpense: Button
    private lateinit var tvExpenseInfo: TextView
    private lateinit var btnLocationStatus: TextView
    private lateinit var btnViewRoute: TextView
    private lateinit var btnCallerLookup: Button
    private lateinit var tvAdminAccess: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshUi()
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_driver_home)

        tvDriverName = findViewById(R.id.tvDriverName)
        tvVehicleReg = findViewById(R.id.tvVehicleReg)
        tvTrackingStatus = findViewById(R.id.tvTrackingStatus)
        tvPendingPoints = findViewById(R.id.tvPendingPoints)
        btnAddExpense = findViewById(R.id.btnAddExpense)
        tvExpenseInfo = findViewById(R.id.tvExpenseInfo)
        btnLocationStatus = findViewById(R.id.btnLocationStatus)
        btnViewRoute = findViewById(R.id.btnViewRoute)
        btnCallerLookup = findViewById(R.id.btnCallerLookup)
        tvAdminAccess = findViewById(R.id.tvAdminAccess)

        tvDriverName.text = "Hello, ${EnrollmentPrefs.getEmployeeName(this)}"
        tvVehicleReg.text = "Vehicle: ${EnrollmentPrefs.getVehicleRegistration(this).ifBlank { "—" }}"

        if (EnrollmentPrefs.hasExpenseManagement(this)) {
            tvExpenseInfo.text = "Expenses: open the portal for wallet balance and recent claims"
            btnAddExpense.isEnabled = true
        } else {
            tvExpenseInfo.text = "Expense management is not enabled for this device"
            btnAddExpense.isEnabled = false
        }

        btnAddExpense.setOnClickListener {
            ExpenseLauncher.open(this)
        }

        btnLocationStatus.setOnClickListener {
            startActivity(Intent(this, com.company.callmonitor.location.LocationStatusActivity::class.java))
        }

        btnViewRoute.setOnClickListener {
            startActivity(Intent(this, com.company.callmonitor.location.LocationTrackActivity::class.java))
        }

        btnCallerLookup.setOnClickListener {
            startActivity(Intent(this, CallerLookupActivity::class.java))
        }

        tvAdminAccess.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        maybeRequestLocationPermissions()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
        if (EnrollmentPrefs.hasLocationTracking(this)) {
            LocationUploadWorker.enqueue(this)
            // One tap, in place, without sending the driver into Settings.
            LocationEnableRequest.promptIfNeeded(this)
            ensureTrackingIfPermitted()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    private fun refreshUi() {
        // Report the blocking cause, not just whether a service object exists —
        // "Tracking active" was shown for hours on phones that were recording
        // nothing because Location had been switched off underneath the service.
        tvTrackingStatus.text = when (LocationEnvironment.trackingStatus(this)) {
            LocationEnvironment.STATUS_PERMISSION_DENIED ->
                "Location permission is OFF — tap GPS Status to fix"
            LocationEnvironment.STATUS_GPS_OFF ->
                "Phone Location is OFF — route is not being recorded"
            LocationEnvironment.STATUS_IDLE -> "Route ended from website"
            LocationEnvironment.STATUS_BACKGROUND_DENIED ->
                "Set location access to \"Allow all the time\" to keep tracking"
            LocationEnvironment.STATUS_NO_FIX -> "No GPS fix yet — recovering"
            else -> "Tracking active — start/end from website"
        }

        Thread {
            val db = FleetLocationDatabase.getInstance(this)
            val pending = db.locationPointDao().countByState("PENDING")
            val uploading = db.locationPointDao().countByState("UPLOADING")
            runOnUiThread {
                tvPendingPoints.text = "Pending GPS points: ${pending + uploading}"
            }
        }.start()
    }

    private fun maybeRequestLocationPermissions() {
        if (!EnrollmentPrefs.hasLocationTracking(this)) return

        val permissionsNeeded = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissionsNeeded.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_LOCATION_PERMS)
        } else {
            requestBackgroundLocationIfNeeded()
        }
    }

    private fun ensureTrackingIfPermitted() {
        if (!LocationDutyController.hasLocationPermission(this)) return
        Thread {
            LocationDutyController.ensureAlwaysActive(this, "driver_home")
            runOnUiThread { refreshUi() }
        }.start()
    }

    @Deprecated("Activity result API; kept to match this Activity's permission flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == LocationEnableRequest.REQUEST_CODE && resultCode == RESULT_OK) {
            // Location just came back on — start recording now rather than
            // waiting for the next watchdog cycle.
            ensureTrackingIfPermitted()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION_PERMS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                requestBackgroundLocationIfNeeded()
            } else {
                Toast.makeText(
                    this,
                    "Location permission is required for route tracking",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else if (requestCode == REQ_BG_LOCATION) {
            ensureTrackingIfPermitted()
        }
    }

    private fun requestBackgroundLocationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    REQ_BG_LOCATION
                )
                return
            }
        }
        ensureTrackingIfPermitted()
    }

    companion object {
        private const val REQ_LOCATION_PERMS = 200
        private const val REQ_BG_LOCATION = 201
    }
}
