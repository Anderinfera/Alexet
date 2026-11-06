package dev.ander.alexet.data.smartsearch

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * Token ids identical to the Hugging Face tokenizer (golden_tokens.json, written by
 * tools/smart-search/build_pack.py). Needs the built pack's vocab.bin; skipped without it.
 */
class GemmaTokenizerTest {

    private fun vocabFile(): File? = listOf(
        File("../tools/smart-search/out/pack/vocab.bin"),
        File("tools/smart-search/out/pack/vocab.bin"),
    ).firstOrNull { it.isFile }

    @Test
    fun matchesHuggingFaceTokenIds() {
        val vocab = vocabFile()
        assumeTrue("vocab.bin not built (tools/smart-search/build_pack.py)", vocab != null)
        val tokenizer = GemmaTokenizer.load(ByteBuffer.wrap(vocab!!.readBytes()))
        val text = javaClass.getResourceAsStream("/smartsearch/golden_tokens.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val golden = JSONObject(text)
        val padId = golden.getInt("padId")
        val eosId = golden.getInt("eosId")
        val seqLen = golden.getInt("seqLen")
        val cases = golden.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val expected = case.getJSONArray("ids").let { a -> IntArray(a.length()) { a.getInt(it) } }
            val got = tokenizer.encodePadded(case.getString("text"), seqLen, padId, eosId)
            assertArrayEquals("text: ${case.getString("text")}", expected, got)
        }
    }
}
