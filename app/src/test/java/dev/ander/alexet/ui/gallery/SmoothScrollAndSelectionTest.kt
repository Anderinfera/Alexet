package dev.ander.alexet.ui.gallery

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Tests for smooth scrolling, gestures and selection.
 * 1. Continuous smooth scrolling progress and offsets across row boundaries.
 * 2. animateItem() inclusion/exclusion predicate during reordering and marquee autoscroll.
 * 3. Selection order preservation, duplicate prevention and empty folder edge cases.
 */
class SmoothScrollAndSelectionTest {

    // ---------------------------------------------------------------------------------------------
    // Task 1: Smooth scrolling offset calculation when firstVisibleItemScrollOffset varies from 0 to max
    // ---------------------------------------------------------------------------------------------

    private fun calculateContinuousRow(
        firstVisibleItemIndex: Int,
        firstVisibleItemScrollOffset: Int,
        safeColumns: Int,
        safeRowHeightPx: Float,
        smooth: Boolean,
    ): Float {
        return if (smooth && safeRowHeightPx > 0f) {
            (firstVisibleItemIndex / safeColumns).toFloat() +
                (firstVisibleItemScrollOffset.toFloat() / safeRowHeightPx)
        } else {
            (firstVisibleItemIndex / safeColumns).toFloat()
        }
    }

    private fun calculateNaturalProgress(
        continuousRow: Float,
        totalRows: Int,
    ): Float {
        return (continuousRow / max(1, totalRows - 1)).coerceIn(0f, 1f)
    }

    private fun reverseScrollTarget(
        progress: Float,
        totalRows: Int,
        safeColumns: Int,
        safeRowHeightPx: Float,
        itemCount: Int,
        smooth: Boolean,
    ): Pair<Int, Int> {
        return if (smooth) {
            val floatRow = progress * (totalRows - 1)
            val intRow = floatRow.toInt()
            val offsetPx = ((floatRow - intRow) * safeRowHeightPx).roundToInt()
            val targetItem = (intRow * safeColumns).coerceIn(0, max(0, itemCount - 1))
            Pair(targetItem, offsetPx)
        } else {
            val targetRow = (progress * (totalRows - 1)).roundToInt()
            val targetItem = (targetRow * safeColumns).coerceIn(0, max(0, itemCount - 1))
            Pair(targetItem, 0)
        }
    }

    @Test
    fun testSmoothScrollContinuousOffsetSweep() {
        val safeColumns = 3
        val itemCount = 300
        val totalRows = (itemCount + safeColumns - 1) / safeColumns // 100 rows
        val rowHeightPx = 240f

        var lastProgress = -1f

        // Sweep through first 10 rows pixel by pixel from offset 0 to rowHeightPx
        for (rowIndex in 0 until 10) {
            val itemIndex = rowIndex * safeColumns
            for (offset in 0..rowHeightPx.toInt()) {
                val continuousRow = calculateContinuousRow(
                    firstVisibleItemIndex = itemIndex,
                    firstVisibleItemScrollOffset = offset,
                    safeColumns = safeColumns,
                    safeRowHeightPx = rowHeightPx,
                    smooth = true,
                )
                val progress = calculateNaturalProgress(continuousRow, totalRows)

                // Monotonicity: progress must strictly never decrease
                assertTrue(
                    "Progress decreased at row $rowIndex offset $offset: $progress < $lastProgress",
                    progress >= lastProgress,
                )
                assertTrue("Progress must be <= 1.0f", progress <= 1.0f)
                assertTrue("Progress must be >= 0.0f", progress >= 0.0f)
                lastProgress = progress
            }
        }
    }

    @Test
    fun testSmoothScrollRowBoundaryContinuity() {
        val safeColumns = 4
        val totalRows = 50
        val rowHeightPx = 300f

        for (rowIndex in 0 until 40) {
            // End of row r: offset = rowHeightPx
            val endOfRowContinuous = calculateContinuousRow(
                firstVisibleItemIndex = rowIndex * safeColumns,
                firstVisibleItemScrollOffset = rowHeightPx.toInt(),
                safeColumns = safeColumns,
                safeRowHeightPx = rowHeightPx,
                smooth = true,
            )
            val endOfRowProgress = calculateNaturalProgress(endOfRowContinuous, totalRows)

            // Start of row r+1: offset = 0
            val startOfNextRowContinuous = calculateContinuousRow(
                firstVisibleItemIndex = (rowIndex + 1) * safeColumns,
                firstVisibleItemScrollOffset = 0,
                safeColumns = safeColumns,
                safeRowHeightPx = rowHeightPx,
                smooth = true,
            )
            val startOfNextRowProgress = calculateNaturalProgress(startOfNextRowContinuous, totalRows)

            // At the boundary, row r + 300px must equal row (r+1) + 0px exactly
            assertEquals(
                "Discontinuity detected between row $rowIndex end and row ${rowIndex + 1} start",
                endOfRowProgress,
                startOfNextRowProgress,
                0.0001f,
            )
        }
    }

    @Test
    fun testSmoothScrollRoundTripConsistency() {
        val safeColumns = 3
        val itemCount = 60
        val totalRows = (itemCount + safeColumns - 1) / safeColumns // 20 rows
        val rowHeightPx = 180f

        for (rowIndex in 0 until 19) {
            for (offset in listOf(0, 30, 90, 150, 179)) {
                val itemIndex = rowIndex * safeColumns
                val continuousRow = calculateContinuousRow(
                    firstVisibleItemIndex = itemIndex,
                    firstVisibleItemScrollOffset = offset,
                    safeColumns = safeColumns,
                    safeRowHeightPx = rowHeightPx,
                    smooth = true,
                )
                val progress = calculateNaturalProgress(continuousRow, totalRows)

                val (targetItem, offsetPx) = reverseScrollTarget(
                    progress = progress,
                    totalRows = totalRows,
                    safeColumns = safeColumns,
                    safeRowHeightPx = rowHeightPx,
                    itemCount = itemCount,
                    smooth = true,
                )

                assertEquals("Target item should match original item index", itemIndex, targetItem)
                assertEquals("Offset should match within 1 pixel rounding error", offset.toFloat(), offsetPx.toFloat(), 1.0f)
            }
        }
    }

    @Test
    fun testSmoothScrollEdgeCases() {
        val safeColumns = 2
        val itemCount = 10
        val totalRows = 5
        val rowHeightPx = 100f

        // Test overscroll (offset > rowHeight)
        val overscroll = calculateContinuousRow(0, 500, safeColumns, rowHeightPx, smooth = true)
        val progressOver = calculateNaturalProgress(overscroll, totalRows)
        assertTrue("Progress must be clamped to 1.0f even if offset is huge", progressOver <= 1.0f)

        // Test negative offset
        val negOffset = calculateContinuousRow(0, -50, safeColumns, rowHeightPx, smooth = true)
        val progressNeg = calculateNaturalProgress(negOffset, totalRows)
        assertEquals("Progress must be clamped to 0.0f for negative continuous row", 0f, progressNeg, 0.0001f)

        // Test smooth = false (snapped mode)
        val snapped1 = calculateContinuousRow(0, 50, safeColumns, rowHeightPx, smooth = false)
        val snapped2 = calculateContinuousRow(0, 99, safeColumns, rowHeightPx, smooth = false)
        assertEquals(0f, snapped1, 0.0001f)
        assertEquals(0f, snapped2, 0.0001f)
    }

    // ---------------------------------------------------------------------------------------------
    // Task 2: Modifier.animateItem() exclusion does not break normal reordering when marquee inactive
    // ---------------------------------------------------------------------------------------------

    private fun shouldApplyAnimateItem(itemId: Long, draggingId: Long?, marqueeActive: Boolean): Boolean {
        // Implementation from MediaTiles.kt line 377:
        // draggingId != item.id && !marqueeActive
        return draggingId != itemId && !marqueeActive
    }

    @Test
    fun testAnimateItemMatrixDuringReorderingAndMarquee() {
        val items = listOf(101L, 102L, 103L, 104L, 105L)

        // Scenario 1: Normal idle state (no drag, no marquee)
        // All items should animate if layout changes
        for (id in items) {
            assertTrue("Idle items should have animateItem enabled", shouldApplyAnimateItem(id, draggingId = null, marqueeActive = false))
        }

        // Scenario 2: Active reorder drag of item 103L (marquee not active)
        val activeDraggingId = 103L
        assertFalse(
            "Dragged item 103L MUST NOT have animateItem (avoids fighting gesture)",
            shouldApplyAnimateItem(activeDraggingId, draggingId = activeDraggingId, marqueeActive = false),
        )
        for (id in items) {
            if (id != activeDraggingId) {
                assertTrue(
                    "Non-dragged item $id MUST have animateItem enabled so it animates out of the way",
                    shouldApplyAnimateItem(id, draggingId = activeDraggingId, marqueeActive = false),
                )
            }
        }

        // Scenario 3: Active marquee selection (marqueeActive = true)
        // Stutter elimination: NO items should animate when autoscrolling marquee
        for (id in items) {
            assertFalse(
                "Item $id must NOT have animateItem during marquee autoscroll",
                shouldApplyAnimateItem(id, draggingId = null, marqueeActive = true),
            )
        }
    }

    @Test
    fun testReorderingSimulationMaintainsAnimationForDestinationItems() {
        val originalList = mutableListOf(1L, 2L, 3L, 4L, 5L)
        val draggedId = 2L

        // Simulate dragging item 2 from index 1 to index 3: [1, 3, 4, 2, 5]
        val fromIndex = originalList.indexOf(draggedId)
        val toIndex = 3
        val item = originalList.removeAt(fromIndex)
        originalList.add(toIndex, item)

        assertEquals(listOf(1L, 3L, 4L, 2L, 5L), originalList)

        // Verify that during this swap, items 3 and 4 (which shifted positions) had animateItem active
        assertTrue(shouldApplyAnimateItem(3L, draggingId = draggedId, marqueeActive = false))
        assertTrue(shouldApplyAnimateItem(4L, draggingId = draggedId, marqueeActive = false))
        // And dragged item 2 did NOT have animateItem
        assertFalse(shouldApplyAnimateItem(2L, draggingId = draggedId, marqueeActive = false))
    }

    // ---------------------------------------------------------------------------------------------
    // Task 3: Long press does not cause duplicate entries in selectedFileIdsOrdered or crash on empty folders
    // ---------------------------------------------------------------------------------------------

    private data class SelectionState(
        val selectedFileIds: Set<Long> = emptySet(),
        val selectedFileIdsOrdered: List<Long> = emptyList(),
    )

    private fun toggleFileSelectedLogic(state: SelectionState, id: Long): SelectionState {
        // Faithful reproduction of GalleryViewModel.toggleFileSelected & withFileSelection
        val currentSet = state.selectedFileIds
        val currentOrdered = (state.selectedFileIdsOrdered.ifEmpty { state.selectedFileIds.toList() })
            .filter { it in currentSet }
        val newOrdered = if (id in currentSet) {
            currentOrdered.filter { it != id }
        } else {
            currentOrdered.filter { it != id } + id
        }
        val distinct = newOrdered.distinct()
        return state.copy(
            selectedFileIds = distinct.toSet(),
            selectedFileIdsOrdered = distinct,
        )
    }

    @Test
    fun testLongPressToggleNoDuplicatesAndStrictBijection() {
        var state = SelectionState()

        // 1. Long press item 10
        state = toggleFileSelectedLogic(state, 10L)
        assertEquals(listOf(10L), state.selectedFileIdsOrdered)
        assertEquals(setOf(10L), state.selectedFileIds)

        // 2. Long press item 20
        state = toggleFileSelectedLogic(state, 20L)
        assertEquals(listOf(10L, 20L), state.selectedFileIdsOrdered)
        assertEquals(setOf(10L, 20L), state.selectedFileIds)

        // 3. Repeated long press / toggle on item 10 (deselects 10)
        state = toggleFileSelectedLogic(state, 10L)
        assertEquals(listOf(20L), state.selectedFileIdsOrdered)
        assertEquals(setOf(20L), state.selectedFileIds)

        // 4. Repeated long press on item 10 again (re-selects 10 at end of list)
        state = toggleFileSelectedLogic(state, 10L)
        assertEquals(listOf(20L, 10L), state.selectedFileIdsOrdered)
        assertEquals(setOf(20L, 10L), state.selectedFileIds)
        assertEquals(state.selectedFileIds.size, state.selectedFileIdsOrdered.size)
    }

    @Test
    fun testStressRapidMultiItemSelectionNoDuplicates() {
        var state = SelectionState()
        val randomOps = java.util.Random(42)

        // Perform 5,000 random toggles across 50 items
        for (i in 0 until 5000) {
            val candidateId = randomOps.nextInt(50).toLong()
            state = toggleFileSelectedLogic(state, candidateId)

            // Invariant 1: No duplicates in ordered list
            assertEquals(
                "Duplicate found in selectedFileIdsOrdered",
                state.selectedFileIdsOrdered.distinct().size,
                state.selectedFileIdsOrdered.size,
            )

            // Invariant 2: Strict bijection between set and ordered list
            assertEquals(
                "Set size does not match ordered list size",
                state.selectedFileIds.size,
                state.selectedFileIdsOrdered.size,
            )
            assertEquals(
                "Set content does not match ordered list elements",
                state.selectedFileIds,
                state.selectedFileIdsOrdered.toSet(),
            )
        }
    }

    @Test
    fun testEmptyFolderResilience() {
        // Case 1: itemCount = 0
        val itemCount = 0
        val columns = 3
        val safeColumns = max(1, columns)
        val totalRows = (itemCount + safeColumns - 1) / safeColumns
        assertEquals(0, totalRows)
        // FolderScroller early return condition
        val shouldReturnScroller = itemCount <= 0 || totalRows <= 1
        assertTrue("FolderScroller must exit early for 0 items", shouldReturnScroller)

        // Case 2: Marquee on empty folder
        val emptyBounds = emptyMap<Long, Rect>()
        val emptyOrdered = emptyList<Long>()
        val hitLinear = idsInLinearRange(
            ordered = emptyOrdered,
            bounds = emptyBounds,
            origin = Offset(0f, 0f),
            finger = Offset(100f, 100f),
            startIndex = -1,
        )
        assertTrue("idsInLinearRange must return empty list for empty folder", hitLinear.isEmpty())

        val hit2D = idsIntersectingOrdered(
            bounds = emptyBounds,
            rect = Rect(0f, 0f, 500f, 500f),
            ordered = emptyOrdered,
        )
        assertTrue("idsIntersectingOrdered must return empty list for empty folder", hit2D.isEmpty())

        // Case 3: Toggle on empty state
        var state = SelectionState()
        state = toggleFileSelectedLogic(state, 999L) // User somehow triggers toggle
        assertEquals(listOf(999L), state.selectedFileIdsOrdered)
        state = toggleFileSelectedLogic(state, 999L) // Deselects
        assertTrue(state.selectedFileIdsOrdered.isEmpty())
        assertTrue(state.selectedFileIds.isEmpty())
    }
}
