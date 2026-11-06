package dev.ander.alexet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FileNamesTest {

    @Test
    fun resolveUniqueTargetFileAppendsIndexWhenOccupied() {
        val dir = File.createTempFile("alexet-unique", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        try {
            val original = File(dir, "IMG_001.jpg")
            original.writeText("a")
            val first = resolveUniqueTargetFile(dir, "IMG_001.jpg")
            assertEquals("IMG_001 (1).jpg", first.name)
            first.writeText("b")
            val second = resolveUniqueTargetFile(dir, "IMG_001.jpg")
            assertEquals("IMG_001 (2).jpg", second.name)
            val free = resolveUniqueTargetFile(dir, "other.png")
            assertEquals("other.png", free.name)
        } finally {
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
    }

    @Test
    fun shouldRestoreTrashOnDenyWhenSourceRemovedOrOriginalGone() {
        assertTrue(shouldRestoreTrashOnDeny(sourceRemoved = true, originalExists = false))
        assertTrue(shouldRestoreTrashOnDeny(sourceRemoved = true, originalExists = true))
        assertTrue(shouldRestoreTrashOnDeny(sourceRemoved = false, originalExists = false))
        assertFalse(shouldRestoreTrashOnDeny(sourceRemoved = false, originalExists = true))
    }

    @Test
    fun sanitizeFileNameReplacesIllegalCharactersAndPathTraversal() {
        assertEquals("file", sanitizeFileName(""))
        assertEquals("file", sanitizeFileName("   "))
        assertEquals("file", sanitizeFileName("..."))
        assertEquals("file", sanitizeFileName(".."))
        assertEquals("_test.jpg", sanitizeFileName("../test.jpg"))
        assertEquals("a_.._.._b.png", sanitizeFileName("a/../../b.png"))
        assertEquals("C__secret_doc.txt", sanitizeFileName("C:\\secret\\doc.txt"))
        assertEquals("file_with_bad_chars_.jpg", sanitizeFileName("file:with*bad?chars|.jpg"))
        assertEquals("safe_name.png", sanitizeFileName("  safe_name.png  "))
        assertEquals("___", sanitizeFileName("???"))
        assertEquals("custom", sanitizeFileName("   ", fallback = "custom"))
    }

    @Test
    fun isPathUnderDirectoryAcceptsDescendantsAndRejectsEscapes() {
        val baseDir = File.createTempFile("alexet-guard-base", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        val outsideDir = File.createTempFile("alexet-guard-outside", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }

        try {
            val insideFile = File(baseDir, "nested.jpg")
            val subDir = File(baseDir, "subfolder").apply { mkdirs() }
            val deeplyNestedFile = File(subDir, "deep.png")
            val escapingFile = File(baseDir, "../outside.txt")
            val directOutsideFile = File(outsideDir, "stolen.jpg")

            assertTrue(isPathUnderDirectory(baseDir, baseDir))
            assertTrue(isPathUnderDirectory(baseDir, insideFile))
            assertTrue(isPathUnderDirectory(baseDir, subDir))
            assertTrue(isPathUnderDirectory(baseDir, deeplyNestedFile))

            assertFalse(isPathUnderDirectory(baseDir, escapingFile))
            assertFalse(isPathUnderDirectory(baseDir, directOutsideFile))
            assertFalse(isPathUnderDirectory(baseDir, outsideDir))
        } finally {
            baseDir.deleteRecursively()
            outsideDir.deleteRecursively()
        }
    }

    @Test
    fun stripTildePriorityMatchesOnlyPurelyNumericNames() {
        val priorityRegex = Regex("""^(\d+)~(\d+)$""")

        fun stripPriority(displayName: String): String? {
            val base = displayName.substringBeforeLast('.', missingDelimiterValue = displayName)
            val ext = if (displayName.contains('.')) "." + displayName.substringAfterLast('.') else ""
            val match = priorityRegex.matchEntire(base) ?: return null
            return "${match.groupValues[1]}$ext"
        }

        assertEquals("001.mp4", stripPriority("001~1.mp4"))
        assertEquals("934.mp4", stripPriority("934~4823.mp4"))
        assertEquals("004.mp4", stripPriority("004~1.mp4"))
        assertEquals("005", stripPriority("005~2"))

        // Must not match if there are letters or other non-digits
        assertEquals(null, stripPriority("123~abc.mp4"))
        assertEquals(null, stripPriority("abc~123.mp4"))
        assertEquals(null, stripPriority("001.mp4"))
    }

    @Test
    fun testPriorityRolloffHelperUserExample() {
        val files = listOf("001.mp4", "003~2.mp4", "001~1.mp4", "003~1.mp4")
        val plan = PriorityRolloffHelper.computeRolloffPlan(files)

        val expected = listOf(
            "001~1.mp4" to "001.mp4",
            "001.mp4" to "002.mp4",
            "003~2.mp4" to "003.mp4",
            "003~1.mp4" to "004.mp4",
        )
        assertEquals(expected, plan)
    }

    @Test
    fun testPriorityRolloffHelperCascadingDisplacement() {
        val files = listOf(
            "001.mp4",
            "001~1.mp4",
            "003~2.mp4",
            "003~1.mp4",
            "004.mp4",
            "005.mp4",
            "010.mp4",
        )
        val plan = PriorityRolloffHelper.computeRolloffPlan(files)

        val expected = listOf(
            "001~1.mp4" to "001.mp4",
            "001.mp4" to "002.mp4",
            "003~2.mp4" to "003.mp4",
            "003~1.mp4" to "004.mp4",
            "004.mp4" to "005.mp4",
            "005.mp4" to "006.mp4",
        )
        assertEquals(expected, plan)
    }

    @Test
    fun testPriorityRolloffHelperSeparateExtensions() {
        val files = listOf("001.jpg", "001~1.mp4", "001.mp4")
        val plan = PriorityRolloffHelper.computeRolloffPlan(files)

        val expected = listOf(
            "001~1.mp4" to "001.mp4",
            "001.mp4" to "002.mp4",
        )
        assertEquals(expected, plan)
    }

    @Test
    fun testPriorityRolloffHelperPaddingConsistency() {
        val files = listOf("009~1.mp4", "009.mp4")
        val plan = PriorityRolloffHelper.computeRolloffPlan(files)

        val expected = listOf(
            "009~1.mp4" to "009.mp4",
            "009.mp4" to "010.mp4",
        )
        assertEquals(expected, plan)
    }

    @Test
    fun testPriorityRolloffHelperIgnoresNonNumeric() {
        val files = listOf("0f4~3j.mp4", "photo~1.jpg", "001~1.mp4", "001.mp4")
        val plan = PriorityRolloffHelper.computeRolloffPlan(files)

        val expected = listOf(
            "001~1.mp4" to "001.mp4",
            "001.mp4" to "002.mp4",
        )
        assertEquals(expected, plan)
    }
}


