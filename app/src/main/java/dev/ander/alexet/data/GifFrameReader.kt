package dev.ander.alexet.data

/**
 * Reads a GIF frame by frame (the platform only offers an animated drawable that can't be stepped
 * through): each frame as the whole picture after it is drawn, with its delay. Disposal (keep,
 * clear to transparent, restore) and transparency are followed as browsers do.
 */
object GifFrameReader {

    class Info(val width: Int, val height: Int, val frameCount: Int)

    /** Size and number of frames (their data is skipped, not decoded); null when not a readable GIF. */
    fun info(data: ByteArray): Info? {
        val c = Cursor(data)
        val header = readHeader(c) ?: return null
        var frames = 0
        while (true) {
            when (c.u8()) {
                0x21 -> {
                    c.u8() // label
                    skipSubBlocks(c)
                }
                0x2C -> {
                    c.skip(8)
                    val packed = c.u8()
                    if (packed < 0) break
                    if (packed and 0x80 != 0) c.skip(3 * (1 shl ((packed and 7) + 1)))
                    c.u8() // LZW minimum code size
                    skipSubBlocks(c)
                    frames++
                }
                0x00 -> continue // padding some writers leave between blocks
                else -> break // trailer, end of data or damage
            }
        }
        return Info(header.width, header.height, frames)
    }

    /**
     * Calls [onFrame] with each frame ([pixels]: width x height ARGB, the same array every time: copy
     * what is kept) and its delay in ms; stops early when [onFrame] returns false. Returns the
     * number of frames given, or -1 when [data] is not a GIF.
     */
    fun forEachFrame(
        data: ByteArray,
        onFrame: (pixels: IntArray, width: Int, height: Int, delayMs: Int) -> Boolean,
    ): Int {
        val c = Cursor(data)
        val header = readHeader(c) ?: return -1
        val w = header.width
        val h = header.height
        if (w <= 0 || h <= 0 || w.toLong() * h > MAX_PIXELS) return -1
        val canvas = IntArray(w * h)
        var saved: IntArray? = null
        var delayCs = 0
        var disposal = 0
        var transparent = -1
        // What the previous frame asks for before the next one is drawn.
        var prevDisposal = 0
        var prevX = 0
        var prevY = 0
        var prevW = 0
        var prevH = 0
        var given = 0
        var indices = ByteArray(0)
        while (true) {
            when (c.u8()) {
                0x21 -> {
                    if (c.u8() == 0xF9) {
                        val size = c.u8()
                        val packed = c.u8()
                        delayCs = c.u16()
                        val index = c.u8()
                        disposal = (packed shr 2) and 7
                        transparent = if (packed and 1 != 0) index else -1
                        c.skip((size - 4).coerceAtLeast(0))
                    }
                    skipSubBlocks(c)
                }
                0x2C -> {
                    val ix = c.u16()
                    val iy = c.u16()
                    val iw = c.u16()
                    val ih = c.u16()
                    val packed = c.u8()
                    if (packed < 0) break
                    val table = if (packed and 0x80 != 0) readColorTable(c, packed and 7) else header.globalTable
                    val interlaced = packed and 0x40 != 0
                    val minCodeSize = c.u8()
                    // A frame far bigger than any picture (up to 65535 x 65535) would need gigabytes
                    // or overflow the size: skipped like a broken one.
                    if (iw <= 0 || ih <= 0 || iw.toLong() * ih > MAX_PIXELS || minCodeSize !in 1..11) {
                        skipSubBlocks(c)
                        continue
                    }
                    if (indices.size < iw * ih) indices = ByteArray(iw * ih)
                    indices.fill(0, 0, iw * ih)
                    decodeLzw(c, minCodeSize, indices, iw * ih)

                    when (prevDisposal) {
                        2 -> clearRect(canvas, w, h, prevX, prevY, prevW, prevH)
                        3 -> saved?.copyInto(canvas)
                    }
                    if (disposal == 3) {
                        saved = (saved?.takeIf { it.size == canvas.size } ?: IntArray(canvas.size)).also { canvas.copyInto(it) }
                    }
                    if (table != null) {
                        drawFrame(canvas, w, h, indices, ix, iy, iw, ih, interlaced, table, transparent)
                    }
                    given++
                    // Browsers show a "0 / 10 ms" delay as 100 ms.
                    val delayMs = (delayCs * 10).let { if (it <= 10) DEFAULT_DELAY_MS else it }
                    if (!onFrame(canvas, w, h, delayMs)) return given
                    prevDisposal = disposal
                    prevX = ix
                    prevY = iy
                    prevW = iw
                    prevH = ih
                    delayCs = 0
                    disposal = 0
                    transparent = -1
                }
                0x00 -> continue
                else -> break
            }
        }
        return given
    }

    // ---------- reading ----------

    private const val DEFAULT_DELAY_MS = 100
    /** Larger pictures are refused (a 16k x 16k GIF would need 1 GB per frame). */
    private const val MAX_PIXELS = 40_000_000L

    private class Header(val width: Int, val height: Int, val globalTable: IntArray?)

    private class Cursor(val d: ByteArray) {
        var p = 0
        fun u8(): Int = if (p < d.size) d[p++].toInt() and 0xFF else -1
        fun u16(): Int {
            val lo = u8()
            val hi = u8()
            return if (lo < 0 || hi < 0) 0 else lo or (hi shl 8)
        }
        fun skip(n: Int) {
            p = (p + n).coerceAtMost(d.size)
        }
    }

    private fun readHeader(c: Cursor): Header? {
        if (c.d.size < 13) return null
        val sig = String(c.d, 0, 6, Charsets.ISO_8859_1)
        if (sig != "GIF87a" && sig != "GIF89a") return null
        c.skip(6)
        val w = c.u16()
        val h = c.u16()
        val packed = c.u8()
        c.skip(2) // background index, aspect
        val table = if (packed and 0x80 != 0) readColorTable(c, packed and 7) else null
        return Header(w, h, table)
    }

    private fun readColorTable(c: Cursor, sizeBits: Int): IntArray {
        val n = 1 shl (sizeBits + 1)
        return IntArray(n) {
            val r = c.u8().coerceAtLeast(0)
            val g = c.u8().coerceAtLeast(0)
            val b = c.u8().coerceAtLeast(0)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun skipSubBlocks(c: Cursor) {
        while (true) {
            val n = c.u8()
            if (n <= 0) return
            c.skip(n)
        }
    }

    private fun clearRect(canvas: IntArray, w: Int, h: Int, x: Int, y: Int, rw: Int, rh: Int) {
        for (row in y until minOf(h, y + rh)) {
            if (row < 0) continue
            val from = row * w + x.coerceIn(0, w)
            val to = row * w + (x + rw).coerceIn(0, w)
            if (to > from) canvas.fill(0, from, to)
        }
    }

    private fun drawFrame(
        canvas: IntArray,
        w: Int,
        h: Int,
        indices: ByteArray,
        ix: Int,
        iy: Int,
        iw: Int,
        ih: Int,
        interlaced: Boolean,
        table: IntArray,
        transparent: Int,
    ) {
        var source = 0
        // Interlaced rows come in four passes: every 8th from 0, every 8th from 4, every 4th from 2,
        // every 2nd from 1.
        var pass = 0
        var step = if (interlaced) 8 else 1
        var row = 0
        for (r in 0 until ih) {
            val y = iy + row
            if (y in 0 until h) {
                val base = y * w
                for (col in 0 until iw) {
                    val index = indices[source + col].toInt() and 0xFF
                    if (index == transparent || index >= table.size) continue
                    val x = ix + col
                    if (x in 0 until w) canvas[base + x] = table[index]
                }
            }
            source += iw
            row += step
            // A short frame can skip a pass entirely (no row 4 in a 3-row frame).
            while (interlaced && row >= ih && pass < 3) {
                pass++
                when (pass) {
                    1 -> { row = 4; step = 8 }
                    2 -> { row = 2; step = 4 }
                    3 -> { row = 1; step = 2 }
                }
            }
        }
    }

    /**
     * GIF LZW data (sub-blocks from the cursor) into [out] (up to [count] indices). The rest of the
     * frame's data, if any, is skipped, so the cursor ends after its terminator.
     */
    private fun decodeLzw(c: Cursor, minCodeSize: Int, out: ByteArray, count: Int) {
        val clear = 1 shl minCodeSize
        val end = clear + 1
        var codeSize = minCodeSize + 1
        var available = clear + 2
        val prefix = IntArray(MAX_CODES)
        val suffix = ByteArray(MAX_CODES)
        val stack = ByteArray(MAX_CODES + 1)
        for (i in 0 until clear) suffix[i] = i.toByte()
        var old = -1
        var first = 0
        var written = 0
        var datum = 0
        var bits = 0
        var blockLeft = 0
        var terminated = false

        decode@ while (written < count) {
            while (bits < codeSize) {
                if (blockLeft == 0) {
                    blockLeft = c.u8()
                    if (blockLeft <= 0) {
                        terminated = true
                        break@decode
                    }
                }
                val b = c.u8()
                if (b < 0) {
                    terminated = true
                    break@decode
                }
                datum = datum or (b shl bits)
                bits += 8
                blockLeft--
            }
            val code = datum and ((1 shl codeSize) - 1)
            datum = datum ushr codeSize
            bits -= codeSize

            if (code == clear) {
                codeSize = minCodeSize + 1
                available = clear + 2
                old = -1
                continue
            }
            if (code == end) break
            if (old == -1) {
                if (code >= clear) break // damaged data
                out[written++] = suffix[code]
                old = code
                first = code
                continue
            }
            if (code > available) break // damaged data
            var top = 0
            var current = code
            if (code == available) {
                stack[top++] = first.toByte()
                current = old
            }
            while (current >= clear) {
                if (top >= MAX_CODES || current >= MAX_CODES) break@decode
                stack[top++] = suffix[current]
                current = prefix[current]
            }
            first = suffix[current].toInt() and 0xFF
            stack[top++] = first.toByte()
            if (available < MAX_CODES) {
                prefix[available] = old
                suffix[available] = first.toByte()
                available++
                if (available >= (1 shl codeSize) && codeSize < 12) codeSize++
            }
            old = code
            while (top > 0 && written < count) out[written++] = stack[--top]
        }
        if (!terminated) {
            c.skip(blockLeft)
            skipSubBlocks(c)
        }
    }

    private const val MAX_CODES = 4096
}
