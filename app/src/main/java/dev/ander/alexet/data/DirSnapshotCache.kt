package dev.ander.alexet.data

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Folder dates as [DirSnapshotCache.folderDate] gives them (nanoseconds). */
internal object FolderDates {
    /** A folder date younger than this at a listing may still hide a change made in the same moment. */
    const val SETTLE_MS = 2_000L

    /** [date] (ns) was at least [SETTLE_MS] old at [listedAtMs] (wall clock ms). */
    fun settled(date: Long, listedAtMs: Long): Boolean = listedAtMs - date / 1_000_000L >= SETTLE_MS
}

/** One entry of a folder: its type, and size and date for a file. */
internal class DirEntry(
    val name: String,
    val isDirectory: Boolean,
    val isFile: Boolean,
    /** A symbolic link (type, size and date are its target's). */
    val isLink: Boolean,
    val size: Long,
    val modifiedMs: Long,
)

/**
 * What [read] made of a folder, kept while the folder's date stays. Adding, removing or renaming an
 * entry changes the date of its folder (also for files written through MediaStore, measured on
 * GrapheneOS), so a folder with the same date has the same entries: one stat replaces a listing
 * with a stat per entry (a folder of 4-5k files took 0.4-1.8 s to list). What a kept value says
 * about a file itself (size, date) is out of date when the file was rewritten in place: values
 * older than [maxAgeMs] are read again, and a caller that learns of a change [invalidate]s.
 * A date that was less than [FolderDates.SETTLE_MS] old at the listing is not trusted (FAT and
 * exFAT cards keep folder dates to two seconds, and a change in the same moment as the listing
 * would keep the date): such a folder is listed again next time.
 */
internal class DirSnapshotCache<T : Any>(
    private val read: (File) -> T?,
    private val maxAgeMs: Long = Long.MAX_VALUE,
    private val dateOf: (File) -> Long? = ::folderDate,
    private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val wallNow: () -> Long = System::currentTimeMillis,
) {
    private class Kept<T>(val date: Long, val readAt: Long, val value: T, val trusted: Boolean)

    private val kept = ConcurrentHashMap<String, Kept<T>>()

    /**
     * [dir] as it is now, or null when it is not a readable folder. [mayRead] is asked before a
     * listing (never for a kept value): false leaves the folder unread and gives null.
     */
    fun get(dir: File, force: Boolean = false, mayRead: () -> Boolean = { true }): T? {
        val key = dir.path
        val date = dateOf(dir) ?: run {
            kept.remove(key)
            return null
        }
        val t = now()
        val old = kept[key]
        if (!force && old != null && old.trusted && old.date == date && t - old.readAt < maxAgeMs) return old.value
        if (!mayRead()) return null
        // The date from before the listing: an entry added while listing makes it newer, so the
        // folder is listed again next time.
        val value = read(dir) ?: run {
            kept.remove(key)
            return null
        }
        kept[key] = Kept(date, t, value, FolderDates.settled(date, wallNow()))
        return value
    }

    /** [dir] changed in a way its date does not show (a file rewritten in place): listed again next time. */
    fun invalidate(dir: File) {
        kept.remove(dir.path)
    }

    val size: Int get() = kept.size

    companion object {
        /** The folder's date (one stat, as fine as the file system keeps it), or null when [dir] is not a folder. */
        fun folderDate(dir: File): Long? = try {
            val attrs = Files.readAttributes(dir.toPath(), BasicFileAttributes::class.java)
            if (attrs.isDirectory) attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS) else null
        } catch (e: Exception) {
            null
        }

        /** [name] in [dir] with its type, size and date: one stat (two for a symbolic link); null when unreadable. */
        fun entryOf(dir: File, name: String): DirEntry? = try {
            val path = File(dir, name).toPath()
            var attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            val link = attrs.isSymbolicLink
            if (link) attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
            DirEntry(name, attrs.isDirectory, attrs.isRegularFile, link, attrs.size(), attrs.lastModifiedTime().toMillis())
        } catch (e: Exception) {
            null
        }
    }
}
