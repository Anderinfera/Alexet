package dev.ander.alexet.data

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Content of private-folder `.enc` files.
 *
 * Version 2 (written now): `magic(8) | nonce prefix(8)` followed by chunks of up to [CHUNK]
 * plaintext bytes, each sealed on its own with AES-GCM (iv = prefix + chunk number, tag 16 bytes,
 * the header and a last-chunk flag as associated data). Android's GCM keeps the whole message
 * in memory until the tag is checked, so one GCM message per file ran out of memory on large
 * videos; chunks keep it at about 1 MB, and the flag makes a cut-off file fail instead of
 * reading as shorter.
 *
 * Version 1 (older files): `iv(12) | AES-GCM(whole file)`. Still read.
 */
object EncryptedFormat {
    const val CHUNK = 1 shl 20
    private const val TAG_BYTES = 16
    private const val LEGACY_IV = 12
    private val MAGIC = byteArrayOf(0x41, 0x4C, 0x58, 0x45, 0x4E, 0x43, 0x02, 0x00) // "ALXENC" 2 0
    private const val HEADER = 16

    fun encrypt(input: InputStream, output: OutputStream, key: SecretKey) {
        val header = ByteArray(HEADER)
        System.arraycopy(MAGIC, 0, header, 0, MAGIC.size)
        val prefix = ByteArray(8).also { SecureRandom().nextBytes(it) }
        System.arraycopy(prefix, 0, header, 8, 8)
        output.write(header)

        var current = ByteArray(CHUNK)
        var currentLen = readFully(input, current)
        var next = ByteArray(CHUNK)
        var index = 0
        while (true) {
            val nextLen = readFully(input, next)
            val last = nextLen == 0
            output.write(seal(key, header, index, last, current, currentLen))
            if (last) break
            val swap = current
            current = next
            next = swap
            currentLen = nextLen
            index++
        }
        output.flush()
    }

    /** Wraps [raw] (positioned at the start of the file); throws for a wrong key or bad data. */
    fun decryptingStream(raw: InputStream, key: SecretKey): InputStream {
        val pushback = PushbackInputStream(raw, HEADER)
        val head = ByteArray(HEADER)
        val n = readFully(pushback, head)
        return if (n == HEADER && head.copyOf(MAGIC.size).contentEquals(MAGIC)) {
            ChunkedInput(pushback, key, head)
        } else {
            if (n < LEGACY_IV) throw IOException("Encrypted file too short")
            pushback.unread(head, LEGACY_IV, n - LEGACY_IV)
            decryptLegacy(pushback, key, head)
        }
    }

    /**
     * Version 1: one GCM message, decrypted in one call. It went through CipherInputStream before,
     * which reads 512 bytes at a time and, before each piece, makes a new output buffer as large as
     * everything read so far (GCM gives nothing back before the end): about 24 GB of allocations
     * for a 5 MB photo, tens of seconds. GCM holds the whole file in memory either way.
     */
    private fun decryptLegacy(source: InputStream, key: SecretKey, head: ByteArray): InputStream {
        try {
            val sealed = source.use { GrowingBuffer().apply { readFrom(it) } }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, head, 0, LEGACY_IV))
            val plain = try {
                cipher.doFinal(sealed.bytes(), 0, sealed.size())
            } catch (e: Exception) {
                throw IOException("Encrypted file failed to authenticate", e)
            }
            return WipingInput(plain)
        } catch (e: OutOfMemoryError) {
            throw IOException("Encrypted file too large to open", e)
        }
    }

    /** True for a file in the chunked format (version 2); false for version 1 or unreadable. */
    fun isChunked(file: java.io.File): Boolean = runCatching {
        java.io.FileInputStream(file).use { input ->
            val bytes = ByteArray(MAGIC.size)
            readFully(input, bytes) == MAGIC.size && bytes.contentEquals(MAGIC)
        }
    }.getOrDefault(false)

    /** A byte buffer whose array can be read without a copy. */
    private class GrowingBuffer : java.io.ByteArrayOutputStream(64 * 1024) {
        fun bytes(): ByteArray = buf
        fun readFrom(input: InputStream) {
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                write(chunk, 0, n)
            }
        }
    }

    /** Plaintext in memory, zeroed when closed. */
    private class WipingInput(plain: ByteArray) : java.io.ByteArrayInputStream(plain) {
        override fun close() {
            java.util.Arrays.fill(buf, 0)
        }
    }

    /** Plaintext length for a file of [fileLength] bytes whose start is readable from [head]. */
    fun plaintextSize(fileLength: Long, head: InputStream): Long {
        val bytes = ByteArray(MAGIC.size)
        val n = readFully(head, bytes)
        if (n == MAGIC.size && bytes.contentEquals(MAGIC)) {
            val body = fileLength - HEADER
            if (body <= 0) return 0
            val chunks = (body + CHUNK + TAG_BYTES - 1) / (CHUNK + TAG_BYTES)
            return (body - chunks * TAG_BYTES).coerceAtLeast(0)
        }
        return (fileLength - LEGACY_IV - TAG_BYTES).coerceAtLeast(0)
    }

    /**
     * Reads a version 2 file at any position, decrypting only the chunk that holds it (video
     * playback seeks). Null for version 1 files, which can only be read from the start.
     */
    fun openRandomAccess(file: java.io.File, key: SecretKey): RandomAccessReader? {
        val raf = java.io.RandomAccessFile(file, "r")
        try {
            val head = ByteArray(HEADER)
            if (raf.length() < HEADER || raf.read(head) != HEADER || !head.copyOf(MAGIC.size).contentEquals(MAGIC)) {
                raf.close()
                return null
            }
            return RandomAccessReader(raf, key, head)
        } catch (e: Exception) {
            raf.close()
            throw e
        }
    }

    class RandomAccessReader internal constructor(
        private val raf: java.io.RandomAccessFile,
        private val key: SecretKey,
        private val header: ByteArray,
    ) : java.io.Closeable {
        private val fileLength = raf.length()
        val size: Long = plaintextSize(fileLength, java.io.ByteArrayInputStream(header))
        private val sealed = ByteArray(CHUNK + TAG_BYTES)
        private var plain = ByteArray(0)
        private var plainIndex = -1

        /** Copies up to [len] bytes at [position]; -1 at the end. Throws for tampered data. */
        fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
            if (len == 0) return 0
            if (position >= size) return -1
            val index = (position / CHUNK).toInt()
            if (index != plainIndex) load(index)
            val inChunk = (position - index.toLong() * CHUNK).toInt()
            val n = minOf(len, plain.size - inChunk)
            if (n <= 0) return -1
            System.arraycopy(plain, inChunk, buffer, offset, n)
            return n
        }

        private fun load(index: Int) {
            val start = HEADER + index.toLong() * (CHUNK + TAG_BYTES)
            val length = minOf((CHUNK + TAG_BYTES).toLong(), fileLength - start).toInt()
            if (length < TAG_BYTES) throw EOFException("Encrypted file cut off")
            raf.seek(start)
            raf.readFully(sealed, 0, length)
            val last = start + length >= fileLength
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, iv(header, index)))
            cipher.updateAAD(header)
            cipher.updateAAD(byteArrayOf(if (last) 1 else 0))
            java.util.Arrays.fill(plain, 0)
            plain = try {
                cipher.doFinal(sealed, 0, length)
            } catch (e: Exception) {
                throw IOException("Encrypted chunk $index failed to authenticate", e)
            }
            plainIndex = index
        }

        override fun close() {
            java.util.Arrays.fill(plain, 0)
            plainIndex = -1
            raf.close()
        }
    }

    private fun seal(key: SecretKey, header: ByteArray, index: Int, last: Boolean, data: ByteArray, len: Int): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, iv(header, index)))
        cipher.updateAAD(header)
        cipher.updateAAD(byteArrayOf(if (last) 1 else 0))
        return cipher.doFinal(data, 0, len)
    }

    private fun iv(header: ByteArray, index: Int): ByteArray {
        val iv = ByteArray(12)
        System.arraycopy(header, 8, iv, 0, 8)
        iv[8] = (index ushr 24).toByte()
        iv[9] = (index ushr 16).toByte()
        iv[10] = (index ushr 8).toByte()
        iv[11] = index.toByte()
        return iv
    }

    /** Reads until [buf] is full or the stream ends; returns the count. */
    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var done = 0
        while (done < buf.size) {
            val n = input.read(buf, done, buf.size - done)
            if (n < 0) break
            done += n
        }
        return done
    }

    private class ChunkedInput(
        private val source: PushbackInputStream,
        private val key: SecretKey,
        private val header: ByteArray,
    ) : InputStream() {
        private val sealed = ByteArray(CHUNK + TAG_BYTES)
        private var plain = ByteArray(0)
        private var pos = 0
        private var index = 0
        private var finished = false

        private fun fill(): Boolean {
            if (finished) return false
            val n = readFully(source, sealed)
            if (n < TAG_BYTES) throw EOFException("Encrypted file cut off")
            val peek = source.read()
            val last = peek < 0
            if (!last) source.unread(peek)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, iv(header, index)))
            cipher.updateAAD(header)
            cipher.updateAAD(byteArrayOf(if (last) 1 else 0))
            plain = try {
                cipher.doFinal(sealed, 0, n)
            } catch (e: Exception) {
                throw IOException("Encrypted chunk $index failed to authenticate", e)
            }
            pos = 0
            index++
            if (last) finished = true
            return true
        }

        override fun read(): Int {
            while (pos >= plain.size) if (!fill()) return -1
            return plain[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= plain.size) if (!fill()) return -1
            val n = minOf(len, plain.size - pos)
            System.arraycopy(plain, pos, b, off, n)
            pos += n
            return n
        }

        override fun available(): Int = plain.size - pos

        override fun close() {
            java.util.Arrays.fill(plain, 0)
            source.close()
        }
    }
}
