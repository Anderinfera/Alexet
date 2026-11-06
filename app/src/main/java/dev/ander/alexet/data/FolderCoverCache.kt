package dev.ander.alexet.data

import android.graphics.Bitmap
import androidx.collection.LruCache

/**
 * Pinned in-memory cache for folder cover thumbnails.
 *
 * Holding strong references to decoded folder cover bitmaps prevents Coil's
 * [weakMemoryCache] from losing them to garbage collection when the user navigates into
 * a folder whose hundreds of media tiles fill the primary [MemoryCache].
 */
object FolderCoverCache {
    /** An eighth of the heap (the app runs with largeHeap), at least 32 MB, at most 160 MB. */
    private val MAX_BYTES = (Runtime.getRuntime().maxMemory() / 8)
        .coerceIn(32L * 1024 * 1024, 160L * 1024 * 1024)
        .toInt()

    /** Generation-free key -> key of the entry holding the bitmap; one bitmap is counted once. */
    private val aliases = HashMap<String, String>()

    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount.coerceAtLeast(1)
        }

        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (newValue == null) aliases.values.removeAll { it == key }
        }
    }

    fun get(key: String): Bitmap? = synchronized(cache) { cache.get(key) }

    /** Bitmap stored under [key], or the one [alias] points to (an older generation of the file). */
    fun get(key: String, alias: String): Bitmap? = synchronized(cache) {
        cache.get(key) ?: aliases[alias]?.let { cache.get(it) }
    }

    fun put(key: String, bitmap: Bitmap) {
        synchronized(cache) {
            cache.put(key, bitmap)
        }
    }

    /** Stores [bitmap] once under [key]; [alias] finds it too. */
    fun put(key: String, alias: String, bitmap: Bitmap) {
        synchronized(cache) {
            cache.put(key, bitmap)
            if (alias != key) aliases[alias] = key
        }
    }

    fun clear() {
        synchronized(cache) {
            cache.evictAll()
            aliases.clear()
        }
    }

    /** Drops the entries of one file (keys start with "thumb-<id>-"), e.g. after it was edited. */
    fun removeByPrefix(prefix: String) {
        synchronized(cache) {
            cache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
            aliases.keys.removeAll { it.startsWith(prefix) }
        }
    }
}
