package dev.ander.alexet.data.smartsearch

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.PriorityQueue

/**
 * SentencePiece BPE encoding of the Gemma tokenizer (SigLIP 2's), from the pack's vocab.bin
 * (written by tools/smart-search/build_pack.py). Mirrors sentencepiece's bpe_model.cc: the text
 * is split into characters (user-defined pieces stay whole), then the adjacent pair whose
 * merge has the highest score is merged until none is in the vocabulary; pieces not in the
 * vocabulary fall back to their UTF-8 bytes.
 */
class GemmaTokenizer private constructor(
    private val pieces: Array<String>,
    private val types: ByteArray,
    private val scores: FloatArray,
    private val flags: Int,
    private val unkId: Int,
) {
    private val mergeable = HashMap<String, Int>(pieces.size * 2)
    private val ids = HashMap<String, Int>(pieces.size * 2)
    private val userPieces = HashSet<String>()
    private val byteIds = IntArray(256) { -1 }
    private var maxUserCodePoints = 0

    init {
        for (i in pieces.indices) {
            val piece = pieces[i]
            ids.putIfAbsent(piece, i)
            when (types[i].toInt()) {
                TYPE_NORMAL, TYPE_UNUSED -> mergeable[piece] = i
                TYPE_USER -> {
                    mergeable[piece] = i
                    userPieces += piece
                    maxUserCodePoints = maxOf(maxUserCodePoints, piece.codePointCount(0, piece.length))
                }
                TYPE_BYTE -> piece.removePrefix("<0x").removeSuffix(">").toIntOrNull(16)?.let { byteIds[it] = i }
            }
        }
    }

    val vocabSize: Int get() = pieces.size

    /** Token ids of [text] as the model takes them: lower case, EOS, cut and padded to [seqLen]. */
    fun encodePadded(text: String, seqLen: Int, padId: Int, eosId: Int, lowercase: Boolean = true): IntArray {
        val body = encode(if (lowercase) text.lowercase(Locale.ROOT) else text)
        val out = IntArray(seqLen) { padId }
        val n = minOf(body.size, seqLen - 1)
        System.arraycopy(body, 0, out, 0, n)
        out[n] = eosId
        return out
    }

    fun encode(text: String): IntArray {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return IntArray(0)
        val n: Int
        val symbols: Array<String>
        val frozen: BooleanArray
        split(normalized).let { parts ->
            n = parts.size
            symbols = Array(n) { parts[it].first }
            frozen = BooleanArray(n) { parts[it].second }
        }
        val prev = IntArray(n) { it - 1 }
        val next = IntArray(n) { if (it == n - 1) -1 else it + 1 }
        val revMerge = HashMap<String, Pair<String, String>>()
        val agenda = PriorityQueue<Candidate>()

        fun addPair(left: Int, right: Int) {
            if (left < 0 || right < 0 || frozen[left] || frozen[right]) return
            val piece = symbols[left] + symbols[right]
            val id = mergeable[piece] ?: return
            agenda += Candidate(left, right, scores[id], piece.length)
            if (types[id].toInt() == TYPE_UNUSED) revMerge[piece] = symbols[left] to symbols[right]
        }

        for (i in 1 until n) addPair(i - 1, i)
        while (agenda.isNotEmpty()) {
            val top = agenda.poll()!!
            val left = symbols[top.left]
            val right = symbols[top.right]
            if (left.isEmpty() || right.isEmpty() || left.length + right.length != top.size) continue
            symbols[top.left] = left + right
            symbols[top.right] = ""
            next[top.left] = next[top.right]
            if (next[top.right] >= 0) prev[next[top.right]] = top.left
            addPair(prev[top.left], top.left)
            addPair(top.left, next[top.left])
        }

        val out = ArrayList<Int>(n)
        fun emit(piece: String) {
            val id = ids[piece]
            if (id != null && types[id].toInt() != TYPE_UNUSED) {
                out += id
                return
            }
            val split = revMerge[piece]
            if (split != null) {
                emit(split.first)
                emit(split.second)
                return
            }
            if (flags and FLAG_BYTE_FALLBACK != 0) {
                for (b in piece.toByteArray(Charsets.UTF_8)) {
                    val byteId = byteIds[b.toInt() and 0xFF]
                    out += if (byteId >= 0) byteId else unkId
                }
            } else {
                out += unkId
            }
        }

        var i = 0
        while (i >= 0) {
            if (symbols[i].isNotEmpty()) emit(symbols[i])
            i = next[i]
        }
        return out.toIntArray()
    }

    private fun normalize(text: String): String {
        var s = text
        if (flags and FLAG_REMOVE_EXTRA_WS != 0) {
            s = s.trim(' ').replace(MULTI_SPACE, " ")
        }
        if (s.isEmpty()) return s
        if (flags and FLAG_DUMMY_PREFIX != 0) s = " $s"
        if (flags and FLAG_ESCAPE_WS != 0) s = s.replace(' ', '▁')
        return s
    }

    /** Code points; the longest user-defined piece at a position is one symbol that never merges (true). */
    private fun split(s: String): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>(s.length)
        var i = 0
        while (i < s.length) {
            var match: String? = null
            if (maxUserCodePoints > 0) {
                var end = i
                var count = 0
                val ends = IntArray(maxUserCodePoints)
                while (end < s.length && count < maxUserCodePoints) {
                    end += Character.charCount(s.codePointAt(end))
                    ends[count++] = end
                }
                for (k in count - 1 downTo 0) {
                    val candidate = s.substring(i, ends[k])
                    if (candidate in userPieces) {
                        match = candidate
                        break
                    }
                }
            }
            val piece = match ?: s.substring(i, i + Character.charCount(s.codePointAt(i)))
            out += piece to (match != null)
            i += piece.length
        }
        return out
    }

    private class Candidate(val left: Int, val right: Int, val score: Float, val size: Int) : Comparable<Candidate> {
        // Highest score first; on a tie, the leftmost pair (sentencepiece's SymbolPairComparator).
        override fun compareTo(other: Candidate): Int {
            val byScore = other.score.compareTo(score)
            return if (byScore != 0) byScore else left.compareTo(other.left)
        }
    }

    companion object {
        private const val TYPE_NORMAL = 1
        private const val TYPE_USER = 4
        private const val TYPE_UNUSED = 5
        private const val TYPE_BYTE = 6
        private const val FLAG_BYTE_FALLBACK = 1
        private const val FLAG_DUMMY_PREFIX = 2
        private const val FLAG_REMOVE_EXTRA_WS = 4
        private const val FLAG_ESCAPE_WS = 8
        private val MULTI_SPACE = Regex(" {2,}")

        /** Reads vocab.bin: 'AXVB', u32 version, u32 count, u32 flags, i32 unk id, then per piece u8 type, f32 score, u16 length, UTF-8. */
        fun load(buffer: ByteBuffer): GemmaTokenizer {
            val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { b.get(it) }
            require(String(magic, Charsets.US_ASCII) == "AXVB") { "Not a vocab.bin" }
            require(b.int == 1) { "Unsupported vocab.bin version" }
            val count = b.int
            require(count in 1..2_000_000) { "Bad vocab size $count" }
            val flags = b.int
            val unk = b.int
            val pieces = arrayOfNulls<String>(count)
            val types = ByteArray(count)
            val scores = FloatArray(count)
            val bytes = ByteArray(0xFFFF)
            for (i in 0 until count) {
                types[i] = b.get()
                scores[i] = b.float
                val len = b.short.toInt() and 0xFFFF
                b.get(bytes, 0, len)
                pieces[i] = String(bytes, 0, len, Charsets.UTF_8)
            }
            @Suppress("UNCHECKED_CAST")
            return GemmaTokenizer(pieces as Array<String>, types, scores, flags, unk)
        }
    }
}
