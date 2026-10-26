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
import com.company.callmonitor.location.LocationGate
import com.company.callmonitor.location.LocationUploadWorker
/**
 * Driver home. The Shift On / Shift Off button is the driver's own gate: nothing
 * is recorded while the shift is off, whatever the website asks for. With it on,
 * tracking stays active for the shift, and the shift's start, end and every
 * tracking interruption are logged for HR (see [ShiftEventReporter]).
 */
class DriverHomeActivity : AppCompatActivity() {

    private lateinit var tvDriverName: TextView
    private lateinit var tvVehicleReg: TextView
    private lateinit var tvTrackingStatus: TextView
    private lateinit var tvPendingPoints: TextView
    private lateinit var btnAddExpense: Button
    private lateinit var tvExpenseInfo: TextView
    private lateinit var tvWalletBalance: TextView
    private lateinit var btnLocationStatus: TextView
    private lateinit var btnViewRoute: TextView
    private lateinit var tvAdminAccess: TextView
    private lateinit var shiftToggle: ShiftButtonController

    private val handler = Handler(Looper.getMainLooper())
    private var lastWalletFetchAt = 0L
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
        tvWalletBalance = findViewById(R.id.tvWalletBalance)
        btnLocationStatus = findViewById(R.id.btnLocationStatus)
        btnViewRoute = findViewById(R.id.btnViewRoute)
        tvAdminAccess = findViewById(R.id.tvAdminAccess)

        tvDriverName.text = "Hello, ${EnrollmentPrefs.getEmployeeName(this)}"
        tvVehicleReg.text = "Vehicle: ${EnrollmentPrefs.getVehicleRegistration(this).ifBlank { "—" }}"

        shiftToggle = ShiftButtonController(
            activity = this,
            button = findViewById(R.id.btnShift),
            nameView = findViewById(R.id.tvShiftName),
            statusView = findViewById(R.id.tvShiftStatus),
            hintView = findViewById(R.id.tvShiftHint),
            // Starting a shift without location permission would leave the driver
            // believing their route is recorded when nothing is. Ask first, and
            // only open the shift once the phone can actually honour it.
            preTurnOn = {
                if (EnrollmentPrefs.hasLocationTracking(this) &&
                    !LocationDutyController.hasLocationPermission(this)
                ) {
                    maybeRequestLocationPermissions()
                    Toast.makeText(
                        this,
                        "Allow location access, then turn your shift on",
                        Toast.LENGTH_LONG
                    ).show()
                    false
                } else {
                    true
                }
            },
            onChanged = { refreshUi() }
        )
        shiftToggle.attach()

        updateExpenseAccessUi()

        btnAddExpense.setOnClickListener {
            if (refuseWhileLocationOff()) return@setOnClickListener
            ExpenseLauncher.open(this)
        }

        // Never gated: this is the screen that explains how to fix the block, so
        // withholding it would leave the driver stuck with no way out.
        btnLocationStatus.setOnClickListener {
            startActivity(Intent(this, com.company.callmonitor.location.LocationStatusActivity::class.java))
        }

        btnViewRoute.setOnClickListener {
            if (refuseWhileLocationOff()) return@setOnClickListener
            startActivity(Intent(this, com.company.callmonitor.location.LocationTrackActivity::class.java))
        }

        tvAdminAccess.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        maybeRequestLocationPermissions()
    }

    /** Expense access as last granted by the server; re-read on every resume below. */
    private fun updateExpenseAccessUi() {
        if (EnrollmentPrefs.hasExpenseManagement(this)) {
            tvExpenseInfo.text = "Money added on the website appears in the balance above"
            btnAddExpense.isEnabled = true
        } else {
            tvExpenseInfo.text = "Expense management is not enabled for this device"
            btnAddExpense.isEnabled = false
        }
    }

    /**
     * Pull the wallet for this handset. Forced on resume so an advance allocated
     * while the app was in the background shows up as soon as the driver looks.
     */
    private fun refreshWallet(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastWalletFetchAt < 15_000) return
        lastWalletFetchAt = now
        val appCtx = applicationContext
        Thread {
            val wallet = WalletRefresher.fetch(appCtx)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                tvWalletBalance.text = if (wallet == null) {
                    "Wallet balance unavailable"
                } else {
                    "Wallet balance: ${WalletRefresher.formatInr(wallet.balance)}"
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        // Access changed in Device Enrollment reaches the phone here, without
        // the driver having to sign in again.
        CapabilityRefresher.refresh(this) {
            runOnUiThread {
                updateExpenseAccessUi()
                refreshUi()
            }
        }
        refreshWallet(force = true)
        handler.post(refreshRunnable)
        // Opening the app is a chance to deliver queued shift events and to learn
        // whether the server has closed the shift while the phone was away.
        ShiftEventReporter.tick(this)
        // Off shift there is nothing to prompt about and nothing to resume — the
        // driver has told us not to record, and nagging them for Location while
        // they are off the clock would be exactly the wrong reading of that.
        if (EnrollmentPrefs.hasLocationTracking(this) && ShiftManager.isOn(this)) {
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
        if (::shiftToggle.isInitialized) shiftToggle.refresh()
        if (::tvWalletBalance.isInitialized) refreshWallet(force = false)

        // Off shift there is one honest thing to say, and it is not a fault
        // report: nothing is being recorded because the driver said so.
        if (!ShiftManager.isOn(this)) {
            tvTrackingStatus.text = "Shift is OFF — no route or call logs are being recorded"
            btnAddExpense.isEnabled = EnrollmentPrefs.hasExpenseManagement(this)
            btnViewRoute.isEnabled = true
            refreshPendingPoints()
            return
        }

        // Report the blocking cause, not just whether a service object exists —
        // "Tracking active" was shown for hours on phones that were recording
        // nothing because Location had been switched off underneath the service.
        tvTrackingStatus.text = when (LocationEnvironment.trackingStatus(this)) {
            LocationEnvironment.STATUS_PERMISSION_DENIED ->
                "Location permission is OFF — tap GPS Status to fix${outageSuffix()}"
            LocationEnvironment.STATUS_GPS_OFF ->
                "Phone Location is OFF — route is not being recorded${outageSuffix()}"
            LocationEnvironment.STATUS_IDLE -> "Route ended from website"
            LocationEnvironment.STATUS_BACKGROUND_DENIED ->
                "Set location access to \"Allow all the time\" to keep tracking"
            LocationEnvironment.STATUS_NO_FIX -> "No GPS fix yet — recovering"
            else -> "Tracking active — start/end from website"
        }

        // Withheld features are greyed rather than silently failing, so the block
        // is legible before the driver taps.
        val blocked = LocationGate.isBlocking(this)
        btnAddExpense.isEnabled = EnrollmentPrefs.hasExpenseManagement(this) && !blocked
        btnViewRoute.isEnabled = !blocked

        refreshPendingPoints()
    }

    /**
     * Points already recorded still upload after the shift ends, so the count
     * stays on screen either way — it is the driver's receipt that their route
     * reached the server.
     */
    private fun refreshPendingPoints() {
        Thread {
            val db = FleetLocationDatabase.getInstance(this)
            val pending = db.locationPointDao().countByState("PENDING")
            val uploading = db.locationPointDao().countByState("UPLOADING")
            runOnUiThread {
                tvPendingPoints.text = "Pending GPS points: ${pending + uploading}"
            }
        }.start()
    }

    /**
     * " — off for 12 min" once an outage has been running long enough to be worth
     * naming. Showing the clock is most of the deterrent: the driver can see the
     * number their manager will see.
     */
    private fun outageSuffix(): String {
        val outage = LocationGate.currentOutageMillis(this)
        if (outage < 60_000) return ""
        return " — off for ${LocationGate.formatDuration(outage)}"
    }

    /**
     * The workflow gate. On a phone we cannot lock ([LocationPolicyManager]), the
     * driver's own reasons for opening this app are withheld while the route is
     * not being recorded — the only lever left that costs the driver something at
     * the moment they switch Location off rather than at payroll.
     *
     * Offers the one-tap fix in the same breath, so this reads as a blocked door
     * with a key in it rather than a punishment.
     *
     * @return true when the caller's action must not proceed.
     */
    private fun refuseWhileLocationOff(): Boolean {
        val reason = LocationGate.blockReason(this) ?: return false

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Turn Location back on")
            .setMessage(reason)
            .setPositiveButton("Turn on") { _, _ ->
                LocationEnableRequest.promptIfNeeded(this)
            }
            .setNegativeButton("Cancel", null)
            .show()
        return true
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
