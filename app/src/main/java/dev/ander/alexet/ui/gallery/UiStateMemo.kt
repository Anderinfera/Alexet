package dev.ander.alexet.ui.gallery

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
