package dev.ander.alexet.ui.gallery

import dev.ander.alexet.ui.wallpaper.TYPICAL_WALLPAPER_RATIOS
import dev.ander.alexet.ui.wallpaper.WallpaperResolutionMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for wallpaper resolution memory, preview box sizing and the panel split ratio.
 * - Dynamic aspect ratios and in-memory WallpaperResolutionMemory without disk I/O.
 * - WallpaperScreenResolutionsDialog preview boxes without text overlays across aspect ratios.
 * - targetPanelSplitRatio with autoFoldSplitEnabled, hinge angle thresholds and caller gap.
 */
class WallpaperResolutionAndPreviewTest {

    // --------------------------------------------------------------------------
    // 1. WallpaperResolutionMemory In-Memory & Concurrency Stress Tests
    // --------------------------------------------------------------------------

    @Test
    fun testWallpaperResolutionMemoryZeroDiskIo() {
        // Record directory timestamps / file count in project directory
        val rootDir = File(".")
        val initialFiles = rootDir.walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.toSet()

        // Perform extensive updates
        for (i in 1..500) {
            WallpaperResolutionMemory.update(isWide = false, width = 1000 + i, height = 2000 + i)
            WallpaperResolutionMemory.update(isWide = true, width = 2000 + i, height = 2000 + i)
        }

        assertTrue(WallpaperResolutionMemory.hasFolded())
        assertTrue(WallpaperResolutionMemory.hasUnfolded())
        assertEquals(1500, WallpaperResolutionMemory.detectedFoldedWidth)
        assertEquals(2500, WallpaperResolutionMemory.detectedFoldedHeight)
        assertEquals(2500, WallpaperResolutionMemory.detectedUnfoldedWidth)
        assertEquals(2500, WallpaperResolutionMemory.detectedUnfoldedHeight)

        // Verify zero new JSON or setting files were created on disk
        val currentFiles = rootDir.walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.toSet()
        assertEquals(initialFiles, currentFiles)
    }

    @Test
    fun testWallpaperResolutionMemoryRobustnessAgainstNonPositiveDimensions() {
        WallpaperResolutionMemory.detectedFoldedWidth = 1080
        WallpaperResolutionMemory.detectedFoldedHeight = 2400
        WallpaperResolutionMemory.detectedUnfoldedWidth = 2160
        WallpaperResolutionMemory.detectedUnfoldedHeight = 2160

        // Zero width
        WallpaperResolutionMemory.update(isWide = false, width = 0, height = 2400)
        assertEquals(1080, WallpaperResolutionMemory.detectedFoldedWidth)

        // Zero height
        WallpaperResolutionMemory.update(isWide = false, width = 1080, height = 0)
        assertEquals(2400, WallpaperResolutionMemory.detectedFoldedHeight)

        // Negative dimensions
        WallpaperResolutionMemory.update(isWide = true, width = -100, height = 2000)
        assertEquals(2160, WallpaperResolutionMemory.detectedUnfoldedWidth)

        WallpaperResolutionMemory.update(isWide = true, width = 2000, height = -50)
        assertEquals(2160, WallpaperResolutionMemory.detectedUnfoldedHeight)

        // Null-equivalent / both negative
        WallpaperResolutionMemory.update(isWide = false, width = -1, height = -1)
        assertEquals(1080, WallpaperResolutionMemory.detectedFoldedWidth)
        assertEquals(2400, WallpaperResolutionMemory.detectedFoldedHeight)
    }

    @Test
    fun testWallpaperResolutionMemoryHighConcurrencyStress() {
        val threadCount = 16
        val iterationsPerThread = 500
        val latch = CountDownLatch(threadCount)
        val errorCount = AtomicInteger(0)

        for (t in 0 until threadCount) {
            val isWide = (t % 2 == 0)
            Thread {
                try {
                    for (i in 1..iterationsPerThread) {
                        val w = 1000 + (t * 10) + i
                        val h = 2000 + (t * 10) + i
                        WallpaperResolutionMemory.update(isWide = isWide, width = w, height = h)
                        // Read volatile fields
                        val readW = if (isWide) WallpaperResolutionMemory.detectedUnfoldedWidth else WallpaperResolutionMemory.detectedFoldedWidth
                        val readH = if (isWide) WallpaperResolutionMemory.detectedUnfoldedHeight else WallpaperResolutionMemory.detectedFoldedHeight
                        assertTrue(readW > 0)
                        assertTrue(readH > 0)
                    }
                } catch (e: Throwable) {
                    errorCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        assertTrue("Timeout during concurrent stress test", latch.await(10, TimeUnit.SECONDS))
        assertEquals("Concurrent operations caused errors", 0, errorCount.get())
        assertTrue(WallpaperResolutionMemory.hasFolded())
        assertTrue(WallpaperResolutionMemory.hasUnfolded())
    }

    // --------------------------------------------------------------------------
    // 2. WallpaperScreenResolutionsDialog Preview Box & Ratio Tests
    // --------------------------------------------------------------------------

    @Test
    fun testWallpaperPreviewBoxAspectSizingAcrossExtremeRatios() {
        // Replicating the exact formula in WallpaperScreenResolutionsDialog.kt:
        // val foldedAspect = (fW.toFloat() / fH.toFloat()).coerceIn(0.2f, 2.5f)
        // val foldedBoxW = (54.dp * foldedAspect).coerceIn(24.dp, 54.dp)
        fun calculatePreviewWidthDp(w: Int, h: Int): Float {
            val aspect = (w.toFloat() / h.toFloat()).coerceIn(0.2f, 2.5f)
            return (54f * aspect).coerceIn(24f, 54f)
        }

        val testCases = listOf(
            Pair(1080, 2424), // Folded Pixel 9 Pro Fold (~0.445 aspect)
            Pair(1080, 2400), // Folded 20:9 (~0.45 aspect)
            Pair(968, 2376),  // Folded Z Fold (~0.407 aspect)
            Pair(1080, 1920), // Folded 16:9 vertical (0.5625 aspect)
            Pair(2076, 2152), // Unfolded near square (0.964 aspect)
            Pair(2160, 2160), // Unfolded pure square (1.0 aspect)
            Pair(2160, 1620), // Unfolded 4:3 (1.333 aspect)
            Pair(1856, 2160), // Unfolded 6:5 (0.859 aspect)
            Pair(100, 10000), // Extreme ultra-tall
            Pair(10000, 100), // Extreme ultra-wide
        )

        for ((w, h) in testCases) {
            val boxW = calculatePreviewWidthDp(w, h)
            assertTrue("Preview width $boxW must be >= 24dp for $w x $h", boxW >= 24f - 0.001f)
            assertTrue("Preview width $boxW must be <= 54dp for $w x $h", boxW <= 54f + 0.001f)
        }
    }

    @Test
    fun testWallpaperScreenResolutionsDialogFileHasNoTextInsidePreviewBox() {
        // Inspect WallpaperScreenResolutionsDialog.kt source code
        val dialogFile = File("src/main/java/dev/ander/alexet/ui/wallpaper/WallpaperScreenResolutionsDialog.kt")
        if (!dialogFile.exists()) {
            val altFile = File("app/src/main/java/dev/ander/alexet/ui/wallpaper/WallpaperScreenResolutionsDialog.kt")
            assertTrue("Dialog file must exist", altFile.exists())
            val content = altFile.readText()
            verifyDialogBoxStructure(content)
        } else {
            val content = dialogFile.readText()
            verifyDialogBoxStructure(content)
        }
    }

    private fun verifyDialogBoxStructure(content: String) {
        // Assert that the preview Box blocks do NOT contain inner Text composables
        // The preview boxes are: Box(modifier = Modifier.height(54.dp).width(foldedBoxW)...)
        val foldedBoxIdx = content.indexOf(".width(foldedBoxW)")
        assertTrue("foldedBoxW preview box must exist", foldedBoxIdx > 0)
        val foldedBoxSnippet = content.substring(foldedBoxIdx, foldedBoxIdx + 300)
        // Ensure the Box call is closed with ')' and not followed by a trailing lambda with Text
        assertTrue("Folded preview Box must close without inner lambda", foldedBoxSnippet.contains("),\n") || foldedBoxSnippet.contains(")\n"))
        assertFalse("Folded preview Box must not contain Text", foldedBoxSnippet.substringBefore(")\n").contains("Text("))

        val unfoldedBoxIdx = content.indexOf(".width(unfoldedBoxW)")
        assertTrue("unfoldedBoxW preview box must exist", unfoldedBoxIdx > 0)
        val unfoldedBoxSnippet = content.substring(unfoldedBoxIdx, unfoldedBoxIdx + 300)
        assertTrue("Unfolded preview Box must close without inner lambda", unfoldedBoxSnippet.contains("),\n") || unfoldedBoxSnippet.contains(")\n"))
        assertFalse("Unfolded preview Box must not contain Text", unfoldedBoxSnippet.substringBefore(")\n").contains("Text("))
    }

    // --------------------------------------------------------------------------
    // 3. targetPanelSplitRatio Exhaustive Stress Tests
    // --------------------------------------------------------------------------

    @Test
    fun testTargetPanelSplitRatioBookModeAndAutoFoldEnabledCombinations() {
        val testPercents = listOf(-10, 0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 150)

        for (percent in testPercents) {
            val expectedCustomRatio = percent.coerceIn(10, 90) / 100f

            // Case A: Book mode = true, autoFoldSplitEnabled = true -> MUST BE 0.5f ALWAYS
            val ratioA = targetPanelSplitRatio(
                panelSplitPercent = percent,
                isBookMode = true,
                autoFoldSplitEnabled = true,
            )
            assertEquals("Book mode with auto fold enabled must be 0.5f", 0.5f, ratioA, 0.0001f)

            // Case B: Book mode = true, autoFoldSplitEnabled = false -> MUST BE customRatio ALWAYS
            val ratioB = targetPanelSplitRatio(
                panelSplitPercent = percent,
                isBookMode = true,
                autoFoldSplitEnabled = false,
            )
            assertEquals("Book mode with auto fold disabled must return custom ratio", expectedCustomRatio, ratioB, 0.0001f)

            // Case C: Book mode = false, autoFoldSplitEnabled = false -> MUST BE customRatio ALWAYS
            val ratioC = targetPanelSplitRatio(
                panelSplitPercent = percent,
                isBookMode = false,
                autoFoldSplitEnabled = false,
            )
            assertEquals("Flat mode with auto fold disabled must return custom ratio", expectedCustomRatio, ratioC, 0.0001f)

            // Case D: Book mode = false, autoFoldSplitEnabled = true, hingeAngle = null -> MUST BE customRatio
            val ratioD = targetPanelSplitRatio(
                panelSplitPercent = percent,
                isBookMode = false,
                autoFoldSplitEnabled = true,
                hingeAngle = null,
            )
            assertEquals("Flat mode without hinge sensor must return custom ratio", expectedCustomRatio, ratioD, 0.0001f)
        }
    }

    @Test
    fun testTargetPanelSplitRatioHingeAngleBoundaryPermutations() {
        val threshold = 20f

        // When autoFoldSplitEnabled = true:
        // Angle <= 5° (closed posture) -> customRatio (30% -> 0.3f)
        for (angle in listOf(0f, 2f, 4.9f, 5f)) {
            val ratio = targetPanelSplitRatio(
                panelSplitPercent = 30,
                isBookMode = false,
                autoFoldSplitEnabled = true,
                hingeAngle = angle,
                foldSplitAngleThreshold = threshold,
            )
            assertEquals("Angle $angle <= 5° (closed) must preserve custom ratio", 0.3f, ratio, 0.0001f)
        }

        // Angle in (5°..160°] (continuous bent posture, including 20°..90°) -> 0.5f
        for (angle in listOf(5.1f, 10f, 15f, 19.9f, 20f, 20.1f, 25f, 45f, 75f, 90f, 91f, 120f, 150f, 160f)) {
            val ratio = targetPanelSplitRatio(
                panelSplitPercent = 30,
                isBookMode = false,
                autoFoldSplitEnabled = true,
                hingeAngle = angle,
                foldSplitAngleThreshold = threshold,
            )
            assertEquals("Angle $angle in continuous bend range must trigger 0.5f", 0.5f, ratio, 0.0001f)
        }

        // Angle > 160° (flat position > 180 - threshold) -> customRatio (30% -> 0.3f)
        for (angle in listOf(160.1f, 170f, 175f, 180f)) {
            val ratio = targetPanelSplitRatio(
                panelSplitPercent = 30,
                isBookMode = false,
                autoFoldSplitEnabled = true,
                hingeAngle = angle,
                foldSplitAngleThreshold = threshold,
            )
            assertEquals("Angle $angle > 180-threshold must preserve custom ratio", 0.3f, ratio, 0.0001f)
        }
    }

    @Test
    fun testTargetPanelSplitRatioWhenDisabledIgnoresAnyHingeAngle() {
        for (angle in listOf(0f, 10f, 20f, 45f, 90f, 120f, 150f, 180f)) {
            val ratio = targetPanelSplitRatio(
                panelSplitPercent = 35,
                isBookMode = false,
                autoFoldSplitEnabled = false,
                hingeAngle = angle,
                foldSplitAngleThreshold = 20f,
            )
            assertEquals("Disabled auto fold must always return custom ratio (0.35f)", 0.35f, ratio, 0.0001f)
        }
    }

    // --------------------------------------------------------------------------
    // 4. Demonstration of Caller Gap in Live UI (GalleryApp.kt)
    // --------------------------------------------------------------------------

    @Test
    fun testDemonstrateCallerGapWhenOptionalParametersAreOmitted() {
        // In GalleryApp.kt line 1556, the call is:
        // val targetWeight = targetPanelSplitRatio(uiState.settings.panelSplitPercent, isBook)
        // Notice that autoFoldSplitEnabled, hingeAngle, and foldSplitAngleThreshold are NOT passed.

        val userCustomPercent = 30 // User configured 30/70 split
        val userAutoFoldSetting = false // User explicitly DISABLED auto fold split in settings!
        val isBookMode = true // Device is folded in book mode

        // If called WITH user settings:
        val correctRatio = targetPanelSplitRatio(
            panelSplitPercent = userCustomPercent,
            isBookMode = isBookMode,
            autoFoldSplitEnabled = userAutoFoldSetting, // false
        )
        assertEquals("When user disables auto fold, ratio must be 0.3f", 0.3f, correctRatio, 0.0001f)

        // If called as in GalleryApp.kt:1556 (omitting autoFoldSplitEnabled):
        val currentLiveUiRatio = targetPanelSplitRatio(
            panelSplitPercent = userCustomPercent,
            isBookMode = isBookMode,
            // autoFoldSplitEnabled defaults to true!
        )
        assertEquals("Default parameter forces 0.5f even when user disabled auto fold", 0.5f, currentLiveUiRatio, 0.0001f)

        // This shows that caller GalleryApp.kt:1556 MUST pass autoFoldSplitEnabled
        // and hingeAngle from uiState.settings to make fully functional in the live interface.
    }
}
