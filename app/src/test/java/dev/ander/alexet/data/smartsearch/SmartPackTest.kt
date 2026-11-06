package dev.ander.alexet.data.smartsearch

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Reading model packs and settings bundles: checked files only, nothing written outside. */
class SmartPackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val vocabSize = 2
    private val dim = 4

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    /** A tiny but well-formed pack: every file present with sizes the manifest can check. */
    private fun packFiles(): LinkedHashMap<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        for (name in SmartManifest.FILES) files[name] = "data of $name".toByteArray()
        files[SmartManifest.TOKEN_TABLE] = ByteArray(vocabSize * dim) { it.toByte() }
        files[SmartManifest.TOKEN_SCALES] = ByteArray(vocabSize * 4)
        return files
    }

    private fun manifest(files: Map<String, ByteArray>, tamper: (JSONObject) -> Unit = {}): ByteArray {
        val o = JSONObject()
            .put("format", 1)
            .put("modelId", "test-model")
            .put("dim", dim)
            .put("seqLen", 64)
            .put("imageSize", 224)
            .put("vocabSize", vocabSize)
            .put("padId", 0)
            .put("eosId", 1)
            .put("lowercase", true)
            .put("logitScale", 112.0)
            .put("logitBias", -16.0)
            .put("files", JSONObject().apply {
                files.forEach { (n, b) -> put(n, JSONObject().put("size", b.size).put("sha256", sha(b))) }
            })
        tamper(o)
        return o.toString().toByteArray()
    }

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun packEntries(files: Map<String, ByteArray> = packFiles()): List<Pair<String, ByteArray>> =
        listOf(SmartManifest.MANIFEST to manifest(files)) + files.map { it.key to it.value }

    @Test
    fun validPackIsWrittenAndChecked() {
        val dir = tmp.newFolder("staging")
        val manifest = SmartPackWriter.readPack(ByteArrayInputStream(zip(packEntries())), dir)
        assertEquals("test-model", manifest.modelId)
        assertEquals(vocabSize * dim.toLong(), File(dir, SmartManifest.TOKEN_TABLE).length())
        assertEquals(
            (SmartManifest.FILES + SmartManifest.MANIFEST).toSet(),
            dir.list()!!.toSet(),
        )
    }

    @Test
    fun unknownAndTraversalEntriesAreNotWritten() {
        val root = tmp.newFolder("root")
        val dir = File(root, "staging").apply { mkdirs() }
        val entries = packEntries() + listOf(
            "../evil.txt" to "x".toByteArray(),
            "/abs/evil.txt" to "x".toByteArray(),
            "sub/${SmartManifest.VOCAB}" to "x".toByteArray(),
        )
        SmartPackWriter.readPack(ByteArrayInputStream(zip(entries)), dir)
        assertFalse(File(root, "evil.txt").exists())
        assertEquals(setOf("staging"), root.list()!!.toSet())
        assertEquals((SmartManifest.FILES + SmartManifest.MANIFEST).toSet(), dir.list()!!.toSet())
    }

    @Test
    fun damagedMissingOrForeignPacksAreRefused() {
        val files = packFiles()
        // A file changed after the manifest was written.
        val damaged = packEntries(files).map { (n, b) -> if (n == SmartManifest.VOCAB) n to "changed".toByteArray() else n to b }
        assertRefused(damaged)
        // A file missing.
        assertRefused(packEntries(files).filter { it.first != SmartManifest.SELFTEST })
        // No manifest.
        assertRefused(packEntries(files).filter { it.first != SmartManifest.MANIFEST })
        // A file twice.
        assertRefused(packEntries(files) + (SmartManifest.NOTICE to files.getValue(SmartManifest.NOTICE)))
        // Another pack format, or an extra file in the manifest.
        assertRefused(listOf(SmartManifest.MANIFEST to manifest(files) { it.put("format", 2) }) + files.toList())
        assertRefused(
            listOf(SmartManifest.MANIFEST to manifest(files) { it.getJSONObject("files").put("x.so", JSONObject().put("size", 1).put("sha256", "0".repeat(64))) }) +
                files.toList(),
        )
        // A token table that doesn't match the dimensions.
        assertRefused(listOf(SmartManifest.MANIFEST to manifest(files) { it.put("vocabSize", 3) }) + files.toList())
    }

    private fun assertRefused(entries: List<Pair<String, ByteArray>>) {
        try {
            SmartPackWriter.readPack(ByteArrayInputStream(zip(entries)), tmp.newFolder())
            fail("accepted")
        } catch (_: IOException) {
        } catch (_: org.json.JSONException) {
        }
    }

    @Test
    fun settingsBundleCarriesConfigModelAndIndex() {
        val staging = tmp.newFolder("bundle-staging")
        val temp = tmp.newFolder("temp")
        val entries = listOf(SmartConfigBundle.CONFIG_ENTRY to """{"a":1}""".toByteArray()) +
            packEntries().map { (n, b) -> SmartSearch.MODEL_PREFIX + n to b } +
            listOf(SmartSearch.INDEX_ENTRY to ByteArray(10) { 7 }, "../x" to ByteArray(1))
        val bytes = zip(entries)
        assertTrue(SmartConfigBundle.isZip(bytes[0].toInt(), bytes[1].toInt()))
        val imported = SmartConfigBundle.read(ByteArrayInputStream(bytes), { staging }, temp)
        assertEquals("""{"a":1}""", imported.configJson)
        assertEquals("test-model", imported.manifest?.modelId)
        assertEquals(10L, imported.indexFile?.length())
        assertFalse(File(tmp.root, "x").exists())
    }

    @Test
    fun settingsBundleWithoutModel() {
        val bytes = zip(listOf(SmartConfigBundle.CONFIG_ENTRY to "{}".toByteArray()))
        val imported = SmartConfigBundle.read(ByteArrayInputStream(bytes), { fail("no staging needed"); tmp.root }, tmp.newFolder())
        assertEquals("{}", imported.configJson)
        assertNull(imported.manifest)
        assertNull(imported.indexFile)
        assertFalse(SmartConfigBundle.isZip('{'.code, '"'.code))
    }
}
