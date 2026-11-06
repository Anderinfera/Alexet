package dev.ander.alexet.ui.gallery

import androidx.window.core.layout.WindowSizeClass
import dev.ander.alexet.data.AppSettings
import dev.ander.alexet.foldable.FoldableState
import dev.ander.alexet.foldable.HingeAngleState
import dev.ander.alexet.ui.wallpaper.FALLBACK_COVER_ASPECT
import dev.ander.alexet.ui.wallpaper.FALLBACK_INNER_ASPECT
import dev.ander.alexet.ui.wallpaper.IntSize2
import dev.ander.alexet.ui.wallpaper.TYPICAL_WALLPAPER_RATIOS
import dev.ander.alexet.ui.wallpaper.WallpaperPreviewScreen
import dev.ander.alexet.ui.wallpaper.WallpaperResolutionMemory
import dev.ander.alexet.ui.wallpaper.WallpaperScreenTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tests for the panel split ratio at extreme hinge angles and thresholds.
 */
class PanelSplitAngleTest {

    @Before
    fun setUp() {
        WallpaperResolutionMemory.detectedFoldedWidth = 0
        WallpaperResolutionMemory.detectedFoldedHeight = 0
        WallpaperResolutionMemory.detectedUnfoldedWidth = 0
        WallpaperResolutionMemory.detectedUnfoldedHeight = 0
    }

    // =========================================================================
    // 1. HINGE ANGLE SENSOR MATH & EXTREME ANGLES IN targetPanelSplitRatio
    // =========================================================================

    @Test
    fun testTargetPanelSplitRatioExtremeAngles() {
        val customPercent = 40
        val expectedCustom = 0.4f
        val expectedFold = 0.5f

        // Angle 0° (fully closed <= 5°) maintains custom ratio
        val r0 = targetPanelSplitRatio(customPercent, isBookMode = false, autoFoldSplitEnabled = true, hingeAngle = 0f, foldSplitAngleThreshold = 20f)
        assertEquals("Angle 0° maintains custom ratio", expectedCustom, r0, 0.0001f)

        // Angle 15° (within continuous bend range 5°..160°)
        val r15 = targetPanelSplitRatio(customPercent, isBookMode = false, autoFoldSplitEnabled = true, hingeAngle = 15f, foldSplitAngleThreshold = 20f)
        assertEquals("Angle 15° triggers 50/50", expectedFold, r15, 0.0001f)

        // Angle 20° (continuous bend range)
        val r20 = targetPanelSplitRatio(customPercent, isBookMode = false, autoFoldSplitEnabled = true, hingeAngle = 20f, foldSplitAngleThreshold = 20f)
        assertEquals("Angle 20° triggers 50/50", expectedFold, r20, 0.0001f)

        // Angle 20.001° (continuous bend range)
        val r20_1 = targetPanelSplitRatio(customPercent, isBookMode = false, autoFoldSplitEnabled = true, hingeAngle = 20.001f, foldSplitAngleThreshold = 20f)
        assertEquals("Angle 20.001° triggers 50/50 in continuous bend detection", expectedFold, r20_1, 0.0001f)

        // Angle 45° (partially folded book posture between 20° and 90°)
        val r45 = targetPanelSplitRatio(customPercent, isBookMode = false, autoFoldSplitEnabled = true, hingeAngle = 45f, foldSplitAngleThreshold = 20f)
        assertEquals("Angle 45° triggers 50/50 in continuous bend detection", expectedFold, r45, 0.0001f)

        // Angle 90° (half-opened right-angle book / laptop posture)
        val r90 = targetPanelSplitRatio(customPercent, isBookMode = false, autoFoldSplitEnabled = true, hingeAngle = 90f, foldSplitAngleThreshold = 20f)
        assertEquals("Angle 90° triggers 50/50 in continuous bend detection", expectedFold, r90, 0.0001f)

        // Angle 91° (continuous bend range)
        val r91 = targetPanelSplitRatio(customPercent, isBookMode = false, autoFoldSplitEnabled = true, hingeAngle = 91f, foldSplitAngleThreshold = 20f)
        assertEquals("Angle 91° triggers 50/50", expectedFold, r91, 0.0001f)

        // Angle 160° (upper bound: 180 - threshold = 160)
        val r160 = targetPanelSplitRatio(customPercent, isBookMode = false, autoFoldSplitEnabled = true, hingeAngle = 160f, foldSplitAngleThreshold = 20f)
        assertEquals("Angle 160° triggers 50/50", expectedFold, r160, 0.0001f)

        // Angle 160.001° (just above fold-from-flat boundary)
        val r160_1 = targetPanelSplitRatio(customPercent, isBookMode = false, autoFoldSplitEnabled = true, hingeAngle = 160.001f, foldSplitAngleThreshold = 20f)
        assertEquals("Angle 160.001° maintains custom ratio", expectedCustom, r160_1, 0.0001f)

        // Angle 180° (fully opened flat)
        val r180 = targetPanelSplitRatio(customPercent, isBookMode = false, autoFoldSplitEnabled = true, hingeAngle = 180f, foldSplitAngleThreshold = 20f)
        assertEquals("Angle 180° maintains custom ratio", expectedCustom, r180, 0.0001f)

        // Negative angles (sensor anomaly / closed <= 5°)
        val rNeg = targetPanelSplitRatio(customPercent, isBookMode = false, autoFoldSplitEnabled = true, hingeAngle = -10f, foldSplitAngleThreshold = 20f)
        assertEquals("Negative angle <= 5° maintains custom ratio", expectedCustom, rNeg, 0.0001f)

        // Null hinge angle with isBookMode false
        val rNullFalse = targetPanelSplitRatio(customPercent, isBookMode = false, autoFoldSplitEnabled = true, hingeAngle = null)
        assertEquals("Null hingeAngle with isBookMode=false maintains custom ratio", expectedCustom, rNullFalse, 0.0001f)

        // Null hinge angle with isBookMode true
        val rNullTrue = targetPanelSplitRatio(customPercent, isBookMode = true, autoFoldSplitEnabled = true, hingeAngle = null)
        assertEquals("Null hingeAngle with isBookMode=true triggers 50/50", expectedFold, rNullTrue, 0.0001f)
    }

    @Test
    fun testThresholdBoundaryStrictMonotonicity() {
        val customPercent = 35
        val expectedCustom = 0.35f
        val expectedFold = 0.5f

        val testThresholds = listOf(5f, 10f, 20f, 30f, 45f, 60f, 85f, 90f)
        for (th in testThresholds) {
            val flatBoundary = 180f - th

            // Strictly at flat bend boundary
            val atTh = targetPanelSplitRatio(customPercent, isBookMode = false, autoFoldSplitEnabled = true, hingeAngle = flatBoundary, foldSplitAngleThreshold = th)
            assertEquals("Angle exactly equal to flat boundary ($flatBoundary) must trigger 50/50", expectedFold, atTh, 0.0001f)

            // Strictly below flat boundary (bent posture)
            if (flatBoundary - 0.5f > 5f) {
                val belowTh = targetPanelSplitRatio(customPercent, isBookMode = false, autoFoldSplitEnabled = true, hingeAngle = flatBoundary - 0.5f, foldSplitAngleThreshold = th)
                assertEquals("Angle below flat boundary ($flatBoundary - 0.5) must trigger 50/50", expectedFold, belowTh, 0.0001f)
            }

            // Just above flat boundary (flat posture > 180 - th)
            val aboveTh = targetPanelSplitRatio(customPercent, isBookMode = false, autoFoldSplitEnabled = true, hingeAngle = flatBoundary + 0.1f, foldSplitAngleThreshold = th)
            assertEquals("Angle just above flat boundary ($flatBoundary + 0.1) must maintain custom ratio", expectedCustom, aboveTh, 0.0001f)

            // Closed posture (<= 5f)
            val closedAngle = targetPanelSplitRatio(customPercent, isBookMode = false, autoFoldSplitEnabled = true, hingeAngle = 4f, foldSplitAngleThreshold = th)
            assertEquals("Closed angle (4°) must maintain custom ratio", expectedCustom, closedAngle, 0.0001f)
        }
    }

    @Test
    fun testAutoFoldSplitDisabledOverridesAllHingeAnglesAndBookMode() {
        val customPercent = 65
        val expectedCustom = 0.65f

        val angles = listOf(-10f, 0f, 10f, 20f, 45f, 90f, 95f, 150f, 160f, 180f, null)
        for (angle in angles) {
            val rBook = targetPanelSplitRatio(customPercent, isBookMode = true, autoFoldSplitEnabled = false, hingeAngle = angle, foldSplitAngleThreshold = 20f)
            assertEquals("Disabled auto-fold must maintain custom ratio even in book mode (angle=$angle)", expectedCustom, rBook, 0.0001f)

            val rFlat = targetPanelSplitRatio(customPercent, isBookMode = false, autoFoldSplitEnabled = false, hingeAngle = angle, foldSplitAngleThreshold = 20f)
            assertEquals("Disabled auto-fold must maintain custom ratio (angle=$angle)", expectedCustom, rFlat, 0.0001f)
        }
    }

    @Test
    fun testFoldableStateIsBentBookDiscrepancy() {
        val dummySize = WindowSizeClass.compute(1000f, 1000f)

        // FoldableState.isBentBook requires angle > 5f
        val state0 = FoldableState(windowSizeClass = dummySize, foldingFeature = null, hingeAngleState = HingeAngleState().also { it.angle = 0f })
        assertFalse("FoldableState.isBentBook is false for 0° due to angle > 5f guard", state0.isBentBook(20f))

        val stateNeg = FoldableState(windowSizeClass = dummySize, foldingFeature = null, hingeAngleState = HingeAngleState().also { it.angle = -5f })
        assertFalse("FoldableState.isBentBook is false for negative angles", stateNeg.isBentBook(20f))

        val state15 = FoldableState(windowSizeClass = dummySize, foldingFeature = null, hingeAngleState = HingeAngleState().also { it.angle = 15f })
        assertTrue("FoldableState.isBentBook is true for 15°", state15.isBentBook(20f))

        val state90 = FoldableState(windowSizeClass = dummySize, foldingFeature = null, hingeAngleState = HingeAngleState().also { it.angle = 90f })
        assertTrue("FoldableState.isBentBook is true for 90° partially folded posture", state90.isBentBook(20f))

        val state150 = FoldableState(windowSizeClass = dummySize, foldingFeature = null, hingeAngleState = HingeAngleState().also { it.angle = 150f })
        assertTrue("FoldableState.isBentBook is true for 150° (in 5..160)", state150.isBentBook(20f))
    }

    // =========================================================================
    // 2. IN-MEMORY ASPECT RATIO DETECTION & RESOLUTION MEMORY STRESS TESTS
    // =========================================================================

    @Test
    fun testWallpaperResolutionMemoryZeroAndNegativeDimensions() {
        assertFalse(WallpaperResolutionMemory.hasFolded())
        assertFalse(WallpaperResolutionMemory.hasUnfolded())

        // 1. Zero dimensions must be rejected
        WallpaperResolutionMemory.update(isWide = false, width = 0, height = 2400)
        assertFalse("Zero width should not update folded memory", WallpaperResolutionMemory.hasFolded())

        WallpaperResolutionMemory.update(isWide = false, width = 1080, height = 0)
        assertFalse("Zero height should not update folded memory", WallpaperResolutionMemory.hasFolded())

        WallpaperResolutionMemory.update(isWide = true, width = 0, height = 0)
        assertFalse("Zero width and height should not update unfolded memory", WallpaperResolutionMemory.hasUnfolded())

        // 2. Negative dimensions must be rejected
        WallpaperResolutionMemory.update(isWide = false, width = -1080, height = 2400)
        assertFalse("Negative width should not update folded memory", WallpaperResolutionMemory.hasFolded())

        WallpaperResolutionMemory.update(isWide = false, width = 1080, height = -2400)
        assertFalse("Negative height should not update folded memory", WallpaperResolutionMemory.hasFolded())

        WallpaperResolutionMemory.update(isWide = true, width = -2000, height = -2000)
        assertFalse("Negative width and height should not update unfolded memory", WallpaperResolutionMemory.hasUnfolded())

        // 3. Valid update followed by invalid update must preserve existing valid dimensions
        WallpaperResolutionMemory.update(isWide = false, width = 1080, height = 2424)
        assertTrue(WallpaperResolutionMemory.hasFolded())
        assertEquals(1080, WallpaperResolutionMemory.detectedFoldedWidth)
        assertEquals(2424, WallpaperResolutionMemory.detectedFoldedHeight)

        WallpaperResolutionMemory.update(isWide = false, width = -500, height = -500)
        assertEquals(1080, WallpaperResolutionMemory.detectedFoldedWidth)
        assertEquals(2424, WallpaperResolutionMemory.detectedFoldedHeight)

        WallpaperResolutionMemory.update(isWide = false, width = 0, height = 1000)
        assertEquals(1080, WallpaperResolutionMemory.detectedFoldedWidth)
        assertEquals(2424, WallpaperResolutionMemory.detectedFoldedHeight)
    }

    @Test
    fun testWallpaperResolutionMemoryConcurrentUpdates() {
        val iterations = 500
        val latch = CountDownLatch(2)

        val t1 = Thread {
            for (i in 1..iterations) {
                WallpaperResolutionMemory.update(isWide = false, width = 1000 + i, height = 2000 + i)
            }
            latch.countDown()
        }

        val t2 = Thread {
            for (i in 1..iterations) {
                WallpaperResolutionMemory.update(isWide = true, width = 2000 + i, height = 2000 + i)
            }
            latch.countDown()
        }

        t1.start()
        t2.start()
        assertTrue("Concurrent updates must finish cleanly", latch.await(5, TimeUnit.SECONDS))

        assertTrue(WallpaperResolutionMemory.hasFolded())
        assertTrue(WallpaperResolutionMemory.hasUnfolded())
        assertTrue(WallpaperResolutionMemory.detectedFoldedWidth > 1000)
        assertTrue(WallpaperResolutionMemory.detectedUnfoldedWidth > 2000)
    }

    @Test
    fun testWallpaperScreenTargetsZeroAndNegativeAspectSafety() {
        // Test targets with zero height (must not throw ArithmeticException / divide-by-zero)
        val targetsZeroH = WallpaperScreenTargets(
            foldedWidth = 1080,
            foldedHeight = 0,
            unfoldedWidth = 2000,
            unfoldedHeight = 0,
        )
        assertEquals(FALLBACK_COVER_ASPECT, targetsZeroH.foldedAspect, 0.0001f)
        assertEquals(FALLBACK_INNER_ASPECT, targetsZeroH.unfoldedAspect, 0.0001f)

        // Test sizeFor with zero or negative dimensions (must coerce to at least 1)
        val targetsNegative = WallpaperScreenTargets(
            foldedWidth = -100,
            foldedHeight = 0,
            unfoldedWidth = 0,
            unfoldedHeight = -50,
        )
        val foldedSize = targetsNegative.sizeFor(WallpaperPreviewScreen.FOLDED)
        assertEquals(1, foldedSize.width)
        assertEquals(1, foldedSize.height)

        val unfoldedSize = targetsNegative.sizeFor(WallpaperPreviewScreen.UNFOLDED)
        assertEquals(1, unfoldedSize.width)
        assertEquals(1, unfoldedSize.height)
    }

    @Test
    fun testRotatedDisplaysAndPortraitNormalization() {
        // Standard phone portrait: 1080 x 2400 (aspect 0.45)
        val portraitPhone = IntSize2(1080, 2400)
        assertEquals(0.45f, portraitPhone.aspect, 0.001f)

        // Rotated phone in landscape: 2400 x 1080 (aspect 2.222f)
        val landscapePhone = IntSize2(2400, 1080)
        assertTrue(landscapePhone.width > landscapePhone.height)
        assertTrue((landscapePhone.width.toFloat() / landscapePhone.height.toFloat()) > 1.4f)

        // Normalization rule in WallpaperScreenSizes.kt:
        // if (size.width > size.height && (size.width / size.height) > 1.4f) IntSize2(size.height, size.width)
        val normalizedLandscape = if (landscapePhone.width > landscapePhone.height &&
            (landscapePhone.width.toFloat() / landscapePhone.height.toFloat()) > 1.4f) {
            IntSize2(landscapePhone.height, landscapePhone.width)
        } else landscapePhone

        assertEquals(1080, normalizedLandscape.width)
        assertEquals(2400, normalizedLandscape.height)
        assertEquals(0.45f, normalizedLandscape.aspect, 0.001f)
        assertTrue("Normalized cover aspect must be within 0.35..0.65", normalizedLandscape.aspect in 0.35f..0.65f)

        // Unfolded inner screen (e.g. 2152 x 2076): aspect 1.036
        val unfoldedSquareish = IntSize2(2152, 2076)
        val normalizedUnfolded = if (unfoldedSquareish.width > unfoldedSquareish.height &&
            (unfoldedSquareish.width.toFloat() / unfoldedSquareish.height.toFloat()) > 1.4f) {
            IntSize2(unfoldedSquareish.height, unfoldedSquareish.width)
        } else unfoldedSquareish

        // Unfolded ratio (1.036f) is NOT > 1.4f, so width and height are preserved
        assertEquals(2152, normalizedUnfolded.width)
        assertEquals(2076, normalizedUnfolded.height)
        assertTrue("Unfolded aspect must be within 0.70..1.35", normalizedUnfolded.aspect in 0.70f..1.35f)
    }

    @Test
    fun testMultiWindowSplitScreenAreaFilterSelection() {
        // In multi-window split screen, window size may be smaller (e.g. 1080 x 2160) than physical display (1080 x 2424)
        val multiWindowCover = IntSize2(1080, 2160) // aspect 0.50
        val physicalCover = IntSize2(1080, 2424)    // aspect 0.4455

        val candidates = listOf(multiWindowCover, physicalCover)
        val coverCandidates = candidates.filter { it.aspect in 0.35f..0.65f }
        assertEquals(2, coverCandidates.size)

        // maxByOrNull { it.width * it.height } must select the physical display over the multi-window partition
        val selected = coverCandidates.maxByOrNull { it.width * it.height }
        assertNotNull(selected)
        assertEquals(1080, selected!!.width)
        assertEquals(2424, selected.height)
    }

    @Test
    fun testTypicalWallpaperRatiosCoverage() {
        assertEquals(4, TYPICAL_WALLPAPER_RATIOS.size)
        val ids = TYPICAL_WALLPAPER_RATIOS.map { it.id }
        assertTrue(ids.contains("ratio_20_9_square"))
        assertTrue(ids.contains("ratio_21_9_4_3"))
        assertTrue(ids.contains("ratio_22_9_6_5"))
        assertTrue(ids.contains("ratio_16_9_square"))

        for (item in TYPICAL_WALLPAPER_RATIOS) {
            assertTrue("Aspect must be positive", item.foldedAspect in 0.3f..0.7f)
            assertTrue("Unfolded aspect must be positive", item.unfoldedAspect in 0.6f..1.5f)
            assertFalse("No phone brands allowed in labels", item.label.contains("Pixel") || item.label.contains("Galaxy"))
        }
    }
}
