package dev.ander.alexet.data

import android.net.Uri
import java.io.File

internal object DownloadsWatchLogic {
    const val DEBOUNCE_MS = 400L

    fun mediaMimeOf(path: String): String? {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return null
        val name = trimmed.substringAfterLast('/').substringAfterLast('\\')
        if (name.startsWith('.')) return null
        return NomediaScanner.mimeOf(File(trimmed))
    }

    fun filterMediaPaths(paths: Iterable<String>): List<String> {
        val seen = LinkedHashSet<String>()
        for (raw in paths) {
            val path = raw.trim()
            if (path.isEmpty()) continue
            if (mediaMimeOf(path) == null) continue
            seen += path
        }
        return seen.toList()
    }

    fun addPendingPaths(pending: MutableSet<String>, incoming: Iterable<String>) {
        for (path in incoming) {
            val trimmed = path.trim()
            if (trimmed.isNotEmpty()) pending += trimmed
        }
    }

    fun isDownloadsUri(uri: Uri?): Boolean {
        if (uri == null) return false
        val s = uri.toString().lowercase()
        return s.contains("/downloads") ||
            s.contains("/download/") ||
            (s.contains("content://media") && s.contains("download"))
    }

    fun isDownloadsFolderItem(
        relativePath: String,
        bucketName: String,
        absolutePath: String? = null,
    ): Boolean {
        val rel = relativePath.trim().trim('/').replace('\\', '/').lowercase()
        if (rel.isNotEmpty()) {
            val first = rel.substringBefore('/')
            return first == "download" || first == "downloads"
        }
        val abs = absolutePath?.replace('\\', '/')?.lowercase().orEmpty()
        if (abs.isNotEmpty()) {
            val segs = abs.trim('/').split('/')
            val dlIdx = segs.indexOfFirst { it == "download" || it == "downloads" }
            if (dlIdx >= 0) {
                val parent = segs.getOrNull(dlIdx - 1).orEmpty()
                // Root Downloads album: /storage/emulated/0/Download or /storage/<uuid>/Download.
                // Nested folders like Pictures/Download/ are not the Downloads album.
                val underStorageRoot = parent == "0" ||
                    parent.contains('-') ||
                    parent.equals("emulated", ignoreCase = true) ||
                    parent.equals("storage", ignoreCase = true)
                return underStorageRoot
            }
            return false
        }
        val name = bucketName.trim()
        return name.equals("Download", ignoreCase = true) ||
            name.equals("Downloads", ignoreCase = true)
    }

    fun itemDedupeKey(item: GalleryMedia): String =
        "${item.bucketId}_${item.displayName}_${item.sizeBytes}".lowercase()

    fun itemNameKey(item: GalleryMedia): String =
        "${item.bucketId}_${item.displayName}".lowercase()

    fun isDownloadsCollectionUri(uri: Uri): Boolean {
        val s = uri.toString().lowercase()
        return s.contains("/downloads/") || s.endsWith("/downloads")
    }

    /**
     * Append files that exist only in the Downloads collection.
     * Never replace an Images/Video row — that swapped URIs under visible tiles
     * and forced Download thumbnails to decode full originals (~16s of brown tiles).
     */
    fun mergeDownloads(
        current: List<GalleryMedia>,
        downloads: List<GalleryMedia>,
    ): List<GalleryMedia> {
        if (downloads.isEmpty()) return current
        val keys = HashSet<String>(current.size)
        val uris = HashSet<Uri>(current.size)
        val paths = HashSet<String>(current.size)
        val names = HashSet<String>(current.size)
        for (item in current) {
            keys += itemDedupeKey(item)
            uris += item.uri
            item.absolutePath?.lowercase()?.let { paths += it }
            names += itemNameKey(item)
        }
        val merged = ArrayList<GalleryMedia>(current.size + downloads.size)
        merged.addAll(current)
        for (item in downloads) {
            val path = item.absolutePath?.lowercase()
            if (itemDedupeKey(item) in keys) continue
            if (item.uri in uris) continue
            if (path != null && path in paths) continue
            if (itemNameKey(item) in names) continue
            merged += item
            keys += itemDedupeKey(item)
            uris += item.uri
            if (path != null) paths += path
            names += itemNameKey(item)
        }
        return merged
    }
}
