package dev.ander.alexet.integration

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.ander.alexet.data.AppLog as Log

/**
 * A video or song that goes on playing after the app is left: the viewer's own player gets a
 * media session, and [PlaybackService] keeps the app alive with the media notification until the
 * app is back. Nothing starts here that was not already playing in the app.
 * Main thread only.
 */
object BackgroundPlayback {
    private const val TAG = "BackgroundPlayback"

    var session: MediaSession? = null
        private set
    private var sessionCount = 0

    /** [player] goes on playing in the background. False when the system does not allow it now. */
    fun start(context: Context, player: Player): Boolean {
        val app = context.applicationContext
        val current = session
        val active = if (current != null && current.player === player) {
            current
        } else {
            current?.release()
            MediaSession.Builder(app, player)
                .setId("alexet-playback-${++sessionCount}")
                .apply { openAppIntent(app)?.let(::setSessionActivity) }
                .build()
        }
        session = active
        return try {
            app.startService(Intent(app, PlaybackService::class.java))
            true
        } catch (e: RuntimeException) {
            // Not allowed from the background: the video pauses as it did before.
            Log.w(TAG, "background playback not started", e)
            active.release()
            session = null
            false
        }
    }

    /** Back in the app, or [player] is gone: the notification goes, and the session with it. */
    fun stop(context: Context, player: Player) {
        val active = session ?: return
        if (active.player !== player) return
        session = null
        val app = context.applicationContext
        app.stopService(Intent(app, PlaybackService::class.java))
        active.release()
    }

    /** The notification opens the app where it was (the activity is single task). */
    private fun openAppIntent(context: Context): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** Keeps the app running with the media notification while [BackgroundPlayback] plays. */
class PlaybackService : MediaSessionService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val active = BackgroundPlayback.session
        if (active == null) {
            // Restarted by the system after its process died: nothing plays any more.
            stopSelf()
        } else if (active !in sessions) {
            addSession(active)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        BackgroundPlayback.session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiped away from Recents: playback ends with the app.
        BackgroundPlayback.session?.player?.pause()
        stopSelf()
    }
}
