package dev.ander.alexet.ui.player

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.ander.alexet.data.SponsorBlockCategory

/**
 * Follows the segments of the current video and skips those of the [autoSkip] categories as
 * playback reaches them. The player's progress ticker calls [skipSegmentReached].
 */
@UnstableApi
class SponsorBlockSkipper(
    private val player: Player,
    private val autoSkip: () -> Set<SponsorBlockCategory>,
    private val onSegmentsChanged: (List<SponsorSegment>) -> Unit,
    private val onSkipped: (SponsorBlockCategory) -> Unit,
) : Player.Listener {

    private var segments = emptyList<SponsorSegment>()
    private var lastPositionMs = 0L

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(Player.EVENT_TRACKS_CHANGED, Player.EVENT_TIMELINE_CHANGED)) {
            updateSegments(player.currentTracks.chapters(player.duration).toSponsorSegments(player.duration))
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        lastPositionMs = newPosition.positionMs
    }

    fun updateSegments(newSegments: List<SponsorSegment>) {
        if (newSegments == segments) return
        segments = newSegments
        onSegmentsChanged(newSegments)
    }

    fun skipSegmentReached() {
        val autoSkip = autoSkip()
        val positionMs = player.currentPosition
        val segment = segments.autoSkipSegment(lastPositionMs, positionMs, autoSkip)
        lastPositionMs = positionMs
        if (segment == null) return
        player.seekTo(segment.endMs.coerceAtMost(player.duration))
        onSkipped(segment.categories.first { it in autoSkip })
    }
}
