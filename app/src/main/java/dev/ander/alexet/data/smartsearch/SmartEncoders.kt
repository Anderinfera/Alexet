package dev.ander.alexet.data.smartsearch

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import dev.ander.alexet.data.GalleryMedia
import dev.ander.alexet.data.ImageEditEngine
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/** The text tower's token embedding table, int8 rows mapped from disk (~200 MB, never read whole). */
class TokenTable(dir: File, private val manifest: SmartManifest) : Closeable {
    private val file = RandomAccessFile(File(dir, SmartManifest.TOKEN_TABLE), "r")
    private val table: MappedByteBuffer = file.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
    private val scales: FloatArray = File(dir, SmartManifest.TOKEN_SCALES).readBytes().let { bytes ->
        val out = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        out
    }

    /** Rows of [ids] into [out] ([ids].size x dim floats). */
    fun fill(ids: IntArray, out: FloatArray) {
        val dim = manifest.dim
        val row = ByteArray(dim)
        val view = table.duplicate()
        for ((p, id) in ids.withIndex()) {
            require(id in 0 until manifest.vocabSize) { "Token id $id out of range" }
            view.position(id * dim)
            view.get(row)
            val s = scales[id]
            val base = p * dim
            for (i in 0 until dim) out[base + i] = row[i] * s
        }
    }

    override fun close() {
        file.close()
    }
}

/** Text -> normalized embedding. Runs on the CPU: one query at a time, the GPU would not pay off. */
class SmartTextEncoder(dir: File, private val manifest: SmartManifest) : Closeable {
    val tokenizer: GemmaTokenizer = GemmaTokenizer.load(ByteBuffer.wrap(File(dir, SmartManifest.VOCAB).readBytes()))
    private val table = TokenTable(dir, manifest)
    private val model = CompiledModel.create(File(dir, SmartManifest.TEXT_ENCODER).absolutePath, CompiledModel.Options(Accelerator.CPU))
    private val inputs: List<TensorBuffer> = model.createInputBuffers()
    private val outputs: List<TensorBuffer> = model.createOutputBuffers()
    private val embeds = FloatArray(manifest.seqLen * manifest.dim)

    fun tokens(text: String): IntArray =
        tokenizer.encodePadded(text, manifest.seqLen, manifest.padId, manifest.eosId, manifest.lowercase)

    fun encode(text: String): FloatArray = encodeIds(tokens(text))

    @Synchronized
    fun encodeIds(ids: IntArray): FloatArray {
        table.fill(ids, embeds)
        inputs[0].writeFloat(embeds)
        model.run(inputs, outputs)
        return outputs[0].readFloat().normalizedL2()
    }

    override fun close() {
        inputs.forEach { it.close() }
        outputs.forEach { it.close() }
        model.close()
        table.close()
    }
}

/** Square RGB image -> normalized embedding. */
class SmartImageEncoder(dir: File, private val manifest: SmartManifest, val accelerator: Accelerator) : Closeable {
    private val model = CompiledModel.create(File(dir, SmartManifest.IMAGE_ENCODER).absolutePath, CompiledModel.Options(accelerator))
    private val inputs: List<TensorBuffer> = model.createInputBuffers()
    private val outputs: List<TensorBuffer> = model.createOutputBuffers()
    private val pixels = FloatArray(3 * manifest.imageSize * manifest.imageSize)

    /** [argb]: imageSize x imageSize pixels, row by row. */
    @Synchronized
    fun encode(argb: IntArray): FloatArray {
        toPixels(argb, manifest.imageSize, pixels)
        inputs[0].writeFloat(pixels)
        model.run(inputs, outputs)
        return outputs[0].readFloat().normalizedL2()
    }

    override fun close() {
        inputs.forEach { it.close() }
        outputs.forEach { it.close() }
        model.close()
    }

    companion object {
        /** ARGB -> NCHW RGB in [-1, 1]: (x / 255 - 0.5) / 0.5, as SigLIP's image processor. */
        fun toPixels(argb: IntArray, size: Int, out: FloatArray) {
            val plane = size * size
            for (i in 0 until plane) {
                val p = argb[i]
                out[i] = ((p shr 16) and 0xFF) / 127.5f - 1f
                out[plane + i] = ((p shr 8) and 0xFF) / 127.5f - 1f
                out[2 * plane + i] = (p and 0xFF) / 127.5f - 1f
            }
        }
    }
}

object SmartImageLoader {
    /**
     * The photo upright (EXIF), squeezed to size x size without cropping (SigLIP 2's
     * preprocessing), as ARGB pixels; null when it can't be decoded.
     */
    fun load(context: Context, media: GalleryMedia, size: Int): IntArray? {
        val file = media.absolutePath?.let(::File)?.takeIf { it.isFile && it.canRead() }
        val decoded = runCatching { ImageEditEngine.decodeSourceBitmap(context, file, media.uri, maxDim = size * 2) }.getOrNull()
            ?: return null
        val argb = if (decoded.config == Bitmap.Config.ARGB_8888) decoded else decoded.copy(Bitmap.Config.ARGB_8888, false).also { decoded.recycle() }
        argb ?: return null
        val scaled = Bitmap.createScaledBitmap(argb, size, size, true)
        if (scaled !== argb) argb.recycle()
        val out = IntArray(size * size)
        scaled.getPixels(out, 0, size, 0, 0, size, size)
        scaled.recycle()
        return out
    }
}

/** The pack's selftest.bin: reference outputs of the original model, checked on this phone. */
class SmartSelfTest private constructor(
    private val images: List<Pair<IntArray, FloatArray>>,
    private val queries: List<Triple<String, IntArray, FloatArray>>,
) {
    /** Exact token ids, and the worst cosine against the reference vectors. */
    fun checkText(encoder: SmartTextEncoder): Pair<Boolean, Float> {
        var tokens = true
        var worst = 1f
        for ((text, ids, expected) in queries) {
            val got = encoder.tokens(text)
            if (!got.contentEquals(ids)) tokens = false
            worst = minOf(worst, dot(encoder.encodeIds(ids), expected.normalizedL2()))
        }
        return tokens to worst
    }

    fun checkImages(encoder: SmartImageEncoder): Float {
        var worst = 1f
        for ((argb, expected) in images) worst = minOf(worst, dot(encoder.encode(argb), expected.normalizedL2()))
        return worst
    }

    companion object {
        const val TEXT_MIN = 0.99f
        const val IMAGE_MIN = 0.98f

        fun load(dir: File, manifest: SmartManifest): SmartSelfTest {
            val b = ByteBuffer.wrap(File(dir, SmartManifest.SELFTEST).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { b.get(it) }
            require(String(magic, Charsets.US_ASCII) == "AXST" && b.int == 1) { "Bad selftest.bin" }
            val size = manifest.imageSize
            val dim = manifest.dim
            val images = List(b.int) {
                val rgb = ByteArray(size * size * 3).also { b.get(it) }
                val argb = IntArray(size * size) { i ->
                    (0xFF shl 24) or ((rgb[i * 3].toInt() and 0xFF) shl 16) or
                        ((rgb[i * 3 + 1].toInt() and 0xFF) shl 8) or (rgb[i * 3 + 2].toInt() and 0xFF)
                }
                argb to FloatArray(dim) { b.float }
            }
            val queries = List(b.int) {
                val text = ByteArray(b.short.toInt() and 0xFFFF).also { b.get(it) }.toString(Charsets.UTF_8)
                val ids = IntArray(manifest.seqLen) { b.int }
                Triple(text, ids, FloatArray(dim) { b.float })
            }
            return SmartSelfTest(images, queries)
        }

        private fun dot(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }
    }
}
