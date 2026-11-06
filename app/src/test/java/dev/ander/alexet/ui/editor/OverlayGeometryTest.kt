package dev.ander.alexet.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlayGeometryTest {
    private val eps = 0.001f

    @Test
    fun rotatePointIsClockwiseOnScreen() {
        // y points down: +90 deg turns "right" into "down".
        val p = rotatePoint(Offset(1f, 0f), 90f)
        assertEquals(0f, p.x, eps)
        assertEquals(1f, p.y, eps)
    }

    @Test
    fun displayToSourceIsIdentityWithoutGeometry() {
        val rect = Rect(0f, 0f, 200f, 100f)
        val n = displayToSourceNorm(Offset(50f, 25f), rect, 0, flipH = false, flipV = false)!!
        assertEquals(0.25f, n.x, eps)
        assertEquals(0.25f, n.y, eps)
    }

    @Test
    fun displayToSourceUndoesQuarterTurn() {
        // Source 200x100 rotated 90 deg clockwise is displayed as 100x200.
        // Source top-left corner ends up at the display top-right corner.
        val display = Rect(0f, 0f, 100f, 200f)
        val n = displayToSourceNorm(Offset(99f, 1f), display, 90, flipH = false, flipV = false)!!
        assertEquals(0f, n.x, 0.01f)
        assertEquals(0f, n.y, 0.01f)
    }

    @Test
    fun displayToSourceUndoesFlip() {
        val rect = Rect(0f, 0f, 200f, 100f)
        val n = displayToSourceNorm(Offset(50f, 25f), rect, 0, flipH = true, flipV = false)!!
        assertEquals(0.75f, n.x, eps)
        assertEquals(0.25f, n.y, eps)
    }

    @Test
    fun displayToSourceRejectsPointsOutsideImage() {
        assertNull(displayToSourceNorm(Offset(-1f, 5f), Rect(0f, 0f, 10f, 10f), 0, flipH = false, flipV = false))
    }

    @Test
    fun colorMatrixIdentityAndOffset() {
        val identity = floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
        val color = 0xFF336699.toInt()
        assertEquals(color, applyColorMatrix(color, identity))
        val brighter = identity.copyOf().also { it[4] = 10f; it[9] = 10f; it[14] = 10f }
        assertEquals(0xFF3D70A3.toInt(), applyColorMatrix(color, brighter))
    }

    @Test
    fun aspectChipFollowsStoredRatio() {
        assertEquals(AspectOption.CUSTOM, aspectOptionFor(null))
        assertEquals(AspectOption.RATIO_16_9, aspectOptionFor(16f / 9f))
        assertEquals(AspectOption.EXACT, aspectOptionFor(4f / 5f))
    }
}
