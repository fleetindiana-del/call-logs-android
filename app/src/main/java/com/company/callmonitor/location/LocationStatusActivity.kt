package com.company.callmonitor.location

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.company.callmonitor.EnrollmentPrefs
import com.company.callmonitor.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class LocationStatusActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() { refreshAll(); handler.postDelayed(this, 4_000) }
    }

    private lateinit var tvDutyStatus: TextView
    private lateinit var tvGpsEnabled: TextView
    private lateinit var tvPermissions: TextView
    private lateinit var tvLastPoint: TextView
    private lateinit var tvAccuracy: TextView
    private lateinit var tvLocalCount: TextView
    private lateinit var tvPendingUpload: TextView
    private lateinit var tvBatteryOpt: TextView
    private lateinit var btnFixSettings: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_location_status)

        tvDutyStatus = findViewById(R.id.tvDutyStatus)
        tvGpsEnabled = findViewById(R.id.tvGpsEnabled)
        tvPermissions = findViewById(R.id.tvPermissions)
        tvLastPoint = findViewById(R.id.tvLastPoint)
        tvAccuracy = findViewById(R.id.tvAccuracy)
        tvLocalCount = findViewById(R.id.tvLocalCount)
        tvPendingUpload = findViewById(R.id.tvPendingUpload)
        tvBatteryOpt = findViewById(R.id.tvBatteryOpt)
        btnFixSettings = findViewById(R.id.btnFixSettings)

        btnFixSettings.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                })
            }
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "GPS Status"
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun refreshAll() {
        tvDutyStatus.text = if (LocationTrackingService.isRunning) {
            "Tracking active — website controlled"
        } else {
            "Tracking idle"
        }

        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gpsOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }
        tvGpsEnabled.text = "GPS enabled: ${if (gpsOn) "Yes" else "No"}"

        val fineGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        tvPermissions.text = "Fine location permission: ${if (fineGranted) "Granted" else "DENIED"}"

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryExempt = pm.isIgnoringBatteryOptimizations(packageName)
        tvBatteryOpt.text = "Battery optimisation: ${if (batteryExempt) "Unrestricted" else "Restricted (fix recommended)"}"

        executor.execute {
            val db = FleetLocationDatabase.getInstance(this)
            val pending = db.locationPointDao().countByState("PENDING")
            val uploading = db.locationPointDao().countByState("UPLOADING")
            val synced = db.locationPointDao().countByState("SYNCED")
            val last = db.locationPointDao().getLatestPoint()

            val deviceId = EnrollmentPrefs.getDeviceId(this)
            val lastUploadMs = getSharedPreferences("location_prefs", Context.MODE_PRIVATE)
                .getLong("lastUploadAt", 0L)

            handler.post {
                tvLocalCount.text = "Total local points: ${pending + uploading + synced} (synced: $synced)"
                tvPendingUpload.text = "Pending upload: $pending | Uploading: $uploading"
                if (last != null) {
                    val fmt = SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault())
                    tvLastPoint.text = "Last point: ${fmt.format(Date(last.recordedAt))}"
                    tvAccuracy.text = "Accuracy: ±${last.accuracyMeters.toInt()} m  Speed: ${"%.1f".format(last.speedMetersPerSecond * 3.6)} km/h"
                } else {
                    tvLastPoint.text = "Last point: none yet"
                    tvAccuracy.text = "Accuracy: —"
                }
            }
        }
    }
}
