package dev.ander.alexet.data.videoeditor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Self-contained, pure Kotlin animated GIF encoder with NeuQuant color quantization and LZW compression.
 */
class GifEncoder : AutoCloseable {
    private var width: Int = 0
    private var height: Int = 0
    private var delayMs: Int = 100
    private var delayCarryMs: Int = 0
    private var repeatCount: Int = 0 // 0 = loop forever
    /** See-through pixels stay see-through (an edited GIF); video frames are opaque anyway. */
    var keepTransparency: Boolean = false
    private var out: OutputStream? = null
    private var isStarted = false
    /** One pixel buffer for all frames (they all have the GIF's size). */
    private var pixels: IntArray? = null
    /** The quantizer's tables, reused for every frame. */
    private val quantizeBuffers = GifCodec.Buffers()

    fun start(file: File): Boolean {
        return try {
            out = BufferedOutputStream(FileOutputStream(file))
            writeString("GIF89a") // Header
            isStarted = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun setDelay(ms: Int) {
        delayMs = ms
    }

    fun setRepeat(repeat: Int) {
        repeatCount = repeat
    }

    fun addFrame(bitmap: Bitmap): Boolean {
        if (!isStarted || out == null) return false
        var scaled: Bitmap? = null
        try {
            if (width == 0 || height == 0) {
                width = bitmap.width
                height = bitmap.height
                writeLSD()
                if (repeatCount >= 0) {
                    writeNetscapeExt()
                }
            }

            val pixels = this.pixels?.takeIf { it.size == width * height }
                ?: IntArray(width * height).also { this.pixels = it }
            scaled = if (bitmap.width != width || bitmap.height != height) {
                Bitmap.createScaledBitmap(bitmap, width, height, true)
            } else {
                bitmap
            }
            scaled.getPixels(pixels, 0, width, 0, 0, width, height)

            val indexed = GifCodec.quantize(pixels, keepTransparency, quantizeBuffers)

            writeGraphicCtrlExt(indexed.transparentIndex)
            writeImageDesc()
            writeColorTable(indexed.palette)
            GifCodec.writeLzw(indexed.indices, out ?: return false)

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            val s = scaled
            if (s != null && s != bitmap) {
                s.recycle()
            }
        }
    }

    fun finish(): Boolean {
        if (!isStarted || out == null) return false
        return try {
            out?.write(0x3B) // GIF trailer
            out?.flush()
            out?.close()
            out = null
            isStarted = false
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun close() {
        try {
            out?.flush()
            out?.close()
        } catch (_: Exception) {}
        out = null
        isStarted = false
    }

    private fun writeString(s: String) {
        for (i in s.indices) {
            out?.write(s[i].code)
        }
    }

    private fun writeShort(value: Int) {
        out?.write(value and 0xFF)
        out?.write((value shr 8) and 0xFF)
    }

    private fun writeLSD() {
        writeShort(width)
        writeShort(height)
        out?.write(0x70) // Global color table flag = 0, color resolution = 7, sort = 0
        out?.write(0) // Background color index
        out?.write(0) // Pixel aspect ratio
    }

    private fun writeGraphicCtrlExt(transparentIndex: Int = -1) {
        out?.write(0x21) // Extension introducer
        out?.write(0xF9) // GCE label
        out?.write(4) // Block size
        // Packed fields: a see-through frame is cleared before the next one (disposal 2), or the
        // previous frame would show through its transparent pixels.
        out?.write(if (transparentIndex >= 0) (2 shl 2) or 1 else 0)
        // Hundredths of a second; the rounding error is carried over so 33 ms frames average 3.3.
        val exact = delayMs + delayCarryMs
        val cs = ((exact + 5) / 10).coerceAtLeast(1)
        delayCarryMs = exact - cs * 10
        writeShort(cs)
        out?.write(transparentIndex.coerceAtLeast(0)) // Transparent color index
        out?.write(0) // Block terminator
    }

    private fun writeNetscapeExt() {
        out?.write(0x21)
        out?.write(0xFF)
        out?.write(11)
        writeString("NETSCAPE2.0")
        out?.write(3)
        out?.write(1)
        writeShort(repeatCount)
        out?.write(0)
    }

    private fun writeImageDesc() {
        out?.write(0x2C) // Image separator
        writeShort(0) // Image left position
        writeShort(0) // Image top position
        writeShort(width)
        writeShort(height)
        out?.write(0x87) // Local color table flag = 1, size = 7 (256 colors)
    }

    private fun writeColorTable(table: ByteArray) {
        out?.write(table, 0, table.size)
        val pad = 768 - table.size
        for (i in 0 until pad) {
            out?.write(0)
        }
    }
}
