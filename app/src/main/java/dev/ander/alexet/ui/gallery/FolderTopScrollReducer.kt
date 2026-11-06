package dev.ander.alexet.ui.gallery

/**
 * Stan pomocniczy do decydowania o zachowaniu scrolla w widoku folderu.
 */
data class FolderTopScrollState(
    val userScrolledAwayFromTop: Boolean,
)

/**
 * Zdarzenia wpływające na pozycję scrolla folderu.
 */
sealed interface FolderTopScrollEvent {
    /**
     * Zdarzenie przewijania przez użytkownika (gest w toku).
     */
    data class ScrollInProgress(val index: Int, val offset: Int) : FolderTopScrollEvent

    /**
     * Zdarzenie stanu spoczynku (brak aktywnego gestu).
     */
    data class ScrollIdle(val index: Int, val offset: Int) : FolderTopScrollEvent

    /**
     * Zdarzenie pojawienia się nowej zawartości w folderze.
     */
    data class NewContentArrived(val hasNewFiles: Boolean) : FolderTopScrollEvent
}

/**
 * Akcje wynikowe decydujące o konieczności przewinięcia na górę.
 */
sealed interface FolderTopScrollAction {
    object None : FolderTopScrollAction
    object ScrollToTop : FolderTopScrollAction
}

object FolderTopScrollReducer {

    /**
     * Wyznacza stan początkowy flagi [userScrolledAwayFromTop].
     * Respektuje zarówno pozycję z żywego [gridIndex]/[gridOffset], jak i [initialIndex]/[initialOffset].
     */
    fun initialAwayState(
        gridIndex: Int = 0,
        gridOffset: Int = 0,
        initialIndex: Int = 0,
        initialOffset: Int = 0,
    ): Boolean {
        return (gridIndex > 0 || gridOffset > 0) || (initialIndex > 0 || initialOffset > 0)
    }

    /**
     * Przetwarza zdarzenie i decyduje o aktualizacji stanu oraz akcji przewinięcia.
     *
     * @param currentState Aktualny stan
     * @param event Zdarzenie
     * @param forceScrollToTopOnNewContent Ustawienie newContentScrollsToTop z preferencji
     */
    fun reduce(
        currentState: FolderTopScrollState,
        event: FolderTopScrollEvent,
        forceScrollToTopOnNewContent: Boolean = false,
    ): Pair<FolderTopScrollState, FolderTopScrollAction> {
        return when (event) {
            is FolderTopScrollEvent.ScrollInProgress -> {
                val isAway = event.index > 0 || event.offset > 0
                val nextState = currentState.copy(userScrolledAwayFromTop = isAway)
                nextState to FolderTopScrollAction.None
            }
            is FolderTopScrollEvent.ScrollIdle -> {
                if (event.index == 0 && event.offset == 0) {
                    // Dotarliśmy dokładnie na sam szczyt
                    val nextState = currentState.copy(userScrolledAwayFromTop = false)
                    nextState to FolderTopScrollAction.None
                } else {
                    // Użytkownik odjechał od góry i jest w spoczynku (np. po suwaku, gestach lub flingu)
                    val nextState = currentState.copy(userScrolledAwayFromTop = true)
                    nextState to FolderTopScrollAction.None
                }
            }
            is FolderTopScrollEvent.NewContentArrived -> {
                if (event.hasNewFiles && forceScrollToTopOnNewContent && currentState.userScrolledAwayFromTop) {
                    // Ustawienie wymuszonego skoku na górę przy nowej zawartości (nawet gdy użytkownik odjechał)
                    currentState.copy(userScrolledAwayFromTop = false) to FolderTopScrollAction.ScrollToTop
                } else if (event.hasNewFiles && !currentState.userScrolledAwayFromTop) {
                    // Użytkownik jest na górze: siatka z kluczami trzymałaby dawny pierwszy plik w miejscu,
                    // a nowe pliki nad nim byłyby niewidoczne. Zostajemy na samej górze.
                    currentState to FolderTopScrollAction.ScrollToTop
                } else {
                    currentState to FolderTopScrollAction.None
                }
            }
        }
    }
}
