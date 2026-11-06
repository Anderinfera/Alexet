package dev.ander.alexet.data

/** State of a download as the downloader app reports it. */
enum class DownloaderState {
    QUEUED,
    RUNNING,
    PAUSED,
    POSTPROCESSING,
    ERROR,
    FINISHED,
    ;

    companion object {
        fun fromName(name: String?): DownloaderState? = entries.firstOrNull { it.name == name }
    }
}

/** One download of the downloader app; [bytes] and [total] are not positive when unknown. */
data class DownloaderMission(
    val path: String,
    val bytes: Long,
    val total: Long,
    val state: DownloaderState,
)

/**
 * Where a download lands when that is [folder] (the NewPipe folder), null otherwise: the name alone
 * proves nothing, a download can be saved somewhere else.
 */
fun downloaderMissionPath(root: String, folder: String?, relativePath: String): String? {
    if (folder.isNullOrBlank()) return null
    val path = root.trimEnd('/') + "/" + relativePath.trim('/')
    val parent = path.substringBeforeLast('/')
    return path.takeIf {
        DownloadMediaFilter.normalizePath(parent) == DownloadMediaFilter.normalizePath(folder).trimEnd('/')
    }
}

/** The download at [path] (normalized, see [DownloadMediaFilter.normalizePath]) writes into this folder. */
fun Album.holdsDownload(path: String): Boolean {
    val rel = relativePath.trim().trim('/').replace('\\', '/').lowercase()
    val name = name.trim().lowercase()
    return (rel.isNotEmpty() && (path.contains("/$rel/") || path.endsWith("/$rel"))) ||
        (name.isNotEmpty() && path.contains("/$name/"))
}

/** How much of the downloads into [album] is done, of those with a known size; null when there are none. */
fun folderDownloadPercent(album: Album, downloads: Map<String, ActiveDownload>): Int? {
    var done = 0L
    var total = 0L
    for ((path, download) in downloads) {
        val size = download.totalBytes?.takeIf { it > 0L } ?: continue
        if (!album.holdsDownload(path)) continue
        done += download.bytes.coerceIn(0L, size)
        total += size
    }
    return if (total > 0L) (done * 100L / total).toInt() else null
}
