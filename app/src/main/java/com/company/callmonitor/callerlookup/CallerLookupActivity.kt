package com.company.callmonitor.callerlookup

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.company.callmonitor.EnrollmentPrefs
import com.company.callmonitor.R

/**
 * On-device UI to start a number-series fetch job.
 * Asks for batch size, harvests known contacts in the series, walks the range,
 * saves results locally, and uploads them when the backend job exists.
 */
class CallerLookupActivity : AppCompatActivity() {

    private lateinit var spinnerSeries: Spinner
    private lateinit var etBatchSize: EditText
    private lateinit var etStartNumber: EditText
    private lateinit var etDelayMs: EditText
    private lateinit var btnStart: Button
    private lateinit var btnPause: Button
    private lateinit var btnResume: Button
    private lateinit var btnStop: Button
    private lateinit var btnExport: Button
    private lateinit var tvJobStatus: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvRecentResults: TextView
    private lateinit var progressBar: ProgressBar

    private val handler = Handler(Looper.getMainLooper())
    private var activeJobId: String? = null
    private var busy = false
    private var lastCreateError: String? = null
    private var pendingStart: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val contactsOk = grants[Manifest.permission.READ_CONTACTS] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        val callLogOk = grants[Manifest.permission.READ_CALL_LOG] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED
        if (contactsOk || callLogOk) {
            pendingStart?.invoke()
        } else {
            Toast.makeText(
                this,
                "Contacts / Call Log permission required to find names",
                Toast.LENGTH_LONG
            ).show()
        }
        pendingStart = null
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshUi()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_caller_lookup)
        title = "Caller Number Lookup"

        spinnerSeries = findViewById(R.id.spinnerSeries)
        etBatchSize = findViewById(R.id.etBatchSize)
        etStartNumber = findViewById(R.id.etStartNumber)
        etDelayMs = findViewById(R.id.etDelayMs)
        btnStart = findViewById(R.id.btnStart)
        btnPause = findViewById(R.id.btnPause)
        btnResume = findViewById(R.id.btnResume)
        btnStop = findViewById(R.id.btnStop)
        btnExport = findViewById(R.id.btnExport)
        tvJobStatus = findViewById(R.id.tvJobStatus)
        tvStats = findViewById(R.id.tvStats)
        tvRecentResults = findViewById(R.id.tvRecentResults)
        progressBar = findViewById(R.id.progressBar)

        spinnerSeries.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            JioNumberSeriesCatalog.ALL
        )

        btnStart.setOnClickListener { confirmAndStart() }
        btnPause.setOnClickListener {
            CallerLookupRunner.pause()
            activeJobId?.let { id ->
                if (!id.startsWith("local-")) {
                    Thread { CallerLookupApiClient.controlJob(this, id, "pause") }.start()
                }
            }
            Toast.makeText(this, "Pause requested", Toast.LENGTH_SHORT).show()
        }
        btnResume.setOnClickListener {
            val id = activeJobId
            if (id.isNullOrBlank()) {
                Toast.makeText(this, "No job to resume", Toast.LENGTH_SHORT).show()
            } else {
                CallerLookupRunner.resume(this, id)
                Toast.makeText(this, "Resuming…", Toast.LENGTH_SHORT).show()
            }
        }
        btnStop.setOnClickListener {
            CallerLookupRunner.stop()
            activeJobId?.let { id ->
                if (!id.startsWith("local-")) {
                    Thread { CallerLookupApiClient.controlJob(this, id, "stop") }.start()
                }
            }
            Toast.makeText(this, "Stop requested", Toast.LENGTH_SHORT).show()
        }
        btnExport.setOnClickListener {
            Toast.makeText(this, "Preparing Excel export…", Toast.LENGTH_SHORT).show()
            CallerLookupExcelExporter.exportAndShare(this, activeJobId)
        }

        ensurePermissions()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    private fun ensurePermissions(then: (() -> Unit)? = null) {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.READ_CONTACTS
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.READ_CALL_LOG
        if (need.isEmpty()) {
            then?.invoke()
            return
        }
        pendingStart = then
        permissionLauncher.launch(need.toTypedArray())
    }

    private fun confirmAndStart() {
        if (busy) return

        val batchText = etBatchSize.text.toString().trim()
        val batchSize = batchText.toIntOrNull()
        if (batchSize == null || batchSize < 1) {
            etBatchSize.requestFocus()
            Toast.makeText(this, "Enter a batch size (e.g. 200)", Toast.LENGTH_LONG).show()
            return
        }
        if (batchSize > 100_000) {
            Toast.makeText(this, "Batch size max is 100000", Toast.LENGTH_LONG).show()
            return
        }

        val series = spinnerSeries.selectedItem as? NumberSeries
        if (series == null) {
            Toast.makeText(this, "Select a number series", Toast.LENGTH_SHORT).show()
            return
        }

        val startNumber = etStartNumber.text.toString().trim()
        val delayMs = etDelayMs.text.toString().trim().toLongOrNull() ?: 200L

        AlertDialog.Builder(this)
            .setTitle("Start number fetch?")
            .setMessage(
                "Series: ${series.label}\n" +
                    "Batch size: $batchSize numbers\n" +
                    "Start: ${startNumber.ifBlank { series.startNumber() }}\n\n" +
                    "1) Harvest named contacts already on this phone in this series\n" +
                    "2) Walk $batchSize generated numbers and look each one up\n" +
                    "3) Save names found and sync to the website when online"
            )
            .setPositiveButton("Start $batchSize") { _, _ ->
                ensurePermissions {
                    startJob(series, batchSize, startNumber, delayMs)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startJob(
        series: NumberSeries,
        batchSize: Int,
        startNumber: String,
        delayMs: Long
    ) {
        busy = true
        setControlsEnabled(false)
        lastCreateError = null
        tvJobStatus.text = "Creating job for $batchSize numbers…"
        Thread {
            val actualStart = if (startNumber.isBlank()) series.startNumber()
            else AndroidCallLogLookupProvider.normalizeDigits(startNumber).ifBlank { series.startNumber() }
            val seriesEndLong = series.endNumber().toLong()
            val startLong = actualStart.toLong()
            val maxInSeries = (seriesEndLong - startLong + 1).toInt().coerceAtLeast(0)
            val total = batchSize.coerceAtMost(maxInSeries).coerceIn(1, 100_000)
            val computedEnd = (startLong + total - 1).toString().padStart(10, '0')
            val computedStart = startLong.toString().padStart(10, '0')
            val deviceId = EnrollmentPrefs.getDeviceId(this).ifBlank { "local-device" }

            var remoteJob: RemoteCallerLookupJob? = null
            var createError: String? = null
            if (EnrollmentPrefs.isEnrolled(this) &&
                EnrollmentPrefs.getServerUrl(this).isNotBlank() &&
                EnrollmentPrefs.getDeviceToken(this).isNotBlank()
            ) {
                val result = CallerLookupApiClient.createJob(
                    context = this,
                    series = series,
                    batchSize = batchSize,
                    startNumber = if (startNumber.isBlank()) null else computedStart,
                    delayMs = delayMs
                )
                remoteJob = result.value
                createError = result.error
            } else if (!EnrollmentPrefs.isEnrolled(this)) {
                createError = "Device not enrolled — running locally only"
            } else {
                createError = "Missing server URL or device token — running locally only"
            }

            val jobId = remoteJob?.id ?: "local-${System.currentTimeMillis()}"
            val localEntity = CallerLookupJobEntity(
                jobId = jobId,
                status = "requested",
                deviceId = remoteJob?.deviceId ?: deviceId,
                mobileProvider = series.provider,
                seriesId = series.id,
                seriesPrefix = series.prefix,
                seriesLabel = series.label,
                startNumber = remoteJob?.startNumber ?: computedStart,
                endNumber = remoteJob?.endNumber ?: computedEnd,
                totalPlanned = remoteJob?.totalPlanned ?: total,
                delayMs = delayMs,
                workers = 1,
                lookupProviderId = "android-call-log-cache",
                maxRetries = 2,
                cursorIndex = 0,
                processed = 0,
                successful = 0,
                failed = 0,
                totalLookupDurationMs = 0,
                currentNumber = remoteJob?.startNumber ?: computedStart
            )

            val dao = CallerLookupDatabase.getInstance(this).callerLookupDao()
            dao.upsertJob(localEntity)

            handler.post {
                busy = false
                setControlsEnabled(true)
                activeJobId = jobId
                lastCreateError = if (remoteJob == null) createError else null
                tvJobStatus.text = if (remoteJob == null) {
                    "Running on device (${createError ?: "offline"}). Harvesting contacts…"
                } else {
                    "Job synced. Harvesting contacts, then fetching $total numbers…"
                }
                // Reliable in-process runner (not WorkManager)
                CallerLookupRunner.start(this, jobId)
                Toast.makeText(
                    this,
                    "Looking up contacts for $total numbers…",
                    Toast.LENGTH_SHORT
                ).show()
                refreshUi()
            }
        }.start()
    }

    private fun refreshUi() {
        Thread {
            val dao = CallerLookupDatabase.getInstance(this).callerLookupDao()
            val jobId = activeJobId ?: CallerLookupRunner.activeJobId()
            val local = jobId?.let { dao.getJob(it) } ?: dao.getLatestJob()
            val found = local?.let { dao.getRecentFoundResults(it.jobId, 40) }.orEmpty()
            val recent = local?.let { dao.getRecentResults(it.jobId, 25) }.orEmpty()
            val foundCount = local?.let { dao.countFound(it.jobId) } ?: 0

            handler.post {
                if (local == null) {
                    tvJobStatus.text = "No job yet. Enter a batch size and tap Start."
                    progressBar.progress = 0
                    tvStats.text = "Processed: 0  |  Found: 0  |  Empty: 0"
                    return@post
                }
                activeJobId = local.jobId
                val pct = if (local.totalPlanned > 0) {
                    ((local.processed * 100.0) / local.totalPlanned).toInt().coerceIn(0, 100)
                } else 0
                progressBar.progress = pct
                val runnerNote = if (CallerLookupRunner.isRunning() &&
                    CallerLookupRunner.activeJobId() == local.jobId
                ) " · LIVE" else ""
                val banner = lastCreateError?.let { "⚠ Website sync: $it\n" }.orEmpty()
                tvJobStatus.text = banner +
                    "Status: ${local.status.uppercase()}$runnerNote  ·  ${local.seriesLabel}\n" +
                    "Range: ${local.startNumber} → ${local.endNumber}\n" +
                    "Current: ${local.currentNumber ?: "—"}  ·  $pct%"
                tvStats.text =
                    "Processed: ${local.processed}/${local.totalPlanned}  |  " +
                        "Names found: $foundCount  |  No name: ${local.failed}"

                val foundBlock = if (found.isEmpty()) {
                    "No named contacts found yet in this series on this phone."
                } else {
                    "FOUND (${found.size} shown):\n" + found.joinToString("\n") { r ->
                        "${r.phoneNumber}  →  ${r.callerName}"
                    }
                }
                val recentBlock = if (recent.isEmpty()) {
                    ""
                } else {
                    "\n\nRECENT LOOKUPS:\n" + recent.take(15).joinToString("\n") { r ->
                        val mark = when (r.lookupStatus) {
                            "found" -> "✓"
                            "error" -> "!"
                            else -> "·"
                        }
                        "$mark ${r.phoneNumber}  ${r.callerName ?: r.lookupStatus}"
                    }
                }
                tvRecentResults.text = foundBlock + recentBlock
            }
        }.start()
    }

    private fun setControlsEnabled(enabled: Boolean) {
        btnStart.isEnabled = enabled
        btnPause.isEnabled = enabled
        btnResume.isEnabled = enabled
        btnStop.isEnabled = enabled
    }
}
