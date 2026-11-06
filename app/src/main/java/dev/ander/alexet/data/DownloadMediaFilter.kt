package dev.ander.alexet.data

import java.io.File

/**
 * Single entry-point filter: only images and videos Alexet can show are tracked.
 * Format list lives in [NomediaScanner.mimeOf] — do not duplicate extensions here.
 */
object DownloadMediaFilter {
    private val tempSuffixes = listOf(
        ".crdownload",
        ".part",
        ".partial",
        ".tmp",
        ".download",
        ".opdownload",
    )

    fun stripTempSuffixes(filename: String): String {
        var name = filename.trim()
        var changed = true
        while (changed && name.isNotEmpty()) {
            changed = false
            for (suffix in tempSuffixes) {
                if (name.endsWith(suffix, ignoreCase = true)) {
                    name = name.substring(0, name.length - suffix.length)
                    changed = true
                    break
                }
            }
        }
        return name
    }

    fun isMediaMime(mime: String?): Boolean {
        if (mime.isNullOrBlank()) return false
        return mime.startsWith("image/", ignoreCase = true) ||
            mime.startsWith("video/", ignoreCase = true)
    }

    fun isMediaDownload(pathOrName: String, mime: String? = null): Boolean {
        val lowerPath = pathOrName.lowercase().replace('\\', '/')
        if (lowerPath.contains("/screenshots/") ||
            lowerPath.contains("/camera/") ||
            lowerPath.contains("/screenrecords/") ||
            lowerPath.contains("/screen_recordings/") ||
            lowerPath.contains("/restored/")) {
            return false
        }
        if (isMediaMime(mime)) return true
        val name = pathOrName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        if (name.isEmpty() || name.startsWith('.')) return false
        val clean = stripTempSuffixes(name)
        if (clean.isEmpty() || clean.startsWith('.')) return false
        return NomediaScanner.mimeOf(File(clean)) != null
    }

    fun normalizePath(path: String): String =
        path.trim().replace('\\', '/').lowercase()
}
