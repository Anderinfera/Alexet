package dev.ander.alexet.ui.player

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.util.Rational
import dev.ander.alexet.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The video in a small window over other apps (picture in picture), opened with the player's
 * button. While it is shown the window holds the video alone: no controls, no viewer around it.
 */
object PictureInPicture {
    /** Broadcast of the window's play / pause button (only to this app). */
    const val ACTION_PLAY_PAUSE = "dev.ander.alexet.action.PIP_PLAY_PAUSE"

    private val _active = MutableStateFlow(false)
    /** The app is shown in the small window now. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** Set by the activity whenever the system opens or closes the window. */
    fun onModeChanged(inPictureInPicture: Boolean) {
        _active.value = inPictureInPicture
    }

    fun isSupported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** The window's shape (that of the video, within what the system allows) and its button. */
    fun params(context: Context, videoWidth: Int, videoHeight: Int, playing: Boolean): PictureInPictureParams {
        val action = RemoteAction(
            Icon.createWithResource(context, if (playing) R.drawable.ic_pip_pause else R.drawable.ic_pip_play),
            context.getString(R.string.play_pause),
            context.getString(R.string.play_pause),
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_PLAY_PAUSE).setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        return PictureInPictureParams.Builder()
            .setAspectRatio(aspectRatio(videoWidth, videoHeight))
            .setActions(listOf(action))
            .build()
    }

    /** False when the system refuses (the window is not available now). */
    fun enter(activity: Activity, params: PictureInPictureParams): Boolean = try {
        activity.enterPictureInPictureMode(params)
    } catch (_: IllegalStateException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    /** The system allows 1:2.39 up to 2.39:1; an unknown size gets 16:9. */
    internal fun aspectRatio(width: Int, height: Int): Rational {
        if (width <= 0 || height <= 0) return Rational(16, 9)
        val ratio = width.toFloat() / height
        return when {
            ratio > MAX_RATIO -> Rational(239, 100)
            ratio < 1f / MAX_RATIO -> Rational(100, 239)
            else -> Rational(width, height)
        }
    }

    private const val MAX_RATIO = 2.39f
}
