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
