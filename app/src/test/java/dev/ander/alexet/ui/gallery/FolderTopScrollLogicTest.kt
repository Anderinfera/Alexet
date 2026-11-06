package dev.ander.alexet.ui.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderTopScrollLogicTest {

    @Test
    fun testInitialAwayState() {
        // Początek na samej górze
        assertFalse(FolderTopScrollReducer.initialAwayState(0, 0, 0, 0))

        // Początek z zapisaną pozycją > 0
        assertTrue(FolderTopScrollReducer.initialAwayState(0, 0, 15, 0))

        // Początek z żywego gridState > 0
        assertTrue(FolderTopScrollReducer.initialAwayState(5, 0, 0, 0))
    }

    @Test
    fun testIdleScrollAwaySetsFlagAndNoAction() {
        val state = FolderTopScrollState(userScrolledAwayFromTop = false)

        // Przewinięcie w stanie idle (np. suwak, fast scroll, programmatic scroll)
        val (nextState, action) = FolderTopScrollReducer.reduce(
            currentState = state,
            event = FolderTopScrollEvent.ScrollIdle(index = 3, offset = 0),
            forceScrollToTopOnNewContent = false,
        )

        // Flaga przełącza się na true, akcja: None (brak cofania)
        assertTrue(nextState.userScrolledAwayFromTop)
        assertEquals(FolderTopScrollAction.None, action)
    }

    @Test
    fun testUserScrollingAwaySetsFlagAndNoAction() {
        val state = FolderTopScrollState(userScrolledAwayFromTop = false)

        // Użytkownik aktywnie scrolluje w dół
        val (nextState, action) = FolderTopScrollReducer.reduce(
            currentState = state,
            event = FolderTopScrollEvent.ScrollInProgress(index = 10, offset = 50),
            forceScrollToTopOnNewContent = false,
        )

        assertTrue(nextState.userScrolledAwayFromTop)
        assertEquals(FolderTopScrollAction.None, action)
    }

    @Test
    fun testIdleAfterUserScrolledAwayDoesNotTriggerDriftCorrection() {
        val state = FolderTopScrollState(userScrolledAwayFromTop = true)

        // Użytkownik puścił palec, stoi w dole folderu
        val (nextState, action) = FolderTopScrollReducer.reduce(
            currentState = state,
            event = FolderTopScrollEvent.ScrollIdle(index = 10, offset = 50),
            forceScrollToTopOnNewContent = false,
        )

        assertTrue(nextState.userScrolledAwayFromTop)
        assertEquals(FolderTopScrollAction.None, action)
    }

    @Test
    fun testUserScrollingBackToTopClearsFlag() {
        val state = FolderTopScrollState(userScrolledAwayFromTop = true)

        // Użytkownik doscrollował na sam szczyt
        val (scrollingState, action1) = FolderTopScrollReducer.reduce(
            currentState = state,
            event = FolderTopScrollEvent.ScrollInProgress(index = 0, offset = 0),
            forceScrollToTopOnNewContent = false,
        )
        assertFalse(scrollingState.userScrolledAwayFromTop)
        assertEquals(FolderTopScrollAction.None, action1)

        // Zatrzymanie na (0,0)
        val (idleState, action2) = FolderTopScrollReducer.reduce(
            currentState = scrollingState,
            event = FolderTopScrollEvent.ScrollIdle(index = 0, offset = 0),
            forceScrollToTopOnNewContent = false,
        )
        assertFalse(idleState.userScrolledAwayFromTop)
        assertEquals(FolderTopScrollAction.None, action2)
    }

    @Test
    fun testForceOnNewContentWhenAway() {
        val state = FolderTopScrollState(userScrolledAwayFromTop = true)

        // Gdy opcja jest wyłączona (domyślnie): brak akcji
        val (stateOff, actionOff) = FolderTopScrollReducer.reduce(
            currentState = state,
            event = FolderTopScrollEvent.NewContentArrived(hasNewFiles = true),
            forceScrollToTopOnNewContent = false,
        )
        assertTrue(stateOff.userScrolledAwayFromTop)
        assertEquals(FolderTopScrollAction.None, actionOff)

        // Gdy opcja jest włączona: wymuszony skok na szczyt
        val (stateOn, actionOn) = FolderTopScrollReducer.reduce(
            currentState = state,
            event = FolderTopScrollEvent.NewContentArrived(hasNewFiles = true),
            forceScrollToTopOnNewContent = true,
        )
        assertFalse(stateOn.userScrolledAwayFromTop)
        assertEquals(FolderTopScrollAction.ScrollToTop, actionOn)
    }
}
