package dev.ander.alexet.data

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Some metadata scrubbers blank QuickTime `moov/meta` (mdta) entries by renaming boxes to `skip`
 * without updating the `keys` table. Android's MPEG4Extractor reads every `ilst` child type as a
 * key index; an unknown index (`skip` = 0x736b6970) makes it reject the whole file
 * (ERROR_MALFORMED): MediaStore gets no duration or size, and neither the system thumbnailer nor
 * MediaMetadataRetriever can read a frame. ExoPlayer tolerates it, so such files still play.
 *
 * The fix is read-only: the broken `meta` box is presented to the extractor as `free`
 * (4 bytes of its type), the file on disk is never changed.
 */
object Mp4MetaRepair {

    /** Positioned read into [buf]; returns bytes read (< 1 at end of data). */
    fun interface Reader {
        fun read(position: Long, buf: ByteArray, len: Int): Int
    }

    private val FREE = byteArrayOf('f'.code.toByte(), 'r'.code.toByte(), 'e'.code.toByte(), 'e'.code.toByte())
    private val NO_PATCHES = LongArray(0)
    private const val MOOV = 0x6d6f6f76 // 'moov'
    private const val META = 0x6d657461 // 'meta'
    private const val HDLR = 0x68646c72 // 'hdlr'
    private const val KEYS = 0x6b657973 // 'keys'
    private const val ILST = 0x696c7374 // 'ilst'
    private const val MAX_TOP_BOXES = 64
    private const val MAX_META_BYTES = 1 shl 20

    private val cache by lazy { LruCache<String, LongArray>(2048) }

    /**
     * File offsets of the box types to read as `free` (each one 4 bytes); empty when the file has
     * no broken `moov/meta`. Not an MP4/MOV, or unreadable: empty as well.
     */
    fun findBrokenMeta(size: Long, reader: Reader): LongArray {
        val header = ByteArray(16)
        var pos = 0L
        var count = 0
        while (pos + 8 <= size && count++ < MAX_TOP_BOXES) {
            val box = readHeader(reader, pos, size, header) ?: return NO_PATCHES
            if (box.type == MOOV) return moovPatches(reader, box, header)
            pos = box.end
        }
        return NO_PATCHES
    }

    private fun moovPatches(reader: Reader, moov: Box, header: ByteArray): LongArray {
        var pos = moov.contentStart
        var patches: MutableList<Long>? = null
        while (pos + 8 <= moov.end) {
            val box = readHeader(reader, pos, moov.end, header) ?: break
            if (box.type == META && isBrokenQtMeta(reader, box)) {
                (patches ?: ArrayList<Long>().also { patches = it }).add(box.start + 4)
            }
            pos = box.end
        }
        return patches?.toLongArray() ?: NO_PATCHES
    }

    /**
     * Same rules as MPEG4Extractor for `moov/meta`: `keys` defines indices 1..count, every `ilst`
     * child type must be one of them, and key entries must be well formed.
     */
    private fun isBrokenQtMeta(reader: Reader, meta: Box): Boolean {
        val length = meta.end - meta.contentStart
        if (length < 8 || length > MAX_META_BYTES) return false
        val content = ByteArray(length.toInt())
        if (!readFully(reader, meta.contentStart, content)) return false
        // QuickTime `meta` has no version/flags; the ISO one has 4 bytes of them first.
        val first = if (int(content, 4) == HDLR) 0 else 4
        var keyCount = 0L
        var keysBroken = false
        val ilstTypes = ArrayList<Int>()
        var i = first
        while (i + 8 <= content.size) {
            val boxSize = uint(content, i)
            val type = int(content, i + 4)
            if (boxSize < 8 || i + boxSize > content.size) return false
            val end = (i + boxSize).toInt()
            when (type) {
                KEYS -> if (i + 16 <= end) {
                    keyCount = uint(content, i + 12)
                    var k = i + 16
                    for (n in 0 until keyCount) {
                        if (k + 8 > end) { keysBroken = true; break }
                        val keySize = uint(content, k)
                        if (keySize < 8 || k + keySize > end) { keysBroken = true; break }
                        k += keySize.toInt()
                    }
                }
                ILST -> {
                    var c = i + 8
                    while (c + 8 <= end) {
                        val childSize = uint(content, c)
                        if (childSize < 8 || c + childSize > end) break
                        ilstTypes += int(content, c + 4)
                        c += childSize.toInt()
                    }
                }
            }
            i = end
        }
        if (keysBroken) return true
        return ilstTypes.any { it < 1 || it > keyCount }
    }

    /** Overwrites, in [buffer], the bytes of [patches] that fall inside the read range. */
    fun applyPatches(patches: LongArray, position: Long, buffer: ByteArray, offset: Int, length: Int) {
        for (patch in patches) {
            for (b in 0 until 4) {
                val at = patch + b - position
                if (at in 0 until length) buffer[offset + at.toInt()] = FREE[b]
            }
        }
    }

    /**
     * Drop-in for `retriever.setDataSource(path)` / `(context, uri)`: a file with a broken
     * `moov/meta` is read through the patching source, every other file keeps the native path.
     */
    fun setDataSource(retriever: MediaMetadataRetriever, context: Context, path: String?, uri: Uri?) {
        val file = path?.let(::File)?.takeIf { it.exists() }
        val source = runCatching { open(context, file, uri) }.getOrNull()
        if (source != null) {
            // The time of the last change is in the key too: a file overwritten in place with the
            // same size must not get the patches found for its old content.
            val key = file?.let { "${it.absolutePath}|${it.length()}|${it.lastModified()}" }
                ?: "$uri|${source.size}|${source.modified}"
            val patches = cache.get(key) ?: runCatching { findBrokenMeta(source.size, source) }
                .getOrDefault(NO_PATCHES)
                .also { cache.put(key, it) }
            if (patches.isNotEmpty()) {
                try {
                    retriever.setDataSource(PatchedSource(source, patches))
                } catch (e: Throwable) {
                    // Not taken by the retriever: the open file would stay open.
                    runCatching { source.close() }
                    throw e
                }
                return
            }
            source.close()
        }
        if (file != null) {
            retriever.setDataSource(file.absolutePath)
        } else {
            retriever.setDataSource(context, requireNotNull(uri))
        }
    }

    /** Patching source over decrypted bytes (private folders). */
    fun dataSourceFor(bytes: ByteArray): MediaDataSource {
        val patches = runCatching {
            findBrokenMeta(bytes.size.toLong()) { position, buf, len ->
                if (position >= bytes.size) {
                    -1
                } else {
                    val n = minOf(len.toLong(), bytes.size - position).toInt()
                    System.arraycopy(bytes, position.toInt(), buf, 0, n)
                    n
                }
            }
        }.getOrDefault(NO_PATCHES)
        return object : MediaDataSource() {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position >= bytes.size) return -1
                val n = minOf(size.toLong(), bytes.size - position).toInt()
                System.arraycopy(bytes, position.toInt(), buffer, offset, n)
                if (patches.isNotEmpty()) applyPatches(patches, position, buffer, offset, n)
                return n
            }

            override fun getSize(): Long = bytes.size.toLong()
            override fun close() {}
        }
    }

    private fun open(context: Context, file: File?, uri: Uri?): ChannelSource? {
        if (file != null) {
            val raf = RandomAccessFile(file, "r")
            return ChannelSource(raf.channel, raf, raf.length())
        }
        val pfd = uri?.let { context.contentResolver.openFileDescriptor(it, "r") } ?: return null
        val stream = FileInputStream(pfd.fileDescriptor)
        val channel = stream.channel
        val closer = Closeable {
            runCatching { stream.close() }
            runCatching { pfd.close() }
        }
        val size = runCatching { channel.size() }.getOrDefault(pfd.statSize)
        if (size <= 0L) {
            closer.close()
            return null
        }
        val modified = runCatching { android.system.Os.fstat(pfd.fileDescriptor).st_mtime }.getOrDefault(0L)
        return ChannelSource(channel, closer, size, modified)
    }

    private class ChannelSource(
        private val channel: FileChannel,
        private val owner: Closeable,
        val size: Long,
        /** Last change (seconds) when known, else 0. */
        val modified: Long = 0L,
    ) : Reader, Closeable {
        override fun read(position: Long, buf: ByteArray, len: Int): Int =
            read(position, buf, 0, len)

        fun read(position: Long, buf: ByteArray, offset: Int, len: Int): Int {
            if (position >= size) return -1
            return channel.read(ByteBuffer.wrap(buf, offset, len), position)
        }

        override fun close() = owner.close()
    }

    private class PatchedSource(
        private val source: ChannelSource,
        private val patches: LongArray,
    ) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            val n = source.read(position, buffer, offset, size)
            if (n > 0) applyPatches(patches, position, buffer, offset, n)
            return n
        }

        override fun getSize(): Long = source.size
        override fun close() = source.close()
    }

    private class Box(val type: Int, val start: Long, val contentStart: Long, val end: Long)

    private fun readHeader(reader: Reader, pos: Long, limit: Long, header: ByteArray): Box? {
        val want = if (limit - pos >= 16) 16 else 8
        if (!readFully(reader, pos, header, want)) return null
        val size32 = uint(header, 0)
        val type = int(header, 4)
        val (size, headerLength) = when (size32) {
            0L -> (limit - pos) to 8L
            1L -> {
                if (want < 16) return null
                val large = (uint(header, 8) shl 32) or uint(header, 12)
                large to 16L
            }
            else -> size32 to 8L
        }
        if (size < headerLength || pos + size > limit) return null
        return Box(type, pos, pos + headerLength, pos + size)
    }

    private fun readFully(reader: Reader, position: Long, buf: ByteArray, len: Int = buf.size): Boolean {
        val tmp = ByteArray(len)
        var done = 0
        while (done < len) {
            val n = reader.read(position + done, tmp, len - done)
            if (n <= 0) return false
            System.arraycopy(tmp, 0, buf, done, n)
            done += n
        }
        return true
    }

    private fun int(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xff) shl 24) or ((b[i + 1].toInt() and 0xff) shl 16) or
            ((b[i + 2].toInt() and 0xff) shl 8) or (b[i + 3].toInt() and 0xff)

    private fun uint(b: ByteArray, i: Int): Long = int(b, i).toLong() and 0xffffffffL
}
