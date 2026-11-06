package dev.ander.alexet.data

import android.net.Uri

/** Pure list/diff logic behind the incremental media index; unit-tested on the JVM. */
object MediaIndexLogic {

    private const val MEDIA_URI_PREFIX = "content://media/"

    /** Row that MediaStore owns (delta sync may replace or delete it). Hidden `file://` and secure rows are not. */
    fun isMediaStoreRow(item: GalleryMedia): Boolean =
        item.uri.toString().startsWith(MEDIA_URI_PREFIX)

    private fun isDownloadsUri(uri: Uri): Boolean =
        uri.toString().contains("/downloads", ignoreCase = true)

    /**
     * One row per id. The same file is returned by both the Images/Video and the Downloads
     * collection with the same id; keep the non-Downloads URI (it has MediaStore thumbnails).
     */
    fun dedupeById(rows: List<GalleryMedia>): List<GalleryMedia> {
        if (rows.isEmpty()) return rows
        val byId = LinkedHashMap<Long, GalleryMedia>(rows.size)
        for (row in rows) {
            val existing = byId[row.id]
            if (existing == null || (isDownloadsUri(existing.uri) && !isDownloadsUri(row.uri))) {
                byId[row.id] = row
            }
        }
        return byId.values.toList()
    }

    /**
     * Apply a MediaStore delta to the currently published list: drop [deletedIds], replace
     * rows in place by id, append new rows, and drop hidden `file://` duplicates that a new
     * MediaStore row now covers (same absolute path).
     */
    fun mergeDelta(
        base: List<GalleryMedia>,
        upserts: List<GalleryMedia>,
        deletedIds: Set<Long>,
    ): List<GalleryMedia> {
        if (upserts.isEmpty() && deletedIds.isEmpty()) return base
        val upsertById = HashMap<Long, GalleryMedia>(upserts.size)
        val upsertPaths = HashSet<String>(upserts.size)
        for (u in upserts) {
            upsertById[u.id] = u
            u.absolutePath?.let { upsertPaths += it.lowercase() }
        }
        val out = ArrayList<GalleryMedia>(base.size + upserts.size)
        val placed = HashSet<Long>(upserts.size)
        for (item in base) {
            if (item.id in deletedIds) continue
            val replacement = upsertById[item.id]
            if (replacement != null) {
                out += replacement
                placed += item.id
                continue
            }
            if (!isMediaStoreRow(item)) {
                val path = item.absolutePath?.lowercase()
                if (path != null && path in upsertPaths) continue
            }
            out += item
        }
        for (u in upserts) {
            if (u.id !in placed) out += u
        }
        return out
    }

    /** Ids of MediaStore rows that MediaStore no longer lists. */
    fun missingIds(base: List<GalleryMedia>, liveIds: Set<Long>): List<Long> {
        val out = ArrayList<Long>()
        for (item in base) {
            if (isMediaStoreRow(item) && item.id !in liveIds) out += item.id
        }
        return out
    }

    class Diff(val upserts: List<GalleryMedia>, val deletes: List<Long>) {
        val isEmpty: Boolean get() = upserts.isEmpty() && deletes.isEmpty()
    }

    /**
     * Journal diff: what changed in [published] since [persisted] was written. Unchanged rows
     * are usually the same object instance, so the common case is an identity check.
     */
    fun diff(persisted: Map<Long, GalleryMedia>, published: List<GalleryMedia>): Diff {
        val seen = HashSet<Long>(published.size)
        val upserts = ArrayList<GalleryMedia>()
        for (item in published) {
            if (!seen.add(item.id)) continue
            val old = persisted[item.id]
            if (old !== item && old != item) upserts += item
        }
        val deletes = if (seen.size == persisted.size && persisted.keys.all { it in seen }) {
            emptyList()
        } else {
            persisted.keys.filter { it !in seen }
        }
        return Diff(upserts, deletes)
    }

    /**
     * A published list that suddenly lost most of the index is a transient state (permission
     * lost, query failure), not a user operation; the journal must not wipe the index for it.
     */
    fun isSuspiciousShrink(persistedSize: Int, deletes: Int): Boolean =
        deletes >= SUSPICIOUS_MIN_DELETES && deletes * 2 > persistedSize

    private const val SUSPICIOUS_MIN_DELETES = 1_000

    /**
     * Why the stored index no longer describes this phone's MediaStore database, or null when it
     * still does. MediaStore gets a new version string whenever its database is created anew
     * (data moved to another phone, media storage cleared, rebuild): ids then point at other files
     * and the generation counter starts over, so a delta sync would see "nothing changed".
     * Volumes missing on either side (SD card out, first run of this check) are not compared.
     */
    fun storeReplacedReason(
        storedVersions: Map<String, String?>,
        currentVersions: Map<String, String>,
        storedGenerations: Map<String, Long>,
        currentGenerations: Map<String, Long>?,
    ): String? {
        for ((volume, current) in currentVersions) {
            val stored = storedVersions[volume]
            if (stored != null && databaseIdentity(stored) != databaseIdentity(current)) {
                return "MediaStore database of $volume replaced ($stored -> $current)"
            }
        }
        currentGenerations?.forEach { (volume, current) ->
            val stored = storedGenerations[volume] ?: 0L
            if (stored > 0L && current < stored) return "MediaStore generation of $volume went back ($stored -> $current)"
        }
        return null
    }

    /**
     * The version reads "<schema version>:<database uuid>". A schema bump (system update) keeps the
     * rows and their ids, only a new uuid means a new database. Other formats are compared whole.
     */
    private fun databaseIdentity(version: String): String =
        version.substringAfterLast(':').ifEmpty { version }
}
