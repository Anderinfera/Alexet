package dev.ander.alexet.data

import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Contents of archives (ZIP, 7Z, TAR, TAR.GZ, RAR) without unpacking them: the list of entries,
 * and one entry copied out on request. Only real archives: files that are ZIPs inside (APK, JAR,
 * DOCX, EPUB…) are not handled here. RAR is listed by reading its headers; its files cannot be
 * unpacked (RAR5 has no decoder for Android without a large native library).
 */
object ArchiveReader {

    enum class Format { ZIP, SEVEN_Z, TAR, TAR_GZ, RAR }

    class Entry(
        /** Path inside the archive, with '/' separators and no leading or trailing '/'. */
        val path: String,
        val isDirectory: Boolean,
        /** Unpacked size; -1 when the archive does not say. */
        val size: Long,
        /** Epoch ms; 0 when unknown. */
        val modifiedMs: Long,
        val encrypted: Boolean,
    ) {
        val name: String get() = path.substringAfterLast('/')
    }

    sealed class Listing {
        /** [canExtract]: files can be copied out (not for RAR). */
        class Ok(val format: Format, val entries: List<Entry>, val canExtract: Boolean, val truncated: Boolean) : Listing()
        /** The names themselves are encrypted (7Z / RAR with hidden names). */
        object Encrypted : Listing()
        class Failed(val reason: String) : Listing()
    }

    /** At most this many entries are listed (a huge archive would fill the memory). */
    private const val MAX_ENTRIES = 50_000
    /** Free space left on the phone's storage while unpacking. */
    private const val MIN_FREE_BYTES = 64L * 1024 * 1024
    private const val SPACE_CHECK_BYTES = 8L * 1024 * 1024

    fun formatOf(name: String): Format? {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> Format.TAR_GZ
            lower.endsWith(".zip") -> Format.ZIP
            lower.endsWith(".7z") -> Format.SEVEN_Z
            lower.endsWith(".tar") -> Format.TAR
            lower.endsWith(".rar") -> Format.RAR
            else -> null
        }
    }

    fun list(file: File): Listing {
        val format = formatOf(file.name) ?: return Listing.Failed("not an archive")
        return try {
            when (format) {
                Format.ZIP -> listZip(file)
                Format.SEVEN_Z -> listSevenZ(file)
                Format.TAR -> listTar(file, gzip = false)
                Format.TAR_GZ -> listTar(file, gzip = true)
                Format.RAR -> RarHeaders.list(file)
            }
        } catch (e: PasswordRequiredException) {
            Listing.Encrypted
        } catch (e: OutOfMemoryError) {
            Listing.Failed("out of memory")
        } catch (e: Exception) {
            AppLog.w("ArchiveReader", "Listing ${file.name} failed", e)
            Listing.Failed(e.javaClass.simpleName)
        }
    }

    /**
     * Copies [entryPath] of [archive] to [target]. [onProgress] gets 0..100 when the size is
     * known; stops (false) when [isCancelled] says so. False also when it cannot be read.
     */
    fun extract(
        archive: File,
        entryPath: String,
        target: File,
        onProgress: (Int) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Boolean {
        val format = formatOf(archive.name) ?: return false
        return try {
            when (format) {
                Format.ZIP -> ZipFile.builder().setFile(archive).get().use { zip ->
                    val entry = zip.getEntry(entryPath) ?: zip.entries.toList().firstOrNull { normalize(it.name) == entryPath }
                        ?: return false
                    if (!zip.canReadEntryData(entry)) return false
                    zip.getInputStream(entry).use { copy(it, target, entry.size, onProgress, isCancelled) }
                }
                Format.SEVEN_Z -> SevenZFile.builder().setFile(archive).get().use { sevenZ ->
                    val entry = sevenZ.entries.firstOrNull { !it.isDirectory && normalize(it.name) == entryPath } ?: return false
                    sevenZ.getInputStream(entry).use { copy(it, target, entry.size, onProgress, isCancelled) }
                }
                Format.TAR, Format.TAR_GZ -> openTar(archive, format == Format.TAR_GZ).use { tar ->
                    // No index: read from the start until the entry comes.
                    var copied = false
                    while (!isCancelled()) {
                        val entry = tar.nextEntry ?: break
                        if (!entry.isDirectory && normalize(entry.name) == entryPath) {
                            copied = copy(tar, target, entry.size, onProgress, isCancelled)
                            break
                        }
                    }
                    copied
                }
                Format.RAR -> false
            }
        } catch (e: Exception) {
            AppLog.w("ArchiveReader", "Extracting $entryPath from ${archive.name} failed", e)
            target.delete()
            false
        } catch (e: OutOfMemoryError) {
            target.delete()
            false
        }
    }

    private fun copy(input: InputStream, target: File, size: Long, onProgress: (Int) -> Unit, isCancelled: () -> Boolean): Boolean {
        // The phone's own storage must not fill up: a huge (or lying) entry stops in time.
        val dir = target.absoluteFile.parentFile
        if (dir != null && size > 0 && dir.usableSpace < size + MIN_FREE_BYTES) {
            AppLog.w("ArchiveReader", "Not enough space to unpack ${target.name} ($size bytes)")
            return false
        }
        val buffer = ByteArray(64 * 1024)
        var done = 0L
        var lastPercent = -1
        var nextSpaceCheck = SPACE_CHECK_BYTES
        target.outputStream().use { out ->
            while (true) {
                if (isCancelled()) {
                    out.close()
                    target.delete()
                    return false
                }
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                done += n
                if (done >= nextSpaceCheck) {
                    nextSpaceCheck = done + SPACE_CHECK_BYTES
                    if (dir != null && dir.usableSpace < MIN_FREE_BYTES) {
                        AppLog.w("ArchiveReader", "Unpacking ${target.name} stopped: storage almost full")
                        out.close()
                        target.delete()
                        return false
                    }
                }
                if (size > 0) {
                    val percent = (done * 100 / size).toInt().coerceIn(0, 100)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onProgress(percent)
                    }
                }
            }
        }
        return true
    }

    private fun listZip(file: File): Listing = ZipFile.builder().setFile(file).get().use { zip ->
        val out = ArrayList<Entry>()
        var truncated = false
        for (entry in zip.entries) {
            if (out.size >= MAX_ENTRIES) {
                truncated = true
                break
            }
            val path = normalize(entry.name)
            if (path.isEmpty()) continue
            out += Entry(
                path = path,
                isDirectory = entry.isDirectory,
                size = entry.size,
                modifiedMs = entry.time.coerceAtLeast(0L),
                encrypted = entry.generalPurposeBit.usesEncryption(),
            )
        }
        Listing.Ok(Format.ZIP, out, canExtract = true, truncated = truncated)
    }

    private fun listSevenZ(file: File): Listing = SevenZFile.builder().setFile(file).get().use { sevenZ ->
        val out = ArrayList<Entry>()
        var truncated = false
        for (entry in sevenZ.entries) {
            if (out.size >= MAX_ENTRIES) {
                truncated = true
                break
            }
            val path = normalize(entry.name ?: continue)
            if (path.isEmpty()) continue
            val encrypted = runCatching { entry.contentMethods?.any { it.method == SevenZMethod.AES256SHA256 } == true }.getOrDefault(false)
            out += Entry(
                path = path,
                isDirectory = entry.isDirectory,
                size = if (entry.hasStream()) entry.size else 0L,
                modifiedMs = if (entry.hasLastModifiedDate) entry.lastModifiedDate.time else 0L,
                encrypted = encrypted,
            )
        }
        Listing.Ok(Format.SEVEN_Z, out, canExtract = true, truncated = truncated)
    }

    private fun openTar(file: File, gzip: Boolean): TarArchiveInputStream {
        val raw: InputStream = BufferedInputStream(FileInputStream(file), 64 * 1024)
        val source = if (gzip) GzipCompressorInputStream(raw, true) else raw
        return TarArchiveInputStream(source)
    }

    private fun listTar(file: File, gzip: Boolean): Listing = openTar(file, gzip).use { tar ->
        val out = ArrayList<Entry>()
        var truncated = false
        while (true) {
            val entry = tar.nextEntry ?: break
            if (out.size >= MAX_ENTRIES) {
                truncated = true
                break
            }
            val path = normalize(entry.name)
            if (path.isEmpty()) continue
            out += Entry(
                path = path,
                isDirectory = entry.isDirectory,
                size = if (entry.isDirectory) 0L else entry.size,
                modifiedMs = entry.lastModifiedDate?.time ?: 0L,
                encrypted = false,
            )
        }
        Listing.Ok(if (gzip) Format.TAR_GZ else Format.TAR, out, canExtract = true, truncated = truncated)
    }

    /** '/' separators, no "./" prefix, no leading or trailing '/'. */
    internal fun normalize(name: String): String {
        var p = name.replace('\\', '/')
        while (p.startsWith("./")) p = p.substring(2)
        return p.trim('/')
    }

    /**
     * RAR headers (versions 4 and 5) read for the names, sizes and dates. Nothing is unpacked.
     * Formats: RARLAB's technote (RAR 5) and the RAR 2.9-4.x block layout.
     */
    internal object RarHeaders {
        private val RAR5_SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)
        private val RAR4_SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
        /** A single header larger than this is taken for a damaged file. */
        private const val MAX_HEADER = 4 * 1024 * 1024

        fun list(file: File): Listing = RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(8)
            val n = raf.read(head)
            when {
                n >= 8 && head.contentEquals(RAR5_SIGNATURE) -> listRar5(raf)
                n >= 7 && head.copyOf(7).contentEquals(RAR4_SIGNATURE) -> listRar4(raf)
                else -> Listing.Failed("not a RAR archive")
            }
        }

        private class Reader(val bytes: ByteArray, var pos: Int = 0) {
            fun u8(): Int = bytes[pos++].toInt() and 0xFF
            fun u16(): Int = u8() or (u8() shl 8)
            fun u32(): Long = (u16().toLong()) or (u16().toLong() shl 16)
            fun vint(): Long {
                var result = 0L
                var shift = 0
                while (shift < 70) {
                    val b = u8()
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                }
                throw IllegalStateException("bad vint")
            }
            fun bytes(count: Int): ByteArray = bytes.copyOfRange(pos, pos + count).also { pos += count }
        }

        /** Reads a vint from the file at its position (the header size, before the header is read). */
        private fun readVint(raf: RandomAccessFile): Pair<Long, Int> {
            var result = 0L
            var shift = 0
            var count = 0
            while (shift < 70) {
                val b = raf.read()
                if (b < 0) throw java.io.EOFException()
                count++
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result to count
                shift += 7
            }
            throw IllegalStateException("bad vint")
        }

        private fun listRar5(raf: RandomAccessFile): Listing {
            val out = ArrayList<Entry>()
            var pos = 8L
            val length = raf.length()
            var truncated = false
            while (pos + 4 < length) {
                raf.seek(pos + 4) // CRC32 of the header, not checked
                val (headerSize, sizeLen) = readVint(raf)
                if (headerSize <= 0 || headerSize > MAX_HEADER) break
                val headerStart = pos + 4 + sizeLen
                if (headerStart + headerSize > length) break
                val data = ByteArray(headerSize.toInt())
                raf.seek(headerStart)
                raf.readFully(data)
                val r = Reader(data)
                val type = r.vint()
                val flags = r.vint()
                if (flags and 0x0001L != 0L) r.vint() // extra area size
                val dataSize = if (flags and 0x0002L != 0L) r.vint() else 0L
                when (type) {
                    4L -> return Listing.Encrypted // archive encryption header: names are encrypted
                    2L -> {
                        if (out.size >= MAX_ENTRIES) {
                            truncated = true
                            break
                        }
                        val fileFlags = r.vint()
                        val unpacked = r.vint()
                        r.vint() // attributes
                        val mtime = if (fileFlags and 0x0002L != 0L) r.u32() else 0L
                        if (fileFlags and 0x0004L != 0L) r.u32() // data CRC32
                        r.vint() // compression information
                        r.vint() // host OS
                        val nameLength = r.vint().toInt()
                        val name = String(r.bytes(nameLength), Charsets.UTF_8)
                        val path = normalize(name)
                        if (path.isNotEmpty()) {
                            out += Entry(
                                path = path,
                                isDirectory = fileFlags and 0x0001L != 0L,
                                size = if (fileFlags and 0x0008L != 0L) -1L else unpacked,
                                modifiedMs = mtime * 1000L,
                                // Encryption is a record in the extra area: checked there.
                                encrypted = hasRar5EncryptionRecord(data, r.pos, flags),
                            )
                        }
                    }
                    5L -> break // end of archive
                }
                // A damaged size (negative, or pointing back) would read the same headers forever.
                val next = headerStart + headerSize + dataSize
                if (dataSize < 0 || next <= pos) {
                    truncated = true
                    break
                }
                pos = next
            }
            return Listing.Ok(Format.RAR, out, canExtract = false, truncated = truncated)
        }

        /** The file header's extra area (at its end) holds a file encryption record (type 1). */
        private fun hasRar5EncryptionRecord(header: ByteArray, afterName: Int, flags: Long): Boolean {
            if (flags and 0x0001L == 0L) return false
            return runCatching {
                val r = Reader(header, afterName)
                while (r.pos < header.size) {
                    val size = r.vint()
                    val start = r.pos
                    // A record must move on and stay inside the header: a damaged size looped here.
                    if (size <= 0 || size > header.size - start) return false
                    val recordType = r.vint()
                    if (recordType == 1L) return true
                    r.pos = (start + size).toInt()
                }
                false
            }.getOrDefault(false)
        }

        private fun listRar4(raf: RandomAccessFile): Listing {
            val out = ArrayList<Entry>()
            var pos = 7L
            val length = raf.length()
            var truncated = false
            val fixed = ByteArray(7)
            while (pos + 7 <= length) {
                raf.seek(pos)
                raf.readFully(fixed)
                val r = Reader(fixed)
                r.u16() // header CRC
                val type = r.u8()
                val flags = r.u16()
                val headSize = r.u16()
                if (headSize < 7 || pos + headSize > length) break
                val header = ByteArray(headSize)
                raf.seek(pos)
                raf.readFully(header)
                var addSize = 0L
                when (type) {
                    0x73 -> { // main header
                        if (flags and 0x0080 != 0) return Listing.Encrypted // headers encrypted
                    }
                    0x74 -> { // file header
                        val h = Reader(header, 7)
                        val packLow = h.u32()
                        val unpLow = h.u32()
                        h.u8() // host OS
                        h.u32() // file CRC
                        val dosTime = h.u32()
                        h.u8() // version
                        h.u8() // method
                        val nameSize = h.u16()
                        h.u32() // attributes
                        var packHigh = 0L
                        var unpHigh = 0L
                        if (flags and 0x0100 != 0) {
                            packHigh = h.u32()
                            unpHigh = h.u32()
                        }
                        val nameBytes = h.bytes(nameSize)
                        addSize = packLow or (packHigh shl 32)
                        if (out.size >= MAX_ENTRIES) {
                            truncated = true
                            break
                        }
                        val path = normalize(rar4Name(nameBytes, flags and 0x0200 != 0))
                        if (path.isNotEmpty()) {
                            out += Entry(
                                path = path,
                                isDirectory = flags and 0x00E0 == 0x00E0,
                                size = unpLow or (unpHigh shl 32),
                                modifiedMs = dosTimeToMs(dosTime),
                                encrypted = flags and 0x0004 != 0,
                            )
                        }
                    }
                    0x7B -> break // end of archive
                    else -> if (flags and 0x8000 != 0) {
                        addSize = Reader(header, 7).u32()
                    }
                }
                // A damaged size (negative) would go back and read the same headers again and again.
                if (addSize < 0) {
                    truncated = true
                    break
                }
                pos += headSize + addSize
            }
            return Listing.Ok(Format.RAR, out, canExtract = false, truncated = truncated)
        }

        /** Unicode names hold an ASCII name, a zero, then an encoded form: the ASCII part is used. */
        private fun rar4Name(bytes: ByteArray, unicode: Boolean): String {
            val end = if (unicode) bytes.indexOf(0.toByte()).let { if (it < 0) bytes.size else it } else bytes.size
            val raw = bytes.copyOf(end)
            val utf8 = String(raw, Charsets.UTF_8)
            return if ('�' in utf8) String(raw, Charsets.ISO_8859_1) else utf8
        }

        private fun dosTimeToMs(dos: Long): Long {
            if (dos == 0L) return 0L
            val time = (dos and 0xFFFF).toInt()
            val date = ((dos shr 16) and 0xFFFF).toInt()
            val cal = java.util.Calendar.getInstance()
            cal.clear()
            cal.set(
                ((date shr 9) and 0x7F) + 1980,
                ((date shr 5) and 0x0F) - 1,
                date and 0x1F,
                (time shr 11) and 0x1F,
                (time shr 5) and 0x3F,
                (time and 0x1F) * 2,
            )
            return cal.timeInMillis
        }
    }
}
