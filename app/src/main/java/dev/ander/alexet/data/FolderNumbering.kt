package dev.ander.alexet.data

/**
 * Folder settings → Numbering: the folder's files are always named by their place, 1.jpg, 2.mp4,
 * 3.png… This works out which files must be renamed when the folder changed.
 */
object FolderNumbering {

    /** One file of the folder: its name and when it came in. */
    data class Entry(val id: Long, val name: String, val addedMs: Long)

    /** The number a name carries ("12.jpg" or "12" → 12); null for any other name ("01.jpg" included). */
    fun numberOf(name: String): Int? {
        val base = name.substringBeforeLast('.', missingDelimiterValue = name)
        if (base.isEmpty() || base.length > 9 || !base.all { it in '0'..'9' }) return null
        if (base.length > 1 && base[0] == '0') return null
        return base.toInt().takeIf { it > 0 }
    }

    /**
     * The order the files should be numbered in: the numbered ones keep theirs (a gap left by a
     * deleted file closes), the others come after them, first in [newNames] order (the files
     * Alexet just moved or copied in, in selection order), then by when they came in.
     */
    fun order(entries: List<Entry>, newNames: List<String> = emptyList()): List<Entry> {
        val (numbered, others) = entries.partition { numberOf(it.name) != null }
        val rank = HashMap<String, Int>(newNames.size * 2)
        newNames.forEachIndexed { i, name -> rank.putIfAbsent(name, i) }
        return numbered.sortedWith(compareBy<Entry>({ numberOf(it.name) }, { it.name })) +
            others.sortedWith(compareBy<Entry>({ rank[it.name] ?: Int.MAX_VALUE }, { it.addedMs }, { it.name }))
    }

    /** Index of the first file in [ordered] not named by its place (1-based), -1 when all are. */
    fun firstMisplaced(ordered: List<Entry>): Int =
        ordered.indices.firstOrNull { numberOf(ordered[it].name) != it + 1 } ?: -1
}
