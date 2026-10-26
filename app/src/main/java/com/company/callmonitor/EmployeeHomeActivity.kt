package com.company.callmonitor

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
/**
 * Home screen for non-driver employees. Shows call monitoring status and basic info.
 * This screen NEVER requests location permissions.
 */
class EmployeeHomeActivity : AppCompatActivity() {

    private lateinit var tvEmployeeName: TextView
    private lateinit var tvMonitoringStatus: TextView
    private lateinit var tvLastSync: TextView
    private lateinit var shiftToggle: ShiftToggleController
    private lateinit var btnCheckStatus: Button
    private lateinit var tvAdminAccess: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_employee_home)

        tvEmployeeName = findViewById(R.id.tvEmployeeName)
        tvMonitoringStatus = findViewById(R.id.tvMonitoringStatus)
        tvLastSync = findViewById(R.id.tvLastSync)
        btnCheckStatus = findViewById(R.id.btnCheckStatus)
        tvAdminAccess = findViewById(R.id.tvAdminAccess)

        tvEmployeeName.text = "Hello, ${EnrollmentPrefs.getEmployeeName(this)}"

        shiftToggle = ShiftToggleController(
            activity = this,
            switch = findViewById(R.id.switchShift),
            statusView = findViewById(R.id.tvShiftStatus),
            hintView = findViewById(R.id.tvShiftHint),
            hintWhenOn = "Your call logs are being recorded while your shift is on.",
            hintWhenOff = "Turn your shift on to start recording your call logs.",
            onChanged = { refreshStatus() }
        )
        shiftToggle.attach()

        btnCheckStatus.setOnClickListener {
            refreshStatus()
            Toast.makeText(this, "Status refreshed", Toast.LENGTH_SHORT).show()
        }

        tvAdminAccess.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        if (::shiftToggle.isInitialized) shiftToggle.refresh()

        val prefs = getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
        val monitoringEnabled = prefs.getBoolean("monitoringEnabled", false)
        // Monitoring is the technician's switch; the shift is the user's. Both
        // have to be on for anything to be collected, so say which one is missing.
        tvMonitoringStatus.text = when {
            !monitoringEnabled -> "Work monitoring: Inactive"
            ShiftManager.isOn(this) -> "Work monitoring: Active"
            else -> "Work monitoring: Paused — shift is off"
        }

        val lastSyncMs = prefs.getLong("lastSyncTime", 0L)
        tvLastSync.text = if (lastSyncMs > 0L) {
            "Last sync: ${java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(lastSyncMs))}"
        } else {
            "Last sync: —"
        }
    }
}
