package dev.ander.alexet.data.smartsearch

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.ander.alexet.data.AppLog as Log

/**
 * Image embeddings on disk, one row per photo. Its own database: media_index.db is rebuilt
 * from scratch on every version change, which here would mean indexing every photo again.
 * A row with an empty vector marks a photo that could not be decoded (not tried again until
 * the file changes).
 */
class SmartSearchStore(context: Context) {

    class Row(
        val mediaId: Long,
        val portablePath: String?,
        val sizeBytes: Long,
        val dateModifiedMs: Long,
        val vector: QuantizedVector?,
    ) {
        val key: IndexedKey get() = IndexedKey(sizeBytes, dateModifiedMs)
    }

    private val helper = object : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(CREATE_EMBEDDING)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Version 1 is the first; later versions migrate here and keep the vectors.
        }
    }

    init {
        helper.setWriteAheadLoggingEnabled(true)
    }

    /** Rows made by [modelId]; rows of another model are deleted (their vectors don't compare). */
    fun loadAll(modelId: String): List<Row> {
        val out = ArrayList<Row>()
        runCatching {
            val db = helper.writableDatabase
            db.delete("embedding", "model_id != ?", arrayOf(modelId))
            db.query("embedding", COLUMNS, null, null, null, null, null).use { c ->
                while (c.moveToNext()) {
                    val vec = c.getBlob(5)
                    out += Row(
                        mediaId = c.getLong(0),
                        portablePath = c.getString(1),
                        sizeBytes = c.getLong(2),
                        dateModifiedMs = c.getLong(3),
                        vector = if (vec != null && vec.isNotEmpty()) QuantizedVector(vec, c.getFloat(4)) else null,
                    )
                }
            }
        }.onFailure { Log.e(TAG, "Loading the smart search index failed", it) }
        return out
    }

    fun upsert(rows: List<Row>, modelId: String) {
        if (rows.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues()
            for (row in rows) {
                values.clear()
                values.put("media_id", row.mediaId)
                values.put("portable_path", row.portablePath)
                values.put("size", row.sizeBytes)
                values.put("date_modified", row.dateModifiedMs)
                values.put("model_id", modelId)
                values.put("scale", row.vector?.scale ?: 0f)
                values.put("vec", row.vector?.vec ?: ByteArray(0))
                db.insertWithOnConflict("embedding", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun delete(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for (chunk in ids.chunked(500)) {
                db.execSQL("DELETE FROM embedding WHERE media_id IN (${chunk.joinToString(",")})")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clear() {
        runCatching { helper.writableDatabase.delete("embedding", null, null) }
            .onFailure { Log.e(TAG, "Clearing the smart search index failed", it) }
    }

    private companion object {
        const val TAG = "SmartSearchStore"
        const val DB_NAME = "smart_search.db"
        const val DB_VERSION = 1
        val COLUMNS = arrayOf("media_id", "portable_path", "size", "date_modified", "scale", "vec")
        const val CREATE_EMBEDDING = """
            CREATE TABLE IF NOT EXISTS embedding (
                media_id INTEGER PRIMARY KEY,
                portable_path TEXT,
                size INTEGER NOT NULL,
                date_modified INTEGER NOT NULL,
                model_id TEXT NOT NULL,
                scale REAL NOT NULL,
                vec BLOB NOT NULL
            )
        """
    }
}
