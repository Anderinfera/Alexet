package dev.ander.alexet.ui.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ListReuseAndReorderRowsTest {

    @Test
    fun aListIsReusedWhileItsInputsAreTheSameObjects() {
        val media = listOf(1, 2, 3)
        val hidden = setOf(2)
        var computed = 0
        val first = UiStateMemo.get("testReuse", media, false, hidden) { computed++; media.filter { it !in hidden } }
        // Another settings object around the same lists (a scroll position saved): same result.
        val again = UiStateMemo.get("testReuse", media, false, hidden) { computed++; media.filter { it !in hidden } }
        assertSame(first, again)
        assertEquals(1, computed)
        // A really changed input: worked out again.
        val otherHidden = setOf(3)
        val changed = UiStateMemo.get("testReuse", media, false, otherHidden) { computed++; media.filter { it !in otherHidden } }
        assertNotSame(first, changed)
        assertEquals(listOf(1, 2), changed)
        assertEquals(2, computed)
        // Equal values (not the same object) of a plain type still count as the same.
        val byValue = UiStateMemo.get("testReuse", media, false, otherHidden) { computed++; emptyList<Int>() }
        assertSame(changed, byValue)
    }

    @Test
    fun onlyTheRowsOnScreenAndAMarginAreShown() {
        // 100 px rows, a 450 px view: rows 0-4 on screen, plus 2 rows each way.
        assertEquals(0..6, reorderShownRows(scrollY = 0, viewportHeight = 450f, strideY = 100f, margin = 2))
        assertEquals(8..16, reorderShownRows(scrollY = 1_000, viewportHeight = 450f, strideY = 100f, margin = 2))
        // Before the view is measured: the first rows.
        assertEquals(0..2, reorderShownRows(scrollY = 0, viewportHeight = 0f, strideY = 100f, margin = 2))
        assertEquals(0..2, reorderShownRows(scrollY = 0, viewportHeight = 450f, strideY = 0f, margin = 2))
    }
}
