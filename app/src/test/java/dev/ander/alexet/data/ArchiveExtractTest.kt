package dev.ander.alexet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveExtractTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun zip(file: File, vararg entries: Pair<String, String?>) {
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, text) ->
                out.putNextEntry(ZipEntry(name))
                if (text != null) out.write(text.toByteArray())
                out.closeEntry()
            }
        }
    }

    @Test
    fun unpacksFilesAndFolders() {
        val archive = tmp.newFile("photos.zip")
        zip(archive, "a.txt" to "A", "sub/" to null, "sub/b.txt" to "BB")
        val target = File(tmp.root, "photos")
        val result = ArchiveReader.extractAll(archive, target, totalBytes = 3)
        assertFalse(result.stopped)
        assertEquals(2, result.files.size)
        assertEquals("A", File(target, "a.txt").readText())
        assertEquals("BB", File(target, "sub/b.txt").readText())
    }

    @Test
    fun nothingLeavesTheTargetFolder() {
        val archive = tmp.newFile("evil.zip")
        zip(archive, "../../escape.txt" to "x", "/abs.txt" to "y")
        val target = File(tmp.root, "out")
        val result = ArchiveReader.extractAll(archive, target, totalBytes = -1)
        assertEquals(2, result.files.size)
        assertTrue(File(target, "escape.txt").exists())
        assertTrue(File(target, "abs.txt").exists())
        assertFalse(File(tmp.root, "escape.txt").exists())
    }

    @Test
    fun existingFilesAreKept() {
        val archive = tmp.newFile("a.zip")
        zip(archive, "a.txt" to "new")
        val target = tmp.newFolder("t")
        File(target, "a.txt").writeText("old")
        ArchiveReader.extractAll(archive, target, totalBytes = 3)
        assertEquals("old", File(target, "a.txt").readText())
        assertEquals("new", File(target, "a (1).txt").readText())
    }

    @Test
    fun safePathsAndFolderNames() {
        assertEquals("a/b.txt", ArchiveReader.safeRelativePath("./a/../b.txt".replace("../", "")))
        assertEquals("x.txt", ArchiveReader.safeRelativePath("../../x.txt"))
        assertNull(ArchiveReader.safeRelativePath("../.."))
        assertEquals("photos", ArchiveReader.folderNameFor("photos.zip"))
        assertEquals("backup", ArchiveReader.folderNameFor("backup.tar.gz"))
        assertEquals("noext", ArchiveReader.folderNameFor("noext"))
    }
}
