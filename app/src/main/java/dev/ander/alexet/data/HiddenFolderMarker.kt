package dev.ander.alexet.data

/**
 * Puts hidden (`.nomedia`) folders into a library list: MediaStore rows that lie in a hidden folder
 * are marked hidden and moved to that folder's album, and the hidden files Alexet read from disk
 * itself are added where the list has no row for the same file yet.
 */
internal object HiddenFolderMarker {

    /** Hidden folders (lowercased, no slashes at the ends) -> their place in the list and their name as written. */
    class Index(hiddenRelativePaths: List<String>) {
        val isEmpty = hiddenRelativePaths.isEmpty()
        val byFolder = HashMap<String, Pair<Int, String>>(hiddenRelativePaths.size * 2).also { map ->
            hiddenRelativePaths.forEachIndexed { index, prefix ->
                val p = prefix.trim().trim('/')
                map.putIfAbsent(p.lowercase(), index to p)
            }
        }
    }

    /**
     * What a fresh read of the hidden folders does: [keep] is the read to cache (the cached object
     * itself while it holds the same, so lists marked with it stay marked), [publish] the read to
     * put into the lists, or null when they already hold the same.
     */
    class Intake(val keep: NomediaScanResult, val publish: NomediaScanResult?, val changed: Boolean)

    /**
     * @param cached The read kept so far.
     * @param inLists The read the published list was last merged with; null when it was not merged
     *   in this run (a list from the index of an earlier run can lack files the cached read has).
     */
    fun intake(cached: NomediaScanResult?, inLists: NomediaScanResult?, fresh: NomediaScanResult): Intake {
        val same = cached != null && sameRead(cached, fresh)
        val keep = if (same) cached!! else fresh
        // Compared with the lists, not with the cache: a read cached without being put into them
        // (a quick read at a start) made every later equal read look like "nothing to show".
        val listed = inLists != null && (inLists === keep || sameRead(inLists, keep))
        return Intake(keep, if (listed) null else keep, changed = !same)
    }

    /** The same hidden folders and files, in any order. */
    fun sameRead(a: NomediaScanResult, b: NomediaScanResult): Boolean =
        a.media.size == b.media.size &&
            a.hiddenRelativePaths.mapTo(HashSet()) { it.trim().trim('/').lowercase() } ==
            b.hiddenRelativePaths.mapTo(HashSet()) { it.trim().trim('/').lowercase() } &&
            a.media.toHashSet() == b.media.toHashSet()

    /** Marks the rows of [store] in hidden folders and adds the hidden files of [nomedia] it lacks. */
    fun merge(store: List<GalleryMedia>, nomedia: NomediaScanResult): List<GalleryMedia> {
        val marked = mark(store, Index(nomedia.hiddenRelativePaths))
        if (nomedia.media.isEmpty()) return marked
        return marked + missingHiddenFiles(marked, nomedia.media)
    }

    /** [rows] with every row in a hidden folder of [index] marked hidden (the same row objects otherwise). */
    fun mark(rows: List<GalleryMedia>, index: Index): List<GalleryMedia> {
        if (index.isEmpty) return rows
        // The rows of one folder get the same answer: worked out once per folder (its relative
        // path and the parent of its files' path), not trimmed and lowercased again for each row.
        val byFolder = HashMap<String, Any>()
        return rows.map { item ->
            val path = item.absolutePath
            val parent = if (path == null) "" else path.substring(0, path.lastIndexOf('/').coerceAtLeast(0))
            val folderKey = item.relativePath + '\u0000' + parent
            val found = byFolder.getOrPut(folderKey) { hiddenFolderOf(item, index) ?: NOT_HIDDEN }
            if (found === NOT_HIDDEN) item else markedAs(item, found as String)
        }
    }

    private val NOT_HIDDEN = Any()

    /** [item] marked hidden when it lies in a hidden folder of [index], else [item] itself. */
    fun markRow(item: GalleryMedia, index: Index): GalleryMedia {
        val key = hiddenFolderOf(item, index) ?: return item
        return markedAs(item, key)
    }

    private fun markedAs(item: GalleryMedia, key: String): GalleryMedia = item.copy(
        isHidden = true,
        bucketId = NomediaScanner.syntheticBucketId(key),
        bucketName = key.substringAfterLast('/').ifBlank { item.bucketName },
        relativePath = "$key/",
    )

    /** The hidden folder of [index] (as written) that [item] lies in; null when none. */
    private fun hiddenFolderOf(item: GalleryMedia, index: Index): String? {
        val itemRel = item.relativePath.trim().trim('/').lowercase()
        // Anchored at the volume root: a hidden "Movies/" must not catch "DCIM/Movies/".
        val itemAbsRel = item.absolutePath?.let(::volumeRelative).orEmpty().lowercase()
        // The first hidden folder in list order wins. Each row looks up its own folder and the ones
        // above it; checking every hidden folder for every row cost rows x folders comparisons.
        var best: Pair<Int, String>? = null
        val consider: (String) -> Unit = { dir ->
            val found = index.byFolder[dir]
            if (found != null && (best == null || found.first < best!!.first)) best = found
        }
        forEachFolderOf(itemRel, includeSelf = true, consider)
        if (itemAbsRel.isNotEmpty()) forEachFolderOf(itemAbsRel, includeSelf = false, consider)
        return best?.second
    }

    /**
     * The hidden files of [hidden] that [rows] has no row for: same folder and name, same path, or
     * same album, name and size. Only rows named like one of the hidden files can be the same file,
     * so the keys are built for those rows alone (not for the whole library).
     */
    private fun missingHiddenFiles(rows: List<GalleryMedia>, hidden: List<GalleryMedia>): List<GalleryMedia> {
        // Lowercased as the keys below are, so a row passes exactly when one of its keys could match.
        val names = HashSet<String>(hidden.size * 4)
        for (item in hidden) {
            names += item.displayName.lowercase()
            item.absolutePath?.let { names += it.substringAfterLast('/').lowercase() }
        }
        val seen = HashSet<String>()
        for (item in rows) {
            val fileName = item.absolutePath?.substringAfterLast('/')?.lowercase()
            if (item.displayName.lowercase() !in names && (fileName == null || fileName !in names)) continue
            val normRel = item.relativePath.trim().trim('/')
            if (normRel.isNotEmpty()) seen.add("$normRel/${item.displayName}".lowercase())
            seen.add(item.storagePath.lowercase())
            item.absolutePath?.let { seen.add(it.lowercase()) }
            seen.add("${item.bucketId}:${item.displayName}:${item.sizeBytes}".lowercase())
        }
        return hidden.filter { item ->
            val normRel = item.relativePath.trim().trim('/')
            val relKey = if (normRel.isNotEmpty()) "$normRel/${item.displayName}".lowercase() else ""
            val absKey = item.absolutePath?.lowercase().orEmpty()
            val sigKey = "${item.bucketId}:${item.displayName}:${item.sizeBytes}".lowercase()
            val isDuplicate = (relKey.isNotEmpty() && relKey in seen) ||
                (absKey.isNotEmpty() && absKey in seen) ||
                sigKey in seen ||
                item.storagePath.lowercase() in seen
            !isDuplicate &&
                !hasTrashMarker(item.storagePath) &&
                !hasTrashMarker(item.relativePath) &&
                !item.bucketName.equals(".alexet_trash", ignoreCase = true) &&
                item.bucketId != AlbumIds.TRASH
        }
    }

    /**
     * Path below its storage volume (`/storage/emulated/<user>/` or `/storage/<id>/`), or null.
     * The same as matching `^/storage/(?:emulated/\d+|[^/]+)/(.+)$`, without a regex per row.
     */
    fun volumeRelative(absolutePath: String): String? {
        val root = "/storage/"
        if (!absolutePath.startsWith(root)) return null
        val volumeEnd = absolutePath.indexOf('/', root.length)
        if (volumeEnd <= root.length) return null
        if (volumeEnd - root.length == EMULATED.length && absolutePath.startsWith(EMULATED, root.length)) {
            val userEnd = absolutePath.indexOf('/', volumeEnd + 1)
            if (userEnd > volumeEnd + 1 && userEnd + 1 < absolutePath.length &&
                (volumeEnd + 1 until userEnd).all { absolutePath[it] in '0'..'9' }
            ) {
                return absolutePath.substring(userEnd + 1)
            }
        }
        return if (volumeEnd + 1 < absolutePath.length) absolutePath.substring(volumeEnd + 1) else null
    }

    private const val EMULATED = "emulated"

    /** Calls [action] with every folder above [path] ("a", "a/b" for "a/b/c"), and [path] itself when [includeSelf]. */
    private inline fun forEachFolderOf(path: String, includeSelf: Boolean, action: (String) -> Unit) {
        var slash = path.indexOf('/')
        while (slash > 0) {
            action(path.substring(0, slash))
            slash = path.indexOf('/', slash + 1)
        }
        if (includeSelf) action(path)
    }
}
