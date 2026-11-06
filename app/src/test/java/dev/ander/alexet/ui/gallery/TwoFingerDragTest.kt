package dev.ander.alexet.ui.gallery

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class TwoFingerDragTest {

    private data class MockPointer(val id: Long, val position: Offset)

    private fun selectBottomFinger(pointers: List<MockPointer>): MockPointer? {
        return pointers.maxByOrNull { it.position.y }
    }

    @Test
    fun testSelectBottomFingerWhenFirstFingerIsLower() {
        val finger1 = MockPointer(1L, Offset(100f, 500f))
        val finger2 = MockPointer(2L, Offset(200f, 300f))

        val selected = selectBottomFinger(listOf(finger1, finger2))
        assertEquals(finger1, selected)
        assertEquals(500f, selected?.position?.y ?: 0f, 0.001f)
    }

    @Test
    fun testSelectBottomFingerWhenSecondFingerIsLower() {
        val finger1 = MockPointer(1L, Offset(100f, 250f))
        val finger2 = MockPointer(2L, Offset(300f, 650f))

        val selected = selectBottomFinger(listOf(finger1, finger2))
        assertEquals(finger2, selected)
        assertEquals(650f, selected?.position?.y ?: 0f, 0.001f)
    }

    @Test
    fun testSelectBottomFingerWithThreePointers() {
        val top = MockPointer(1L, Offset(150f, 100f))
        val mid = MockPointer(2L, Offset(250f, 400f))
        val bottom = MockPointer(3L, Offset(350f, 750f))

        val selected = selectBottomFinger(listOf(mid, top, bottom))
        assertEquals(bottom, selected)
    }
}
