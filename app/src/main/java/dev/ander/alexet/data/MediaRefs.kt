package dev.ander.alexet.data

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/**
 * Imported file references (hidden, favorites, custom order) whose files are not on this phone yet,
 * kept by portable path and attached as soon as the files show up.
 */
@Immutable
data class PendingMediaRefs(
    val hidden: Set<String> = emptySet(),
    val favorites: List<String> = emptyList(),
    val contentOrders: Map<Long, List<String>> = emptyMap(),
) {
    val isEmpty: Boolean get() = hidden.isEmpty() && favorites.isEmpty() && contentOrders.values.all { it.isEmpty() }
}

/**
 * MediaStore ids differ between phones (and after a MediaStore rebuild); a file's path below the
 * storage root does not. The config export stores that path next to every file id so an import on
 * another phone can find the same files.
 */
object MediaRefs {

    private val STORAGE_ROOT = Regex("^/(storage/emulated/\\d+|sdcard)/", RegexOption.IGNORE_CASE)

    /** Path below the storage root, '/' separated, without the leading slash; case kept. */
    fun portableDirPath(absolute: String): String =
        absolute.replace('\\', '/').trim().replace(STORAGE_ROOT, "").trim('/')

    /** Comparison key for a file: portable path, lower case (shared storage ignores case). */
    fun portablePath(media: GalleryMedia): String? {
        val rel = media.relativePath.trim().trim('/')
        val raw = when {
            rel.isNotEmpty() && !rel.startsWith("storage/", ignoreCase = true) && media.displayName.isNotBlank() ->
                "$rel/${media.displayName}"
            !media.absolutePath.isNullOrBlank() -> portableDirPath(media.absolutePath)
            else -> return null
        }
        return key(raw)
    }

    fun key(path: String): String = path.replace('\\', '/').trim().trim('/').lowercase()

    /** Portable path -> id for every file in [media]. */
    fun index(media: List<GalleryMedia>): Map<String, Long> {
        val out = HashMap<String, Long>(media.size * 2)
        for (item in media) {
            val path = portablePath(item) ?: continue
            out.putIfAbsent(path, item.id)
        }
        return out
    }

    /**
     * Maps imported ids to this phone's ids. An id with a stored path is matched by path (missing
     * file -> pending). Without a path: in an export that carries paths ([withPaths]) a positive
     * MediaStore id belonged to a file that was gone already and would hit a random file here, so it
     * is dropped; negative ids are derived from the path (.nomedia / private files) and are kept.
     * Exports from older versions have no paths at all and keep every id.
     */
    fun remap(
        settings: AppSettings,
        withPaths: Boolean,
        pathOfImportedId: (Long) -> String?,
        idForPath: (String) -> Long?,
        importedPending: PendingMediaRefs,
    ): AppSettings {
        val hiddenPending = LinkedHashSet<String>()
        val favoritePending = ArrayList<String>()
        val orderPending = LinkedHashMap<Long, MutableList<String>>()

        fun map(id: Long, onMissing: (String) -> Unit): Long? {
            val path = pathOfImportedId(id) ?: return if (withPaths && id > 0L) null else id
            return idForPath(key(path)) ?: run {
                onMissing(key(path))
                null
            }
        }

        val hidden = settings.hiddenMediaIds.mapNotNullTo(LinkedHashSet()) { id -> map(id) { hiddenPending += it } }
        val favorites = settings.favoriteMediaIds.mapNotNull { id -> map(id) { favoritePending += it } }.distinct()
        val orders = settings.folderContentOrders.mapValues { (folder, ids) ->
            ids.mapNotNull { id -> map(id) { orderPending.getOrPut(folder) { ArrayList() } += it } }.distinct()
        }.filterValues { it.isNotEmpty() }

        val merged = PendingMediaRefs(
            hidden = hiddenPending + importedPending.hidden,
            favorites = (favoritePending + importedPending.favorites).distinct(),
            contentOrders = (orderPending.keys + importedPending.contentOrders.keys).associateWith { folder ->
                ((orderPending[folder] ?: emptyList()) + (importedPending.contentOrders[folder] ?: emptyList())).distinct()
            },
        )
        val remapped = settings.copy(
            hiddenMediaIds = hidden,
            favoriteMediaIds = favorites,
            folderContentOrders = orders,
            pendingMedia = PendingMediaRefs(),
        )
        // Pending entries from the file may already be here.
        return resolvePending(remapped.copy(pendingMedia = merged), idForPath) ?: remapped.copy(pendingMedia = merged)
    }

    /** Attaches pending references whose files now exist; null when nothing changed. */
    fun resolvePending(settings: AppSettings, idForPath: (String) -> Long?): AppSettings? {
        val pending = settings.pendingMedia
        if (pending.isEmpty) return null
        var changed = false

        val hiddenLeft = LinkedHashSet<String>()
        val hidden = LinkedHashSet(settings.hiddenMediaIds)
        for (path in pending.hidden) {
            val id = idForPath(path)
            if (id == null) hiddenLeft += path else { hidden += id; changed = true }
        }

        val favoritesLeft = ArrayList<String>()
        val favorites = ArrayList(settings.favoriteMediaIds)
        for (path in pending.favorites) {
            val id = idForPath(path)
            if (id == null) favoritesLeft += path else { if (id !in favorites) favorites += id; changed = true }
        }

        val ordersLeft = LinkedHashMap<Long, List<String>>()
        val orders = LinkedHashMap(settings.folderContentOrders)
        for ((folder, paths) in pending.contentOrders) {
            val left = ArrayList<String>()
            val ids = ArrayList(orders[folder] ?: emptyList())
            for (path in paths) {
                val id = idForPath(path)
                if (id == null) left += path else { if (id !in ids) ids += id; changed = true }
            }
            if (ids.isNotEmpty()) orders[folder] = ids
            if (left.isNotEmpty()) ordersLeft[folder] = left
        }

        if (!changed) return null
        return settings.copy(
            hiddenMediaIds = hidden,
            favoriteMediaIds = favorites,
            folderContentOrders = orders,
            pendingMedia = PendingMediaRefs(hiddenLeft, favoritesLeft, ordersLeft),
        )
    }

    fun toJson(pending: PendingMediaRefs): JSONObject = JSONObject().apply {
        put("hidden", JSONArray(pending.hidden.toList()))
        put("favorites", JSONArray(pending.favorites))
        put("contentOrders", JSONObject().apply {
            pending.contentOrders.forEach { (folder, paths) -> put(folder.toString(), JSONArray(paths)) }
        })
    }

    fun fromJson(o: JSONObject?): PendingMediaRefs {
        if (o == null) return PendingMediaRefs()
        fun strings(a: JSONArray?): List<String> =
            if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
        val orders = LinkedHashMap<Long, List<String>>()
        o.optJSONObject("contentOrders")?.let { obj ->
            obj.keys().forEach { k -> k.toLongOrNull()?.let { orders[it] = strings(obj.optJSONArray(k)) } }
        }
        return PendingMediaRefs(
            hidden = strings(o.optJSONArray("hidden")).toSet(),
            favorites = strings(o.optJSONArray("favorites")),
            contentOrders = orders,
        )
    }
}
