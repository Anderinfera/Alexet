package dev.ander.alexet.data.videoeditor

import android.graphics.Bitmap
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Self-contained, pure Kotlin animated GIF encoder with median-cut color quantization and LZW compression.
 */
class GifEncoder : AutoCloseable {
    private var width: Int = 0
    private var height: Int = 0
    private var delayMs: Int = 100
    private var delayCarryMs: Int = 0
    private var repeatCount: Int = 0 // 0 = loop forever
    /** See-through pixels stay see-through (an edited GIF); video frames are opaque anyway. */
    var keepTransparency: Boolean = false
    var maxColors: Int = 256
    var temporalMaxDist: Int = 0
    var spatialMaxDist: Int = 0
    var optimizeDelta: Boolean = false
    private var out: OutputStream? = null
    private var ownsStream: Boolean = true
    private var isStarted = false
    /** One pixel buffer for all frames (they all have the GIF's size). */
    private var pixels: IntArray? = null
    /** The quantizer's tables, reused for every frame. */
    private val quantizeBuffers = GifCodec.Buffers()
    /** Source pixels last drawn onto the viewer's canvas, for delta comparison without quantization flicker. */
    private var prevCanvas: IntArray? = null
    private var deltaBuf: IntArray? = null

    fun start(file: File): Boolean {
        return try {
            resetState()
            out = BufferedOutputStream(FileOutputStream(file))
            ownsStream = true
            writeString("GIF89a") // Header
            isStarted = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun start(stream: OutputStream): Boolean {
        return try {
            resetState()
            out = stream
            ownsStream = false
            writeString("GIF89a") // Header
            isStarted = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun resetState() {
        width = 0
        height = 0
        delayCarryMs = 0
        prevCanvas = null
        deltaBuf = null
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
            val targetW = if (width == 0) bitmap.width else width
            val targetH = if (height == 0) bitmap.height else height
            if (targetW <= 0 || targetH <= 0) return false
            val pixels = this.pixels?.takeIf { it.size == targetW * targetH }
                ?: IntArray(targetW * targetH).also { this.pixels = it }
            scaled = if (bitmap.width != targetW || bitmap.height != targetH) {
                Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
            } else {
                bitmap
            }
            scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
            return addFramePixels(pixels, targetW, targetH)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            val s = scaled
            if (s != null && s !== bitmap) {
                s.recycle()
            }
        }
    }

    internal fun addFramePixels(framePixels: IntArray, frameWidth: Int, frameHeight: Int): Boolean {
        if (!isStarted || out == null || frameWidth <= 0 || frameHeight <= 0) return false
        return try {
            if (width == 0 || height == 0) {
                width = frameWidth
                height = frameHeight
                writeLSD()
                if (repeatCount >= 0) {
                    writeNetscapeExt()
                }
            }
            if (frameWidth != width || frameHeight != height) return false
            val total = width * height
            val canvas = prevCanvas
            val useDelta = optimizeDelta && canvas != null && canvas.size == total && (!keepTransparency || !hasTransparent(framePixels, total))
            if (!useDelta) {
                val indexed = GifCodec.quantize(
                    framePixels,
                    keepTransparency = keepTransparency,
                    buffers = quantizeBuffers,
                    maxColors = maxColors,
                    width = width,
                    spatialMaxDist = spatialMaxDist,
                    length = total,
                )
                val packed = when {
                    indexed.transparentIndex >= 0 -> (2 shl 2) or 1
                    optimizeDelta -> (1 shl 2)
                    else -> 0
                }
                writeGraphicCtrlExt(indexed.transparentIndex, packed)
                writeImageDesc(0, 0, width, height, indexed.colorTableSizeBits)
                writeColorTable(indexed.palette, indexed.colorTableSizeBits)
                GifCodec.writeLzw(indexed.indices, out ?: return false, indexed.lzwMinCodeSize, length = total)
                prevCanvas = if (optimizeDelta && indexed.transparentIndex < 0) {
                    val dst = canvas?.takeIf { it.size == total } ?: IntArray(total)
                    framePixels.copyInto(dst, 0, 0, total)
                    dst
                } else {
                    null
                }
                return true
            }

            var minX = width
            var minY = height
            var maxX = -1
            var maxY = -1
            var idx = 0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    if (GifCodec.colorDistSq(framePixels[idx], canvas[idx]) > temporalMaxDist) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                    idx++
                }
            }
            if (maxX < minX) {
                writeGraphicCtrlExt(0, (1 shl 2) or 1)
                writeImageDesc(0, 0, 1, 1, 1)
                out?.write(NO_OP_COLOR_TABLE)
                GifCodec.writeLzw(NO_OP_INDICES, out ?: return false, initCodeSize = 2, length = 1)
                return true
            }

            val ix = minX
            val iy = minY
            val iw = maxX - minX + 1
            val ih = maxY - minY + 1
            val count = iw * ih
            val sub = deltaBuf?.takeIf { it.size >= count } ?: IntArray(count).also { deltaBuf = it }
            var subIdx = 0
            for (y in iy until iy + ih) {
                var srcIdx = y * width + ix
                for (x in 0 until iw) {
                    val c = framePixels[srcIdx]
                    sub[subIdx++] = if (GifCodec.colorDistSq(c, canvas[srcIdx]) <= temporalMaxDist) {
                        0
                    } else {
                        c or (0xFF shl 24)
                    }
                    srcIdx++
                }
            }

            val indexed = GifCodec.quantize(
                sub,
                keepTransparency = true,
                buffers = quantizeBuffers,
                maxColors = maxColors,
                width = iw,
                spatialMaxDist = spatialMaxDist,
                length = count,
            )
            val transp = indexed.transparentIndex
            val pal = indexed.palette
            subIdx = 0
            for (y in iy until iy + ih) {
                var dstIdx = y * width + ix
                for (x in 0 until iw) {
                    val palIdx = indexed.indices[subIdx++].toInt() and 0xFF
                    if (palIdx != transp) {
                        canvas[dstIdx] = framePixels[dstIdx]
                    }
                    dstIdx++
                }
            }

            val packed = (1 shl 2) or (if (transp >= 0) 1 else 0)
            writeGraphicCtrlExt(transp, packed)
            writeImageDesc(ix, iy, iw, ih, indexed.colorTableSizeBits)
            writeColorTable(pal, indexed.colorTableSizeBits)
            GifCodec.writeLzw(indexed.indices, out ?: return false, indexed.lzwMinCodeSize, length = count)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    internal fun seedDeltaCanvas(framePixels: IntArray, frameWidth: Int, frameHeight: Int) {
        if (frameWidth <= 0 || frameHeight <= 0) return
        val total = frameWidth * frameHeight
        if (framePixels.size < total) return
        val dst = prevCanvas?.takeIf { it.size == total } ?: IntArray(total).also { prevCanvas = it }
        framePixels.copyInto(dst, 0, 0, total)
    }

    private fun hasTransparent(pixels: IntArray, total: Int): Boolean {
        for (i in 0 until total) {
            if ((pixels[i] ushr 24) < 128) return true
        }
        return false
    }

    fun finish(): Boolean {
        if (!isStarted || out == null) return false
        return try {
            out?.write(0x3B) // GIF trailer
            out?.flush()
            if (ownsStream) out?.close()
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
            if (ownsStream) out?.close()
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

    private fun writeGraphicCtrlExt(transparentIndex: Int = -1, packedOverride: Int? = null) {
        out?.write(0x21) // Extension introducer
        out?.write(0xF9) // GCE label
        out?.write(4) // Block size
        // Packed fields: a see-through frame is cleared before the next one (disposal 2), or the
        // previous frame would show through its transparent pixels.
        out?.write(packedOverride ?: if (transparentIndex >= 0) (2 shl 2) or 1 else 0)
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

    private fun writeImageDesc(left: Int, top: Int, w: Int, h: Int, colorTableSizeBits: Int) {
        out?.write(0x2C) // Image separator
        writeShort(left) // Image left position
        writeShort(top) // Image top position
        writeShort(w)
        writeShort(h)
        out?.write(0x80 or (colorTableSizeBits and 7)) // Local color table flag = 1
    }

    private fun writeColorTable(table: ByteArray, colorTableSizeBits: Int) {
        val bytes = 3 * (1 shl ((colorTableSizeBits and 7) + 1))
        val writeLen = minOf(table.size, bytes)
        out?.write(table, 0, writeLen)
        for (i in writeLen until bytes) {
            out?.write(0)
        }
    }

    private companion object {
        val NO_OP_COLOR_TABLE = ByteArray(12)
        val NO_OP_INDICES = byteArrayOf(0)
    }
}
