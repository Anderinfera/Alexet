package dev.ander.alexet.data

/**
 * What one MediaStore change notification says (Android 11+): MediaProvider notifies every changed
 * row with its id, once per collection it belongs to (the file table always, images or video by
 * type, downloads when it is one) and again under the `external` alias, with the kind of change in
 * the flags. Read from the uri text so it is testable without Android.
 */
data class MediaChange(
    val volume: String,
    val collection: Collection,
    /** Null for a collection-wide notification (no row named). */
    val id: Long?,
    val kind: Kind,
) {
    enum class Collection { IMAGES, VIDEO, AUDIO, FILES, DOWNLOADS, OTHER }
    enum class Kind { INSERT, UPDATE, DELETE, UNKNOWN }

    companion object {
        /** ContentResolver.NOTIFY_INSERT / NOTIFY_UPDATE / NOTIFY_DELETE (API 30). */
        const val NOTIFY_INSERT = 1 shl 2
        const val NOTIFY_UPDATE = 1 shl 3
        const val NOTIFY_DELETE = 1 shl 4

        private const val PREFIX = "content://media/"

        fun kindOf(flags: Int): Kind = when {
            flags and NOTIFY_DELETE != 0 -> Kind.DELETE
            flags and NOTIFY_INSERT != 0 -> Kind.INSERT
            flags and NOTIFY_UPDATE != 0 -> Kind.UPDATE
            else -> Kind.UNKNOWN
        }

        /** Null when [uri] is not a MediaStore uri. */
        fun parse(uri: String, flags: Int): MediaChange? {
            if (!uri.startsWith(PREFIX)) return null
            val parts = uri.substring(PREFIX.length).substringBefore('?').split('/').filter { it.isNotEmpty() }
            val volume = parts.getOrNull(0) ?: return null
            val collection = when (parts.getOrNull(1)) {
                "images" -> Collection.IMAGES
                "video" -> Collection.VIDEO
                "audio" -> Collection.AUDIO
                "file" -> Collection.FILES
                "downloads" -> Collection.DOWNLOADS
                else -> Collection.OTHER
            }
            // images/media/<id>, video/media/<id>, audio/media/<id>; file/<id>, downloads/<id>.
            val idPart = when (collection) {
                Collection.IMAGES, Collection.VIDEO, Collection.AUDIO ->
                    if (parts.getOrNull(2) == "media" && parts.size == 4) parts[3] else null
                Collection.FILES, Collection.DOWNLOADS -> if (parts.size == 3) parts[2] else null
                Collection.OTHER -> null
            }
            return MediaChange(volume, collection, idPart?.toLongOrNull(), kindOf(flags))
        }

        /** Ids MediaStore may notify for photos and videos the library lists (images, video, downloads). */
        val LISTED = setOf(Collection.IMAGES, Collection.VIDEO, Collection.DOWNLOADS)

        /** One line for the debug log: how many uris, of which kinds and collections, and how many name a row. */
        fun summary(changes: List<MediaChange?>): String {
            val known = changes.filterNotNull()
            val byCollection = known.groupingBy { it.collection }.eachCount()
                .entries.sortedBy { it.key }.joinToString(" ") { "${it.key.name.lowercase()}=${it.value}" }
            val byKind = known.groupingBy { it.kind }.eachCount()
                .entries.sortedBy { it.key }.joinToString(" ") { "${it.key.name.lowercase()}=${it.value}" }
            val withId = known.count { it.id != null }
            val other = changes.size - known.size
            return "${changes.size} uri(s): $byKind; $byCollection; $withId with id" +
                if (other > 0) "; $other not MediaStore" else ""
        }
    }
}

/**
 * The MediaStore changes one sync looks at. [unknown]: some change said nothing exact (no row id
 * or no kind, an Android without them, too many at once), so everything has to be checked.
 */
internal class MediaChangeBatch(val changes: List<MediaChange>, val unknown: Boolean) {

    /** Rows of the library's collections (photos, videos, downloads) that changed. */
    val listedIds: Set<Long> = changes.filter { it.collection in MediaChange.LISTED }.mapNotNullTo(HashSet()) { it.id }

    /** Of [listedIds], the deleted ones. */
    val deletedListedIds: Set<Long> = changes
        .filter { it.collection in MediaChange.LISTED && it.kind == MediaChange.Kind.DELETE }
        .mapNotNullTo(HashSet()) { it.id }

    /** Of [listedIds], the added or changed ones that are still there. */
    val addedListedIds: Set<Long> = listedIds - deletedListedIds

    /**
     * Rows added or changed only in the file table: not photos or videos MediaStore lists, but
     * documents, or files in hidden (`.nomedia`) folders, which Alexet reads from disk itself.
     */
    val fileOnlyIds: Set<Long> = run {
        val inOthers = changes.filter { it.collection != MediaChange.Collection.FILES }.mapNotNullTo(HashSet()) { it.id }
        changes.filter { it.collection == MediaChange.Collection.FILES && it.kind != MediaChange.Kind.DELETE }
            .mapNotNullTo(HashSet()) { it.id?.takeIf { id -> id !in inOthers } }
    }

    /** A file-table row that is no photo or video of the library came, changed or went (a document, an archive). */
    val fileTableChanged: Boolean = run {
        val inOthers = changes.filter { it.collection != MediaChange.Collection.FILES }.mapNotNullTo(HashSet()) { it.id }
        changes.any { it.collection == MediaChange.Collection.FILES && it.id != null && it.id !in inOthers }
    }

    val isEmpty: Boolean get() = changes.isEmpty() && !unknown
}

/**
 * MediaStore changes noted by the observer and not yet taken by a sync. The observer runs on the
 * main thread, so it only stores the uris; they are read when a sync takes them. Bounded: past
 * [max] it only remembers that there were more (the next batch is [MediaChangeBatch.unknown]).
 */
internal class MediaChangeQueue(private val max: Int = 5_000) {
    private val lock = Any()
    private var uris = ArrayList<String>()
    private var flags = ArrayList<Int>()
    private var unknown = false

    fun add(changed: Collection<String>, changeFlags: Int) = synchronized(lock) {
        for (uri in changed) {
            if (uris.size >= max) {
                unknown = true
                break
            }
            uris += uri
            flags += changeFlags
        }
    }

    /** A change that says nothing exact (an Android without ids, changes missed in the background). */
    fun markUnknown() = synchronized(lock) { unknown = true }

    fun drain(): MediaChangeBatch {
        val (taken, takenFlags, wasUnknown) = synchronized(lock) {
            Triple(uris, flags, unknown).also {
                uris = ArrayList()
                flags = ArrayList()
                unknown = false
            }
        }
        var inexact = wasUnknown
        val changes = ArrayList<MediaChange>(taken.size)
        for (i in taken.indices) {
            val change = MediaChange.parse(taken[i], takenFlags[i])
            // An audio file also notifies its genres, playlists, artists and albums without a row:
            // not the library's, so not a reason to check everything. Any other change that names
            // no row (a whole collection or volume) or no kind is.
            if (change == null || change.kind == MediaChange.Kind.UNKNOWN ||
                (change.id == null && change.collection != MediaChange.Collection.AUDIO)
            ) {
                inexact = true
            }
            if (change != null) changes += change
        }
        return MediaChangeBatch(changes, inexact)
    }
}
