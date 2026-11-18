package dev.ander.alexet.ui.gallery

import dev.ander.alexet.data.GalleryMedia
import dev.ander.alexet.data.SortSpec

/**
 * Lists derived from [GalleryUiState], kept across its copies.
 *
 * Every `copy` of the state (a download tick, a hover, a progress step) started with empty caches,
 * so the library was filtered and sorted again and the grid got a new list. Here a result is
 * reused while each input is the same object (collections, maps, settings) or an equal value
 * (strings, numbers, enums).
 */
internal object UiStateMemo {
    private class Entry(val inputs: Array<out Any?>, val value: Any?)

    private const val MAX_ENTRIES = 128

    private val entries = object : LinkedHashMap<Any, Entry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, Entry>?): Boolean =
            size > MAX_ENTRIES
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: Any, vararg inputs: Any?, compute: () -> T): T {
        synchronized(entries) {
            val hit = entries[key]
            if (hit != null && sameInputs(hit.inputs, inputs)) return hit.value as T
        }
        val value = compute()
        synchronized(entries) { entries[key] = Entry(inputs, value) }
        return value
    }

    private fun sameInputs(old: Array<out Any?>, new: Array<out Any?>): Boolean {
        if (old.size != new.size) return false
        for (i in old.indices) {
            val a = old[i]
            val b = new[i]
            if (a === b) continue
            // Deep equality on big collections would cost as much as recomputing.
            val byValue = a is String || a is Number || a is Boolean || a is Enum<*>
            if (!byValue || a != b) return false
        }
        return true
    }
}

/**
 * A folder's sorted files, kept while its own rows are equal.
 *
 * A folder's list is built again on every library change (any download, in any folder); its rows
 * usually stay the same, and sorting them again cost more than comparing them. The same list
 * also lets the grid skip the folder.
 */
internal object FolderSortCache {
    private class Entry(
        val rows: List<GalleryMedia>,
        val contentOrder: List<Long>?,
        val spec: SortSpec?,
        val seed: Long,
        val result: List<GalleryMedia>,
    )

    private const val MAX_ENTRIES = 16

    private val entries = object : LinkedHashMap<Long, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Entry>?): Boolean =
            size > MAX_ENTRIES
    }

    /** [compute] sorts [rows] by [contentOrder] or [spec] (and [seed]); its result depends on nothing else. */
    fun get(
        albumId: Long,
        rows: List<GalleryMedia>,
        contentOrder: List<Long>?,
        spec: SortSpec?,
        seed: Long,
        compute: () -> List<GalleryMedia>,
    ): List<GalleryMedia> {
        val hit = synchronized(entries) { entries[albumId] }
        // Compared outside the lock: a big folder's rows take a moment.
        if (hit != null && hit.seed == seed && hit.spec == spec && hit.contentOrder == contentOrder && hit.rows == rows) {
            return hit.result
        }
        val result = compute()
        synchronized(entries) { entries[albumId] = Entry(rows, contentOrder, spec, seed, result) }
        return result
    }
}
