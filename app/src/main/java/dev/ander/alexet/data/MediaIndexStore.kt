package dev.ander.alexet.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import android.net.Uri
import dev.ander.alexet.data.AppLog as Log
import kotlinx.coroutines.yield

/**
 * Persistent media index: the app's current belief about every gallery row, updated
 * incrementally (MediaStore deltas + the app's own operations) instead of being rewritten
 * as one snapshot.
 *
 * Every write is a SQLite transaction (WAL). A killed process rolls back the transaction
 * in flight, so the index is always either the previous or the next consistent state.
 * Writes are chunked; MediaStore generations are committed only in the final chunk, so an
 * interrupted delta is simply re-fetched on the next sync.
 */
class MediaIndexStore(context: Context) {

    private val helper = object : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(CREATE_MEDIA)
            db.execSQL(CREATE_INDEX_DATE_ADDED)
            db.execSQL(CREATE_INDEX_BUCKET_ID)
            db.execSQL(CREATE_META)
        }

        override fun onOpen(db: SQLiteDatabase) {
            super.onOpen(db)
            db.execSQL(CREATE_INDEX_DATE_ADDED)
            db.execSQL(CREATE_INDEX_BUCKET_ID)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS media")
            db.execSQL("DROP TABLE IF EXISTS meta")
            onCreate(db)
        }

        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            onUpgrade(db, oldVersion, newVersion)
        }
    }

    init {
        helper.setWriteAheadLoggingEnabled(true)
    }

    fun loadAll(): List<GalleryMedia> {
        val out = ArrayList<GalleryMedia>()
        runCatching {
            helper.readableDatabase.query(
                "media",
                null,
                null,
                null,
                null,
                null,
                "date_added DESC",
            ).use { c ->
                val id = c.getColumnIndexOrThrow("id")
                val uri = c.getColumnIndexOrThrow("uri")
                val name = c.getColumnIndexOrThrow("display_name")
                val mime = c.getColumnIndexOrThrow("mime")
                val taken = c.getColumnIndexOrThrow("date_taken")
                val modified = c.getColumnIndexOrThrow("date_modified")
                val added = c.getColumnIndexOrThrow("date_added")
                val size = c.getColumnIndexOrThrow("size")
                val width = c.getColumnIndexOrThrow("width")
                val height = c.getColumnIndexOrThrow("height")
                val duration = c.getColumnIndexOrThrow("duration")
                val bucketId = c.getColumnIndexOrThrow("bucket_id")
                val bucketName = c.getColumnIndexOrThrow("bucket_name")
                val relPath = c.getColumnIndexOrThrow("relative_path")
                val hidden = c.getColumnIndexOrThrow("is_hidden")
                val absPath = c.getColumnIndexOrThrow("absolute_path")
                val rawBucket = c.getColumnIndexOrThrow("raw_bucket_id")
                out.ensureCapacity(c.count)
                while (c.moveToNext()) {
                    val parsed = runCatching { Uri.parse(c.getString(uri)) }.getOrNull() ?: continue
                    out += GalleryMedia(
                        id = c.getLong(id),
                        uri = parsed,
                        displayName = c.getString(name).orEmpty(),
                        mimeType = c.getString(mime).orEmpty(),
                        dateTakenMs = c.getLong(taken),
                        dateModifiedMs = c.getLong(modified),
                        dateAddedMs = c.getLong(added),
                        sizeBytes = c.getLong(size),
                        width = c.getInt(width),
                        height = c.getInt(height),
                        durationMs = c.getLong(duration),
                        bucketId = c.getLong(bucketId),
                        bucketName = c.getString(bucketName).orEmpty(),
                        relativePath = c.getString(relPath).orEmpty(),
                        isHidden = c.getInt(hidden) != 0,
                        absolutePath = if (c.isNull(absPath)) null else c.getString(absPath),
                        rawBucketId = c.getLong(rawBucket),
                    )
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "Failed to load media index", e)
            out.clear()
        }
        return out
    }

    fun generation(volume: String): Long = metaLong("$KEY_GENERATION_PREFIX$volume")

    fun lastFullReconcileMs(): Long = metaLong(KEY_LAST_FULL)

    /** MediaStore version string the index was built against; null when never recorded. */
    fun storeVersion(volume: String): String? = metaString("$KEY_VERSION_PREFIX$volume")

    /** Volumes (internal storage, SD cards) present at the last full sync; null when never recorded. */
    fun volumeSet(): Set<String>? = metaString(KEY_VOLUMES)?.split('|')?.filter { it.isNotEmpty() }?.toSet()

    fun putVolumeSet(volumes: Set<String>) {
        val db = helper.writableDatabase
        transaction(db) { putMetaString(db, KEY_VOLUMES, volumes.sorted().joinToString("|")) }
    }

    fun putStoreVersions(versions: Map<String, String>) {
        if (versions.isEmpty()) return
        val db = helper.writableDatabase
        transaction(db) {
            versions.forEach { (volume, version) -> putMetaString(db, "$KEY_VERSION_PREFIX$volume", version) }
        }
    }

    /** Drops every row and all sync state (generations, versions, last full reconcile). */
    fun clear() {
        val db = helper.writableDatabase
        transaction(db) {
            db.delete("media", null, null)
            db.delete("meta", null, null)
        }
    }

    /**
     * Upsert + delete in chunks of [CHUNK] rows, each its own transaction; [generations] and
     * [fullReconcileAt] are written in the last chunk only. Suspends between chunks so a
     * background sync yields to foreground work.
     */
    suspend fun applyDelta(
        upserts: List<GalleryMedia>,
        deleteIds: Collection<Long>,
        generations: Map<String, Long>? = null,
        fullReconcileAt: Long? = null,
        pauseBetweenChunks: (suspend () -> Unit)? = null,
    ) {
        val pause: suspend () -> Unit = pauseBetweenChunks ?: { yield() }
        val db = helper.writableDatabase
        val deleteList = deleteIds.toList()
        var d = 0
        while (d < deleteList.size) {
            val end = minOf(d + CHUNK, deleteList.size)
            transaction(db) {
                val stmt = db.compileStatement("DELETE FROM media WHERE id = ?")
                for (i in d until end) {
                    stmt.bindLong(1, deleteList[i])
                    stmt.executeUpdateDelete()
                }
                stmt.close()
            }
            d = end
            pause()
        }
        var u = 0
        while (u < upserts.size) {
            val end = minOf(u + CHUNK, upserts.size)
            transaction(db) {
                val stmt = db.compileStatement(UPSERT)
                for (i in u until end) bindAndInsert(stmt, upserts[i])
                stmt.close()
            }
            u = end
            pause()
        }
        if (generations != null || fullReconcileAt != null) {
            transaction(db) {
                generations?.forEach { (volume, gen) -> putMeta(db, "$KEY_GENERATION_PREFIX$volume", gen) }
                fullReconcileAt?.let { putMeta(db, KEY_LAST_FULL, it) }
            }
        }
    }

    /** Atomically replace the whole index (full reconcile). Old rows stay visible until commit. */
    fun replaceAll(items: List<GalleryMedia>, generations: Map<String, Long>, fullReconcileAt: Long) {
        val db = helper.writableDatabase
        transaction(db) {
            db.delete("media", null, null)
            val stmt = db.compileStatement(UPSERT)
            for (item in items) bindAndInsert(stmt, item)
            stmt.close()
            generations.forEach { (volume, gen) -> putMeta(db, "$KEY_GENERATION_PREFIX$volume", gen) }
            putMeta(db, KEY_LAST_FULL, fullReconcileAt)
        }
    }

    fun count(): Int = runCatching {
        helper.readableDatabase.compileStatement("SELECT COUNT(*) FROM media").use { it.simpleQueryForLong().toInt() }
    }.getOrDefault(0)

    private fun metaLong(key: String): Long = runCatching {
        helper.readableDatabase.query("meta", arrayOf("value"), "key = ?", arrayOf(key), null, null, null).use { c ->
            if (c.moveToFirst()) c.getString(0).toLongOrNull() ?: 0L else 0L
        }
    }.getOrDefault(0L)

    private fun metaString(key: String): String? = runCatching {
        helper.readableDatabase.query("meta", arrayOf("value"), "key = ?", arrayOf(key), null, null, null).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun putMetaString(db: SQLiteDatabase, key: String, value: String) {
        db.compileStatement("INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)").use { stmt ->
            stmt.bindString(1, key)
            stmt.bindString(2, value)
            stmt.executeInsert()
        }
    }

    private fun putMeta(db: SQLiteDatabase, key: String, value: Long) {
        db.compileStatement("INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)").use { stmt ->
            stmt.bindString(1, key)
            stmt.bindString(2, value.toString())
            stmt.executeInsert()
        }
    }

    private inline fun transaction(db: SQLiteDatabase, block: () -> Unit) {
        db.beginTransactionNonExclusive()
        try {
            block()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun bindAndInsert(stmt: SQLiteStatement, item: GalleryMedia) {
        stmt.clearBindings()
        stmt.bindLong(1, item.id)
        stmt.bindString(2, item.uri.toString())
        stmt.bindString(3, item.displayName)
        stmt.bindString(4, item.mimeType)
        stmt.bindLong(5, item.dateTakenMs)
        stmt.bindLong(6, item.dateModifiedMs)
        stmt.bindLong(7, item.dateAddedMs)
        stmt.bindLong(8, item.sizeBytes)
        stmt.bindLong(9, item.width.toLong())
        stmt.bindLong(10, item.height.toLong())
        stmt.bindLong(11, item.durationMs)
        stmt.bindLong(12, item.bucketId)
        stmt.bindString(13, item.bucketName)
        stmt.bindString(14, item.relativePath)
        stmt.bindLong(15, if (item.isHidden) 1L else 0L)
        val abs = item.absolutePath
        if (abs == null) stmt.bindNull(16) else stmt.bindString(16, abs)
        stmt.bindLong(17, item.rawBucketId)
        stmt.executeInsert()
    }

    companion object {
        private const val TAG = "MediaIndexStore"
        const val DB_NAME = "media_index.db"
        const val DB_VERSION = 1
        const val CHUNK = 500
        private const val KEY_GENERATION_PREFIX = "generation:"
        private const val KEY_LAST_FULL = "last_full_reconcile"
        private const val KEY_VOLUMES = "volumes"
        private const val KEY_VERSION_PREFIX = "store_version:"

        private const val CREATE_MEDIA = """
            CREATE TABLE IF NOT EXISTS media(
                id INTEGER PRIMARY KEY,
                uri TEXT NOT NULL,
                display_name TEXT NOT NULL,
                mime TEXT NOT NULL,
                date_taken INTEGER NOT NULL,
                date_modified INTEGER NOT NULL,
                date_added INTEGER NOT NULL,
                size INTEGER NOT NULL,
                width INTEGER NOT NULL,
                height INTEGER NOT NULL,
                duration INTEGER NOT NULL,
                bucket_id INTEGER NOT NULL,
                bucket_name TEXT NOT NULL,
                relative_path TEXT NOT NULL,
                is_hidden INTEGER NOT NULL,
                absolute_path TEXT,
                raw_bucket_id INTEGER NOT NULL
            )
        """
        private const val CREATE_INDEX_DATE_ADDED = "CREATE INDEX IF NOT EXISTS idx_media_date_added ON media(date_added DESC)"
        private const val CREATE_INDEX_BUCKET_ID = "CREATE INDEX IF NOT EXISTS idx_media_bucket_id ON media(bucket_id)"
        private const val CREATE_META = "CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)"
        private const val UPSERT = """
            INSERT OR REPLACE INTO media(
                id, uri, display_name, mime, date_taken, date_modified, date_added, size,
                width, height, duration, bucket_id, bucket_name, relative_path, is_hidden,
                absolute_path, raw_bucket_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
    }
}
