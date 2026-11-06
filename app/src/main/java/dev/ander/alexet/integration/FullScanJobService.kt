package dev.ander.alexet.integration

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import dev.ander.alexet.data.AppGraph
import dev.ander.alexet.data.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * The daily full scan, run by the system while the phone charges and is not in use (an app in
 * the background is frozen within seconds, so it can't do this on its own). Thumbnails are kept;
 * only "Re-scan" in the settings makes them again.
 */
class FullScanJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            try {
                AppGraph.repository(applicationContext).runFullScan("daily", background = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Stopped by the system (onStopJob): not a failure, and the system already knows.
                throw e
            } catch (e: Exception) {
                AppLog.e(TAG, "Daily full scan failed", e)
            }
            jobFinished(params, false)
        }
        return true
    }

    /** Stopped by the system (phone in use, unplugged): tried again later. */
    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FullScanJob"
        private const val JOB_ID = 4201

        /** Idempotent; called on every app start (the job is not kept across reboots). */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, FullScanJobService::class.java))
                .setPeriodic(TimeUnit.HOURS.toMillis(24))
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .build()
            runCatching { scheduler.schedule(job) }.onFailure { AppLog.e(TAG, "Scheduling failed", it) }
        }
    }
}
