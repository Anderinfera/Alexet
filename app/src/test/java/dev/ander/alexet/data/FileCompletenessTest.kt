package dev.ander.alexet.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class FileCompletenessTest {

    private fun check(bytes: ByteArray): Boolean? =
        FileCompleteness.check(bytes.size.toLong()) { position, buf, len ->
            if (position >= bytes.size) return@check -1
            val n = minOf(len.toLong(), bytes.size - position).toInt()
            System.arraycopy(bytes, position.toInt(), buf, 0, n)
            n
        }

    private fun box(type: String, payload: Int, declared: Int = payload + 8): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf((declared ushr 24).toByte(), (declared ushr 16).toByte(), (declared ushr 8).toByte(), declared.toByte()))
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(ByteArray(payload))
        return out.toByteArray()
    }

    @Test
    fun mp4WithMoovAtTheEndIsComplete() {
        assertEquals(true, check(box("ftyp", 16) + box("mdat", 100) + box("moov", 40)))
    }

    @Test
    fun mp4StillReceivingMdatIsIncomplete() {
        val full = box("ftyp", 16) + box("moov", 40) + box("mdat", 100)
        assertEquals(false, check(full.copyOf(full.size - 30)))
    }

    @Test
    fun mp4WithoutMoovYetIsIncomplete() {
        // mdat written, the index not yet: the walk ends exactly but there's nothing to play.
        assertEquals(null, check(box("ftyp", 16) + box("mdat", 100)))
        assertEquals(false, check(box("ftyp", 16) + box("mdat", 100, declared = 0)))
    }

    @Test
    fun jpegNeedsItsEndMarker() {
        val body = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(40)
        assertEquals(true, check(body + byteArrayOf(0xFF.toByte(), 0xD9.toByte())))
        assertEquals(null, check(body))
    }

    @Test
    fun pngNeedsIend() {
        val head = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(40)
        val iend = byteArrayOf(0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte())
        assertEquals(true, check(head + iend))
        assertEquals(null, check(head))
    }

    @Test
    fun webpUsesTheRiffSize() {
        fun riff(declaredPayload: Int, actualPayload: Int): ByteArray {
            val size = declaredPayload
            return "RIFF".toByteArray() +
                byteArrayOf(size.toByte(), (size ushr 8).toByte(), (size ushr 16).toByte(), (size ushr 24).toByte()) +
                "WEBP".toByteArray() + ByteArray(actualPayload - 4)
        }
        assertEquals(true, check(riff(100, 100)))
        assertEquals(false, check(riff(100, 60)))
    }

    @Test
    fun matroskaUsesTheSegmentSize() {
        val ebml = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0x84.toByte(), 1, 2, 3, 4)
        fun segment(declared: Int, actual: Int) = ebml +
            byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67, 0x01, 0, 0, 0, 0, 0, (declared ushr 8).toByte(), declared.toByte()) +
            ByteArray(actual)
        assertEquals(true, check(segment(300, 300)))
        assertEquals(false, check(segment(300, 120)))
        val unknown = ebml + byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67, 0x01, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) + ByteArray(50)
        assertEquals(null, check(unknown))
    }

    @Test
    fun unknownFormatCannotTell() {
        assertEquals(null, check(ByteArray(64) { 7 }))
    }

    @Test
    fun mp4WithPaddingAfterTheIndexIsNotCalledIncomplete() {
        val file = box("ftyp", 16) + box("mdat", 100) + box("moov", 40)
        assertEquals(null, check(file + ByteArray(4)))
        assertEquals(null, check(file + ByteArray(16)))
    }

    @Test
    fun fragmentedMp4CannotTell() {
        assertEquals(null, check(box("ftyp", 16) + box("moov", 40) + box("moof", 20) + box("mdat", 100)))
    }

    /** Header, screen descriptor (no colour table), one image with [data] as its sub-blocks. */
    private fun gif(data: ByteArray): ByteArray =
        "GIF89a".toByteArray() +
            byteArrayOf(4, 0, 4, 0, 0, 0, 0) +
            byteArrayOf(0x2C, 0, 0, 0, 0, 4, 0, 4, 0, 0) +
            byteArrayOf(0x02) + data

    @Test
    fun gifNeedsTerminatorAndTrailer() {
        // Image data (one 2-byte sub-block), block terminator, trailer.
        assertEquals(true, check(gif(byteArrayOf(0x02, 0x11, 0x22, 0x00, 0x3B))))
        assertEquals(null, check(gif(byteArrayOf(0x02, 0x11, 0x22, 0x17, 0x3B))))
    }

    @Test
    fun gifCutOffInsideImageDataIsNotComplete() {
        // A 16-byte sub-block of which only "00 3B" was written: the last bytes look like the end.
        assertEquals(false, check(gif(byteArrayOf(0x10, 0x00, 0x3B))))
    }
}
