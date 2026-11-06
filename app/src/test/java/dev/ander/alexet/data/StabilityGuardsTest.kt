package dev.ander.alexet.data

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/** Damaged files and interrupted work: they end in time and lose nothing. */
class StabilityGuardsTest {

    private fun vint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        do {
            var b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) b = b or 0x80
            out.write(b)
        } while (v != 0L)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.le(value: Long, bytes: Int) {
        for (i in 0 until bytes) write(((value ushr (8 * i)) and 0xFF).toInt())
    }

    private fun rarFile(bytes: ByteArray): File =
        File.createTempFile("damaged", ".rar").apply {
            deleteOnExit()
            writeBytes(bytes)
        }

    @Test(timeout = 5_000)
    fun rar5HeaderPointingBackEnds() {
        // A service header whose data size sends the next header back to itself.
        val data = vint(3) + vint(2) + vint(-17)
        assertEquals(12, data.size)
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00))
        out.write(ByteArray(4)) // header CRC
        out.write(vint(data.size.toLong()))
        out.write(data)
        out.write(ByteArray(16))
        val listing = ArchiveReader.list(rarFile(out.toByteArray()))
        assertTrue(listing is ArchiveReader.Listing.Ok)
        listing as ArchiveReader.Listing.Ok
        assertTrue(listing.truncated)
        assertTrue(listing.entries.isEmpty())
    }

    @Test(timeout = 5_000)
    fun rar4NegativeSizeEnds() {
        val name = "file.txt".toByteArray()
        val headSize = 7 + 25 + 8 + name.size
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00))
        out.le(0, 2) // header CRC
        out.write(0x74) // file header
        out.le(0x0100, 2) // large file: high size words follow
        out.le(headSize.toLong(), 2)
        // Packed size = -headSize: the next header would be this one again.
        val packed = -headSize.toLong()
        out.le(packed and 0xFFFFFFFFL, 4)
        out.le(10, 4) // unpacked
        out.write(0) // host OS
        out.le(0, 4) // CRC
        out.le(0, 4) // DOS time
        out.write(29) // version
        out.write(0x30) // method
        out.le(name.size.toLong(), 2)
        out.le(0, 4) // attributes
        out.le((packed ushr 32) and 0xFFFFFFFFL, 4)
        out.le(0, 4) // unpacked high
        out.write(name)
        out.write(ByteArray(16))
        val listing = ArchiveReader.list(rarFile(out.toByteArray()))
        assertTrue(listing is ArchiveReader.Listing.Ok)
        listing as ArchiveReader.Listing.Ok
        assertTrue(listing.truncated)
        assertEquals(listOf("file.txt"), listing.entries.map { it.path })
    }

    @Test(timeout = 5_000)
    fun gifFrameFarBiggerThanAnyPictureIsSkipped() {
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray(Charsets.ISO_8859_1))
        out.le(1, 2)
        out.le(1, 2)
        out.write(0x80) // global color table of 2
        out.write(0)
        out.write(0)
        out.write(ByteArray(6))
        out.write(0x2C)
        out.le(0, 2)
        out.le(0, 2)
        out.le(65_535, 2) // 65535 x 65535: gigabytes, and more than an Int counts
        out.le(65_535, 2)
        out.write(0)
        out.write(2) // LZW minimum code size
        out.write(0) // no data
        out.write(0x3B)
        var frames = 0
        val given = GifFrameReader.forEachFrame(out.toByteArray()) { _, _, _, _ ->
            frames++
            true
        }
        assertEquals(0, given)
        assertEquals(0, frames)
    }

    private class FilesContext(private val dir: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = dir
    }

    @Test
    fun workingCopyLeftByAKillIsRemovedAndTheOriginalKept() {
        val root = Files.createTempDirectory("journal").toFile().apply { deleteOnExit() }
        val context = FilesContext(File(root, "files").apply { mkdirs() })
        RenameJournal.init(context)
        val photo = File(root, "photo.jpg").apply { writeText("original") }
        val copy = File(root, ".photo.alexet-exif.jpg").apply { writeText("half-written") }
        RenameJournal.addDiscard(copy)
        // The process dies here; the next start:
        RenameJournal.recover(context)
        assertFalse(copy.exists())
        assertEquals("original", photo.readText())
    }
}
