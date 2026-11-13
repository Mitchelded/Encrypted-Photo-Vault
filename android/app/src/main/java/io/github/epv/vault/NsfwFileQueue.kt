package io.github.epv.vault

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import org.json.JSONArray

/**
 * Private on-disk work queue for local 18+ folder organization.
 *
 * Scan a folder once, then resume from persisted pending entries without
 * traversing the directory on each start. Once the queue is exhausted, do
 * exactly ONE refresh pass to discover new or modified files.
 *
 * The initial snapshot is marked complete only when enumeration finishes.
 * Interrupted indexing is repeated safely using idempotent upserts.
 */
class NsfwFileQueue(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "epv_nsfw_file_queue.db", null, 1) {

    data class Entry(
        val uri: Uri,
        val relativeDir: List<String>,
        val length: Long,
        val modified: Long
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE scans (scan_id TEXT PRIMARY KEY NOT NULL, initialized INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE files (" +
                "scan_id TEXT NOT NULL, uri TEXT NOT NULL, dir TEXT NOT NULL, " +
                "length INTEGER NOT NULL, modified INTEGER NOT NULL, " +
                "state INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY (scan_id, uri))"
        )
        db.execSQL(
            "CREATE INDEX queue_pending ON files(scan_id, state)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported NSFW queue schema")
    }

    fun isInitialized(scanId: String): Boolean {
        readableDatabase.rawQuery(
            "SELECT initialized FROM scans WHERE scan_id = ?",
            arrayOf(scanId)
        ).use { cursor ->
            return cursor.moveToFirst() && cursor.getInt(0) == 1
        }
    }

    fun setInitialized(scanId: String) {
        val db = writableDatabase
        db.insertWithOnConflict("scans", null, ContentValues().apply {
            put("scan_id", scanId)
            put("initialized", 0)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        db.update("scans", ContentValues().apply { put("initialized", 1) },
            "scan_id=?", arrayOf(scanId))
    }

    /**
     * Insert newly discovered files; reset only entries whose size/mtime
     * changed. Keep completed and failed entries unchanged otherwise.
     * Batch in one transaction, never one fsync per photo.
     */
    fun addDiscovered(scanId: String, batch: List<Entry>): Int {
        if (batch.isEmpty()) return 0
        val db = writableDatabase
        var added = 0
        db.beginTransaction()
        try {
            for (entry in batch) {
                val key = entry.uri.toString()
                val pathJson = JSONArray(entry.relativeDir).toString()
                val record = ContentValues().apply {
                    put("scan_id", scanId)
                    put("uri", key)
                    put("dir", pathJson)
                    put("length", entry.length)
                    put("modified", entry.modified)
                    put("state", 0)
                }
                val inserted = db.insertWithOnConflict(
                    "files", null, record, SQLiteDatabase.CONFLICT_IGNORE
                )
                if (inserted != -1L) {
                    added++
                } else {
                    // A file with a changed size/mtime must be analyzed anew.
                    // Avoid resetting unchanged, already-processed records.
                    db.update(
                        "files",
                        ContentValues().apply {
                            put("dir", pathJson)
                            put("length", entry.length)
                            put("modified", entry.modified)
                            put("state", 0)
                        },
                        "scan_id=? AND uri=? AND (length<>? OR modified<>?)",
                        arrayOf(scanId, key, entry.length.toString(), entry.modified.toString())
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added
    }

    fun resetFailed(scanId: String) {
        writableDatabase.update(
            "files", ContentValues().apply { put("state", 0) },
            "scan_id=? AND state=2", arrayOf(scanId)
        )
    }

    fun nextPending(scanId: String, limit: Int = 80): List<Entry> {
        val result = ArrayList<Entry>()
        readableDatabase.query(
            "files", arrayOf("uri", "dir", "length", "modified"),
            "scan_id=? AND state=0", arrayOf(scanId), null, null,
            "rowid ASC", limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val path = JSONArray(cursor.getString(1))
                val segments = ArrayList<String>(path.length())
                for (i in 0 until path.length()) segments.add(path.getString(i))
                result.add(
                    Entry(Uri.parse(cursor.getString(0)), segments,
                        cursor.getLong(2), cursor.getLong(3))
                )
            }
        }
        return result
    }

    fun markDone(scanId: String, uri: Uri) = setState(scanId, uri, 1)

    fun markFailed(scanId: String, uri: Uri) = setState(scanId, uri, 2)

    private fun setState(scanId: String, uri: Uri, state: Int) {
        val changed = writableDatabase.update(
            "files", ContentValues().apply { put("state", state) },
            "scan_id=? AND uri=?", arrayOf(scanId, uri.toString())
        )
        check(changed == 1) { "NSFW queue entry disappeared" }
    }

    fun total(scanId: String): Long = count(scanId, null)
    fun pending(scanId: String): Long = count(scanId, 0)
    fun done(scanId: String): Long = count(scanId, 1)
    fun failed(scanId: String): Long = count(scanId, 2)

    private fun count(scanId: String, state: Int?): Long {
        val selection = if (state == null) "scan_id=?" else "scan_id=? AND state=?"
        val args = if (state == null) arrayOf(scanId)
            else arrayOf(scanId, state.toString())
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM files WHERE $selection", args
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else 0
        }
    }

    fun clear(scanId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("files", "scan_id=?", arrayOf(scanId))
            db.delete("scans", "scan_id=?", arrayOf(scanId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
