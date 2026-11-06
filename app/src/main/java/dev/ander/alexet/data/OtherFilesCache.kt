package dev.ander.alexet.data

import android.content.Context
import dev.ander.alexet.data.AppLog as Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * The other files ("See all files") of recently opened folders, kept on disk: a folder opened
 * after a restart shows them with its photos at once instead of a moment later (they shifted the
 * tiles). Each folder is still listed from disk when opened and corrected if something changed.
 */
object OtherFilesCache {
    private const val TAG = "OtherFilesCache"
    private const val FILE_NAME = "other_files_cache.json"
    private const val VERSION = 1
    private const val MAX_BYTES = 8L * 1024L * 1024L

    private fun file(context: Context): File = File(context.applicationContext.filesDir, FILE_NAME)

    /** Lists by album; empty when there is no cache or it can't be read. */
    fun load(context: Context): Map<Long, List<GalleryMedia>> = runCatching {
        val f = file(context)
        if (!f.isFile || f.length() <= 0L || f.length() > MAX_BYTES) return emptyMap()
        val root = JSONObject(f.readText())
        if (root.optInt("v") != VERSION) return emptyMap()
        val folders = root.optJSONArray("folders") ?: return emptyMap()
        val out = LinkedHashMap<Long, List<GalleryMedia>>()
        for (i in 0 until folders.length()) {
            val folder = folders.optJSONObject(i) ?: continue
            val id = folder.optLong("id", 0L).takeIf { it != 0L } ?: continue
            val name = folder.optString("name")
            val rel = folder.optString("rel")
            val files = folder.optJSONArray("files") ?: continue
            val list = ArrayList<GalleryMedia>(files.length())
            for (j in 0 until files.length()) {
                val entry = files.optJSONObject(j) ?: continue
                val path = entry.optString("p").takeIf { it.isNotEmpty() } ?: continue
                list += OtherFiles.tile(File(path), entry.optLong("s"), entry.optLong("m"), id, name, rel)
            }
            out[id] = list
        }
        out
    }.onFailure { Log.w(TAG, "Failed to load other files cache", it) }.getOrDefault(emptyMap())

    /** Saves run one at a time; an older list still waiting is skipped. */
    private val saveLock = Any()
    @Volatile private var newest: Map<Long, List<GalleryMedia>>? = null

    fun save(context: Context, lists: Map<Long, List<GalleryMedia>>) {
        newest = lists
        synchronized(saveLock) {
            if (newest !== lists) return
            runCatching {
                val folders = JSONArray()
                for ((id, list) in lists) {
                    val first = list.firstOrNull()
                    val files = JSONArray()
                    for (item in list) {
                        val path = item.absolutePath ?: continue
                        files.put(JSONObject().put("p", path).put("s", item.sizeBytes).put("m", item.dateModifiedMs))
                    }
                    folders.put(
                        JSONObject()
                            .put("id", id)
                            .put("name", first?.bucketName.orEmpty())
                            .put("rel", first?.relativePath.orEmpty())
                            .put("files", files),
                    )
                }
                val json = JSONObject().put("v", VERSION).put("folders", folders).toString()
                if (json.length > MAX_BYTES) {
                    Log.w(TAG, "Skipping other files cache save: ${json.length} chars")
                    return
                }
                val target = file(context)
                val temp = File(target.parentFile, "$FILE_NAME.tmp")
                temp.writeText(json)
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
            }.onFailure { Log.w(TAG, "Failed to save other files cache", it) }
        }
    }
}
