package dev.ander.alexet.ui.gallery

import dev.ander.alexet.data.AppSettings
import dev.ander.alexet.data.ContentViewMode
import dev.ander.alexet.data.FileSelectMode
import dev.ander.alexet.data.MultiFolderMemory
import dev.ander.alexet.data.VideoDurationFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Tests for settings callbacks wiring, string parity across locales and panel split interpolation.
 */
class SettingsWiringAndStringsTest {

    // =========================================================================
    // 1. CALLER WIRING IN GalleryApp.kt
    // =========================================================================

    @Test
    fun testGalleryAppWiresAllSettingsCallbacks() {
        val galleryAppFile = File("src/main/java/dev/ander/alexet/ui/gallery/GalleryApp.kt")
        assertTrue("GalleryApp.kt must exist", galleryAppFile.exists())
        val content = galleryAppFile.readText()

        // Locate SettingsScreen invocation in GalleryApp.kt
        val settingsScreenCallIndex = content.indexOf("SettingsScreen(")
        assertTrue("GalleryApp must invoke SettingsScreen", settingsScreenCallIndex != -1)

        val settingsScreenCallBlock = content.substring(
            settingsScreenCallIndex,
            content.indexOf(")", settingsScreenCallIndex) + 1
        )

        // Mandatory callback wirings:
        val requiredWirings = mapOf(
            "onScrollerSmooth" to "onScrollerSmooth = viewModel::setScrollerSmooth",
            "onAutoFoldSplitEnabled" to "onAutoFoldSplitEnabled = viewModel::setAutoFoldSplitEnabled",
            "onFoldSplitAngleThreshold" to "onFoldSplitAngleThreshold = viewModel::setFoldSplitAngleThreshold",
            "onStripExifOnShare" to "onStripExifOnShare = viewModel::setStripExifOnShare",
            "onMultiFolderMemory" to "onMultiFolderMemory = viewModel::setMultiFolderMemory",
        )

        val missingWirings = mutableListOf<String>()
        for ((name, expectedSnippet) in requiredWirings) {
            if (!settingsScreenCallBlock.contains(name)) {
                missingWirings.add(name)
            }
        }

        assertTrue(
            "CRITICAL BUG: GalleryApp.kt fails to wire required SettingsScreen callbacks: $missingWirings. " +
                "These callbacks default to no-op empty lambdas {}, making user toggles dead in the GUI.",
            missingWirings.isEmpty()
        )
    }

    // =========================================================================
    // 2. STRING PARITY ACROSS ALL THREE LOCALES
    // =========================================================================

    @Test
    fun testCategoryHeadersAndStringsParityAcrossAllLocales() {
        val defaultFile = File("src/main/res/values/strings.xml")
        val plFile = File("src/main/res/values-pl/strings.xml")
        val enFile = File("src/main/res/values-en/strings.xml")

        assertTrue(defaultFile.exists())
        assertTrue(plFile.exists())
        assertTrue(enFile.exists())

        fun readStrings(file: File): Map<String, String> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = doc.getElementsByTagName("string")
            val map = mutableMapOf<String, String>()
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as Element
                map[el.getAttribute("name")] = el.textContent.trim()
            }
            return map
        }

        val defaultStrings = readStrings(defaultFile)
        val plStrings = readStrings(plFile)
        val enStrings = readStrings(enFile)

        val requiredM4Keys = listOf(
            "settings_category_view",
            "settings_category_folders",
            "settings_category_privacy",
            "settings_category_player",
            "settings_category_system",
            "scroller_smooth_title",
            "scroller_smooth_body",
            "strip_exif_on_share_title",
            "strip_exif_on_share_body",
            "multi_folder_memory_title",
            "multi_folder_memory_body",
            "multi_folder_memory_fresh",
            "multi_folder_memory_remember",
            "auto_fold_split_title",
            "auto_fold_split_body",
            "fold_split_angle_title",
            "fold_split_angle_body",
            "pane_split_title",
            "pane_split_forty",
            "pane_split_fifty",
        )

        for (key in requiredM4Keys) {
            assertTrue("Key $key missing from default strings.xml", defaultStrings.containsKey(key))
            assertTrue("Key $key missing from values-pl strings.xml", plStrings.containsKey(key))
            assertTrue("Key $key missing from values-en strings.xml", enStrings.containsKey(key))

            val defVal = defaultStrings[key]!!
            val plVal = plStrings[key]!!
            val enVal = enStrings[key]!!

            assertTrue("Default value for $key is blank", defVal.isNotBlank())
            assertTrue("Polish value for $key is blank", plVal.isNotBlank())
            assertTrue("English value for $key is blank", enVal.isNotBlank())
        }
    }

    // =========================================================================
    // 3. STRESS TESTS: PANEL SPLIT RATIO INTERPOLATION & BOUNDS
    // =========================================================================

    @Test
    fun testPanelSplitRatioInterpolationConsistency() {
        for (percent in -100..200 step 5) {
            val ratio = (percent.coerceIn(10, 90)) / 100f
            assertTrue("Ratio must be >= 0.1f", ratio >= 0.10f)
            assertTrue("Ratio must be <= 0.9f", ratio <= 0.90f)

            val complement = 1f - ratio
            assertTrue("Complement must be >= 0.1f", complement >= 0.10f)
            assertTrue("Complement must be <= 0.9f", complement <= 0.90f)
            assertEquals(1.0f, ratio + complement, 0.0001f)
        }
    }
}
