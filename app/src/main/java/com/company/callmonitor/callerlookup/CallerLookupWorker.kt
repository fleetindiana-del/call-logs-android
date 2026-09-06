package com.company.callmonitor.callerlookup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.company.callmonitor.EnrollmentPrefs
import java.util.concurrent.TimeUnit

/**
 * WorkManager entry point. Actual batch execution lives in [CallerLookupRunner]
 * so user-started jobs keep running even when WorkManager is delayed or killed.
 */
class CallerLookupWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val requestedJobId = inputData.getString(KEY_JOB_ID)

        if (requestedJobId.isNullOrBlank()) {
            if (!EnrollmentPrefs.isEnrolled(applicationContext)) return Result.success()
            val pending = CallerLookupApiClient.getPendingJobs(applicationContext)
                ?: return Result.retry()
            pending.forEach { enqueue(applicationContext, it.id) }
            return Result.success()
        }

        // For remote jobs, refresh Room from the server first so Runner has bounds.
        if (!requestedJobId.startsWith("local-") && EnrollmentPrefs.isEnrolled(applicationContext)) {
            val remote = CallerLookupApiClient.getJob(applicationContext, requestedJobId)
            if (remote != null) {
                val dao = CallerLookupDatabase.getInstance(applicationContext).callerLookupDao()
                val existing = dao.getJob(remote.id)
                dao.upsertJob(
                    CallerLookupJobEntity(
                        jobId = remote.id,
                        status = remote.status,
                        deviceId = remote.deviceId,
                        mobileProvider = remote.mobileProvider,
                        seriesId = remote.seriesId,
                        seriesPrefix = remote.seriesPrefix,
                        seriesLabel = remote.seriesLabel,
                        startNumber = remote.startNumber,
                        endNumber = remote.endNumber,
                        totalPlanned = remote.totalPlanned,
                        delayMs = remote.delayMs,
                        workers = remote.workers,
                        lookupProviderId = remote.lookupProviderId,
                        maxRetries = remote.maxRetries,
                        cursorIndex = maxOf(existing?.cursorIndex ?: 0, remote.cursorIndex),
                        processed = maxOf(existing?.processed ?: 0, remote.processed),
                        successful = maxOf(existing?.successful ?: 0, remote.successful),
                        failed = maxOf(existing?.failed ?: 0, remote.failed),
                        totalLookupDurationMs = maxOf(
                            existing?.totalLookupDurationMs ?: 0,
                            remote.totalLookupDurationMs
                        ),
                        currentNumber = existing?.currentNumber ?: remote.currentNumber,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }

        if (!CallerLookupRunner.isRunning() || CallerLookupRunner.activeJobId() != requestedJobId) {
            CallerLookupRunner.start(applicationContext, requestedJobId)
        }
        return Result.success()
    }

    companion object {
        private const val KEY_JOB_ID = "jobId"

        fun enqueue(
            context: Context,
            jobId: String,
            policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP
        ) {
            CallerLookupRunner.start(context, jobId)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<CallerLookupWorker>()
                .setInputData(Data.Builder().putString(KEY_JOB_ID, jobId).build())
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "caller-lookup-$jobId",
                policy,
                request
            )
        }

        fun enqueueLocal(context: Context, jobId: String) {
            CallerLookupRunner.start(context, jobId)
        }

        fun enqueuePending(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<CallerLookupWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "caller-lookup-pending",
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun schedulePeriodicPoll(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<CallerLookupWorker>(
                15,
                TimeUnit.MINUTES
            ).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "caller-lookup-periodic-poll",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
