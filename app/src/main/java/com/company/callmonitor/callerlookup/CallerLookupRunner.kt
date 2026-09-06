package com.company.callmonitor.callerlookup

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.company.callmonitor.Logger
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Reliable on-device batch runner (does not depend on WorkManager).
 *
 * Strategy:
 *  1. Harvest every named contact / call-log entry in the selected series (real people).
 *  2. Walk the configured number range and look each number up on-device.
 *  3. Persist every result to Room; upload to backend when the job id is a server id.
 */
object CallerLookupRunner {
    private const val TAG = "CallerLookupRunner"

    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val pauseRequested = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val activeJobId = AtomicReference<String?>(null)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun isRunning(): Boolean = running.get()
    fun activeJobId(): String? = activeJobId.get()

    fun pause() {
        pauseRequested.set(true)
    }

    fun stop() {
        stopRequested.set(true)
        pauseRequested.set(false)
    }

    fun resume(context: Context, jobId: String) {
        pauseRequested.set(false)
        stopRequested.set(false)
        start(context, jobId)
    }

    fun start(context: Context, jobId: String) {
        if (!running.compareAndSet(false, true)) {
            // Already running — if same job, ignore; otherwise stop old and restart
            if (activeJobId.get() == jobId) return
            stopRequested.set(true)
            // Queue after current finishes
            executor.execute {
                // wait briefly for previous to exit
                var waits = 0
                while (running.get() && waits < 50) {
                    Thread.sleep(100)
                    waits++
                }
                running.set(true)
                runJob(context.applicationContext, jobId)
            }
            return
        }
        pauseRequested.set(false)
        stopRequested.set(false)
        activeJobId.set(jobId)
        executor.execute {
            try {
                runJob(context.applicationContext, jobId)
            } finally {
                running.set(false)
            }
        }
    }

    private fun runJob(context: Context, jobId: String) {
        val dao = CallerLookupDatabase.getInstance(context).callerLookupDao()
        var job: CallerLookupJobEntity = dao.getJob(jobId) ?: run {
            Log.e(TAG, "Job not found locally: $jobId")
            running.set(false)
            return
        }
        if (job.status == "completed" || job.status == "stopped") {
            running.set(false)
            return
        }

        val isLocal = jobId.startsWith("local-")
        val provider = AndroidCallLogLookupProvider(context)

        job = job.copy(status = "running", updatedAt = System.currentTimeMillis())
        dao.upsertJob(job)
        insertLog(dao, jobId, "info", "Runner started at index ${job.cursorIndex}")

        // Step 1: harvest known people in this series (guarantees real contacts when present)
        if (job.cursorIndex == 0 && job.processed == 0) {
            val harvested = provider.harvestNamedNumbersInSeries(job.seriesPrefix)
            insertLog(
                dao,
                jobId,
                "info",
                "Harvested ${harvested.size} named contact(s) in series ${job.seriesPrefix}"
            )
            if (harvested.isNotEmpty()) {
                val now = System.currentTimeMillis()
                val entities = harvested.entries.map { (number, nameAndSource) ->
                    val (name, source) = nameAndSource
                    CallerLookupResultEntity(
                        jobId = jobId,
                        phoneNumber = number,
                        callerName = name,
                        lookupStatus = "found",
                        provider = provider.label,
                        lookupProviderId = provider.id,
                        metadataJson = JSONObjectSource(source),
                        kycJson = null,
                        rawResponseJson = null,
                        durationMs = 0,
                        retryCount = 0,
                        error = null,
                        lookedUpAt = now
                    )
                }
                dao.insertResults(entities)
                job = job.copy(
                    successful = job.successful + entities.size,
                    updatedAt = System.currentTimeMillis()
                )
                dao.upsertJob(job)
                maybeUpload(context, dao, job, isLocal)
            }
        }

        // Step 2: walk the configured batch range
        while (job.cursorIndex < job.totalPlanned) {
            if (stopRequested.get()) {
                job = job.copy(status = "stopped", updatedAt = System.currentTimeMillis())
                dao.upsertJob(job)
                insertLog(dao, jobId, "info", "Stopped by user")
                maybeUpload(context, dao, job, isLocal)
                if (!isLocal) CallerLookupApiClient.updateStatus(context, jobId, "stopped")
                return
            }
            if (pauseRequested.get()) {
                job = job.copy(status = "paused", updatedAt = System.currentTimeMillis())
                dao.upsertJob(job)
                insertLog(dao, jobId, "info", "Paused by user at index ${job.cursorIndex}")
                maybeUpload(context, dao, job, isLocal)
                if (!isLocal) CallerLookupApiClient.updateStatus(context, jobId, "paused")
                return
            }

            val number = numberAt(job.startNumber, job.cursorIndex)
            val outcome = lookupWithRetries(provider, number, job.maxRetries)
            val now = System.currentTimeMillis()

            dao.insertResults(
                listOf(
                    CallerLookupResultEntity(
                        jobId = jobId,
                        phoneNumber = outcome.phoneNumber,
                        callerName = outcome.callerName,
                        lookupStatus = outcome.status,
                        provider = outcome.providerLabel,
                        lookupProviderId = outcome.providerId,
                        metadataJson = outcome.metadataJson,
                        kycJson = outcome.kycJson,
                        rawResponseJson = outcome.rawResponseJson,
                        durationMs = outcome.durationMs,
                        retryCount = 0,
                        error = outcome.error,
                        lookedUpAt = now
                    )
                )
            )

            val found = outcome.status == "found"
            insertLog(
                dao,
                jobId,
                if (found) "success" else if (outcome.status == "error") "error" else "failure",
                when {
                    found -> "Found: ${outcome.callerName}"
                    outcome.status == "error" -> outcome.error ?: "Lookup error"
                    else -> "No name on device"
                },
                outcome.phoneNumber,
                outcome.durationMs
            )

            job = job.copy(
                cursorIndex = job.cursorIndex + 1,
                processed = job.processed + 1,
                successful = job.successful + if (found) 1 else 0,
                failed = job.failed + if (found) 0 else 1,
                totalLookupDurationMs = job.totalLookupDurationMs + outcome.durationMs,
                currentNumber = number,
                updatedAt = System.currentTimeMillis()
            )
            dao.upsertJob(job)

            // Upload every 10 numbers (or on found) so dashboard stays live without thrashing
            if (job.processed % 10 == 0 || found) {
                maybeUpload(context, dao, job, isLocal)
            }

            if (job.delayMs > 0) Thread.sleep(job.delayMs)
        }

        job = job.copy(
            status = "completed",
            currentNumber = null,
            updatedAt = System.currentTimeMillis()
        )
        dao.upsertJob(job)
        insertLog(dao, jobId, "info", "Batch completed — found ${job.successful} name(s)")
        maybeUpload(context, dao, job, isLocal)
        if (!isLocal) CallerLookupApiClient.updateStatus(context, jobId, "completed")
        Logger.log(context, "✅ Caller lookup completed: $jobId (found ${job.successful})")
        mainHandler.post { /* UI refresh hook via Activity polling */ }
    }

    private fun maybeUpload(
        context: Context,
        dao: CallerLookupDao,
        job: CallerLookupJobEntity,
        isLocal: Boolean
    ) {
        if (isLocal) return // local-* ids do not exist on the server
        try {
            val pending = dao.getPendingResults(job.jobId, 500)
            if (pending.isNotEmpty() &&
                CallerLookupApiClient.uploadResults(context, job, pending)
            ) {
                dao.markResultsUploaded(pending.map { it.localId })
            }
            val logs = dao.getPendingLogs(job.jobId, 500)
            if (logs.isNotEmpty() &&
                CallerLookupApiClient.uploadLogs(context, job.jobId, logs)
            ) {
                dao.markLogsUploaded(logs.map { it.localId })
            }
            CallerLookupApiClient.uploadProgress(context, job)
        } catch (t: Throwable) {
            Log.w(TAG, "Upload best-effort failed: ${t.message}")
        }
    }

    private fun lookupWithRetries(
        provider: PhoneLookupProvider,
        number: String,
        maxRetries: Int
    ): PhoneLookupOutcome {
        var retry = 0
        var outcome = provider.lookup(number)
        while (outcome.status == "error" && retry < maxRetries) {
            retry++
            Thread.sleep((200L * retry).coerceAtMost(800L))
            outcome = provider.lookup(number)
        }
        return outcome
    }

    private fun insertLog(
        dao: CallerLookupDao,
        jobId: String,
        level: String,
        message: String,
        phoneNumber: String? = null,
        durationMs: Long? = null
    ) {
        dao.insertLogs(
            listOf(
                CallerLookupLogEntity(
                    jobId = jobId,
                    level = level,
                    message = message,
                    phoneNumber = phoneNumber,
                    durationMs = durationMs
                )
            )
        )
    }

    fun numberAt(startNumber: String, index: Int): String =
        (startNumber.toLong() + index.toLong()).toString().padStart(10, '0')

    private fun JSONObjectSource(source: String): String =
        org.json.JSONObject().put("source", source).toString()
}
