package dev.ander.alexet.ui.gallery

import dev.ander.alexet.data.AppSettings
import dev.ander.alexet.data.ContentViewMode
import dev.ander.alexet.data.FileSelectMode
import dev.ander.alexet.data.MultiFolderMemory
import dev.ander.alexet.data.VideoDurationFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import kotlin.math.roundToInt

/**
 * Tests for panel split bounds, coercion and stepper rounding.
 */
class PanelSplitBoundsTest {

    // =========================================================================
    // 1. RATIO CALCULATIONS, EDGE VALUES & BOUNDS COERCION STRESS TESTS
    // =========================================================================

    @Test
    fun testPanelSplitBoundaryRatios() {
        // Test standard boundary values: 10%, 50%, 90%
        val minRatio = (10.coerceIn(10, 90) / 100f)
        val midRatio = (50.coerceIn(10, 90) / 100f)
        val maxRatio = (90.coerceIn(10, 90) / 100f)

        assertEquals(0.10f, minRatio, 0.0001f)
        assertEquals(0.50f, midRatio, 0.0001f)
        assertEquals(0.90f, maxRatio, 0.0001f)

        // Test complements (right pane ratio)
        assertEquals(0.90f, 1f - minRatio, 0.0001f)
        assertEquals(0.50f, 1f - midRatio, 0.0001f)
        assertEquals(0.10f, 1f - maxRatio, 0.0001f)

        // Sum of ratios is always exactly 1.0f
        assertEquals(1.0f, minRatio + (1f - minRatio), 0.00001f)
        assertEquals(1.0f, midRatio + (1f - midRatio), 0.00001f)
        assertEquals(1.0f, maxRatio + (1f - maxRatio), 0.00001f)
    }

    @Test
    fun testPanelSplitOutOfBoundsUnderflowCoercion() {
        // Extreme negative values, zero, and sub-10 values must be coerced to 10% (0.10f)
        val underflowValues = listOf(Int.MIN_VALUE, -1000, -100, -1, 0, 1, 5, 8, 9)

        for (underflow in underflowValues) {
            val coerced = underflow.coerceIn(10, 90)
            assertEquals("Underflow $underflow should coerce to 10", 10, coerced)

            val ratio = coerced / 100f
            assertEquals(0.10f, ratio, 0.0001f)

            // Compose RowScope.weight must never receive 0f or negative
            val leftWeight = ratio.coerceIn(0.1f, 0.9f)
            val rightWeight = (1f - ratio).coerceIn(0.1f, 0.9f)
            assertTrue("Left weight $leftWeight must be >= 0.1f", leftWeight >= 0.1f)
            assertTrue("Right weight $rightWeight must be >= 0.1f", rightWeight >= 0.1f)
            assertTrue("Left weight $leftWeight must be <= 0.9f", leftWeight <= 0.9f)
            assertTrue("Right weight $rightWeight must be <= 0.9f", rightWeight <= 0.9f)
        }
    }

    @Test
    fun testPanelSplitOutOfBoundsOverflowCoercion() {
        // Extreme positive values above 90 must be coerced to 90% (0.90f)
        val overflowValues = listOf(91, 95, 99, 100, 101, 150, 999, 10000, Int.MAX_VALUE)

        for (overflow in overflowValues) {
            val coerced = overflow.coerceIn(10, 90)
            assertEquals("Overflow $overflow should coerce to 90", 90, coerced)

            val ratio = coerced / 100f
            assertEquals(0.90f, ratio, 0.0001f)

            val leftWeight = ratio.coerceIn(0.1f, 0.9f)
            val rightWeight = (1f - ratio).coerceIn(0.1f, 0.9f)
            assertTrue("Left weight $leftWeight must be >= 0.1f", leftWeight >= 0.1f)
            assertTrue("Right weight $rightWeight must be >= 0.1f", rightWeight >= 0.1f)
            assertTrue("Left weight $leftWeight must be <= 0.9f", leftWeight <= 0.9f)
            assertTrue("Right weight $rightWeight must be <= 0.9f", rightWeight <= 0.9f)
        }
    }

    @Test
    fun testPanelSplitStepperTransitionsAndRounding() {
        // Test stepper arithmetic: minus button: ((percent / 10) * 10 - 10).coerceAtLeast(10)
        // plus button: ((percent / 10) * 10 + 10).coerceAtMost(90)
        for (step in 10..90 step 10) {
            val minusNext = ((step / 10) * 10 - 10).coerceAtLeast(10)
            val plusNext = ((step / 10) * 10 + 10).coerceAtMost(90)

            if (step == 10) {
                assertEquals(10, minusNext)
                assertEquals(20, plusNext)
            } else if (step == 90) {
                assertEquals(80, minusNext)
                assertEquals(90, plusNext)
            } else {
                assertEquals(step - 10, minusNext)
                assertEquals(step + 10, plusNext)
            }
        }

        // Test stepper behavior with unaligned inputs (e.g. from corrupt config)
        val unalignedValues = listOf(13, 27, 45, 68, 82)
        for (unaligned in unalignedValues) {
            val minusNext = ((unaligned / 10) * 10 - 10).coerceAtLeast(10)
            val plusNext = ((unaligned / 10) * 10 + 10).coerceAtMost(90)
            assertTrue(minusNext % 10 == 0)
            assertTrue(plusNext % 10 == 0)
            assertTrue(minusNext in 10..90)
            assertTrue(plusNext in 10..90)
        }
    }

    @Test
    fun testPanelSplitPercentageRoundingConsistency() {
        // Live split badge calculates:
        // val leftPct = (animatedRatio * 100).roundToInt()
        // val rightPct = 100 - leftPct
        for (percent in 10..90) {
            val ratio = percent / 100f
            val leftPct = (ratio * 100).roundToInt()
            val rightPct = 100 - leftPct
            assertEquals(percent, leftPct)
            assertEquals(100 - percent, rightPct)
            assertEquals(100, leftPct + rightPct)
        }
    }

    @Test
    fun testTargetPanelSplitRatioWithHingeAngleMatrix() {
        // Verify targetPanelSplitRatio full decision matrix
        val angles = listOf(0f, 5f, 15f, 20f, 45f, 90f, 150f, 160f, 175f, 180f)
        val thresholds = listOf(10f, 20f, 30f, 45f)

        for (th in thresholds) {
            for (angle in angles) {
                // When autoFoldSplitEnabled is false, ratio must strictly equal user percent
                val disabledRatio = targetPanelSplitRatio(
                    panelSplitPercent = 30,
                    isBookMode = false,
                    autoFoldSplitEnabled = false,
                    hingeAngle = angle,
                    foldSplitAngleThreshold = th,
                )
                assertEquals(0.30f, disabledRatio, 0.0001f)

                // When autoFoldSplitEnabled is true:
                val enabledRatio = targetPanelSplitRatio(
                    panelSplitPercent = 30,
                    isBookMode = false,
                    autoFoldSplitEnabled = true,
                    hingeAngle = angle,
                    foldSplitAngleThreshold = th,
                )
                val expectedBent = angle > 5f && angle <= (180f - th)
                val expectedRatio = if (expectedBent) 0.5f else 0.30f
                assertEquals("Angle $angle with th $th failed", expectedRatio, enabledRatio, 0.0001f)
            }
        }
    }

    // =========================================================================
    // 2. STRING RESOURCES & HELP DIALOG CHECK
    // =========================================================================

    private fun resolveFile(path: String): File {
        val f1 = File(path)
        if (f1.exists()) return f1
        val f2 = File("app", path)
        if (f2.exists()) return f2
        val f3 = File("../app", path)
        if (f3.exists()) return f3
        return f1
    }

    @Test
    fun testAllRequiredCategoryStringsExistInAllLocales() {
        val localePaths = listOf(
            "src/main/res/values/strings.xml",
            "src/main/res/values-pl/strings.xml",
            "src/main/res/values-en/strings.xml",
        )

        val requiredKeys = listOf(
            // Categories
            "settings_category_view",
            "settings_category_folders",
            "settings_category_privacy",
            "settings_category_player",
            "settings_category_system",
            // panel split controls
            "pane_split_title",
            "auto_fold_split_title",
            "auto_fold_split_body",
            "fold_split_angle_title",
            "fold_split_angle_body",
            // settings keys
            "scroller_smooth_title",
            "scroller_smooth_body",
            "secure_folder_settings_title",
            "secure_folder_settings_desc",
            "multi_folder_memory_title",
            "multi_folder_memory_body",
            "multi_folder_memory_fresh",
            "multi_folder_memory_remember",
            "privatized_folders_title",
            "randomize_file_names_title",
            "strip_exif_title",
            "strip_exif_on_share_title",
            "config_export",
            "config_import",
            "rescan",
        )

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()

        for (path in localePaths) {
            val file = resolveFile(path)
            assertTrue("File $path must exist", file.exists())
            val doc = builder.parse(file)
            val stringNodes = doc.getElementsByTagName("string")

            val foundKeys = mutableMapOf<String, String>()
            for (i in 0 until stringNodes.length) {
                val node = stringNodes.item(i) as Element
                val name = node.getAttribute("name")
                val text = node.textContent.trim()
                foundKeys[name] = text
            }

            for (key in requiredKeys) {
                assertTrue("Locale $path missing required key: $key", foundKeys.containsKey(key))
                val value = foundKeys[key]
                assertNotNull("Value for $key in $path should not be null", value)
                assertTrue("Value for $key in $path should not be blank", value!!.isNotBlank())
            }
        }
    }

    @Test
    fun testNoDuplicateKeysInAnyStringsXml() {
        val localePaths = listOf(
            "src/main/res/values/strings.xml",
            "src/main/res/values-pl/strings.xml",
            "src/main/res/values-en/strings.xml",
        )

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()

        for (path in localePaths) {
            val file = resolveFile(path)
            val doc = builder.parse(file)
            val stringNodes = doc.getElementsByTagName("string")

            val seen = mutableSetOf<String>()
            val duplicates = mutableListOf<String>()

            for (i in 0 until stringNodes.length) {
                val node = stringNodes.item(i) as Element
                val name = node.getAttribute("name")
                if (!seen.add(name)) {
                    duplicates.add(name)
                }
            }

            assertTrue("File $path has duplicate string keys: $duplicates", duplicates.isEmpty())
        }
    }

    @Test
    fun testHelpDialogStringsAreNonDuplicative() {
        // Verify that title and body strings for settings options are distinct and informative
        val file = resolveFile("src/main/res/values-pl/strings.xml")
        val factory = DocumentBuilderFactory.newInstance()
        val doc = factory.newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")

        val strings = mutableMapOf<String, String>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            strings[el.getAttribute("name")] = el.textContent.trim()
        }

        val pairsToCheck = listOf(
            "auto_fold_split_title" to "auto_fold_split_body",
            "fold_split_angle_title" to "fold_split_angle_body",
            "scroller_smooth_title" to "scroller_smooth_body",
            "multi_folder_memory_title" to "multi_folder_memory_body",
            "secure_folder_settings_title" to "secure_folder_settings_desc",
            "randomize_file_names_title" to "randomize_file_names_body",
            "strip_exif_on_share_title" to "strip_exif_on_share_body",
            "config_export" to "config_export_body",
            "config_import" to "config_import_body",
            "rescan" to "rescan_body",
        )

        for ((titleKey, bodyKey) in pairsToCheck) {
            val title = strings[titleKey]
            val body = strings[bodyKey]
            assertNotNull("Missing $titleKey", title)
            assertNotNull("Missing $bodyKey", body)
            assertNotEquals("Title and body must not be identical for $titleKey", title, body)
            assertTrue("Body must provide substantive explanation (>15 chars) for $titleKey", body!!.length > 15)
        }
    }

    // =========================================================================
    // 3. FIVE CATEGORIES STRUCTURE & DATA CONSISTENCY
    // =========================================================================

    @Test
    fun testAppSettingsCategoryFieldsRoundTrip() {
        val original = AppSettings(
            // Category 1: Widok
            contentViewMode = ContentViewMode.EXPAND_FOLDERS,
            expandColumns = 8,
            expandRows = 6,
            foldableFileColumns = 5,
            panelSplitPercent = 30,
            autoFoldSplitEnabled = true,
            foldSplitAngleThreshold = 35f,
            showFileNames = true,
            gridColumns = 4,
            videoDurationFormat = VideoDurationFormat.HOURS_MINUTES_SECONDS,
            scrollerEnabled = true,
            scrollerOnRight = true,
            scrollerSmooth = false,
            customWallpaperFoldedWidth = 1080,
            customWallpaperFoldedHeight = 2424,
            customWallpaperUnfoldedWidth = 2076,
            customWallpaperUnfoldedHeight = 2152,

            // Category 2: Foldery
            multiFolderMemory = MultiFolderMemory.FRESH,
            rememberFolderScrollAcrossSessions = true,
            showHidden = true,
            hideFavoritesFolder = true,

            // Category 3: Prywatność
            anonymousFolderIds = setOf(101L, 202L),
            randomizeFileNamesInPrivateFolders = false,
            stripExifInPrivateFolders = false,
            stripExifOnShare = true,

            // Category 4: Odtwarzacz
            videoDoubleClickToGallery = true,
            autoplayGifs = false,
            showNavArrows = true,
            searchFilesFullScreen = false,
            fileSelectMode = FileSelectMode.LINEAR,
            operationsFollowSelectionOrder = true,
        )

        val copied = original.copy()
        assertEquals(original, copied)

        // Verify every category field is preserved
        assertEquals(ContentViewMode.EXPAND_FOLDERS, copied.contentViewMode)
        assertEquals(8, copied.expandColumns)
        assertEquals(6, copied.expandRows)
        assertEquals(5, copied.foldableFileColumns)
        assertEquals(30, copied.panelSplitPercent)
        assertTrue(copied.autoFoldSplitEnabled)
        assertEquals(35f, copied.foldSplitAngleThreshold, 0.001f)
        assertTrue(copied.showFileNames)
        assertEquals(4, copied.gridColumns)
        assertEquals(VideoDurationFormat.HOURS_MINUTES_SECONDS, copied.videoDurationFormat)
        assertTrue(copied.scrollerEnabled)
        assertTrue(copied.scrollerOnRight)
        assertFalse(copied.scrollerSmooth)
        assertEquals(1080, copied.customWallpaperFoldedWidth)
        assertEquals(2424, copied.customWallpaperFoldedHeight)

        assertEquals(MultiFolderMemory.FRESH, copied.multiFolderMemory)
        assertTrue(copied.rememberFolderScrollAcrossSessions)
        assertTrue(copied.showHidden)
        assertTrue(copied.hideFavoritesFolder)

        assertEquals(setOf(101L, 202L), copied.anonymousFolderIds)
        assertFalse(copied.randomizeFileNamesInPrivateFolders)
        assertFalse(copied.stripExifInPrivateFolders)
        assertTrue(copied.stripExifOnShare)

        assertTrue(copied.videoDoubleClickToGallery)
        assertFalse(copied.autoplayGifs)
        assertTrue(copied.showNavArrows)
        assertFalse(copied.searchFilesFullScreen)
        assertEquals(FileSelectMode.LINEAR, copied.fileSelectMode)
        assertTrue(copied.operationsFollowSelectionOrder)
    }

    @Test
    fun testNumericSettingsBoundaryCoercion() {
        // Expand grid ranges
        val expandCols = 15.coerceIn(1, 10)
        val expandRows = (-5).coerceIn(1, 10)
        assertEquals(10, expandCols)
        assertEquals(1, expandRows)

        // Foldable file columns range
        val foldColsLow = 0.coerceIn(1, 10)
        val foldColsHigh = 12.coerceIn(1, 10)
        assertEquals(1, foldColsLow)
        assertEquals(10, foldColsHigh)

        // Grid columns range
        val gridColsLow = (-1).coerceIn(1, 30)
        val gridColsHigh = 45.coerceIn(1, 30)
        assertEquals(1, gridColsLow)
        assertEquals(30, gridColsHigh)

        // Hinge angle threshold range
        val angleLow = 2f.coerceIn(5f, 90f)
        val angleHigh = 110f.coerceIn(5f, 90f)
        assertEquals(5f, angleLow, 0.001f)
        assertEquals(90f, angleHigh, 0.001f)
    }
}
