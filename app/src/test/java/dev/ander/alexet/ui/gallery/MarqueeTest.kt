package dev.ander.alexet.ui.gallery

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarqueeTest {

    @Test
    fun testRectNormalized() {
        val inverted = Rect(left = 100f, top = 200f, right = 50f, bottom = 80f)
        val norm = inverted.normalized()
        assertEquals(50f, norm.left, 0.001f)
        assertEquals(80f, norm.top, 0.001f)
        assertEquals(100f, norm.right, 0.001f)
        assertEquals(200f, norm.bottom, 0.001f)
    }

    @Test
    fun testIdsIntersecting2DSelection() {
        val bounds = mapOf(
            1L to Rect(0f, 0f, 100f, 100f),
            2L to Rect(110f, 0f, 210f, 100f),
            3L to Rect(0f, 110f, 100f, 210f),
            4L to Rect(110f, 110f, 210f, 210f),
        )

        // Select top row only
        val topSelection = idsIntersecting(bounds, Rect(0f, 0f, 220f, 50f))
        assertEquals(setOf(1L, 2L), topSelection)

        // Select all 4
        val allSelection = idsIntersecting(bounds, Rect(50f, 50f, 150f, 150f))
        assertEquals(setOf(1L, 2L, 3L, 4L), allSelection)

        // Select none
        val noneSelection = idsIntersecting(bounds, Rect(300f, 300f, 400f, 400f))
        assertTrue(noneSelection.isEmpty())
    }

    @Test
    fun testIdsInLinearRange() {
        val ordered = listOf(1L, 2L, 3L, 4L, 5L)
        val bounds = mapOf(
            1L to Rect(0f, 0f, 50f, 50f),
            2L to Rect(60f, 0f, 110f, 50f),
            3L to Rect(120f, 0f, 170f, 50f),
            4L to Rect(0f, 60f, 50f, 110f),
            5L to Rect(60f, 60f, 110f, 110f),
        )

        val range = idsInLinearRange(
            ordered = ordered,
            bounds = bounds,
            origin = Offset(70f, 20f), // index 1 (item 2)
            finger = Offset(10f, 70f), // index 3 (item 4)
            startIndex = 1,
        )
        assertEquals(listOf(2L, 3L, 4L), range)
    }

    @Test
    fun testMarqueeOrderedMergeKeepsPriorAndAppendsNew() {
        val base = listOf(9L, 8L)
        val hit = listOf(1L, 2L, 3L, 8L) // 8 already selected
        val next = base + hit.filter { it !in base.toSet() }
        assertEquals(listOf(9L, 8L, 1L, 2L, 3L), next)
    }

    @Test
    fun testIdsIntersectingOrderedUsesReadingOrder() {
        val ordered = listOf(1L, 2L, 3L, 4L)
        val bounds = mapOf(
            1L to Rect(0f, 0f, 100f, 100f),
            2L to Rect(110f, 0f, 210f, 100f),
            3L to Rect(0f, 110f, 100f, 210f),
            4L to Rect(110f, 110f, 210f, 210f),
        )
        val hit = idsIntersectingOrdered(bounds, Rect(0f, 0f, 220f, 220f), ordered)
        assertEquals(listOf(1L, 2L, 3L, 4L), hit)
    }

    @Test
    fun testSelectionToggleStrictBijection() {
        // Simulates GalleryViewModel.toggleFileSelected logic
        val currentSet = setOf(1L, 2L)
        val currentOrdered = listOf(1L, 2L).filter { it in currentSet }
        val idToToggle = 3L
        val newOrdered = if (idToToggle in currentSet) {
            currentOrdered.filter { it != idToToggle }
        } else {
            currentOrdered.filter { it != idToToggle } + idToToggle
        }
        val distinct = newOrdered.distinct()
        val newSet = distinct.toSet()

        assertEquals(listOf(1L, 2L, 3L), distinct)
        assertEquals(setOf(1L, 2L, 3L), newSet)
        assertEquals(newSet.size, distinct.size)
    }

    @Test
    fun testSelectionDeselectMaintainsBijection() {
        val currentSet = setOf(1L, 2L, 3L)
        val currentOrdered = listOf(1L, 2L, 3L).filter { it in currentSet }
        val idToToggle = 2L
        val newOrdered = if (idToToggle in currentSet) {
            currentOrdered.filter { it != idToToggle }
        } else {
            currentOrdered.filter { it != idToToggle } + idToToggle
        }
        val distinct = newOrdered.distinct()
        val newSet = distinct.toSet()

        assertEquals(listOf(1L, 3L), distinct)
        assertEquals(setOf(1L, 3L), newSet)
        assertEquals(newSet.size, distinct.size)
    }

    @Test
    fun testAutoscrollDeltaAccumulationAcrossFrames() {
        val maxSpeed = 22f
        var pendingDelta = 0f

        // Frame 1: delta generated = 10f, list at boundary consumes 0
        val delta1 = 10f
        pendingDelta = (pendingDelta + delta1).coerceIn(-maxSpeed * 2f, maxSpeed * 2f)
        val consumed1 = 0f
        pendingDelta -= consumed1
        assertEquals(10f, pendingDelta, 0.001f)

        // Frame 2: delta generated = 10f, list now consumes 15f of accumulated 20f
        val delta2 = 10f
        pendingDelta = (pendingDelta + delta2).coerceIn(-maxSpeed * 2f, maxSpeed * 2f)
        assertEquals(20f, pendingDelta, 0.001f)
        val consumed2 = 15f
        pendingDelta -= consumed2
        assertEquals(5f, pendingDelta, 0.001f)
    }
}
