package dev.ander.alexet.integration

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.ander.alexet.R
import dev.ander.alexet.data.AppLog
import dev.ander.alexet.data.FolderDeleteProgress
import dev.ander.alexet.data.FolderDeleteQueue
import dev.ander.alexet.data.FolderDeleteResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the app alive while [FolderDeleteQueue] deletes folders, so the work finishes after the
 * user leaves the app. The work itself runs in the queue; this only holds the foreground state
 * and the progress notification (with Cancel).
 */
class FolderDeleteService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var inForeground = false
    private var progressJob: kotlinx.coroutines.Job? = null
    /**
     * The newest start command: stopping with it leaves a start sent meanwhile (a new folder
     * queued) running. A plain stopSelf ended that one before its startForeground, a crash.
     */
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_CANCEL) {
            FolderDeleteQueue.cancelAll()
            if (!inForeground) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (!inForeground) {
            // Started with startForegroundService: startForeground must follow, even when the
            // queue already finished in the meantime.
            val notification = FolderDeleteNotifications.progress(this, FolderDeleteQueue.jobs.value)
            val started = runCatching {
                ServiceCompat.startForeground(
                    this,
                    FolderDeleteNotifications.PROGRESS_ID,
                    notification,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
                )
            }.onFailure { AppLog.e(TAG, "startForeground failed", it) }.isSuccess
            if (!started) {
                // The deleting goes on in the app; only the guarantee after leaving it is lost.
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            inForeground = true
            progressJob = scope.launch {
                var lastShown: List<Any>? = null
                FolderDeleteQueue.jobs.collect { jobs ->
                    if (jobs.isEmpty()) {
                        finish()
                        return@collect
                    }
                    // Only when what the notification shows changed, and built off the main thread
                    // (several system calls each time).
                    val shown = listOf(jobs.size, jobs.values.sumOf { it.total }, jobs.values.sumOf { it.done }) +
                        jobs.values.map { it.name to it.running }
                    if (shown == lastShown) return@collect
                    lastShown = shown
                    val notification = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        FolderDeleteNotifications.progress(this@FolderDeleteService, jobs)
                    }
                    FolderDeleteNotifications.notify(this@FolderDeleteService, FolderDeleteNotifications.PROGRESS_ID, notification)
                    // The system drops notification updates sent faster than a few per second.
                    delay(400)
                }
            }
        } else {
            // Every startForegroundService needs its startForeground; repeating it while already in
            // the foreground is harmless and covers systems that ask for it again.
            runCatching {
                ServiceCompat.startForeground(
                    this,
                    FolderDeleteNotifications.PROGRESS_ID,
                    FolderDeleteNotifications.progress(this, FolderDeleteQueue.jobs.value),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
                )
            }.onFailure { AppLog.e(TAG, "startForeground (again) failed", it) }
        }
        return START_NOT_STICKY
    }

    private fun finish() {
        if (!inForeground) return
        inForeground = false
        progressJob?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // A start that came in meanwhile keeps the service; it goes into the foreground again.
        stopSelfResult(lastStartId)
    }

    /** Android 15+ ends data-sync services after 6 h a day; the queue keeps going in the app. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLog.w(TAG, "Foreground time limit reached")
        finish()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FolderDeleteService"
        private const val ACTION_START = "dev.ander.alexet.action.FOLDER_DELETE_START"
        const val ACTION_CANCEL = "dev.ander.alexet.action.FOLDER_DELETE_CANCEL"

        fun start(context: Context) {
            val intent = Intent(context, FolderDeleteService::class.java).setAction(ACTION_START)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { AppLog.e(TAG, "Could not start the service", it) }
        }
    }
}

object FolderDeleteNotifications {
    private const val CHANNEL_ID = "alexet_folder_delete"
    const val PROGRESS_ID = 4101
    private const val RESULT_ID = 4102

    @Volatile private var channelReady = false

    private fun ensureChannel(context: Context) {
        if (channelReady || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) {
            channelReady = true
            return
        }
        channelReady = true
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.folder_delete_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.folder_delete_channel_desc)
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            },
        )
    }

    private fun openAppIntent(context: Context): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun progress(context: Context, jobs: Map<Long, FolderDeleteProgress>): Notification {
        ensureChannel(context)
        val list = jobs.values.toList()
        val current = list.firstOrNull { it.running } ?: list.firstOrNull()
        val title = when {
            list.size > 1 -> context.getString(R.string.folder_delete_title_many, list.size)
            current == null -> context.getString(R.string.folder_delete_channel_name)
            current.toTrash -> context.getString(R.string.folder_trash_title_one, current.name)
            else -> context.getString(R.string.folder_delete_title_one, current.name)
        }
        val total = list.sumOf { it.total }
        val done = list.sumOf { it.done }
        val cancel = PendingIntent.getService(
            context,
            1,
            Intent(context, FolderDeleteService::class.java).setAction(FolderDeleteService.ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_delete)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.folder_delete_progress, done, total))
            .setProgress(total.coerceAtLeast(1), done.coerceAtMost(total), total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(context))
            .addAction(0, context.getString(R.string.cancel), cancel)
            .build()
    }

    fun showResult(context: Context, result: FolderDeleteResult) {
        ensureChannel(context)
        val title = when {
            result.cancelled -> context.getString(R.string.folder_delete_cancelled, result.name)
            result.toTrash -> context.getString(R.string.folder_trash_done, result.name)
            else -> context.getString(R.string.folder_delete_done, result.name)
        }
        var text = context.getString(R.string.folder_delete_result_count, result.removed, result.total)
        if (result.failed > 0) text += " · " + context.getString(R.string.folder_delete_result_failed, result.failed)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_delete)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent(context))
            .build()
        notify(context, RESULT_ID, notification)
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun notify(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}
