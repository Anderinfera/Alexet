package dev.ander.alexet.ui.gallery

import dev.ander.alexet.data.AppSettings
import dev.ander.alexet.data.ContentViewMode
import dev.ander.alexet.data.FileSelectMode
import dev.ander.alexet.data.MultiFolderMemory
import dev.ander.alexet.data.VideoDurationFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for settings categories, defaults and previews.
 * - Category organization and defaults
 * - Panel split calculation and bounds
 * - MultiFolderMemory, Scroller and Foldable settings
 */
class SettingsCategoryAndPreviewTest {

    @Test
    fun testDefaultAppSettingsDefaults() {
        val settings = AppSettings()

        // Category 1: Widok defaults
        assertEquals(ContentViewMode.LAST_FOLDER, settings.contentViewMode)
        assertEquals(40, settings.panelSplitPercent)
        assertEquals(6, settings.expandColumns)
        assertEquals(5, settings.expandRows)
        assertEquals(3, settings.gridColumns)
        assertEquals(3, settings.foldableFileColumns)
        assertFalse(settings.showFileNames)
        assertEquals(VideoDurationFormat.MINUTES, settings.videoDurationFormat)
        assertTrue(settings.scrollerEnabled)
        assertFalse(settings.scrollerOnRight)
        assertTrue(settings.scrollerSmooth)
        assertTrue(settings.autoFoldSplitEnabled)
        assertEquals(20f, settings.foldSplitAngleThreshold, 0.001f)

        // Category 2: Foldery defaults
        assertEquals(MultiFolderMemory.REMEMBER, settings.multiFolderMemory)
        assertFalse(settings.rememberFolderScrollAcrossSessions)
        assertFalse(settings.showHidden)
        assertFalse(settings.hideFavoritesFolder)

        // Category 3: Prywatność defaults
        assertTrue(settings.randomizeFileNamesInPrivateFolders)
        assertTrue(settings.stripExifInPrivateFolders)
        assertFalse(settings.stripExifOnShare)

        // Category 4: Odtwarzacz defaults
        assertFalse(settings.videoDoubleClickToGallery)
        assertTrue(settings.autoplayGifs)
        assertFalse(settings.showNavArrows)
        assertTrue(settings.searchFilesFullScreen)
        assertEquals(FileSelectMode.RECTANGLE, settings.fileSelectMode)
        assertFalse(settings.operationsFollowSelectionOrder)
    }

    @Test
    fun testPanelSplitPercentBoundsAndStepperStep() {
        // Step size is 10%
        val step = 10
        var current = 40

        // Decrease down to minimum 10%
        while (current > 10) {
            current = (current - step).coerceAtLeast(10)
        }
        assertEquals(10, current)
        // Attempting to step below 10 should stay at 10
        current = (current - step).coerceAtLeast(10)
        assertEquals(10, current)

        // Increase up to maximum 90%
        while (current < 90) {
            current = (current + step).coerceAtMost(90)
        }
        assertEquals(90, current)
        // Attempting to step above 90 should stay at 90
        current = (current + step).coerceAtMost(90)
        assertEquals(90, current)
    }

    @Test
    fun testPanelSplitContentComplementRatio() {
        for (folderPercent in 10..90 step 10) {
            val contentPercent = 100 - folderPercent
            assertEquals(100, folderPercent + contentPercent)
            assertTrue(contentPercent in 10..90)
        }
    }

    @Test
    fun testTargetPanelSplitRatioCalculations() {
        // Flat mode preserves user percent as ratio clamped between 0.1 and 0.9
        assertEquals(0.1f, targetPanelSplitRatio(10, isBookMode = false), 0.001f)
        assertEquals(0.4f, targetPanelSplitRatio(40, isBookMode = false), 0.001f)
        assertEquals(0.5f, targetPanelSplitRatio(50, isBookMode = false), 0.001f)
        assertEquals(0.7f, targetPanelSplitRatio(70, isBookMode = false), 0.001f)
        assertEquals(0.9f, targetPanelSplitRatio(90, isBookMode = false), 0.001f)

        // Book mode overrides to symmetrical 0.5f when autoFoldSplitEnabled is true
        assertEquals(0.5f, targetPanelSplitRatio(10, isBookMode = true, autoFoldSplitEnabled = true), 0.001f)
        assertEquals(0.5f, targetPanelSplitRatio(40, isBookMode = true, autoFoldSplitEnabled = true), 0.001f)
        assertEquals(0.5f, targetPanelSplitRatio(90, isBookMode = true, autoFoldSplitEnabled = true), 0.001f)

        // When autoFoldSplitEnabled is false, book mode does NOT override user ratio
        assertEquals(0.1f, targetPanelSplitRatio(10, isBookMode = true, autoFoldSplitEnabled = false), 0.001f)
        assertEquals(0.4f, targetPanelSplitRatio(40, isBookMode = true, autoFoldSplitEnabled = false), 0.001f)
        assertEquals(0.9f, targetPanelSplitRatio(90, isBookMode = true, autoFoldSplitEnabled = false), 0.001f)
    }

    @Test
    fun testMultiFolderMemoryEnum() {
        val values = MultiFolderMemory.values()
        assertEquals(2, values.size)
        assertTrue(values.contains(MultiFolderMemory.FRESH))
        assertTrue(values.contains(MultiFolderMemory.REMEMBER))
    }

    @Test
    fun testContentViewModes() {
        val modes = ContentViewMode.values()
        assertTrue(modes.contains(ContentViewMode.LAST_FOLDER))
        assertTrue(modes.contains(ContentViewMode.EXPAND_FOLDERS))
        assertTrue(modes.contains(ContentViewMode.CLICK_DRAG))
    }

    @Test
    fun testFileSelectModes() {
        val modes = FileSelectMode.values()
        assertEquals(2, modes.size)
        assertTrue(modes.contains(FileSelectMode.RECTANGLE))
        assertTrue(modes.contains(FileSelectMode.LINEAR))
    }

    @Test
    fun testVideoDurationFormats() {
        val formats = VideoDurationFormat.values()
        assertEquals(2, formats.size)
        assertTrue(formats.contains(VideoDurationFormat.MINUTES))
        assertTrue(formats.contains(VideoDurationFormat.HOURS_MINUTES_SECONDS))
    }

    @Test
    fun testSettingsCategoryEnumDefinitions() {
        val categories = SettingsCategory.values()
        assertEquals(6, categories.size)
        assertEquals(SettingsCategory.VIEW, categories[0])
        assertEquals(SettingsCategory.FOLDERS, categories[1])
        assertEquals(SettingsCategory.PRIVACY, categories[2])
        assertEquals(SettingsCategory.PLAYER, categories[3])
        assertEquals(SettingsCategory.EXTRA, categories[4])
        assertEquals(SettingsCategory.SYSTEM, categories[5])

        for (cat in categories) {
            assertTrue("Title res must be valid for $cat", cat.titleRes != 0)
            assertTrue("Subtitle res must be valid for $cat", cat.subtitleRes != 0)
        }
    }
}
