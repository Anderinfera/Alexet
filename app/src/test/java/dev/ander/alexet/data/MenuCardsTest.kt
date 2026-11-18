package dev.ander.alexet.data

import dev.ander.alexet.data.SelectionAction.COPY
import dev.ander.alexet.data.SelectionAction.DELETE
import dev.ander.alexet.data.SelectionAction.FAVORITE
import dev.ander.alexet.data.SelectionAction.HIDE
import dev.ander.alexet.data.SelectionAction.INFO
import dev.ander.alexet.data.SelectionAction.MOVE
import dev.ander.alexet.data.SelectionAction.RENAME
import dev.ander.alexet.data.SelectionAction.SHARE
import org.junit.Assert.assertEquals
import org.junit.Test

class MenuCardsTest {

    private val bar = listOf(DELETE, SHARE, INFO, null, null, null)

    @Test
    fun withoutExtraCardsThereIsOneCard() {
        val s = AppSettings(barActionSlots = bar)
        val cards = s.menuCards(wide = false)
        assertEquals(1, cards.size)
        assertEquals(s.activeMenuActions(wide = false), cards[0])
    }

    @Test
    fun extraCardsFollowTheFirstAndMayRepeatActions() {
        val s = AppSettings(
            barActionSlots = bar,
            extraMenuCards = listOf(listOf(MOVE, COPY), listOf(MOVE, FAVORITE)),
        )
        val cards = s.menuCards(wide = false)
        assertEquals(3, cards.size)
        assertEquals(listOf(MOVE, COPY), cards[1])
        assertEquals(listOf(MOVE, FAVORITE), cards[2])
        // MOVE is on card 1 as well.
        assertEquals(true, MOVE in cards[0])
    }

    @Test
    fun barActionsLeaveEveryCardAndEmptiedCardsAreSkipped() {
        val s = AppSettings(
            barActionSlots = bar,
            extraMenuCards = listOf(listOf(SHARE, RENAME), listOf(DELETE, INFO)),
        )
        val cards = s.menuCards(wide = false)
        assertEquals(2, cards.size)
        assertEquals(listOf(RENAME), cards[1])
    }

    @Test
    fun atMostThreeCards() {
        val s = AppSettings(
            barActionSlots = bar,
            extraMenuCards = listOf(listOf(MOVE), listOf(COPY), listOf(HIDE)),
        )
        assertEquals(3, s.menuCards(wide = false).size)
    }
}
