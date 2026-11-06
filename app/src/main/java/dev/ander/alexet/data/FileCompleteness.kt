package dev.ander.alexet.data

import java.io.File
import java.io.RandomAccessFile

/**
 * Tells from a file's own structure whether it was written to the end, so a finished download can
 * be opened without waiting for filesystem events (often missing for other apps' writes).
 *
 * Returns true only on positive evidence (the container's declared sizes cover the file exactly,
 * or the format's end marker is the last thing in it), false when the structure says data is
 * still missing, and null when it can't tell (unknown format, trailers after the end marker).
 */
object FileCompleteness {

    fun interface Reader {
        /** Positioned read into [buf]; returns bytes read (< 1 at end of data). */
        fun read(position: Long, buf: ByteArray, len: Int): Int
    }

    fun check(path: String): Boolean? = runCatching {
        val file = File(path)
        if (!file.isFile) return null
        RandomAccessFile(file, "r").use { raf ->
            check(raf.length()) { position, buf, len ->
                raf.seek(position)
                raf.read(buf, 0, len)
            }
        }
    }.getOrNull()

    fun check(size: Long, reader: Reader): Boolean? {
        if (size < 16) return null
        val head = ByteArray(16)
        if (!readFully(reader, 0, head, 16)) return null
        return when {
            isIsoBmff(head) -> isoBmff(size, reader)
            head.startsWith(0xFF, 0xD8, 0xFF) -> endsWith(size, reader, JPEG_END)
            head.startsWith(0x89, 'P'.code, 'N'.code, 'G'.code) -> endsWith(size, reader, PNG_END)
            head.startsWith('G'.code, 'I'.code, 'F'.code, '8'.code) -> gif(size, reader)
            head.startsWith('R'.code, 'I'.code, 'F'.code, 'F'.code) -> riff(size, head)
            head.startsWith(0x1A, 0x45, 0xDF, 0xA3) -> matroska(size, reader)
            else -> null
        }
    }

    // ---------- MP4 / MOV / 3GP / HEIF ----------

    private val ISO_FIRST_BOXES = setOf("ftyp", "moov", "mdat", "free", "skip", "wide", "pnot")

    private fun isIsoBmff(head: ByteArray): Boolean =
        String(head, 4, 4, Charsets.ISO_8859_1) in ISO_FIRST_BOXES

    /** Top-level boxes must tile the file exactly and include the index (`moov`, or `meta` for HEIF). */
    private fun isoBmff(size: Long, reader: Reader): Boolean? {
        val header = ByteArray(16)
        var pos = 0L
        var hasIndex = false
        var fragmented = false
        var boxes = 0
        while (pos < size) {
            if (++boxes > MAX_TOP_BOXES) return null
            // A few bytes after a complete index: padding / trailer, can't tell.
            if (pos + 8 > size) return if (hasIndex) null else false
            if (!readFully(reader, pos, header, 8)) return null
            val small = readUInt32(header, 0)
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            val boxSize = when (small) {
                // Runs to the end of the file: legal only for the last box, and writers use it as a
                // placeholder while the length is still unknown.
                0L -> return if (hasIndex) null else false
                1L -> {
                    if (pos + 16 > size) return false
                    if (!readFully(reader, pos + 8, header, 8)) return null
                    readInt64(header, 0)
                }
                else -> small
            }
            if (boxSize < 8) return null
            if (type == "moov" || type == "meta") hasIndex = true
            if (type == "moof") fragmented = true
            pos += boxSize
        }
        // Fragmented files tile exactly at every fragment boundary while still being written.
        if (fragmented) return if (pos > size) false else null
        return if (pos == size && hasIndex) true else if (pos > size) false else null
    }

    // ---------- end-marker formats ----------

    private val JPEG_END = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    private val PNG_END = byteArrayOf(
        0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
    )
    /** Block terminator + trailer: a lone 0x3B shows up in LZW data all the time. */
    private val GIF_END = byteArrayOf(0x00, 0x3B)

    /**
     * Walks the GIF's blocks to its trailer: "00 3B" as the last two bytes also occurs in the
     * middle of image data, so a GIF cut off right there looked finished. True only when the
     * trailer the blocks lead to is the last byte; false when the blocks run past the end.
     */
    private fun gif(size: Long, reader: Reader): Boolean? {
        if (endsWith(size, reader, GIF_END) != true) return null
        val buf = BufferedReader(reader, size)
        val lsd = 13L // header (6) + logical screen descriptor (7)
        val flags = buf.byte(10) ?: return null
        var pos = lsd + (if (flags and 0x80 != 0) 3L * (1 shl ((flags and 0x07) + 1)) else 0L)
        var blocks = 0
        while (true) {
            if (pos >= size) return false
            when (buf.byte(pos) ?: return null) {
                0x3B -> return if (pos == size - 1) true else null
                0x21 -> pos = buf.skipSubBlocks(pos + 2) ?: return false
                0x2C -> {
                    val imageFlags = buf.byte(pos + 9) ?: return false
                    pos += 10
                    if (imageFlags and 0x80 != 0) pos += 3L * (1 shl ((imageFlags and 0x07) + 1))
                    pos = buf.skipSubBlocks(pos + 1) ?: return false // + LZW minimum code size
                }
                else -> return null
            }
            if (++blocks > 1_000_000) return null
        }
    }

    /** Sequential reads of single bytes through a 64 KB window (a GIF's block lengths). */
    private class BufferedReader(private val reader: Reader, private val size: Long) {
        private val window = ByteArray(64 * 1024)
        private var start = -1L
        private var length = 0

        fun byte(position: Long): Int? {
            if (position < 0 || position >= size) return null
            if (start < 0 || position < start || position >= start + length) {
                start = position
                length = reader.read(position, window, minOf(window.size.toLong(), size - position).toInt())
                if (length < 1) {
                    start = -1
                    return null
                }
            }
            return window[(position - start).toInt()].toInt() and 0xFF
        }

        /** Position after the sub-blocks starting at [position]; null when they run past the end. */
        fun skipSubBlocks(position: Long): Long? {
            var pos = position
            while (true) {
                val len = byte(pos) ?: return null
                pos += 1 + len
                if (len == 0) return pos
                if (pos > size) return null
            }
        }
    }

    /** The end marker as the last bytes proves it; anything else may be an appended trailer. */
    private fun endsWith(size: Long, reader: Reader, marker: ByteArray): Boolean? {
        val tail = ByteArray(marker.size)
        if (!readFully(reader, size - marker.size, tail, marker.size)) return null
        return if (tail.contentEquals(marker)) true else null
    }

    // ---------- WebP / AVI / WAV ----------

    private fun riff(size: Long, head: ByteArray): Boolean? {
        val declared = readUInt32LE(head, 4) + 8
        return when {
            declared == size || declared + 1 == size -> true // a pad byte may follow an odd size
            declared > size -> false
            else -> null
        }
    }

    // ---------- Matroska / WebM ----------

    private const val SEGMENT_ID = 0x18538067L

    private fun matroska(size: Long, reader: Reader): Boolean? {
        val buf = ByteArray(12)
        var pos = 0L
        // EBML header element, then the Segment.
        for (element in 0 until 2) {
            if (!readFully(reader, pos, buf, minOf(12L, size - pos).toInt().coerceAtLeast(0))) return null
            val idLen = vintLength(buf[0]) ?: return null
            val id = readRaw(buf, 0, idLen)
            val sizeLen = vintLength(buf[idLen]) ?: return null
            val dataSize = readVintValue(buf, idLen, sizeLen) ?: return null // null: unknown size
            val dataStart = pos + idLen + sizeLen
            if (element == 1) {
                if (id != SEGMENT_ID) return null
                val end = dataStart + dataSize
                return when {
                    end == size -> true
                    end > size -> false
                    else -> null
                }
            }
            pos = dataStart + dataSize
            if (pos >= size) return false
        }
        return null
    }

    private fun vintLength(first: Byte): Int? {
        val b = first.toInt() and 0xFF
        if (b == 0) return null
        return Integer.numberOfLeadingZeros(b) - 23
    }

    private fun readRaw(buf: ByteArray, offset: Int, len: Int): Long {
        var value = 0L
        for (i in 0 until len) value = (value shl 8) or (buf[offset + i].toLong() and 0xFF)
        return value
    }

    /** Value of a size vint; null when it is the reserved "unknown size" (all value bits set). */
    private fun readVintValue(buf: ByteArray, offset: Int, len: Int): Long? {
        if (offset + len > buf.size) return null
        val mask = (1L shl (7 * len)) - 1
        val value = readRaw(buf, offset, len) and mask
        return if (value == mask) null else value
    }

    // ---------- helpers ----------

    private const val MAX_TOP_BOXES = 4096

    private fun readFully(reader: Reader, position: Long, buf: ByteArray, len: Int): Boolean {
        if (position < 0 || len <= 0) return false
        var done = 0
        val tmp = if (len == buf.size) buf else ByteArray(len)
        while (done < len) {
            val chunk = ByteArray(len - done)
            val n = reader.read(position + done, chunk, len - done)
            if (n < 1) return false
            System.arraycopy(chunk, 0, tmp, done, n)
            done += n
        }
        if (tmp !== buf) System.arraycopy(tmp, 0, buf, 0, len)
        return true
    }

    private fun ByteArray.startsWith(vararg bytes: Int): Boolean =
        size >= bytes.size && bytes.indices.all { (this[it].toInt() and 0xFF) == bytes[it] }

    private fun readUInt32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun readUInt32LE(b: ByteArray, o: Int): Long =
        ((b[o + 3].toLong() and 0xFF) shl 24) or ((b[o + 2].toLong() and 0xFF) shl 16) or
            ((b[o + 1].toLong() and 0xFF) shl 8) or (b[o].toLong() and 0xFF)

    private fun readInt64(b: ByteArray, o: Int): Long = (readUInt32(b, o) shl 32) or readUInt32(b, o + 4)
}
