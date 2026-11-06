package dev.ander.alexet.data

import android.net.DummyUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HiddenFolderMarkerTest {

    private fun row(
        id: Long,
        name: String,
        rel: String,
        abs: String? = "/storage/emulated/0/$rel$name",
        size: Long = 100L,
        bucket: Long = NomediaScanner.syntheticBucketId(rel),
        file: Boolean = false,
        hidden: Boolean = false,
    ) = GalleryMedia(
        id = id,
        uri = DummyUri(if (file) "file://$abs" else "content://media/external/images/media/$id"),
        displayName = name,
        mimeType = "image/jpeg",
        dateTakenMs = 0L,
        dateModifiedMs = id * 1000L,
        dateAddedMs = id * 1000L,
        sizeBytes = size,
        width = 0,
        height = 0,
        durationMs = 0L,
        bucketId = bucket,
        bucketName = rel.trim('/').substringAfterLast('/'),
        relativePath = rel,
        isHidden = hidden,
        absolutePath = abs,
    )

    private val hiddenFolders = listOf("Gallery/Anime/", "Movies", "Gallery/Secret/Deep")

    private val library = listOf(
        row(1, "a.jpg", "DCIM/Camera/"),
        // In a hidden folder by its relative path and by its absolute path.
        row(2, "b.jpg", "Gallery/Anime/"),
        row(3, "c.jpg", "Gallery/anime/sub/"),
        // "DCIM/Movies" is not the hidden "Movies" at the volume root.
        row(4, "d.jpg", "DCIM/Movies/"),
        row(5, "e.jpg", "Movies/"),
        row(6, "f.jpg", "", abs = "/storage/1A2B-3C4D/Movies/f.jpg"),
        row(7, "g.jpg", "Gallery/Secret/Deep/x/"),
        row(8, "h.jpg", "Pictures/", abs = null),
    )

    private val hiddenFiles = listOf(
        // Same file as row 2 (path): a duplicate.
        row(-2, "b.jpg", "Gallery/Anime/", file = true, hidden = true),
        // New hidden files.
        row(-9, "new.jpg", "Gallery/Anime/", file = true, hidden = true),
        row(-10, "B.JPG", "Gallery/Anime/", abs = "/storage/emulated/0/Gallery/Anime/other/B.JPG", file = true, hidden = true),
        // Same album, name and size as row 7 under another path: a duplicate.
        row(-7, "g.jpg", "x/", abs = "/storage/emulated/0/elsewhere/g.jpg", bucket = 7L, file = true, hidden = true),
        // The trash is never added.
        row(-11, "t.jpg", ".alexet_trash/", file = true, hidden = true),
    ).let { list -> list.map { if (it.id == -7L) it.copy(bucketId = library[6].bucketId) else it } }

    private val nomedia = NomediaScanResult(hiddenFiles, hiddenFolders)

    @Test
    fun mergeIsTheSameAsBefore() {
        assertEquals(legacyMerge(library, nomedia), HiddenFolderMarker.merge(library, nomedia))
    }

    @Test
    fun rowsOutsideHiddenFoldersStayTheSameObjects() {
        val merged = HiddenFolderMarker.merge(library, nomedia)
        assertSame(library[0], merged[0])
        assertSame(library[3], merged[3])
        assertSame(library[7], merged[7])
        assertEquals(true, merged[1].isHidden)
        assertEquals(NomediaScanner.syntheticBucketId("Gallery/Anime"), merged[2].bucketId)
    }

    @Test
    fun markingOnlyTheNewRowsOfADeltaGivesTheFullResult() {
        val base = HiddenFolderMarker.merge(library, nomedia)
        val upserts = listOf(
            row(20, "new1.jpg", "Gallery/Anime/"),
            row(21, "new2.jpg", "DCIM/Camera/"),
            // A changed row of a hidden folder comes back unmarked from MediaStore.
            row(2, "b.jpg", "Gallery/Anime/", size = 222L),
        )
        val deleted = setOf(1L)
        val merged = MediaIndexLogic.mergeDelta(base, upserts, deleted)
        val unchanged = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<GalleryMedia, Boolean>())
        unchanged.addAll(base)
        val index = HiddenFolderMarker.Index(nomedia.hiddenRelativePaths)
        val incremental = merged.map { if (it in unchanged) it else HiddenFolderMarker.markRow(it, index) }
        assertEquals(legacyMerge(merged, nomedia), incremental)
    }

    @Test
    fun aReadCachedButNeverPutIntoTheListsIsPublished() {
        // A start: the quick read (with a new file) was cached, the list is the index of an earlier
        // run. Every later read equals the cached one and still has to reach the lists.
        val withNew = NomediaScanResult(hiddenFiles, hiddenFolders)
        val again = NomediaScanResult(hiddenFiles.reversed(), hiddenFolders.reversed())
        val intake = HiddenFolderMarker.intake(cached = withNew, inLists = null, fresh = again)
        assertSame(withNew, intake.keep)
        assertSame(withNew, intake.publish)
        assertEquals(false, intake.changed)
    }

    @Test
    fun aReadTheListsAlreadyHoldIsNotPublishedAgain() {
        val read = NomediaScanResult(hiddenFiles, hiddenFolders)
        val same = NomediaScanResult(hiddenFiles.toList(), hiddenFolders.toList())
        val intake = HiddenFolderMarker.intake(cached = read, inLists = read, fresh = same)
        assertSame(read, intake.keep)
        assertEquals(null, intake.publish)
        // The lists merged with an equal (not the same) read: nothing to put in either.
        assertEquals(null, HiddenFolderMarker.intake(cached = null, inLists = same, fresh = read).publish)
    }

    @Test
    fun aNewFileIsCachedAndPublished() {
        val old = NomediaScanResult(hiddenFiles.dropLast(1), hiddenFolders)
        val fresh = NomediaScanResult(hiddenFiles, hiddenFolders)
        val intake = HiddenFolderMarker.intake(cached = old, inLists = old, fresh = fresh)
        assertSame(fresh, intake.keep)
        assertSame(fresh, intake.publish)
        assertEquals(true, intake.changed)
        // The lists still hold an older read than the cache: the cached one goes in.
        val lagging = HiddenFolderMarker.intake(cached = fresh, inLists = old, fresh = NomediaScanResult(hiddenFiles, hiddenFolders))
        assertSame(fresh, lagging.publish)
    }

    @Test
    fun volumeRelativeMatchesTheRegex() {
        val regex = Regex("^/storage/(?:emulated/\\d+|[^/]+)/(.+)$")
        val paths = listOf(
            "/storage/emulated/0/DCIM/a.jpg", "/storage/emulated/10/x", "/storage/emulated/0", "/storage/emulated/0/",
            "/storage/emulated/abc/x", "/storage/emulated/", "/storage/emulated", "/storage/1A2B-3C4D/Movies/f.jpg",
            "/storage/1A2B-3C4D/", "/storage//x", "/storage/", "/sdcard/DCIM/a.jpg", "/storage/emulated/0x/y",
            "/storage/emulated/0/a/b/c", "/storage/self/primary/x",
        )
        for (path in paths) {
            assertEquals(path, regex.find(path)?.groupValues?.get(1), HiddenFolderMarker.volumeRelative(path))
        }
    }

    /** The merge as it was in GalleryRepository before it moved here (regex, keys for every row). */
    private fun legacyMerge(store: List<GalleryMedia>, nomedia: NomediaScanResult): List<GalleryMedia> {
        val volumeRegex = Regex("^/storage/(?:emulated/\\d+|[^/]+)/(.+)$")
        fun forEachFolderOf(path: String, includeSelf: Boolean, action: (String) -> Unit) {
            var slash = path.indexOf('/')
            while (slash > 0) {
                action(path.substring(0, slash))
                slash = path.indexOf('/', slash + 1)
            }
            if (includeSelf) action(path)
        }
        val prefixes = nomedia.hiddenRelativePaths
        val marked = if (prefixes.isEmpty()) store else {
            val hidden = HashMap<String, Pair<Int, String>>()
            prefixes.forEachIndexed { index, prefix ->
                val p = prefix.trim().trim('/')
                hidden.putIfAbsent(p.lowercase(), index to p)
            }
            store.map { item ->
                val itemRel = item.relativePath.trim().trim('/').lowercase()
                val itemAbsRel = item.absolutePath?.let { volumeRegex.find(it)?.groupValues?.get(1) }.orEmpty().lowercase()
                var best: Pair<Int, String>? = null
                val consider: (String) -> Unit = { dir ->
                    val found = hidden[dir]
                    if (found != null && (best == null || found.first < best!!.first)) best = found
                }
                forEachFolderOf(itemRel, includeSelf = true, consider)
                if (itemAbsRel.isNotEmpty()) forEachFolderOf(itemAbsRel, includeSelf = false, consider)
                val key = best?.second
                if (key != null) {
                    item.copy(
                        isHidden = true,
                        bucketId = NomediaScanner.syntheticBucketId(key),
                        bucketName = key.substringAfterLast('/').ifBlank { item.bucketName },
                        relativePath = "$key/",
                    )
                } else item
            }
        }
        if (nomedia.media.isEmpty()) return marked
        val seen = HashSet<String>()
        marked.forEach { item ->
            val normRel = item.relativePath.trim().trim('/')
            if (normRel.isNotEmpty()) seen.add("$normRel/${item.displayName}".lowercase())
            seen.add(item.storagePath.lowercase())
            item.absolutePath?.let { seen.add(it.lowercase()) }
            seen.add("${item.bucketId}:${item.displayName}:${item.sizeBytes}".lowercase())
        }
        val extra = nomedia.media.filter { item ->
            val normRel = item.relativePath.trim().trim('/')
            val relKey = if (normRel.isNotEmpty()) "$normRel/${item.displayName}".lowercase() else ""
            val absKey = item.absolutePath?.lowercase().orEmpty()
            val sigKey = "${item.bucketId}:${item.displayName}:${item.sizeBytes}".lowercase()
            val isDuplicate = (relKey.isNotEmpty() && relKey in seen) || (absKey.isNotEmpty() && absKey in seen) ||
                sigKey in seen || item.storagePath.lowercase() in seen
            !isDuplicate &&
                !item.storagePath.contains(".alexet_trash", ignoreCase = true) &&
                !item.relativePath.contains(".alexet_trash", ignoreCase = true) &&
                !item.bucketName.equals(".alexet_trash", ignoreCase = true) &&
                item.bucketId != AlbumIds.TRASH
        }
        return marked + extra
    }
}
