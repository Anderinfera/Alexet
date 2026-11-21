package dev.ander.alexet.data.videoeditor

import dev.ander.alexet.data.GifFrameReader
import dev.ander.alexet.data.QuickEdit
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    private fun lzw(indices: ByteArray, initCodeSize: Int = 8): ByteArray =
        ByteArrayOutputStream().also { GifCodec.writeLzw(indices, it, initCodeSize = initCodeSize) }.toByteArray()

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
    fun lzwRoundTripsAllInitCodeSizes() {
        val rnd = java.util.Random(42)
        for (initCodeSize in 2..8) {
            val paletteSize = 1 shl initCodeSize
            val data = ByteArray(8_000) { rnd.nextInt(paletteSize).toByte() }
            assertArrayEquals(
                "initCodeSize=$initCodeSize",
                data,
                decode(lzw(data, initCodeSize), data.size),
            )
            val edge = byteArrayOf(0, (paletteSize - 1).toByte(), 1, 0)
            assertArrayEquals(
                "edge initCodeSize=$initCodeSize",
                edge,
                decode(lzw(edge, initCodeSize), edge.size),
            )
        }
    }

    @Test
    fun quantizeSupportsVariableColorTableSizesAndTransparency() {
        val rnd = java.util.Random(17)
        val opaque = IntArray(4_000) { 0xFF000000.toInt() or rnd.nextInt(0xFFFFFF) }
        val transparent = opaque.copyOf().also { it[0] = 0x00000000 }
        val expectedBits = mapOf(16 to 3, 32 to 4, 64 to 5, 128 to 6, 256 to 7)

        for ((maxColors, bits) in expectedBits) {
            val tableSize = 1 shl (bits + 1)
            val qOpaque = GifCodec.quantize(opaque, keepTransparency = true, maxColors = maxColors)
            assertEquals(bits, qOpaque.colorTableSizeBits)
            assertEquals(bits + 1, qOpaque.lzwMinCodeSize)
            assertEquals(tableSize * 3, qOpaque.palette.size)
            assertEquals(-1, qOpaque.transparentIndex)

            val qTrans = GifCodec.quantize(transparent, keepTransparency = true, maxColors = maxColors)
            assertEquals(bits, qTrans.colorTableSizeBits)
            assertEquals(bits + 1, qTrans.lzwMinCodeSize)
            assertEquals(tableSize * 3, qTrans.palette.size)
            assertEquals(tableSize - 1, qTrans.transparentIndex)
            assertEquals(tableSize - 1, qTrans.indices[0].toInt() and 0xFF)
            for (i in 1 until qTrans.indices.size) {
                val idx = qTrans.indices[i].toInt() and 0xFF
                assertTrue("index $idx must be < ${tableSize - 1}", idx < tableSize - 1)
            }
        }
    }

    @Test
    fun encoderDeltaRoundTripsAndShrinksOpaqueAnimation() {
        val w = 64
        val h = 64
        val bg = 0xFF18395A.toInt()
        val fg = 0xFFE73942.toInt()
        fun makeFrame(boxX: Int, boxY: Int, subtleNoise: Boolean = false): IntArray {
            val px = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val inBox = x in boxX until (boxX + 8) && y in boxY until (boxY + 8)
                    var c = if (inBox) fg else bg
                    if (subtleNoise && !inBox) {
                        val d = ((x + y) % 5) - 2
                        c = (0xFF shl 24) or ((0x1E + d) shl 16) or ((0x3A + d) shl 8) or (0x5F + d)
                    }
                    px[y * w + x] = c
                }
            }
            return px
        }

        // Frame 3 intentionally duplicates frame 2 to exercise the 1x1 transparent no-op path.
        val frames = listOf(
            makeFrame(4, 8),
            makeFrame(12, 8),
            makeFrame(20, 8),
            makeFrame(20, 8),
        )
        val delays = intArrayOf(40, 120, 250, 80)

        fun encodeWith(optimizeDelta: Boolean, quality: Int, sourceFrames: List<IntArray> = frames): ByteArray {
            val profile = QuickEdit.gifProfile(quality)
            val baos = ByteArrayOutputStream()
            GifEncoder().use { enc ->
                enc.keepTransparency = true
                enc.maxColors = profile.maxColors
                enc.temporalMaxDist = profile.temporalMaxDist
                enc.spatialMaxDist = profile.spatialMaxDist
                enc.optimizeDelta = optimizeDelta
                assertTrue(enc.start(baos))
                for (i in sourceFrames.indices) {
                    enc.setDelay(delays[i])
                    assertTrue(enc.addFramePixels(sourceFrames[i], w, h))
                }
                assertTrue(enc.finish())
            }
            return baos.toByteArray()
        }

        val deltaFullQ = encodeWith(optimizeDelta = true, quality = 100)
        val fullFrameQ100 = encodeWith(optimizeDelta = false, quality = 100)
        assertTrue(
            "Delta encoding (${deltaFullQ.size} B) should be smaller than full-frame (${fullFrameQ100.size} B)",
            deltaFullQ.size < fullFrameQ100.size,
        )

        val noisyFrames = listOf(
            makeFrame(4, 8, subtleNoise = true),
            makeFrame(12, 8, subtleNoise = true),
            makeFrame(20, 8, subtleNoise = true),
            makeFrame(20, 8, subtleNoise = true),
        )
        val noisyQ100 = encodeWith(optimizeDelta = true, quality = 100, sourceFrames = noisyFrames)
        val noisyQ20 = encodeWith(optimizeDelta = true, quality = 20, sourceFrames = noisyFrames)
        assertTrue(
            "Lower quality (${noisyQ20.size} B) should produce smaller output than quality 100 (${noisyQ100.size} B)",
            noisyQ20.size < noisyQ100.size,
        )

        val info = GifFrameReader.info(deltaFullQ)
        assertNotNull(info)
        assertEquals(w, info!!.width)
        assertEquals(h, info.height)
        assertEquals(frames.size, info.frameCount)
        assertArrayEquals(delays, info.frameDelaysMs)
        assertFalse(info.hasDisposalClear)

        val decodedFrames = ArrayList<IntArray>()
        val decodedDelays = ArrayList<Int>()
        val count = GifFrameReader.forEachFrame(deltaFullQ) { pixels, fw, fh, delayMs ->
            assertEquals(w, fw)
            assertEquals(h, fh)
            decodedFrames.add(pixels.copyOf())
            decodedDelays.add(delayMs)
            true
        }
        assertEquals(frames.size, count)
        assertArrayEquals(delays, decodedDelays.toIntArray())
        for (i in frames.indices) {
            assertArrayEquals("frame $i round-trip", frames[i], decodedFrames[i])
        }
    }

    @Test
    fun transparentAnimationClearsPreviousFrameWithoutGhosting() {
        val w = 16
        val h = 16
        val red = 0xFFFF0000.toInt()
        val green = 0xFF00FF00.toInt()
        val f0 = IntArray(w * h) { i -> if (i % w < w / 2) red else 0x00000000 }
        val f1 = IntArray(w * h) { i -> if (i % w < w / 2) 0x00000000 else green }

        val baos = ByteArrayOutputStream()
        GifEncoder().use { enc ->
            enc.keepTransparency = true
            enc.optimizeDelta = false
            assertTrue(enc.start(baos))
            enc.setDelay(100)
            assertTrue(enc.addFramePixels(f0, w, h))
            enc.setDelay(150)
            assertTrue(enc.addFramePixels(f1, w, h))
            assertTrue(enc.finish())
        }
        val bytes = baos.toByteArray()

        val info = GifFrameReader.info(bytes)!!
        assertEquals(2, info.frameCount)
        assertArrayEquals(intArrayOf(100, 150), info.frameDelaysMs)
        assertTrue(info.hasDisposalClear)

        val decoded = ArrayList<IntArray>()
        GifFrameReader.forEachFrame(bytes) { pixels, _, _, _ ->
            decoded.add(pixels.copyOf())
            true
        }
        assertEquals(2, decoded.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val px = decoded[1][y * w + x]
                if (x < w / 2) {
                    assertEquals("Pixel ($x,$y) must be transparent without ghosting", 0, px ushr 24)
                } else {
                    assertEquals("Pixel ($x,$y) must be opaque green", green, px)
                }
            }
        }
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
