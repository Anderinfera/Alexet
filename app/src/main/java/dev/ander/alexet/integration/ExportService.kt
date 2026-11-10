package dev.ander.alexet.integration

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.ander.alexet.R
import dev.ander.alexet.data.AppLog

/**
 * Keeps the app alive while a video export runs: a long export used to be stopped by the system
 * once the user switched to another app, with no word about it. The export itself runs in the
 * screen's ViewModel; this only holds the foreground state and shows the progress.
 */
class ExportService : Service() {
    private var inForeground = false
    /** The newest command: stopping with it leaves a start sent meanwhile (a next export) alive. */
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_STOP) {
            finish()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CANCEL) {
            // The notification's "Cancel": the export stops, and with it this service.
            cancelHandler?.invoke()
            return START_NOT_STICKY
        }
        // Every startForegroundService needs its startForeground, also when already running.
        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(this, progress),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        }.onFailure { AppLog.e(TAG, "startForeground failed", it) }.isSuccess
        if (!started) {
            // The export goes on in the app; only the guarantee after leaving it is lost.
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        inForeground = true
        foreground = true
        // The export ended before this start was handled (see stop): done now that the
        // startForeground it owed has been made.
        if (!running) finish()
        return START_NOT_STICKY
    }

    private fun finish() {
        if (inForeground) {
            inForeground = false
            foreground = false
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        stopSelfResult(lastStartId)
    }

    /** Android 15+ limits data-sync services; the export goes on in the app. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLog.w(TAG, "Foreground time limit reached")
        finish()
    }

    companion object {
        private const val TAG = "ExportService"
        private const val CHANNEL_ID = "alexet_video_export"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_START = "dev.ander.alexet.action.EXPORT_START"
        private const val ACTION_STOP = "dev.ander.alexet.action.EXPORT_STOP"
        private const val ACTION_CANCEL = "dev.ander.alexet.action.EXPORT_CANCEL"
        private const val RESULT_NOTIFICATION_ID = 4202

        /** Opens the gallery at a folder (the finished quick edit's notification). */
        const val ACTION_SHOW_FOLDER = "dev.ander.alexet.action.SHOW_FOLDER"
        const val EXTRA_FOLDER_ID = "folder_id"

        @Volatile private var progress = 0
        @Volatile private var running = false
        /** The notification's title (null: the export's usual one) and whether it offers "Cancel". */
        @Volatile private var title: String? = null
        @Volatile private var cancelable = false

        /** Called (on the main thread) when "Cancel" is tapped in the notification. */
        @Volatile var cancelHandler: (() -> Unit)? = null
        /** The service has made its startForeground (stopping it from outside is safe then). */
        @Volatile private var foreground = false

        fun start(context: Context, title: String? = null, cancelable: Boolean = false) {
            progress = 0
            running = true
            this.title = title
            this.cancelable = cancelable
            val intent = Intent(context, ExportService::class.java).setAction(ACTION_START)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { AppLog.e(TAG, "Could not start the service", it) }
        }

        /** Updates the notification; called often, so only whole new percents go out. */
        fun update(context: Context, percent: Int) {
            if (!running || percent == progress) return
            progress = percent
            FolderDeleteNotifications.notify(context, NOTIFICATION_ID, notification(context, percent))
        }

        fun stop(context: Context) {
            if (!running) return
            running = false
            val intent = Intent(context, ExportService::class.java).setAction(ACTION_STOP)
            // Through the queue of commands: the start command (and its startForeground) is
            // handled first, even when the export ended right after it began. When the command
            // can't be sent, stopping from outside is only safe once in the foreground: before
            // that it was a crash, and the start command sees running = false and ends by itself.
            runCatching { context.startService(intent) }
                .onFailure {
                    if (foreground) runCatching { context.stopService(Intent(context, ExportService::class.java)) }
                }
        }

        private fun notification(context: Context, percent: Int): Notification {
            channel(context)
            return progressNotification(context, percent)
        }

        private fun channel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL_ID, context.getString(R.string.export_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                            description = context.getString(R.string.export_channel_desc)
                            setShowBadge(false)
                        },
                    )
                }
            }
        }

        private fun progressNotification(context: Context, percent: Int): Notification {
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title ?: context.getString(R.string.export_running_title))
                .setContentText("$percent%")
                .setProgress(100, percent.coerceIn(0, 100), false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .apply {
                    openApp(context)?.let(::setContentIntent)
                    if (cancelable) {
                        val cancel = PendingIntent.getService(
                            context,
                            1,
                            Intent(context, ExportService::class.java).setAction(ACTION_CANCEL),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        )
                        addAction(0, context.getString(R.string.cancel), cancel)
                    }
                }
                .build()
        }

        /**
         * The outcome once the work is over ("Quick edit finished"): a tap opens the gallery at
         * [folderId] (null: just the app).
         */
        fun notifyResult(context: Context, title: String, text: String, folderId: Long?) {
            channel(context)
            val open = if (folderId != null) {
                PendingIntent.getActivity(
                    context,
                    2,
                    Intent(context, dev.ander.alexet.MainActivity::class.java)
                        .setAction(ACTION_SHOW_FOLDER)
                        .putExtra(EXTRA_FOLDER_ID, folderId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            } else {
                openApp(context)
            }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .apply { open?.let(::setContentIntent) }
                .build()
            FolderDeleteNotifications.notify(context, RESULT_NOTIFICATION_ID, notification)
        }

        private fun openApp(context: Context): PendingIntent? {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
            return PendingIntent.getActivity(context, 3, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
