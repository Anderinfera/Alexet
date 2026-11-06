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
 * Smart search indexing while the phone charges: photos not indexed while the app was open.
 * The system gives a job a few minutes; each run continues where the last one stopped.
 */
class SmartIndexJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            try {
                AppGraph.smartSearch(applicationContext).runBackgroundIndexing()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(TAG, "Smart search indexing failed", e)
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SmartIndexJob"
        private const val JOB_ID = 4202

        /** Idempotent; while photos wait to be indexed. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, SmartIndexJobService::class.java))
                .setPeriodic(TimeUnit.MINUTES.toMillis(30))
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .build()
            runCatching { scheduler.schedule(job) }.onFailure { AppLog.e(TAG, "Scheduling failed", it) }
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }
    }
}
