package dev.ander.alexet.integration

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.ander.alexet.R
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob

class TransferNotifier(context: Context) {
    private val context: Context = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(this.context)
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        dev.ander.alexet.data.AppLog.e("TransferNotifier", "Uncaught coroutine exception", throwable)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + coroutineExceptionHandler)
    private var dismissJob: Job? = null
    /** After [close] (the screen's ViewModel is gone) a copy still finishing posts nothing. */
    @Volatile private var closed = false
    @Volatile private var channelReady = false

    /** Last progress posted: the system drops updates beyond a few per second anyway. */
    private var lastProgressAtMs = 0L
    private var lastProgressPercent = -1

    init {
        // Off the main thread: both are binder calls, and this is built while the first screen is.
        scope.launch(Dispatchers.IO) {
            ensureChannel()
            // A process killed while copying leaves its "in progress" notification behind, and an
            // ongoing one can't be swiped away.
            runCatching { notificationManager.cancel(NOTIFICATION_ID) }
        }
    }

    @Synchronized
    private fun ensureChannel() {
        if (channelReady) return
        channelReady = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.transfer_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.transfer_channel_desc)
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    // Progress comes from the copy's IO thread, the rest from the main thread.
    @Synchronized
    fun showProgress(current: Int, total: Int, isMove: Boolean) {
        if (closed) return
        dismissJob?.cancel()
        val percent = if (total > 0) ((current * 100) / total).coerceIn(0, 100) else 0
        // Called after every file: thousands of small files meant thousands of notify() calls.
        val now = android.os.SystemClock.elapsedRealtime()
        val first = lastProgressPercent < 0
        val last = current >= total
        if (!first && !last && (percent == lastProgressPercent || now - lastProgressAtMs < PROGRESS_MIN_INTERVAL_MS)) return
        lastProgressAtMs = now
        lastProgressPercent = percent
        val title = context.getString(
            if (isMove) R.string.transfer_moving_title else R.string.transfer_copying_title,
        )
        val text = "$current / $total ($percent%)"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(total, current, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

        notifySafely(NOTIFICATION_ID, notification)
    }

    @Synchronized
    fun showCompleted(total: Int, isMove: Boolean) {
        if (closed) return
        dismissJob?.cancel()
        lastProgressPercent = -1
        val title = context.getString(
            if (isMove) R.string.transfer_move_completed else R.string.transfer_copy_completed,
        )
        val text = context.getString(
            if (isMove) R.string.transfer_move_completed_body else R.string.transfer_copy_completed_body,
            total,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        notifySafely(NOTIFICATION_ID, notification)

        dismissJob = scope.launch {
            delay(3500L)
            this@TransferNotifier.cancel()
        }
    }

    @Synchronized
    fun showFailed(isMove: Boolean) {
        if (closed) return
        dismissJob?.cancel()
        lastProgressPercent = -1
        val title = context.getString(
            if (isMove) R.string.move_failed else R.string.copy_failed,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notifySafely(NOTIFICATION_ID, notification)

        dismissJob = scope.launch {
            delay(4000L)
            this@TransferNotifier.cancel()
        }
    }

    @Synchronized
    fun cancel() {
        dismissJob?.cancel()
        lastProgressPercent = -1
        runCatching {
            notificationManager.cancel(NOTIFICATION_ID)
        }
    }

    /** For good: also later progress of a copy that outlives the screen is not shown. */
    fun close() {
        closed = true
        cancel()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun notifySafely(id: Int, notification: android.app.Notification) {
        ensureChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }
        try {
            notificationManager.notify(id, notification)
        } catch (_: SecurityException) {
        } catch (_: Exception) {}
    }

    companion object {
        const val CHANNEL_ID = "alexet_transfer_channel"
        const val NOTIFICATION_ID = 4001
        private const val PROGRESS_MIN_INTERVAL_MS = 300L
    }
}
