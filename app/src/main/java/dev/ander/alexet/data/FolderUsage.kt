package dev.ander.alexet.data

import android.content.Context

/**
 * When each folder was last opened in the app, for its info. Kept on this phone only (not in the
 * settings export): it is this phone's history, not a setting.
 */
object FolderUsage {
    private const val PREFS = "folder_usage"

    fun markOpened(context: Context, folderId: Long, now: Long = System.currentTimeMillis()) {
        if (folderId == AlbumIds.NONE) return
        prefs(context).edit().putLong(folderId.toString(), now).apply()
    }

    /** 0 when it was never opened (since this was first recorded). */
    fun lastOpenedMs(context: Context, folderId: Long): Long = prefs(context).getLong(folderId.toString(), 0L)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** What a folder's info shows beyond its name. */
data class FolderDetails(
    /** The whole path on the phone, or null for a folder that is not one on disk (favorites). */
    val path: String?,
    val fileCount: Int,
    val sizeBytes: Long,
    /** When its oldest file was added: the folder's own creation date is not kept by Android. */
    val createdMs: Long,
    /** When it was last opened in the app; 0 = not yet. */
    val lastUsedMs: Long,
) {
    companion object {
        fun of(files: List<GalleryMedia>, path: String?, lastUsedMs: Long): FolderDetails {
            var size = 0L
            var oldest = Long.MAX_VALUE
            for (file in files) {
                size += file.sizeBytes.coerceAtLeast(0L)
                val added = if (file.dateAddedMs > 0L) file.dateAddedMs else file.dateModifiedMs
                if (added in 1 until oldest) oldest = added
            }
            return FolderDetails(
                path = path,
                fileCount = files.size,
                sizeBytes = size,
                createdMs = if (oldest == Long.MAX_VALUE) 0L else oldest,
                lastUsedMs = lastUsedMs,
            )
        }
    }
}
