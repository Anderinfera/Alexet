package dev.ander.alexet.data.videoeditor

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GifCodecTest {

    /** Plain GIF LZW decoder (as browsers implement it) to check the encoder's output. */
    private fun decode(data: ByteArray, expected: Int): ByteArray {
        val minCodeSize = data[0].toInt()
        val bytes = ByteArrayOutputStream()
        var pos = 1
        while (true) {
            val len = data[pos++].toInt() and 0xFF
            if (len == 0) break
            bytes.write(data, pos, len)
            pos += len
        }
        val stream = bytes.toByteArray()
        val clear = 1 shl minCodeSize
        val eoi = clear + 1
        var codeSize = minCodeSize + 1
        val prefix = IntArray(4096)
        val suffix = IntArray(4096)
        val length = IntArray(4096)
        for (i in 0 until clear) {
            suffix[i] = i
            length[i] = 1
        }
        var next = clear + 2
        var prev = -1
        val out = ByteArray(expected)
        var outPos = 0
        var bitPos = 0
        fun string(code: Int): IntArray {
            val s = IntArray(length[code])
            var c = code
            for (i in s.indices.reversed()) {
                s[i] = suffix[c]
                c = prefix[c]
            }
            return s
        }
        while (true) {
            var code = 0
            for (b in 0 until codeSize) {
                val byte = stream[(bitPos + b) / 8].toInt() and 0xFF
                if ((byte shr ((bitPos + b) % 8)) and 1 == 1) code = code or (1 shl b)
            }
            bitPos += codeSize
            if (code == clear) {
                codeSize = minCodeSize + 1
                next = clear + 2
                prev = -1
                continue
            }
            if (code == eoi) break
            val entry = when {
                code < next && (code < clear || code > eoi) -> string(code)
                code == next && prev >= 0 -> string(prev).let { it + it[0] }
                else -> error("bad code $code (next $next, size $codeSize)")
            }
            for (v in entry) out[outPos++] = v.toByte()
            if (prev >= 0 && next < 4096) {
                prefix[next] = prev
                suffix[next] = entry[0]
                length[next] = length[prev] + 1
                next++
                if (next == (1 shl codeSize) && codeSize < 12) codeSize++
            }
            prev = code
        }
        assertEquals(expected, outPos)
        return out
    }

    private fun lzw(indices: ByteArray): ByteArray =
        ByteArrayOutputStream().also { GifCodec.writeLzw(indices, it) }.toByteArray()

    @Test
    fun lzwRoundTripsSmallAndLargeImages() {
        val rnd = java.util.Random(3)
        // Random noise fills the 4096-code table several times (clear codes); gradients give long runs.
        val noise = ByteArray(200_000) { rnd.nextInt(256).toByte() }
        assertArrayEquals(noise, decode(lzw(noise), noise.size))
        val gradient = ByteArray(480 * 270) { ((it % 480) * 256 / 480).toByte() }
        assertArrayEquals(gradient, decode(lzw(gradient), gradient.size))
        val tiny = byteArrayOf(5)
        assertArrayEquals(tiny, decode(lzw(tiny), 1))
    }

    @Test
    fun quantizerKeepsDistinctColorsAndMapsToPalette() {
        val colors = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        val pixels = IntArray(1000) { colors[it % colors.size] }
        val q = GifCodec.quantize(pixels)
        for (i in pixels.indices) {
            val idx = q.indices[i].toInt() and 0xFF
            val r = q.palette[idx * 3].toInt() and 0xFF
            val g = q.palette[idx * 3 + 1].toInt() and 0xFF
            val b = q.palette[idx * 3 + 2].toInt() and 0xFF
            val c = pixels[i]
            assertTrue(kotlin.math.abs(r - ((c shr 16) and 0xFF)) <= 8)
            assertTrue(kotlin.math.abs(g - ((c shr 8) and 0xFF)) <= 8)
            assertTrue(kotlin.math.abs(b - (c and 0xFF)) <= 8)
        }
    }

    @Test
    fun quantizerHandlesManyColors() {
        val rnd = java.util.Random(9)
        val pixels = IntArray(100_000) { 0xFF000000.toInt() or rnd.nextInt(0xFFFFFF) }
        val q = GifCodec.quantize(pixels)
        assertEquals(pixels.size, q.indices.size)
        assertEquals(768, q.palette.size)
    }
}
