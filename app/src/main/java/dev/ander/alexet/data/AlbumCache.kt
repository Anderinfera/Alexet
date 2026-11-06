package dev.ander.alexet.data

import android.content.Context
import android.net.Uri
import dev.ander.alexet.data.AppLog as Log
import java.io.File

/**
 * Lightweight album-grid snapshot for cold start: paint folders before MediaStore scan finishes.
 * Small (KB) so the album grid can appear before the media index is read.
 */
object AlbumCache {
    const val SCHEMA_VERSION = 2
    const val FILE_NAME = "album_snapshot.json"
    const val MAX_BYTES = 512L * 1024L

    private const val TAG = "AlbumCache"

    data class SnapshotAlbum(
        val id: Long,
        val name: String,
        val itemCount: Int,
        val relativePath: String,
        val isHidden: Boolean = false,
        val latestAddedMs: Long = 0L,
        val coverUri: String? = null,
        val coverId: Long = 0L,
        val coverMimeType: String = "image/*",
        val coverDisplayName: String = "",
        val coverDateModifiedMs: Long = 0L,
        val legacyBucketIds: Set<Long> = emptySet(),
    )

    fun file(context: Context): File = File(context.applicationContext.filesDir, FILE_NAME)

    fun load(context: Context): List<Album>? {
        return runCatching {
            val f = file(context)
            if (!f.exists() || !f.isFile) return null
            if (f.length() <= 0L || f.length() > MAX_BYTES) {
                Log.w(TAG, "Skipping album cache load: size=${f.length()}")
                return null
            }
            decode(f.readText())?.map { it.toAlbum() }
        }.onFailure { e ->
            Log.w(TAG, "Failed to load album cache", e)
        }.getOrNull()
    }

    /** Saves run one at a time: interleaved writes of the shared temp file gave broken JSON. */
    private val saveLock = Any()
    /** The list of the newest save asked for; an older one still waiting is skipped. */
    @Volatile private var newest: List<Album>? = null

    fun save(context: Context, albums: List<Album>) {
        newest = albums
        synchronized(saveLock) {
            if (newest !== albums) return
            saveLocked(context, albums)
        }
    }

    private fun saveLocked(context: Context, albums: List<Album>) {
        runCatching {
            if (albums.size > 5_000) {
                Log.w(TAG, "Skipping album cache save: too many (${albums.size})")
                return
            }
            val snaps = albums
                .filter { it.id != AlbumIds.TRASH && it.id != AlbumIds.FAVORITES }
                .map { SnapshotAlbum(
                    id = it.id,
                    name = it.name,
                    itemCount = it.itemCount,
                    relativePath = it.relativePath,
                    isHidden = it.isHidden,
                    latestAddedMs = it.latestAddedMs,
                    coverUri = it.cover?.uri?.toString(),
                    coverId = it.cover?.id ?: 0L,
                    coverMimeType = it.cover?.mimeType ?: "image/*",
                    coverDisplayName = it.cover?.displayName.orEmpty(),
                    coverDateModifiedMs = it.cover?.dateModifiedMs ?: 0L,
                    legacyBucketIds = it.legacyBucketIds,
                ) }
            val json = encode(snaps)
            if (json.length > MAX_BYTES) {
                Log.w(TAG, "Skipping album cache save: encoded length=${json.length}")
                return
            }
            val target = file(context)
            val parent = target.parentFile ?: return
            if (!parent.exists()) parent.mkdirs()
            val temp = File(parent, "$FILE_NAME.tmp")
            temp.writeText(json)
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }.onFailure { e ->
            Log.w(TAG, "Failed to save album cache", e)
        }
    }

    fun encode(albums: List<SnapshotAlbum>, savedAt: Long = System.currentTimeMillis()): String {
        val sb = StringBuilder(64 + albums.size * 128)
        sb.append("{\"schemaVersion\":").append(SCHEMA_VERSION)
            .append(",\"savedAt\":").append(savedAt)
            .append(",\"albums\":[")
        albums.forEachIndexed { index, a ->
            if (index > 0) sb.append(',')
            sb.append('{')
            appendField(sb, "id", a.id, first = true)
            appendField(sb, "name", a.name)
            appendField(sb, "itemCount", a.itemCount)
            appendField(sb, "relativePath", a.relativePath)
            appendField(sb, "isHidden", a.isHidden)
            appendField(sb, "latestAddedMs", a.latestAddedMs)
            if (a.coverUri != null) appendField(sb, "coverUri", a.coverUri) else sb.append(",\"coverUri\":null")
            appendField(sb, "coverId", a.coverId)
            appendField(sb, "coverMimeType", a.coverMimeType)
            appendField(sb, "coverDisplayName", a.coverDisplayName)
            appendField(sb, "coverDateModifiedMs", a.coverDateModifiedMs)
            sb.append(",\"legacyBucketIds\":[")
            a.legacyBucketIds.forEachIndexed { i, id ->
                if (i > 0) sb.append(',')
                sb.append(id)
            }
            sb.append(']')
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    fun decode(json: String): List<SnapshotAlbum>? {
        if (json.isBlank()) return null
        return try {
            val root = JsonLite(json).parseObject()
            val version = (root["schemaVersion"] as? Number)?.toInt() ?: return null
            if (version != 1 && version != 2) {
                Log.w(TAG, "Unsupported album cache schemaVersion=$version")
                return null
            }
            val raw = root["albums"] as? List<*> ?: return null
            val out = ArrayList<SnapshotAlbum>(raw.size)
            for (entry in raw) {
                @Suppress("UNCHECKED_CAST")
                val obj = entry as? Map<String, Any?> ?: continue
                val id = (obj["id"] as? Number)?.toLong() ?: continue
                val rawLegacy = obj["legacyBucketIds"] as? List<*>
                val legacyBucketIds = rawLegacy?.mapNotNull { (it as? Number)?.toLong() }?.toSet() ?: emptySet()
                out += SnapshotAlbum(
                    id = id,
                    name = obj["name"] as? String ?: "Album",
                    itemCount = (obj["itemCount"] as? Number)?.toInt() ?: 0,
                    relativePath = obj["relativePath"] as? String ?: "",
                    isHidden = obj["isHidden"] as? Boolean ?: false,
                    latestAddedMs = (obj["latestAddedMs"] as? Number)?.toLong() ?: 0L,
                    coverUri = obj["coverUri"] as? String,
                    coverId = (obj["coverId"] as? Number)?.toLong() ?: 0L,
                    coverMimeType = obj["coverMimeType"] as? String ?: "image/*",
                    coverDisplayName = obj["coverDisplayName"] as? String ?: "",
                    coverDateModifiedMs = (obj["coverDateModifiedMs"] as? Number)?.toLong() ?: 0L,
                    legacyBucketIds = legacyBucketIds,
                )
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode album cache", e)
            null
        }
    }

    private fun SnapshotAlbum.toAlbum(): Album {
        val cover = coverUri?.takeIf { it.isNotBlank() }?.let { uriStr ->
            runCatching {
                GalleryMedia(
                    id = coverId,
                    uri = Uri.parse(uriStr),
                    displayName = coverDisplayName.ifBlank { name },
                    mimeType = coverMimeType,
                    dateTakenMs = 0L,
                    dateModifiedMs = coverDateModifiedMs,
                    dateAddedMs = latestAddedMs,
                    sizeBytes = 0L,
                    width = 0,
                    height = 0,
                    durationMs = 0L,
                    bucketId = id,
                    bucketName = name,
                    relativePath = relativePath,
                    isHidden = isHidden,
                )
            }.getOrNull()
        }
        return Album(
            id = id,
            name = name,
            cover = cover,
            itemCount = itemCount,
            relativePath = relativePath,
            isHidden = isHidden,
            latestAddedMs = latestAddedMs,
            legacyBucketIds = legacyBucketIds,
        )
    }

    private fun appendField(sb: StringBuilder, key: String, value: Long, first: Boolean = false) {
        if (!first) sb.append(',')
        sb.append('"').append(key).append("\":").append(value)
    }

    private fun appendField(sb: StringBuilder, key: String, value: Int, first: Boolean = false) {
        if (!first) sb.append(',')
        sb.append('"').append(key).append("\":").append(value)
    }

    private fun appendField(sb: StringBuilder, key: String, value: Boolean, first: Boolean = false) {
        if (!first) sb.append(',')
        sb.append('"').append(key).append("\":").append(value)
    }

    private fun appendField(sb: StringBuilder, key: String, value: String, first: Boolean = false) {
        if (!first) sb.append(',')
        sb.append('"').append(key).append("\":")
        quoteJson(sb, value)
    }

    private fun quoteJson(sb: StringBuilder, value: String) {
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) sb.append(String.format("\\u%04x", ch.code))
                    else sb.append(ch)
                }
            }
        }
        sb.append('"')
    }

    /** Minimal JSON object/array parser (flat objects/arrays only). */
    private class JsonLite(private val src: String) {
        private var i = 0

        fun parseObject(): Map<String, Any?> {
            skipWs()
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { i++; return out }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs(); expect(':'); skipWs()
                out[key] = parseValue()
                skipWs()
                when (peek()) {
                    ',' -> { i++; continue }
                    '}' -> { i++; return out }
                    else -> error("Expected , or } at $i")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('['); skipWs()
            val out = ArrayList<Any?>()
            if (peek() == ']') { i++; return out }
            while (true) {
                skipWs()
                out += parseValue()
                skipWs()
                when (peek()) {
                    ',' -> { i++; continue }
                    ']' -> { i++; return out }
                    else -> error("Expected , or ] at $i")
                }
            }
        }

        private fun parseValue(): Any? {
            skipWs()
            return when (peek()) {
                '"' -> parseString()
                '{' -> parseObject()
                '[' -> parseArray()
                't' -> { expectWord("true"); true }
                'f' -> { expectWord("false"); false }
                'n' -> { expectWord("null"); null }
                else -> parseNumber()
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (i < src.length) {
                when (val c = src[i++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= src.length) error("Bad escape")
                        when (val e = src[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                val hex = src.substring(i, i + 4)
                                i += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            error("Unterminated string")
        }

        private fun parseNumber(): Number {
            val start = i
            if (peek() == '-') i++
            while (i < src.length && src[i].isDigit()) i++
            var isDouble = false
            if (i < src.length && src[i] == '.') {
                isDouble = true; i++
                while (i < src.length && src[i].isDigit()) i++
            }
            if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
                isDouble = true; i++
                if (i < src.length && (src[i] == '+' || src[i] == '-')) i++
                while (i < src.length && src[i].isDigit()) i++
            }
            val raw = src.substring(start, i)
            return if (isDouble) raw.toDouble() else raw.toLong()
        }

        private fun expectWord(w: String) {
            if (!src.startsWith(w, i)) error("Expected $w")
            i += w.length
        }

        private fun expect(c: Char) {
            if (peek() != c) error("Expected $c at $i")
            i++
        }

        private fun peek(): Char = if (i < src.length) src[i] else '\u0000'
        private fun skipWs() { while (i < src.length && src[i].isWhitespace()) i++ }
    }
}
