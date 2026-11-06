package dev.ander.alexet.ui.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneSplitTest {

    @Test
    fun testFastFlingDownClosesBottom() {
        val result = splitReleaseResult(
            ratio = 0.85f,
            velocityPxPerSec = 2000f,
            paneHeightPx = 1000f,
        )
        assertTrue(result.closeBottom)
        assertFalse(result.closeTop)
    }

    @Test
    fun testFastFlingUpClosesTop() {
        val result = splitReleaseResult(
            ratio = 0.15f,
            velocityPxPerSec = -2000f,
            paneHeightPx = 1000f,
        )
        assertTrue(result.closeTop)
        assertFalse(result.closeBottom)
    }

    @Test
    fun testSnapToTopCanvas() {
        val result = splitReleaseResult(
            ratio = 0.88f,
            velocityPxPerSec = 0f,
            paneHeightPx = 1000f,
        )
        assertEquals(PaneStack.TOP, result.stack)
        assertEquals(CANVAS_RATIO, result.ratio, 0.001f)
    }

    @Test
    fun testSnapToBottomCanvas() {
        val result = splitReleaseResult(
            ratio = 0.12f,
            velocityPxPerSec = 0f,
            paneHeightPx = 1000f,
        )
        assertEquals(PaneStack.BOTTOM, result.stack)
        assertEquals(1f - CANVAS_RATIO, result.ratio, 0.001f)
    }

    @Test
    fun testNormalSplitWithoutSnapping() {
        val result = splitReleaseResult(
            ratio = 0.55f,
            velocityPxPerSec = 100f,
            paneHeightPx = 1000f,
        )
        assertEquals(PaneStack.NONE, result.stack)
        assertEquals(0.55f, result.ratio, 0.001f)
        assertFalse(result.closeTop)
        assertFalse(result.closeBottom)
    }

    @Test
    fun testTargetPanelSplitRatioInBookModeAlwaysHalf() {
        assertEquals(0.5f, targetPanelSplitRatio(panelSplitPercent = 40, isBookMode = true), 0.0001f)
        assertEquals(0.5f, targetPanelSplitRatio(panelSplitPercent = 20, isBookMode = true), 0.0001f)
        assertEquals(0.5f, targetPanelSplitRatio(panelSplitPercent = 80, isBookMode = true), 0.0001f)
    }

    @Test
    fun testTargetPanelSplitRatioInFlatModeFollowsUserSetting() {
        assertEquals(0.4f, targetPanelSplitRatio(panelSplitPercent = 40, isBookMode = false), 0.0001f)
        assertEquals(0.3f, targetPanelSplitRatio(panelSplitPercent = 30, isBookMode = false), 0.0001f)
        assertEquals(0.7f, targetPanelSplitRatio(panelSplitPercent = 70, isBookMode = false), 0.0001f)
        // Clamping to 10..90
        assertEquals(0.1f, targetPanelSplitRatio(panelSplitPercent = 5, isBookMode = false), 0.0001f)
        assertEquals(0.9f, targetPanelSplitRatio(panelSplitPercent = 95, isBookMode = false), 0.0001f)
    }

    @Test
    fun testTargetPanelSplitRatioWhenAutoFoldSplitDisabledMaintainsUserSetting() {
        // When autoFoldSplitEnabled is false, user setting is strictly maintained even in book mode or folded hinge angle
        assertEquals(0.4f, targetPanelSplitRatio(panelSplitPercent = 40, isBookMode = true, autoFoldSplitEnabled = false), 0.0001f)
        assertEquals(0.3f, targetPanelSplitRatio(panelSplitPercent = 30, isBookMode = true, autoFoldSplitEnabled = false), 0.0001f)
        assertEquals(0.4f, targetPanelSplitRatio(panelSplitPercent = 40, isBookMode = false, autoFoldSplitEnabled = false, hingeAngle = 10f, foldSplitAngleThreshold = 20f), 0.0001f)
        assertEquals(0.4f, targetPanelSplitRatio(panelSplitPercent = 40, isBookMode = false, autoFoldSplitEnabled = false, hingeAngle = 150f, foldSplitAngleThreshold = 20f), 0.0001f)
    }

    @Test
    fun testTargetPanelSplitRatioWithHingeAngleSensor() {
        // When autoFoldSplitEnabled is true and hinge angle <= (180 - threshold) and > 5f, apply 50/50 split
        assertEquals(0.5f, targetPanelSplitRatio(panelSplitPercent = 40, isBookMode = false, autoFoldSplitEnabled = true, hingeAngle = 15f, foldSplitAngleThreshold = 20f), 0.0001f)
        assertEquals(0.5f, targetPanelSplitRatio(panelSplitPercent = 40, isBookMode = false, autoFoldSplitEnabled = true, hingeAngle = 20f, foldSplitAngleThreshold = 20f), 0.0001f)

        // Partially folded book postures between 20° and 90° correctly split 50/50
        assertEquals(0.5f, targetPanelSplitRatio(panelSplitPercent = 40, isBookMode = false, autoFoldSplitEnabled = true, hingeAngle = 25f, foldSplitAngleThreshold = 20f), 0.0001f)
        assertEquals(0.5f, targetPanelSplitRatio(panelSplitPercent = 40, isBookMode = false, autoFoldSplitEnabled = true, hingeAngle = 80f, foldSplitAngleThreshold = 20f), 0.0001f)

        // When hinge angle indicates fold from flat (angle <= 180 - threshold)
        assertEquals(0.5f, targetPanelSplitRatio(panelSplitPercent = 40, isBookMode = false, autoFoldSplitEnabled = true, hingeAngle = 150f, foldSplitAngleThreshold = 20f), 0.0001f)
        // When flat (> 180 - threshold), maintains user ratio
        assertEquals(0.4f, targetPanelSplitRatio(panelSplitPercent = 40, isBookMode = false, autoFoldSplitEnabled = true, hingeAngle = 175f, foldSplitAngleThreshold = 20f), 0.0001f)
        // When closed (<= 5f), maintains user ratio
        assertEquals(0.4f, targetPanelSplitRatio(panelSplitPercent = 40, isBookMode = false, autoFoldSplitEnabled = true, hingeAngle = 4f, foldSplitAngleThreshold = 20f), 0.0001f)

        // When hinge angle is null, falls back to isBookMode
        assertEquals(0.5f, targetPanelSplitRatio(panelSplitPercent = 40, isBookMode = true, autoFoldSplitEnabled = true, hingeAngle = null), 0.0001f)
        assertEquals(0.4f, targetPanelSplitRatio(panelSplitPercent = 40, isBookMode = false, autoFoldSplitEnabled = true, hingeAngle = null), 0.0001f)
    }

    @Test
    fun testWallpaperResolutionMemoryCache() {
        dev.ander.alexet.ui.wallpaper.WallpaperResolutionMemory.detectedFoldedWidth = 0
        dev.ander.alexet.ui.wallpaper.WallpaperResolutionMemory.detectedFoldedHeight = 0
        dev.ander.alexet.ui.wallpaper.WallpaperResolutionMemory.detectedUnfoldedWidth = 0
        dev.ander.alexet.ui.wallpaper.WallpaperResolutionMemory.detectedUnfoldedHeight = 0
        assertFalse(dev.ander.alexet.ui.wallpaper.WallpaperResolutionMemory.hasFolded())
        assertFalse(dev.ander.alexet.ui.wallpaper.WallpaperResolutionMemory.hasUnfolded())

        dev.ander.alexet.ui.wallpaper.WallpaperResolutionMemory.update(isWide = false, width = 1080, height = 2400)
        assertTrue(dev.ander.alexet.ui.wallpaper.WallpaperResolutionMemory.hasFolded())
        assertEquals(1080, dev.ander.alexet.ui.wallpaper.WallpaperResolutionMemory.detectedFoldedWidth)
        assertEquals(2400, dev.ander.alexet.ui.wallpaper.WallpaperResolutionMemory.detectedFoldedHeight)

        dev.ander.alexet.ui.wallpaper.WallpaperResolutionMemory.update(isWide = true, width = 2160, height = 2160)
        assertTrue(dev.ander.alexet.ui.wallpaper.WallpaperResolutionMemory.hasUnfolded())
        assertEquals(2160, dev.ander.alexet.ui.wallpaper.WallpaperResolutionMemory.detectedUnfoldedWidth)
        assertEquals(2160, dev.ander.alexet.ui.wallpaper.WallpaperResolutionMemory.detectedUnfoldedHeight)
    }

    @Test
    fun testTypicalWallpaperRatiosArePresentAndValid() {
        assertTrue(dev.ander.alexet.ui.wallpaper.TYPICAL_WALLPAPER_RATIOS.isNotEmpty())
        for (preset in dev.ander.alexet.ui.wallpaper.TYPICAL_WALLPAPER_RATIOS) {
            assertTrue(preset.sampleFoldedW > 0)
            assertTrue(preset.sampleFoldedH > 0)
            assertTrue(preset.sampleUnfoldedW > 0)
            assertTrue(preset.sampleUnfoldedH > 0)
            assertTrue(preset.foldedAspect > 0f)
            assertTrue(preset.unfoldedAspect > 0f)
            // Verify labels contain ratio descriptions, not device brand names
            assertFalse(preset.label.contains("Pixel", ignoreCase = true))
            assertFalse(preset.label.contains("Samsung", ignoreCase = true))
            assertFalse(preset.label.contains("Galaxy", ignoreCase = true))
            assertFalse(preset.label.contains("OnePlus", ignoreCase = true))
        }
    }
}
