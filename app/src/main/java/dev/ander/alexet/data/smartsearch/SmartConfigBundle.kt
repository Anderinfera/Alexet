package dev.ander.alexet.data.smartsearch

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The settings export with the AI model installed: a zip holding config.json (the usual
 * export), model/ (the model pack's files, stored) and index.bin (the photo index).
 */
object SmartConfigBundle {
    const val CONFIG_ENTRY = "config.json"
    const val MAX_CONFIG_BYTES = 2L * 1024 * 1024
    private const val MAX_INDEX_BYTES = 4L shl 30

    class Imported(val configJson: String?, val manifest: SmartManifest?, val indexFile: File?)

    fun isZip(first: Int, second: Int): Boolean = first == 'P'.code && second == 'K'.code

    suspend fun write(out: OutputStream, configJson: String, smart: SmartSearch) {
        val files = smart.modelFiles() ?: throw IOException("No AI model installed")
        val zip = ZipOutputStream(out.buffered(SmartPackWriter.BUFFER))
        zip.putNextEntry(ZipEntry(CONFIG_ENTRY))
        zip.write(configJson.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        // The model files don't compress (weights): stored as they are, quickly.
        zip.setLevel(Deflater.NO_COMPRESSION)
        for ((name, file) in files) {
            zip.putNextEntry(ZipEntry(SmartSearch.MODEL_PREFIX + name))
            file.inputStream().use { it.copyTo(zip, SmartPackWriter.BUFFER) }
            zip.closeEntry()
        }
        zip.setLevel(Deflater.DEFAULT_COMPRESSION)
        zip.putNextEntry(ZipEntry(SmartSearch.INDEX_ENTRY))
        smart.writeIndex(zip)
        zip.closeEntry()
        zip.finish()
        zip.flush()
    }

    /**
     * Reads a bundle. Model files go to [staging] (SmartSearch.newStaging) and are checked against their
     * manifest; the index is kept in [tempDir] for [SmartSearch.readIndex]. Unknown entries are
     * skipped and entry names are never used as paths.
     */
    fun read(input: InputStream, staging: () -> File, tempDir: File): Imported {
        var config: String? = null
        var writer: SmartPackWriter? = null
        var index: File? = null
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                when {
                    entry.isDirectory -> Unit
                    name == CONFIG_ENTRY -> {
                        if (config != null) throw IOException("Duplicate $CONFIG_ENTRY")
                        config = readLimited(zip, MAX_CONFIG_BYTES).toString(Charsets.UTF_8)
                    }
                    name.startsWith(SmartSearch.MODEL_PREFIX) -> {
                        val w = writer ?: SmartPackWriter(staging()).also { writer = it }
                        w.accept(name.removePrefix(SmartSearch.MODEL_PREFIX), zip)
                    }
                    name == SmartSearch.INDEX_ENTRY -> {
                        if (index != null) throw IOException("Duplicate ${SmartSearch.INDEX_ENTRY}")
                        val file = File(tempDir, "smart-index-import.bin")
                        file.outputStream().use { copyLimited(zip, it, MAX_INDEX_BYTES) { _, _ -> } }
                        index = file
                    }
                }
                zip.closeEntry()
            }
        }
        return Imported(config, writer?.finish(), index)
    }
}
