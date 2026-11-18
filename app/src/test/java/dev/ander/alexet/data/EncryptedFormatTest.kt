package dev.ander.alexet.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class EncryptedFormatTest {

    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    private fun encrypt(plain: ByteArray): ByteArray =
        ByteArrayOutputStream().also { EncryptedFormat.encrypt(ByteArrayInputStream(plain), it, key) }.toByteArray()

    private fun decrypt(sealed: ByteArray, k: SecretKeySpec = key): ByteArray =
        EncryptedFormat.decryptingStream(ByteArrayInputStream(sealed), k).use { it.readBytes() }

    @Test
    fun roundTripsAcrossChunkBoundaries() {
        val rnd = java.util.Random(7)
        for (size in listOf(0, 1, EncryptedFormat.CHUNK - 1, EncryptedFormat.CHUNK, EncryptedFormat.CHUNK + 1, 3 * EncryptedFormat.CHUNK + 5)) {
            val plain = ByteArray(size).also { rnd.nextBytes(it) }
            val sealed = encrypt(plain)
            assertArrayEquals("size $size", plain, decrypt(sealed))
            assertEquals("size $size", size.toLong(), EncryptedFormat.plaintextSize(sealed.size.toLong(), ByteArrayInputStream(sealed)))
        }
    }

    @Test
    fun chunksOnDiskMatchTheFormat() {
        // Each chunk opened on its own with a fresh cipher, as the format describes.
        val rnd = java.util.Random(19)
        val plain = ByteArray(2 * EncryptedFormat.CHUNK + 321).also { rnd.nextBytes(it) }
        val sealed = encrypt(plain)
        val header = sealed.copyOf(16)
        val out = ByteArrayOutputStream()
        var pos = 16
        var index = 0
        while (pos < sealed.size) {
            val len = minOf(EncryptedFormat.CHUNK + 16, sealed.size - pos)
            val last = pos + len >= sealed.size
            val iv = header.copyOfRange(8, 16) + byteArrayOf((index ushr 24).toByte(), (index ushr 16).toByte(), (index ushr 8).toByte(), index.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(header)
            cipher.updateAAD(byteArrayOf(if (last) 1 else 0))
            out.write(cipher.doFinal(sealed, pos, len))
            pos += len
            index++
        }
        assertEquals(3, index)
        assertArrayEquals(plain, out.toByteArray())
    }

    @Test
    fun randomAccessReadsAnyPosition() {
        val rnd = java.util.Random(11)
        val plain = ByteArray(2 * EncryptedFormat.CHUNK + 777).also { rnd.nextBytes(it) }
        val file = java.io.File.createTempFile("enc", ".enc").apply { deleteOnExit() }
        file.writeBytes(encrypt(plain))
        EncryptedFormat.openRandomAccess(file, key)!!.use { reader ->
            assertEquals(plain.size.toLong(), reader.size)
            for (start in listOf(0, 5, EncryptedFormat.CHUNK - 3, EncryptedFormat.CHUNK, 2 * EncryptedFormat.CHUNK + 700, 17)) {
                val want = minOf(4000, plain.size - start)
                val out = ByteArray(want)
                var done = 0
                while (done < want) {
                    val n = reader.read((start + done).toLong(), out, done, want - done)
                    if (n < 0) break
                    done += n
                }
                assertArrayEquals("start $start", plain.copyOfRange(start, start + want), out)
            }
            assertEquals(-1, reader.read(plain.size.toLong(), ByteArray(4), 0, 4))
        }
    }

    @Test
    fun cachedChunksReadSameBytesInAnyOrder() {
        val rnd = java.util.Random(13)
        val plain = ByteArray(3 * EncryptedFormat.CHUNK + 1234).also { rnd.nextBytes(it) }
        val file = java.io.File.createTempFile("enc", ".enc").apply { deleteOnExit() }
        file.writeBytes(encrypt(plain))
        for (cached in listOf(1, 2, 3)) {
            EncryptedFormat.openRandomAccess(file, key, cached)!!.use { reader ->
                // Jumps between the start and the end, like a retriever reading a trailing moov.
                for (start in listOf(3 * EncryptedFormat.CHUNK + 10, 0, 3 * EncryptedFormat.CHUNK + 1000, 100, EncryptedFormat.CHUNK + 5, 7)) {
                    val want = minOf(3000, plain.size - start)
                    val out = ByteArray(want)
                    var done = 0
                    while (done < want) {
                        val n = reader.read((start + done).toLong(), out, done, want - done)
                        if (n < 0) break
                        done += n
                    }
                    assertArrayEquals("cached $cached start $start", plain.copyOfRange(start, start + want), out)
                }
            }
        }
    }

    @Test
    fun tamperedChunkFailsInStreamAndRandomAccess() {
        val rnd = java.util.Random(17)
        val plain = ByteArray(2 * EncryptedFormat.CHUNK + 500).also { rnd.nextBytes(it) }
        val sealed = encrypt(plain)
        // Flip one byte inside the second chunk.
        val tampered = sealed.copyOf().also { it[16 + EncryptedFormat.CHUNK + 16 + 40] = (it[16 + EncryptedFormat.CHUNK + 16 + 40].toInt() xor 1).toByte() }
        try {
            decrypt(tampered)
            fail("tampered file decrypted")
        } catch (_: IOException) {
        }
        val file = java.io.File.createTempFile("enc", ".enc").apply { deleteOnExit() }
        file.writeBytes(tampered)
        EncryptedFormat.openRandomAccess(file, key, 2)!!.use { reader ->
            val out = ByteArray(10)
            assertEquals(10, reader.read(0, out, 0, 10))
            assertArrayEquals(plain.copyOfRange(0, 10), out)
            try {
                reader.read(EncryptedFormat.CHUNK.toLong() + 5, out, 0, 10)
                fail("tampered chunk read")
            } catch (_: IOException) {
            }
            // The failed chunk is not kept: reading it again fails again, the good one still reads.
            try {
                reader.read(EncryptedFormat.CHUNK.toLong() + 5, out, 0, 10)
                fail("tampered chunk read")
            } catch (_: IOException) {
            }
            assertEquals(10, reader.read(2L * EncryptedFormat.CHUNK + 1, out, 0, 10))
            assertArrayEquals(plain.copyOfRange(2 * EncryptedFormat.CHUNK + 1, 2 * EncryptedFormat.CHUNK + 11), out)
        }
    }

    @Test
    fun readsVersionOneFiles() {
        val plain = "legacy content".toByteArray()
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val sealed = iv + cipher.doFinal(plain)
        assertArrayEquals(plain, decrypt(sealed))
        assertEquals(plain.size.toLong(), EncryptedFormat.plaintextSize(sealed.size.toLong(), ByteArrayInputStream(sealed)))
    }

    @Test
    fun cutOffFileFailsInsteadOfReadingShorter() {
        val plain = ByteArray(2 * EncryptedFormat.CHUNK + 100) { 3 }
        val sealed = encrypt(plain)
        // Drop the whole last chunk: the remaining one was sealed as "not last".
        val cut = sealed.copyOf(16 + 2 * (EncryptedFormat.CHUNK + 16))
        try {
            decrypt(cut)
            fail("cut-off file decrypted")
        } catch (_: IOException) {
        }
    }

    @Test
    fun wrongKeyFails() {
        val sealed = encrypt(ByteArray(100) { 1 })
        try {
            decrypt(sealed, SecretKeySpec(ByteArray(32), "AES"))
            fail("wrong key decrypted")
        } catch (_: IOException) {
        }
    }
}
