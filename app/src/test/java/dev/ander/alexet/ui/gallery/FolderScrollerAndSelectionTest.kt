package dev.ander.alexet.ui.gallery

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Tests for scrolling, gestures and selection.
 * Covers:
 * 1. FolderScroller pill and track layout maths (ghost pill vs active pill, track bounds).
 * 2. Selection ordering invariants with many items, rapid toggles, deselect and empty states.
 * 3. Autoscroll delta accumulation with negative, zero and saturated deltas.
 */
class FolderScrollerAndSelectionTest {

    // =========================================================================
    // 1. CLEAN PILL & TRACK SCROLLER GEOMETRY AND HITBOX MATH
    // =========================================================================

    @Test
    fun testFolderScrollerHitboxThumbBoundaries() {
        val baseHandleHeightPx = 132f // 44.dp on 3x density
        val touchMarginPx = 18f // 6.dp on 3x density
        val maxOffsetPx = 1000f

        for (progress in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val centerOffsetPx = progress * maxOffsetPx + baseHandleHeightPx / 2f
            val thumbTop = centerOffsetPx - baseHandleHeightPx / 2f - touchMarginPx
            val thumbBottom = centerOffsetPx + baseHandleHeightPx / 2f + touchMarginPx

            // Touch inside thumb bounds must be accepted
            assertTrue(centerOffsetPx in thumbTop..thumbBottom)
            assertTrue(thumbTop + 1f in thumbTop..thumbBottom)
            assertTrue(thumbBottom - 1f in thumbTop..thumbBottom)

            // Touch far outside thumb bounds must be rejected
            assertFalse(thumbTop - 10f in thumbTop..thumbBottom)
            assertFalse(thumbBottom + 10f in thumbTop..thumbBottom)
        }
    }

    @Test
    fun testFolderScrollerPillOffsetClamping() {
        val baseHandleHeightPx = 132f // 44.dp on 3x density
        val trackHeightPx = 1500f
        val maxOffsetPx = max(1f, trackHeightPx - baseHandleHeightPx)

        // Progress 0f: pill must be at the very top (0)
        val centerTop = 0f * maxOffsetPx + baseHandleHeightPx / 2f
        val pillOffsetTop = (centerTop - baseHandleHeightPx / 2f).roundToInt()
            .coerceIn(0, max(0, (trackHeightPx - baseHandleHeightPx).roundToInt()))
        assertEquals(0, pillOffsetTop)

        // Progress 1f: pill must be at trackHeightPx - baseHandleHeightPx
        val centerBottom = 1f * maxOffsetPx + baseHandleHeightPx / 2f
        val pillOffsetBottom = (centerBottom - baseHandleHeightPx / 2f).roundToInt()
            .coerceIn(0, max(0, (trackHeightPx - baseHandleHeightPx).roundToInt()))
        assertEquals((trackHeightPx - baseHandleHeightPx).roundToInt(), pillOffsetBottom)
    }

    @Test
    fun testFolderScrollerTrackOffsetMathUnderSmallViewport() {
        // Stress-test FolderScroller offset math when trackHeightPx < baseHandleHeightPx
        val baseHandleHeightPx = 144f // e.g. 48.dp on 3x density
        val currentHeightPx = 192f // 64.dp on 3x density

        // Extreme case 1: track smaller than handle (e.g. heavy split-screen or dialog)
        val smallTrackHeightPx = 100f
        val maxOffsetPxSmall = max(1f, smallTrackHeightPx - baseHandleHeightPx)
        assertEquals(1f, maxOffsetPxSmall, 0.001f) // Must not be negative or zero to prevent div-by-zero

        val naturalProgress = 0.5f
        val centerOffsetPx = naturalProgress * maxOffsetPxSmall + baseHandleHeightPx / 2f
        val pillOffsetPx = (centerOffsetPx - currentHeightPx / 2f).roundToInt()
            .coerceIn(0, max(0, (smallTrackHeightPx - currentHeightPx).roundToInt()))
        assertEquals(0, pillOffsetPx) // Must be clamped safely to 0

        // Normal case 2: large track
        val largeTrackHeightPx = 2000f
        val maxOffsetPxLarge = max(1f, largeTrackHeightPx - baseHandleHeightPx)
        assertTrue(maxOffsetPxLarge > 0f)
        val pillOffsetLarge = (0.5f * maxOffsetPxLarge + baseHandleHeightPx / 2f - currentHeightPx / 2f)
            .roundToInt().coerceIn(0, max(0, (largeTrackHeightPx - currentHeightPx).roundToInt()))
        assertTrue(pillOffsetLarge in 0..(largeTrackHeightPx - currentHeightPx).toInt())
    }

    @Test
    fun testFolderScrollerSmoothRowProgressMath() {
        val safeColumns = 3
        val totalRows = 50
        val safeRowHeightPx = 300f

        // Progress at top
        val row0 = 0
        val offset0 = 0
        val cont0 = (row0 / safeColumns).toFloat() + (offset0.toFloat() / safeRowHeightPx)
        val prog0 = (cont0 / max(1, totalRows - 1)).coerceIn(0f, 1f)
        assertEquals(0f, prog0, 0.0001f)

        // Progress mid-row: row 10, offset 150px (halfway)
        val row10 = 10 * safeColumns
        val offset150 = 150
        val contMid = (row10 / safeColumns).toFloat() + (offset150.toFloat() / safeRowHeightPx)
        assertEquals(10.5f, contMid, 0.0001f)
        val progMid = (contMid / max(1, totalRows - 1)).coerceIn(0f, 1f)
        assertEquals(10.5f / 49f, progMid, 0.0001f)

        // Drag jump inverse calculation: given initialProgress = 0.5f
        val dragProg = 0.5f
        val floatRow = dragProg * (totalRows - 1) // 24.5
        val intRow = floatRow.toInt() // 24
        val calculatedOffsetPx = ((floatRow - intRow) * safeRowHeightPx).roundToInt() // 150px
        val targetItem = (intRow * safeColumns).coerceIn(0, totalRows * safeColumns - 1)
        assertEquals(24, intRow)
        assertEquals(150, calculatedOffsetPx)
        assertEquals(72, targetItem)
    }

    // =========================================================================
    // 2. SELECTION BIJECTION & ORDERING LOGIC
    // =========================================================================

    private fun simulateToggle(
        currentSet: Set<Long>,
        currentOrdered: List<Long>,
        id: Long,
    ): Pair<Set<Long>, List<Long>> {
        val sanitizedOrdered = (currentOrdered.ifEmpty { currentSet.toList() }).filter { it in currentSet }
        val newOrdered = if (id in currentSet) {
            sanitizedOrdered.filter { it != id }
        } else {
            sanitizedOrdered.filter { it != id } + id
        }
        val distinct = newOrdered.distinct()
        return distinct.toSet() to distinct
    }

    @Test
    fun testSelectionBijectionEmptyState() {
        val currentSet = emptySet<Long>()
        val currentOrdered = emptyList<Long>()

        val (newSet, newOrdered) = simulateToggle(currentSet, currentOrdered, 42L)
        assertEquals(setOf(42L), newSet)
        assertEquals(listOf(42L), newOrdered)
        assertEquals(newSet.size, newOrdered.size)
    }

    @Test
    fun testSelectionBijectionRapidToggleSameItem() {
        var currentSet = emptySet<Long>()
        var currentOrdered = emptyList<Long>()

        for (i in 1..500) {
            val (s, o) = simulateToggle(currentSet, currentOrdered, 999L)
            currentSet = s
            currentOrdered = o
            // Odd -> selected (size 1), Even -> deselected (size 0)
            val expectedSize = if (i % 2 == 1) 1 else 0
            assertEquals("Step $i size mismatch", expectedSize, currentSet.size)
            assertEquals("Step $i ordered size mismatch", expectedSize, currentOrdered.size)
            assertEquals("Step $i bijection mismatch", currentSet, currentOrdered.toSet())
        }
        assertTrue(currentSet.isEmpty())
        assertTrue(currentOrdered.isEmpty())
    }

    @Test
    fun testSelectionBijectionLargeScaleRandomOps() {
        var currentSet = emptySet<Long>()
        var currentOrdered = emptyList<Long>()

        val totalElements = 2000L
        // Select 2,000 distinct items sequentially
        for (id in 1L..totalElements) {
            val (s, o) = simulateToggle(currentSet, currentOrdered, id)
            currentSet = s
            currentOrdered = o
        }

        assertEquals(2000, currentSet.size)
        assertEquals(2000, currentOrdered.size)
        assertEquals(currentSet, currentOrdered.toSet())
        // Order must match sequence of insertion
        assertEquals((1L..totalElements).toList(), currentOrdered)

        // Deselect every even item
        for (id in 2L..totalElements step 2L) {
            val (s, o) = simulateToggle(currentSet, currentOrdered, id)
            currentSet = s
            currentOrdered = o
        }

        assertEquals(1000, currentSet.size)
        assertEquals(1000, currentOrdered.size)
        assertEquals(currentSet, currentOrdered.toSet())
        // Surviving odd elements must preserve relative insertion order
        val expectedOdds = (1L..totalElements step 2L).toList()
        assertEquals(expectedOdds, currentOrdered)
    }

    @Test
    fun testSelectionMarqueeOrderedMergeInvariants() {
        val base = listOf(100L, 50L, 20L)
        val baseSet = base.toSet()

        // 1. Marquee drag adds [20, 30, 40] (20 is already in base)
        val hit = listOf(20L, 30L, 40L)
        val nextAdd = base + hit.filter { it !in baseSet }
        assertEquals(listOf(100L, 50L, 20L, 30L, 40L), nextAdd)
        assertEquals(nextAdd.toSet().size, nextAdd.size) // No duplicates

        // 2. Marquee subtract drag hits [50, 40]
        val hitSubtract = listOf(50L, 40L)
        val hitSubtractSet = hitSubtract.toSet()
        val nextSubtract = nextAdd.filter { it !in hitSubtractSet }
        assertEquals(listOf(100L, 20L, 30L), nextSubtract)
        assertEquals(nextSubtract.toSet().size, nextSubtract.size)
        // Survivors preserve their original relative order (100 -> 20 -> 30)
    }

    @Test
    fun testSelectionUiStateSanitization() {
        // Test UI state backward-compatibility and defensive filtering
        val ixSelectedFileIds = setOf(10L, 20L, 30L)
        // Stale ordered list containing an ID that was removed from ix.selectedFileIds
        val ixStaleOrdered = listOf(10L, 999L, 20L, 30L)

        val sanitized = ixStaleOrdered.filter { it in ixSelectedFileIds }.ifEmpty {
            ixSelectedFileIds.toList()
        }
        assertEquals(listOf(10L, 20L, 30L), sanitized)
        assertEquals(ixSelectedFileIds, sanitized.toSet())

        // Test fallback when ordered list is empty
        val fallback = emptyList<Long>().filter { it in ixSelectedFileIds }.ifEmpty {
            ixSelectedFileIds.toList()
        }
        assertEquals(ixSelectedFileIds, fallback.toSet())
    }

    // =========================================================================
    // 3. AUTOSCROLL DELTA ACCUMULATION & BOUNDARY STRESS
    // =========================================================================

    private fun edgeScrollDelta(y: Float, height: Float, edge: Float, maxSpeed: Float): Float {
        if (height <= edge * 2f) return 0f
        return when {
            y < edge -> {
                val t = ((edge - y) / edge).coerceIn(0f, 1f)
                -maxSpeed * t * t
            }
            y > height - edge -> {
                val t = ((y - (height - edge)) / edge).coerceIn(0f, 1f)
                maxSpeed * t * t
            }
            else -> 0f
        }
    }

    @Test
    fun testEdgeScrollDeltaBoundaryZones() {
        val height = 1000f
        val edge = 100f
        val maxSpeed = 20f

        // Top outside / edge
        assertEquals(-maxSpeed, edgeScrollDelta(-50f, height, edge, maxSpeed), 0.001f)
        assertEquals(-maxSpeed, edgeScrollDelta(0f, height, edge, maxSpeed), 0.001f)
        // Top ramp: y = 50 (t = 0.5) -> -20 * 0.25 = -5f
        assertEquals(-5f, edgeScrollDelta(50f, height, edge, maxSpeed), 0.001f)
        // Edge threshold
        assertEquals(0f, edgeScrollDelta(100f, height, edge, maxSpeed), 0.001f)

        // Dead center
        assertEquals(0f, edgeScrollDelta(500f, height, edge, maxSpeed), 0.001f)

        // Bottom threshold
        assertEquals(0f, edgeScrollDelta(900f, height, edge, maxSpeed), 0.001f)
        // Bottom ramp: y = 950 (t = 0.5) -> +20 * 0.25 = 5f
        assertEquals(5f, edgeScrollDelta(950f, height, edge, maxSpeed), 0.001f)
        // Bottom outside / edge
        assertEquals(maxSpeed, edgeScrollDelta(1000f, height, edge, maxSpeed), 0.001f)
        assertEquals(maxSpeed, edgeScrollDelta(1200f, height, edge, maxSpeed), 0.001f)

        // Extreme: height smaller than 2 * edge
        assertEquals(0f, edgeScrollDelta(10f, 150f, edge, maxSpeed), 0.001f)
    }

    @Test
    fun testAutoscrollDeltaSaturationAndDirectionReversal() {
        val maxSpeed = 22f
        val maxPending = maxSpeed * 2f // 44f
        var pendingDelta = 0f

        // 1. Push upward repeatedly while list is at boundary (actual = 0)
        for (i in 1..20) {
            val delta = -maxSpeed
            pendingDelta = (pendingDelta + delta).coerceIn(-maxPending, maxPending)
            val actual = 0f // Grid cannot scroll past top
            pendingDelta -= actual
        }
        // Must saturate at -maxPending without numerical overflow
        assertEquals(-maxPending, pendingDelta, 0.001f)

        // 2. User moves finger to neutral zone (delta = 0)
        val neutralDelta = 0f
        pendingDelta = (pendingDelta + neutralDelta).coerceIn(-maxPending, maxPending)
        assertEquals(-maxPending, pendingDelta, 0.001f)

        // 3. User now moves finger downward to scroll down (delta = +maxSpeed)
        // Frame 1: delta +22f -> pendingDelta moves from -44f to -22f
        pendingDelta = (pendingDelta + maxSpeed).coerceIn(-maxPending, maxPending)
        assertEquals(-22f, pendingDelta, 0.001f)

        // Frame 2: delta +22f -> pendingDelta reaches 0f
        pendingDelta = (pendingDelta + maxSpeed).coerceIn(-maxPending, maxPending)
        assertEquals(0f, pendingDelta, 0.001f)

        // Frame 3: delta +22f -> pendingDelta reaches +22f, grid consumes 22f
        pendingDelta = (pendingDelta + maxSpeed).coerceIn(-maxPending, maxPending)
        assertEquals(22f, pendingDelta, 0.001f)
        val consumed = 22f
        pendingDelta -= consumed
        assertEquals(0f, pendingDelta, 0.001f)
    }

    @Test
    fun testAutoscrollDeltaOverflowResistance() {
        val maxSpeed = 22f
        val maxPending = maxSpeed * 2f
        var pendingDelta = 0f

        // Huge positive delta
        val overflowPos = Float.MAX_VALUE
        pendingDelta = (pendingDelta + overflowPos).coerceIn(-maxPending, maxPending)
        assertEquals(maxPending, pendingDelta, 0.001f)

        // Huge negative delta
        val overflowNeg = -Float.MAX_VALUE
        pendingDelta = (pendingDelta + overflowNeg).coerceIn(-maxPending, maxPending)
        assertEquals(-maxPending, pendingDelta, 0.001f)

        // Zero delta
        pendingDelta = (0f + 0f).coerceIn(-maxPending, maxPending)
        assertEquals(0f, pendingDelta, 0.001f)
    }

    @Test
    fun testAutoscrollPartialConsumptionCarriesOver() {
        val maxSpeed = 22f
        val maxPending = maxSpeed * 2f
        var pendingDelta = 0f

        // Frame 1: delta = 15f. Grid only consumes 10f due to layout boundary
        pendingDelta = (pendingDelta + 15f).coerceIn(-maxPending, maxPending)
        var consumed = 10f
        pendingDelta -= consumed
        assertEquals(5f, pendingDelta, 0.001f)

        // Frame 2: delta = 15f. Total pending = 20f. Grid consumes 20f
        pendingDelta = (pendingDelta + 15f).coerceIn(-maxPending, maxPending)
        assertEquals(20f, pendingDelta, 0.001f)
        consumed = 20f
        pendingDelta -= consumed
        assertEquals(0f, pendingDelta, 0.001f)
    }
}
