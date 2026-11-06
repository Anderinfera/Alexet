package dev.ander.alexet.data

/**
 * Positions of [rows] by bucket id, raw MediaStore bucket id and normalized relative path: a
 * folder's rows are looked up instead of filtering (and trimming the path of) every row of the
 * library for each folder.
 */
internal class FolderRowIndex(val rows: List<GalleryMedia>) {
    val byBucket = HashMap<Long, MutableList<Int>>()
    val byRawBucket = HashMap<Long, MutableList<Int>>()
    val byRelPath = HashMap<String, MutableList<Int>>()

    /** Every row by id (the last one of an id, as associateBy). */
    val byId: Map<Long, GalleryMedia> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        HashMap<Long, GalleryMedia>(rows.size * 2).also { map -> rows.forEach { map[it.id] = it } }
    }

    init {
        rows.forEachIndexed { i, item ->
            byBucket.getOrPut(item.bucketId) { ArrayList() } += i
            if (item.rawBucketId != 0L) byRawBucket.getOrPut(item.rawBucketId) { ArrayList() } += i
            val rel = normalizeRel(item.relativePath)
            if (rel.isNotEmpty()) byRelPath.getOrPut(rel) { ArrayList() } += i
        }
    }

    /**
     * The rows of folder [albumId] in list order, one per id: its bucket, and with [album] its old
     * MediaStore bucket ids and its path too (the same rows as testing each row of the library).
     */
    fun rowsOf(albumId: Long, album: Album?): List<GalleryMedia> {
        val positions = java.util.TreeSet<Int>()
        byBucket[albumId]?.let(positions::addAll)
        if (album != null) {
            album.legacyBucketIds.forEach { legacy -> byRawBucket[legacy]?.let(positions::addAll) }
            val rel = normalizeRel(album.relativePath)
            if (rel.isNotEmpty()) byRelPath[rel]?.let(positions::addAll)
        }
        if (positions.isEmpty()) return emptyList()
        val seen = HashSet<Long>(positions.size * 2)
        val out = ArrayList<GalleryMedia>(positions.size)
        for (i in positions) {
            val item = rows[i]
            if (seen.add(item.id)) out += item
        }
        return out
    }

    companion object {
        fun normalizeRel(path: String): String = path.trim().trim('/').lowercase()
    }
}
