package dev.ander.alexet.ui.gallery

import dev.ander.alexet.data.GalleryMedia
import dev.ander.alexet.data.SortField
import dev.ander.alexet.ui.components.formatBytes
import java.text.DateFormat
import java.util.Date

/** Text of the bubble next to the folder scroller: the sort key of the file at the thumb. */
object ScrollLabels {

    /** Null when the sort has no readable key (random order) or the value is unknown. */
    fun label(media: GalleryMedia, field: SortField, dateFormat: DateFormat): String? = when (field) {
        SortField.DATE_TAKEN -> media.capturedAtMs.takeIf { it > 0L }?.let { dateFormat.format(Date(it)) }
        SortField.DATE_MODIFIED -> media.dateModifiedMs.takeIf { it > 0L }?.let { dateFormat.format(Date(it)) }
        // Inside one folder the path differs only by name, so both show the initial.
        SortField.NAME, SortField.PATH -> initialOf(media.displayName)
        SortField.SIZE -> formatBytes(media.sizeBytes)
        SortField.RANDOM -> null
    }

    /** Upper-case first letter; a digit as itself; anything else as "#". */
    fun initialOf(name: String): String {
        val c = name.trimStart().firstOrNull() ?: return "#"
        return when {
            c.isLetter() -> c.uppercase()
            c.isDigit() -> c.toString()
            else -> "#"
        }
    }
}
