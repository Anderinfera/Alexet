package dev.ander.alexet.ui.player

import androidx.media3.common.C
import androidx.media3.common.Player
import dev.ander.alexet.data.SponsorBlockCategory
import dev.ander.alexet.data.SponsorBlockCategory.SELF_PROMO
import dev.ander.alexet.data.SponsorBlockCategory.SPONSOR
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class SponsorBlockSkipperTest {
    private val player = mock(Player::class.java).also { `when`(it.duration).thenReturn(6_000L) }
    private val skipped = mutableListOf<SponsorBlockCategory>()
    private val shown = mutableListOf<List<SponsorSegment>>()
    private val segments = listOf(
        SponsorSegment(1_500, 3_000, listOf(SPONSOR)),
        SponsorSegment(5_000, 6_000, listOf(SELF_PROMO)),
    )

    private fun skipper(autoSkip: Set<SponsorBlockCategory> = setOf(SPONSOR)) =
        SponsorBlockSkipper(player, { autoSkip }, { shown += it }, { skipped += it }).also {
            it.updateSegments(segments)
            it.seekedTo(500)
        }

    private fun SponsorBlockSkipper.seekedTo(positionMs: Long) {
        val position = Player.PositionInfo(null, 0, null, null, 0, positionMs, positionMs, C.INDEX_UNSET, C.INDEX_UNSET)
        onPositionDiscontinuity(position, position, Player.DISCONTINUITY_REASON_SEEK)
    }

    private fun SponsorBlockSkipper.tickAt(positionMs: Long) {
        `when`(player.currentPosition).thenReturn(positionMs)
        skipSegmentReached()
    }

    @Test
    fun playingIntoASponsorJumpsToItsEndAndReportsTheCategory() {
        skipper().tickAt(1_600)

        verify(player).seekTo(3_000)
        assertEquals(listOf(SPONSOR), skipped)
    }

    @Test
    fun aCategoryThatIsNotChosenKeepsPlaying() {
        skipper().apply {
            tickAt(1_400)
            seekedTo(4_900)
            tickAt(5_100)
        }

        verify(player, never()).seekTo(5_100)
        verify(player, never()).seekTo(6_000)
        assertEquals(emptyList<SponsorBlockCategory>(), skipped)
    }

    @Test
    fun theSameFileSkipsSelfPromotionOnceItIsChosen() {
        skipper(autoSkip = setOf(SELF_PROMO)).apply {
            tickAt(1_600)
            seekedTo(4_900)
            tickAt(5_100)
        }

        verify(player, never()).seekTo(3_000)
        verify(player).seekTo(6_000)
        assertEquals(listOf(SELF_PROMO), skipped)
    }

    @Test
    fun aManualSeekIntoASponsorPlaysIt() {
        skipper().apply {
            seekedTo(2_000)
            tickAt(2_200)
        }

        verify(player, never()).seekTo(3_000)
        assertEquals(emptyList<SponsorBlockCategory>(), skipped)
    }

    @Test
    fun aSponsorIsSkippedAgainAfterSeekingBackBeforeIt() {
        skipper().apply {
            tickAt(1_600)
            seekedTo(1_000)
            tickAt(1_600)
        }

        assertEquals(listOf(SPONSOR, SPONSOR), skipped)
    }

    @Test
    fun aSegmentEndingPastTheVideoStopsAtItsEnd() {
        `when`(player.duration).thenReturn(5_500L)
        skipper(autoSkip = setOf(SELF_PROMO)).apply {
            seekedTo(4_900)
            tickAt(5_100)
        }

        verify(player).seekTo(5_500)
    }

    @Test
    fun reportsSegmentsOnlyWhenTheyChange() {
        val skipper = skipper()

        skipper.updateSegments(segments.toList())
        skipper.updateSegments(emptyList())

        assertEquals(listOf(segments, emptyList()), shown)
    }
}
