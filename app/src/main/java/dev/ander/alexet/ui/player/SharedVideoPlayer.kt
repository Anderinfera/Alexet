package dev.ander.alexet.ui.player

import android.content.Context
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer

/**
 * One ExoPlayer for all videos of the viewer. Building one for every video page and releasing it
 * when the page was swiped away ran on the main thread each time (the release waits for the
 * decoders to close): the next page's first frames stalled. Here a page takes the player
 * ([acquire]) and gives it back cleared ([handBack]): stopped, without its video, with speed, loop
 * and track choices as a new player has them. The viewer releases it when it closes ([release]).
 *
 * Only one page holds it at a time. The next page takes it before the previous one has let go
 * (composition runs before the old page is forgotten), so each holder has a token and only the
 * current holder's hand-back or clean-up touches the player.
 */
class SharedVideoPlayer(private val appContext: Context) {
    private var player: ExoPlayer? = null
    private var owner: Any? = null
    private var defaultTracks: TrackSelectionParameters? = null

    /** The player for [token]'s video; whatever the previous holder left is cleared first. */
    fun acquire(token: Any, build: (Context) -> ExoPlayer): ExoPlayer {
        val current = player ?: build(appContext).also {
            player = it
            defaultTracks = it.trackSelectionParameters
        }
        if (owner != null && owner !== token) clear(current)
        owner = token
        return current
    }

    /** True while [token] holds the player (a page's late clean-up must not stop the next video). */
    fun isHeldBy(token: Any): Boolean = owner === token

    /** [token]'s page went: the player stops and forgets its video, kept for the next page. */
    fun handBack(token: Any) {
        if (owner !== token) return
        owner = null
        player?.let { clear(it) }
    }

    /** The viewer closed: the player goes. */
    fun release() {
        val p = player ?: return
        player = null
        owner = null
        dev.ander.alexet.integration.BackgroundPlayback.stop(appContext, p)
        runCatching { p.clearMediaItems() }
        p.release()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun clear(p: ExoPlayer) {
        dev.ander.alexet.integration.BackgroundPlayback.stop(appContext, p)
        p.playWhenReady = false
        // A swipe-seek cut short leaves scrubbing on: the next video must not start in it.
        runCatching { p.setScrubbingModeEnabled(false) }
        p.stop()
        p.clearMediaItems()
        p.playbackParameters = PlaybackParameters.DEFAULT
        p.repeatMode = Player.REPEAT_MODE_OFF
        defaultTracks?.let { p.trackSelectionParameters = it }
    }
}
