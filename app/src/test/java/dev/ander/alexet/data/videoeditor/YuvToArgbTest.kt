package dev.ander.alexet.data.videoeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.abs

class YuvToArgbTest {

    /** A [w] x [h] frame: Y per pixel, one U/V per 2x2 block (planar, pixel stride 1). */
    private fun frame(w: Int, h: Int, y: IntArray, u: IntArray, v: IntArray, bt709: Boolean = false, fullRange: Boolean = false) = YuvFrame(
        y = ByteBuffer.wrap(ByteArray(y.size) { y[it].toByte() }),
        yRowStride = w,
        yPixelStride = 1,
        u = ByteBuffer.wrap(ByteArray(u.size) { u[it].toByte() }),
        v = ByteBuffer.wrap(ByteArray(v.size) { v[it].toByte() }),
        uvRowStride = (w + 1) / 2,
        uvPixelStride = 1,
        left = 0,
        top = 0,
        width = w,
        height = h,
        bt709 = bt709,
        fullRange = fullRange,
    )

    private fun rgb(c: Int) = Triple((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)

    private fun assertColor(expected: Triple<Int, Int, Int>, actual: Int, tolerance: Int = 3) {
        val (r, g, b) = rgb(actual)
        assertTrue("expected $expected, got ${rgb(actual)}", abs(r - expected.first) <= tolerance && abs(g - expected.second) <= tolerance && abs(b - expected.third) <= tolerance)
        assertEquals(0xFF, (actual ushr 24) and 0xFF)
    }

    @Test
    fun limitedRangeWhiteBlackAndRed() {
        // BT.601 limited range: white, black, and pure red (Y 81, U 90, V 240) in its own block.
        val f = frame(
            4, 2,
            y = intArrayOf(235, 16, 81, 81, 235, 16, 81, 81),
            u = intArrayOf(128, 90),
            v = intArrayOf(128, 240),
        )
        val out = IntArray(8)
        yuvToArgb(f, 0, 4, 2, out)
        assertColor(Triple(255, 255, 255), out[0])
        assertColor(Triple(0, 0, 0), out[1])
        assertColor(Triple(255, 0, 0), out[2])
        assertColor(Triple(255, 0, 0), out[7])
    }

    @Test
    fun fullRangeBt709Grey() {
        val f = frame(2, 2, y = intArrayOf(128, 128, 128, 128), u = intArrayOf(128), v = intArrayOf(128), bt709 = true, fullRange = true)
        val out = IntArray(4)
        yuvToArgb(f, 0, 2, 2, out)
        for (c in out) assertColor(Triple(128, 128, 128), c, tolerance = 1)
    }

    @Test
    fun turnedClockwiseLikeTheVideoIsShown() {
        // Left column white, right column black; turned 90 degrees the left side is on top.
        val f = frame(2, 2, y = intArrayOf(235, 16, 235, 16), u = intArrayOf(128), v = intArrayOf(128))
        val out = IntArray(4)
        yuvToArgb(f, 90, 2, 2, out)
        assertColor(Triple(255, 255, 255), out[0])
        assertColor(Triple(255, 255, 255), out[1])
        assertColor(Triple(0, 0, 0), out[2])
        assertColor(Triple(0, 0, 0), out[3])
        yuvToArgb(f, 180, 2, 2, out)
        assertColor(Triple(0, 0, 0), out[0])
        assertColor(Triple(255, 255, 255), out[1])
        yuvToArgb(f, 270, 2, 2, out)
        assertColor(Triple(0, 0, 0), out[0])
        assertColor(Triple(255, 255, 255), out[2])
    }

    @Test
    fun scaledDownByTakingTheNearestPixel() {
        // 4x2: white, white, black, black in each row -> 2x1: white, black.
        val f = frame(4, 2, y = intArrayOf(235, 235, 16, 16, 235, 235, 16, 16), u = intArrayOf(128, 128), v = intArrayOf(128, 128))
        val out = IntArray(2)
        yuvToArgb(f, 0, 2, 1, out)
        assertColor(Triple(255, 255, 255), out[0])
        assertColor(Triple(0, 0, 0), out[1])
    }
}
