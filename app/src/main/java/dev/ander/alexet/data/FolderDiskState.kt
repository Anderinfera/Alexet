package dev.ander.alexet.data

import android.content.Context

/**
 * The date each folder had when the quick scan last went through all its files looking for ones
 * MediaStore does not list. A folder with the same date holds the same files (a file that comes,
 * goes or is renamed changes it), so it is not listed again: listing Camera or Memes (4-5k files)
 * took 1.4-1.8 s each time a folder was opened, and found nothing. Once in [FULL_LIST_MS] it is
 * listed anyway. Kept across restarts, on this phone only (paths of this phone's storage).
 */
internal class FolderDiskState(private val store: Store) {

    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String)
    }

    /**
     * Whether [path] (now dated [date], ns) has to be listed: never was, its date moved, the last
     * listing is old, or it came so soon after the folder's last change that its date can't be trusted.
     */
    fun needsListing(path: String, date: Long, nowMs: Long, force: Boolean = false): Boolean {
        if (force) return true
        val saved = store.get(path) ?: return true
        val listedDate = saved.substringBefore(':').toLongOrNull() ?: return true
        val listedAt = saved.substringAfter(':', "").toLongOrNull() ?: return true
        return listedDate != date || nowMs - listedAt !in 0 until FULL_LIST_MS ||
            // Listed while the date was fresh: a change in that moment may not have moved it.
            !FolderDates.settled(listedDate, listedAt)
    }

    /** [path] was listed through and every file of it was dealt with; [date] is from before the listing. */
    fun listed(path: String, date: Long, nowMs: Long) {
        store.put(path, "$date:$nowMs")
    }

    companion object {
        const val FULL_LIST_MS = 10 * 60_000L
        private const val PREFS = "folder_disk_state"

        fun of(context: Context): FolderDiskState {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return FolderDiskState(object : Store {
                override fun get(key: String): String? = prefs.getString(key, null)
                override fun put(key: String, value: String) {
                    prefs.edit().putString(key, value).apply()
                }
            })
        }
    }
}
