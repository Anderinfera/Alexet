package dev.ander.alexet.data.videoeditor

import java.io.OutputStream

/** Pure parts of the GIF encoder: palette quantization and LZW image data. */
internal object GifCodec {

    /** [transparentIndex]: the palette entry left for see-through pixels, -1 when there are none. */
    class Indexed(val palette: ByteArray, val indices: ByteArray, val transparentIndex: Int = -1)

    /**
     * Median cut over a 15-bit color histogram: boxes are split at the population median of their
     * longest axis until there are 256 (or nothing left to split); each box becomes the average of
     * its colors. Pixels map to the nearest palette entry, memoized per 15-bit color.
     * With [keepTransparency], pixels under half alpha get entry 255 (and the colours 255 entries).
     */
    /**
     * Work arrays an encoder keeps for all its frames (two 128 KB tables and the index array were
     * made again for every frame). The [Indexed.indices] made with them is reused by the next call.
     */
    class Buffers {
        internal val hist = IntArray(32768)
        internal val lookup = IntArray(32768)
        internal var indices = ByteArray(0)
    }

    fun quantize(argb: IntArray, keepTransparency: Boolean = false, buffers: Buffers? = null): Indexed {
        val hasTransparent = keepTransparency && argb.any { (it ushr 24) < 128 }
        val maxColors = if (hasTransparent) 255 else 256
        val hist = buffers?.hist?.also { it.fill(0) } ?: IntArray(32768)
        for (c in argb) if (!hasTransparent || (c ushr 24) >= 128) hist[key(c)]++

        var colorCount = 0
        for (h in hist) if (h > 0) colorCount++
        val colors = IntArray(colorCount)
        var n = 0
        for (k in hist.indices) if (hist[k] > 0) colors[n++] = k

        val boxes = ArrayList<IntArray>() // [start, end) ranges into `colors`
        // Score (range x population) and longest axis per box, worked out once per box: they were
        // recomputed for every box on each of the up to 255 splits, for every frame.
        val scores = ArrayList<Long>()
        val axes = ArrayList<Int>()
        fun addBox(box: IntArray, at: Int = boxes.size) {
            val (range, axis) = rangeOf(colors, box)
            val score = if (box[1] - box[0] < 2) 0L else range.toLong() * population(hist, colors, box)
            if (at == boxes.size) {
                boxes += box
                scores += score
                axes += axis
            } else {
                boxes[at] = box
                scores[at] = score
                axes[at] = axis
            }
        }
        addBox(intArrayOf(0, colorCount))
        while (boxes.size < maxColors) {
            var best = -1
            var bestScore = 0L
            for (b in boxes.indices) {
                val score = scores[b]
                if (score > bestScore) {
                    bestScore = score
                    best = b
                }
            }
            if (best < 0) break
            val box = boxes[best]
            val axis = axes[best]
            sortByAxis(colors, box[0], box[1], axis)
            val half = population(hist, colors, box) / 2
            var acc = 0L
            var split = box[1] - 1
            for (i in box[0] until box[1] - 1) {
                acc += hist[colors[i]]
                if (acc >= half) {
                    split = i + 1
                    break
                }
            }
            addBox(intArrayOf(box[0], split), at = best)
            addBox(intArrayOf(split, box[1]))
        }

        val palette = ByteArray(256 * 3)
        val pr = IntArray(boxes.size)
        val pg = IntArray(boxes.size)
        val pb = IntArray(boxes.size)
        for ((b, box) in boxes.withIndex()) {
            var r = 0L
            var g = 0L
            var bl = 0L
            var w = 0L
            for (i in box[0] until box[1]) {
                val k = colors[i]
                val h = hist[k].toLong()
                r += expand((k shr 10) and 31) * h
                g += expand((k shr 5) and 31) * h
                bl += expand(k and 31) * h
                w += h
            }
            if (w == 0L) w = 1
            pr[b] = (r / w).toInt()
            pg[b] = (g / w).toInt()
            pb[b] = (bl / w).toInt()
            palette[b * 3] = pr[b].toByte()
            palette[b * 3 + 1] = pg[b].toByte()
            palette[b * 3 + 2] = pb[b].toByte()
        }

        val lookup = buffers?.lookup?.also { it.fill(-1) } ?: IntArray(32768) { -1 }
        val indices = if (buffers != null) {
            buffers.indices.takeIf { it.size == argb.size } ?: ByteArray(argb.size).also { buffers.indices = it }
        } else {
            ByteArray(argb.size)
        }
        for (i in argb.indices) {
            if (hasTransparent && (argb[i] ushr 24) < 128) {
                indices[i] = TRANSPARENT_INDEX.toByte()
                continue
            }
            val k = key(argb[i])
            var idx = lookup[k]
            if (idx < 0) {
                val r = expand((k shr 10) and 31)
                val g = expand((k shr 5) and 31)
                val bl = expand(k and 31)
                var bestD = Int.MAX_VALUE
                idx = 0
                for (p in pr.indices) {
                    val dr = pr[p] - r
                    val dg = pg[p] - g
                    val db = pb[p] - bl
                    val d = dr * dr * 2 + dg * dg * 4 + db * db * 3
                    if (d < bestD) {
                        bestD = d
                        idx = p
                    }
                }
                lookup[k] = idx
            }
            indices[i] = idx.toByte()
        }
        return Indexed(palette, indices, if (hasTransparent) TRANSPARENT_INDEX else -1)
    }

    private const val TRANSPARENT_INDEX = 255

    private fun key(c: Int): Int = ((c shr 19) and 31 shl 10) or ((c shr 11) and 31 shl 5) or ((c shr 3) and 31)

    private fun expand(v: Int): Int = (v shl 3) or (v shr 2)

    private fun population(hist: IntArray, colors: IntArray, box: IntArray): Long {
        var p = 0L
        for (i in box[0] until box[1]) p += hist[colors[i]]
        return p
    }

    /** (largest channel range, its axis: 0 = r, 1 = g, 2 = b). */
    private fun rangeOf(colors: IntArray, box: IntArray): Pair<Int, Int> {
        var minR = 31; var maxR = 0; var minG = 31; var maxG = 0; var minB = 31; var maxB = 0
        for (i in box[0] until box[1]) {
            val k = colors[i]
            val r = (k shr 10) and 31
            val g = (k shr 5) and 31
            val b = k and 31
            if (r < minR) minR = r
            if (r > maxR) maxR = r
            if (g < minG) minG = g
            if (g > maxG) maxG = g
            if (b < minB) minB = b
            if (b > maxB) maxB = b
        }
        val rr = maxR - minR
        val gr = maxG - minG
        val br = maxB - minB
        return when {
            gr >= rr && gr >= br -> gr to 1
            rr >= br -> rr to 0
            else -> br to 2
        }
    }

    private fun sortByAxis(colors: IntArray, from: Int, to: Int, axis: Int) {
        val shift = when (axis) {
            0 -> 10
            1 -> 5
            else -> 0
        }
        // Colors are 15-bit: the axis value above them sorts by axis, then by color, on plain ints.
        val keys = IntArray(to - from) { i ->
            val c = colors[from + i]
            (((c shr shift) and 31) shl 15) or c
        }
        keys.sort()
        for (i in keys.indices) colors[from + i] = keys[i] and 0x7FFF
    }

    /**
     * GIF LZW image data for 8-bit [indices]: code size byte, sub-blocks, terminator. Follows the
     * reference GIF compressor: the code width grows when the next code to be assigned no longer
     * fits, checked after each code is written, as decoders expect.
     */
    fun writeLzw(indices: ByteArray, os: OutputStream) {
        LzwWriter(indices).write(os)
    }

    private class LzwWriter(private val pixels: ByteArray) {
        private val maxBits = 12
        private val maxMaxCode = 1 shl maxBits
        private val hsize = 5003
        private val htab = IntArray(hsize)
        private val codetab = IntArray(hsize)

        private var nBits = 0
        private var maxCode = 0
        private var initBits = 0
        private var clearCode = 0
        private var eofCode = 0
        private var freeEnt = 0
        private var clearFlag = false

        private var curAccum = 0
        private var curBits = 0
        private val block = ByteArray(256)
        private var blockLen = 0

        fun write(os: OutputStream) {
            val initCodeSize = 8
            os.write(initCodeSize)
            initBits = initCodeSize + 1
            nBits = initBits
            maxCode = (1 shl nBits) - 1
            clearCode = 1 shl initCodeSize
            eofCode = clearCode + 1
            freeEnt = clearCode + 2
            htab.fill(-1)

            output(clearCode, os)
            if (pixels.isEmpty()) {
                output(eofCode, os)
                os.write(0)
                return
            }
            var ent = pixels[0].toInt() and 0xFF
            val hshift = 4 // 8 - log2 steps of hsize up to 65536
            var p = 1
            outer@ while (p < pixels.size) {
                val c = pixels[p++].toInt() and 0xFF
                val fcode = (c shl maxBits) + ent
                var i = (c shl hshift) xor ent
                if (htab[i] == fcode) {
                    ent = codetab[i]
                    continue
                } else if (htab[i] >= 0) {
                    var disp = hsize - i
                    if (i == 0) disp = 1
                    do {
                        i -= disp
                        if (i < 0) i += hsize
                        if (htab[i] == fcode) {
                            ent = codetab[i]
                            continue@outer
                        }
                    } while (htab[i] >= 0)
                }
                output(ent, os)
                ent = c
                if (freeEnt < maxMaxCode) {
                    codetab[i] = freeEnt++
                    htab[i] = fcode
                } else {
                    htab.fill(-1)
                    freeEnt = clearCode + 2
                    clearFlag = true
                    output(clearCode, os)
                }
            }
            output(ent, os)
            output(eofCode, os)
            os.write(0)
        }

        private fun output(code: Int, os: OutputStream) {
            curAccum = if (curBits > 0) curAccum or (code shl curBits) else code
            curBits += nBits
            while (curBits >= 8) {
                byteOut(curAccum and 0xFF, os)
                curAccum = curAccum ushr 8
                curBits -= 8
            }
            if (freeEnt > maxCode || clearFlag) {
                if (clearFlag) {
                    nBits = initBits
                    maxCode = (1 shl nBits) - 1
                    clearFlag = false
                } else {
                    nBits++
                    maxCode = if (nBits == maxBits) maxMaxCode else (1 shl nBits) - 1
                }
            }
            if (code == eofCode) {
                while (curBits > 0) {
                    byteOut(curAccum and 0xFF, os)
                    curAccum = curAccum ushr 8
                    curBits -= 8
                }
                flushBlock(os)
            }
        }

        private fun byteOut(b: Int, os: OutputStream) {
            block[blockLen++] = b.toByte()
            if (blockLen >= 254) flushBlock(os)
        }

        private fun flushBlock(os: OutputStream) {
            if (blockLen > 0) {
                os.write(blockLen)
                os.write(block, 0, blockLen)
                blockLen = 0
            }
        }
    }
}
