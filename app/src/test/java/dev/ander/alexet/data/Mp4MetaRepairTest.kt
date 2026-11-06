package dev.ander.alexet.data

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Mp4MetaRepairTest {

    private fun box(type: String, vararg children: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { children.forEach { write(it) } }.toByteArray()
        return int(8 + body.size) + type.toByteArray(Charsets.ISO_8859_1) + body
    }

    private fun raw(vararg parts: ByteArray): ByteArray =
        ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()

    private fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun hdlr() = box("hdlr", ByteArray(8), "mdta".toByteArray(), ByteArray(12), byteArrayOf(0))

    private fun keys(vararg names: String) = box(
        "keys",
        int(0),
        int(names.size),
        *names.map { raw(int(8 + it.length), "mdta".toByteArray(), it.toByteArray()) }.toTypedArray(),
    )

    private fun item(index: Int): ByteArray {
        val data = box("data", int(1), int(0), "x".toByteArray())
        return raw(int(8 + data.size), int(index), data)
    }

    private fun file(meta: ByteArray): ByteArray = raw(
        box("ftyp", "mp42".toByteArray(), int(0), "isom".toByteArray()),
        box("moov", box("mvhd", ByteArray(100)), meta, box("trak", box("tkhd", ByteArray(84)))),
        box("mdat", ByteArray(32)),
    )

    private fun scan(bytes: ByteArray): LongArray =
        Mp4MetaRepair.findBrokenMeta(bytes.size.toLong()) { position, buf, len ->
            if (position >= bytes.size) {
                -1
            } else {
                val n = minOf(len.toLong(), bytes.size - position).toInt()
                System.arraycopy(bytes, position.toInt(), buf, 0, n)
                n
            }
        }

    private fun metaOffset(bytes: ByteArray): Long {
        val needle = "meta".toByteArray()
        for (i in 0..bytes.size - 4) {
            if (bytes.copyOfRange(i, i + 4).contentEquals(needle)) return i.toLong()
        }
        error("no meta")
    }

    @Test
    fun validMetaIsLeftAlone() {
        val bytes = file(box("meta", hdlr(), keys("com.android.version"), box("ilst", item(1))))
        assertEquals(0, scan(bytes).size)
    }

    @Test
    fun skipItemInIlstIsBroken() {
        // The scrubber pattern: key renamed to skip, count 0, ilst child renamed to skip.
        val bytes = file(
            box(
                "meta",
                hdlr(),
                box("keys", int(0), int(0), box("skip", ByteArray(11))),
                box("ilst", box("skip", ByteArray(18))),
            ),
        )
        assertArrayEquals(longArrayOf(metaOffset(bytes)), scan(bytes))
    }

    @Test
    fun indexBeyondKeyCountIsBroken() {
        val bytes = file(box("meta", hdlr(), keys("a.b"), box("ilst", item(1), item(2))))
        assertEquals(1, scan(bytes).size)
    }

    @Test
    fun isoFullBoxMetaIsParsedToo() {
        val bytes = file(box("meta", int(0), hdlr(), keys(), box("ilst", item(1))))
        assertEquals(1, scan(bytes).size)
    }

    @Test
    fun noMoovOrNotMp4GivesNothing() {
        assertEquals(0, scan(ByteArray(64) { 0x41 }).size)
        assertEquals(0, scan(raw(box("ftyp", ByteArray(8)), box("mdat", ByteArray(16)))).size)
    }

    @Test
    fun patchRewritesOnlyOverlappingBytes() {
        val buffer = ByteArray(10) { '.'.code.toByte() }
        // Patch at file offset 102, read covers 100..109 into buffer[0..9].
        Mp4MetaRepair.applyPatches(longArrayOf(102L), 100L, buffer, 0, 10)
        assertEquals("..free....", String(buffer, Charsets.ISO_8859_1))
        val partial = ByteArray(4) { '.'.code.toByte() }
        // Read covers 104..107: only the last two bytes of "free" (at 104,105) land in it.
        Mp4MetaRepair.applyPatches(longArrayOf(102L), 104L, partial, 0, 4)
        assertEquals("ee..", String(partial, Charsets.ISO_8859_1))
    }
}
