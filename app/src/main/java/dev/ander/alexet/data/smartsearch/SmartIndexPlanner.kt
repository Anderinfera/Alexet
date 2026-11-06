package dev.ander.alexet.data.smartsearch

import dev.ander.alexet.data.AppSettings
import dev.ander.alexet.data.GalleryMedia
import dev.ander.alexet.data.MediaRefs
import dev.ander.alexet.data.NomediaScanner
import dev.ander.alexet.data.SmartIndexScope

/** What an indexed row was made from: a changed file is indexed again. */
data class IndexedKey(val sizeBytes: Long, val dateModifiedMs: Long)

class SmartIndexPlan(val toIndex: List<GalleryMedia>, val toDelete: List<Long>)

/** The folders (bucket ids, an album's id) the index covers. */
data class SmartFolderFilter(
    val scope: SmartIndexScope = SmartIndexScope.ALL,
    val included: Set<Long> = emptySet(),
    val excluded: Set<Long> = emptySet(),
) {
    fun accepts(bucketId: Long): Boolean = when (scope) {
        SmartIndexScope.ALL -> true
        SmartIndexScope.ONLY_SELECTED -> bucketId in included
        SmartIndexScope.ALL_EXCEPT -> bucketId !in excluded
    }

    companion object {
        val ALL = SmartFolderFilter()

        fun of(settings: AppSettings): SmartFolderFilter = when (settings.smartIndexScope) {
            SmartIndexScope.ALL -> ALL
            SmartIndexScope.ONLY_SELECTED -> SmartFolderFilter(SmartIndexScope.ONLY_SELECTED, included = settings.smartIndexIncludedFolderIds)
            SmartIndexScope.ALL_EXCEPT -> SmartFolderFilter(SmartIndexScope.ALL_EXCEPT, excluded = settings.smartIndexExcludedFolderIds)
        }
    }
}

object SmartIndexPlanner {

    /**
     * Photos only. Nothing of a private folder is indexed: its files are encrypted, and an
     * embedding on disk would tell what they show.
     */
    fun isEligible(media: GalleryMedia, secureDirs: Collection<String>, folders: SmartFolderFilter = SmartFolderFilter.ALL): Boolean {
        if (media.isVideo || media.isOtherFile || media.isEncrypted) return false
        if (!folders.accepts(media.bucketId)) return false
        val mime = media.mimeType.ifBlank { NomediaScanner.mimeOf(media.displayName).orEmpty() }
        if (!mime.startsWith("image/", ignoreCase = true)) return false
        if (secureDirs.isEmpty()) return true
        val path = MediaRefs.portablePath(media) ?: return false
        return secureDirs.none { path.startsWith("$it/") }
    }

    /** Private folders (absolute paths) as portable lower-case keys, the form [isEligible] takes. */
    fun secureDirKeys(securePaths: Collection<String>): List<String> =
        securePaths.map { MediaRefs.key(MediaRefs.portableDirPath(it)) }.filter { it.isNotEmpty() }

    /** Newest photos first, so recent ones can be found soonest. */
    fun plan(
        media: List<GalleryMedia>,
        indexed: Map<Long, IndexedKey>,
        secureDirs: Collection<String>,
        folders: SmartFolderFilter = SmartFolderFilter.ALL,
    ): SmartIndexPlan {
        val eligible = media.filter { isEligible(it, secureDirs, folders) }
        val live = HashSet<Long>(eligible.size * 2)
        val toIndex = ArrayList<GalleryMedia>()
        for (m in eligible) {
            live += m.id
            val key = indexed[m.id]
            if (key == null || key.sizeBytes != m.sizeBytes || key.dateModifiedMs != m.dateModifiedMs) toIndex += m
        }
        toIndex.sortByDescending { it.capturedAtMs }
        val toDelete = indexed.keys.filter { it !in live }
        return SmartIndexPlan(toIndex, toDelete)
    }
}
