package com.company.callmonitor

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.company.callmonitor.callerlookup.CallerLookupActivity

/**
 * Home screen for non-driver employees. Shows call monitoring status and basic info.
 * This screen NEVER requests location permissions.
 */
class EmployeeHomeActivity : AppCompatActivity() {

    private lateinit var tvEmployeeName: TextView
    private lateinit var tvMonitoringStatus: TextView
    private lateinit var tvLastSync: TextView
    private lateinit var btnCheckStatus: Button
    private lateinit var btnCallerLookup: Button
    private lateinit var tvAdminAccess: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_employee_home)

        tvEmployeeName = findViewById(R.id.tvEmployeeName)
        tvMonitoringStatus = findViewById(R.id.tvMonitoringStatus)
        tvLastSync = findViewById(R.id.tvLastSync)
        btnCheckStatus = findViewById(R.id.btnCheckStatus)
        btnCallerLookup = findViewById(R.id.btnCallerLookup)
        tvAdminAccess = findViewById(R.id.tvAdminAccess)

        tvEmployeeName.text = "Hello, ${EnrollmentPrefs.getEmployeeName(this)}"

        btnCheckStatus.setOnClickListener {
            refreshStatus()
            Toast.makeText(this, "Status refreshed", Toast.LENGTH_SHORT).show()
        }

        btnCallerLookup.setOnClickListener {
            startActivity(Intent(this, CallerLookupActivity::class.java))
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
        val prefs = getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
        val monitoringEnabled = prefs.getBoolean("monitoringEnabled", false)
        tvMonitoringStatus.text = if (monitoringEnabled) "Work monitoring: Active" else "Work monitoring: Inactive"

        val lastSyncMs = prefs.getLong("lastSyncTime", 0L)
        tvLastSync.text = if (lastSyncMs > 0L) {
            "Last sync: ${java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(lastSyncMs))}"
        } else {
            "Last sync: —"
        }
    }
}
