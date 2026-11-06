package dev.ander.alexet.data.smartsearch

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** The model pack's manifest.json (tools/smart-search/build_pack.py writes it). */
data class SmartManifest(
    val format: Int,
    val modelId: String,
    val dim: Int,
    val seqLen: Int,
    val imageSize: Int,
    val vocabSize: Int,
    val padId: Int,
    val eosId: Int,
    val lowercase: Boolean,
    val logitScale: Float,
    val logitBias: Float,
    val files: Map<String, PackFile>,
) {
    data class PackFile(val size: Long, val sha256: String)

    val totalBytes: Long get() = files.values.sumOf { it.size }

    /** SigLIP's match probability of a cosine similarity. */
    fun probability(cosine: Float): Float =
        (1.0 / (1.0 + Math.exp(-(logitScale * cosine + logitBias).toDouble()))).toFloat()

    companion object {
        const val FORMAT = 1
        const val MANIFEST = "manifest.json"
        const val IMAGE_ENCODER = "image_encoder.tflite"
        const val TEXT_ENCODER = "text_encoder.tflite"
        const val TOKEN_TABLE = "token_embeddings.i8"
        const val TOKEN_SCALES = "token_scales.f32"
        const val VOCAB = "vocab.bin"
        const val SELFTEST = "selftest.bin"
        const val LICENSE = "LICENSE"
        const val NOTICE = "NOTICE"
        val FILES = listOf(IMAGE_ENCODER, TEXT_ENCODER, TOKEN_TABLE, TOKEN_SCALES, VOCAB, SELFTEST, LICENSE, NOTICE)

        /** Larger than any pack of this format; a bigger file is refused before it fills the disk. */
        const val MAX_FILE_BYTES = 1L shl 30
        const val MAX_MANIFEST_BYTES = 64 * 1024

        fun parse(text: String): SmartManifest {
            val o = JSONObject(text)
            val files = o.getJSONObject("files").let { f ->
                f.keys().asSequence().associateWith { name ->
                    val e = f.getJSONObject(name)
                    PackFile(e.getLong("size"), e.getString("sha256").lowercase())
                }
            }
            return SmartManifest(
                format = o.getInt("format"),
                modelId = o.getString("modelId"),
                dim = o.getInt("dim"),
                seqLen = o.getInt("seqLen"),
                imageSize = o.getInt("imageSize"),
                vocabSize = o.getInt("vocabSize"),
                padId = o.getInt("padId"),
                eosId = o.getInt("eosId"),
                lowercase = o.optBoolean("lowercase", true),
                logitScale = o.getDouble("logitScale").toFloat(),
                logitBias = o.getDouble("logitBias").toFloat(),
                files = files,
            ).also { it.validate() }
        }
    }

    private fun validate() {
        if (format != FORMAT) throw IOException("Unsupported model pack format $format")
        if (!MODEL_ID.matches(modelId)) throw IOException("Bad model id")
        if (dim !in 1..4096 || seqLen !in 2..1024 || imageSize !in 16..2048 || vocabSize !in 1..2_000_000) {
            throw IOException("Bad model dimensions")
        }
        if (files.keys != FILES.toSet()) throw IOException("Unexpected model pack files ${files.keys}")
        if (files.values.any { it.size !in 0..MAX_FILE_BYTES || !SHA256.matches(it.sha256) }) {
            throw IOException("Bad model pack file entry")
        }
        if (files.getValue(TOKEN_TABLE).size != vocabSize.toLong() * dim) throw IOException("Token table size mismatch")
        if (files.getValue(TOKEN_SCALES).size != vocabSize.toLong() * 4) throw IOException("Token scales size mismatch")
    }
}

private val MODEL_ID = Regex("[A-Za-z0-9._-]{1,64}")
private val SHA256 = Regex("[0-9a-f]{64}")

/**
 * Writes the files of a model pack into a directory. Entry names come from a fixed list and
 * are never used as paths, so a crafted archive can't write elsewhere; every file is checked
 * against the manifest's size and SHA-256.
 */
class SmartPackWriter(private val dir: File) {
    private val written = HashMap<String, String>()
    private var manifestText: String? = null

    /** False for a name that is not a pack file (the caller skips the entry). */
    fun accept(name: String, input: InputStream): Boolean {
        if (name == SmartManifest.MANIFEST) {
            if (manifestText != null) throw IOException("Duplicate manifest")
            val bytes = readLimited(input, SmartManifest.MAX_MANIFEST_BYTES.toLong())
            manifestText = bytes.toString(Charsets.UTF_8)
            File(dir, SmartManifest.MANIFEST).writeBytes(bytes)
            return true
        }
        if (name !in SmartManifest.FILES) return false
        if (name in written) throw IOException("Duplicate entry $name")
        val digest = MessageDigest.getInstance("SHA-256")
        File(dir, name).outputStream().buffered(BUFFER).use { out ->
            copyLimited(input, out, SmartManifest.MAX_FILE_BYTES) { buf, n -> digest.update(buf, 0, n) }
        }
        written[name] = digest.digest().toHex()
        return true
    }

    /** The checked manifest, once every file arrived intact. */
    fun finish(): SmartManifest {
        val manifest = SmartManifest.parse(manifestText ?: throw IOException("No manifest in the model pack"))
        for ((name, expected) in manifest.files) {
            val sha = written[name] ?: throw IOException("Missing $name")
            if (File(dir, name).length() != expected.size || sha != expected.sha256) {
                throw IOException("$name is damaged")
            }
        }
        return manifest
    }

    companion object {
        const val BUFFER = 128 * 1024

        /** A whole .alexetai file. */
        fun readPack(input: InputStream, dir: File): SmartManifest {
            val writer = SmartPackWriter(dir)
            ZipInputStream(input.buffered(BUFFER)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) writer.accept(entry.name, zip)
                    zip.closeEntry()
                }
            }
            return writer.finish()
        }
    }
}

internal fun readLimited(input: InputStream, max: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    copyLimited(input, out, max) { _, _ -> }
    return out.toByteArray()
}

internal fun copyLimited(input: InputStream, out: OutputStream, max: Long, onChunk: (ByteArray, Int) -> Unit): Long {
    val buf = ByteArray(SmartPackWriter.BUFFER)
    var total = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > max) throw IOException("Entry larger than $max bytes")
        onChunk(buf, n)
        out.write(buf, 0, n)
    }
    return total
}

internal fun ByteArray.toHex(): String {
    val chars = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        chars[i * 2] = HEX[v ushr 4]
        chars[i * 2 + 1] = HEX[v and 0x0F]
    }
    return String(chars)
}

private val HEX = "0123456789abcdef".toCharArray()
