package io.github.epv.vault

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import java.security.MessageDigest
import java.util.Locale

/**
 * Durable, per-folder checkpoints for a local NSFW scan.
 *
 * Persist a completion receipt AFTER a file has been classified and, when
 * necessary, fully encrypted. This avoids reclassifying already scanned files
 * after Stop, app restart, or a change in document-provider enumeration order.
 *
 * Source URI + size + lastModified are hashed; names and contents are never
 * stored in the checkpoint DB. A changed file (when the provider exposes
 * updated metadata) is scanned again.
 */
class NsfwProgressStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "epv_nsfw_progress.db", null, 1) {

    private val preferences = context.applicationContext.getSharedPreferences(
        "epv_nsfw_scan", Context.MODE_PRIVATE
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE scanned (scan_id TEXT NOT NULL, source_id TEXT NOT NULL, " +
                "PRIMARY KEY (scan_id, source_id))"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future schema upgrades must preserve existing checkpoints.
        error("Unsupported checkpoint database schema")
    }

    private fun sha256(value: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    }

    /** Threshold and model version are part of the scan identity. */
    fun scanId(folder: Uri, threshold: Float): String {
        val percent = (threshold * 1000f).toInt()
        return sha256("EPV-NSFW-MOBILENET-1|$folder|$percent")
    }

    fun sourceId(source: Uri, length: Long, lastModified: Long): String =
        sha256("$source|$length|$lastModified")

    fun alreadyScanned(scan: String, source: Uri, length: Long, modified: Long): Boolean {
        val key = sourceId(source, length, modified)
        readableDatabase.query(
            "scanned", arrayOf("source_id"),
            "scan_id=? AND source_id=?", arrayOf(scan, key),
            null, null, null, "1"
        ).use { return it.moveToFirst() }
    }

    fun markScanned(scan: String, source: Uri, length: Long, modified: Long) {
        val values = android.content.ContentValues().apply {
            put("scan_id", scan)
            put("source_id", sourceId(source, length, modified))
        }
        val result = writableDatabase.insertWithOnConflict(
            "scanned", null, values, SQLiteDatabase.CONFLICT_IGNORE
        )
        check(result != -1L || alreadyScanned(scan, source, length, modified)) {
            "Cannot persist scan checkpoint"
        }
    }

    fun clear(scan: String) {
        writableDatabase.delete("scanned", "scan_id=?", arrayOf(scan))
    }

    fun completedCount(scan: String): Long {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM scanned WHERE scan_id=?", arrayOf(scan)
        ).use {
            if (it.moveToFirst()) return it.getLong(0)
        }
        return 0L
    }

    fun rememberFolder(folder: Uri, threshold: Float) {
        check(preferences.edit()
            .putString("last_folder", folder.toString())
            .putFloat("threshold", threshold)
            .commit()) { "Cannot save last scan folder" }
    }

    fun lastFolder(): Uri? =
        preferences.getString("last_folder", null)?.let(Uri::parse)

    fun lastThreshold(): Float = preferences.getFloat("threshold", 0.70f)

    /** Never share checkpoints with encryption mode or another destination. */
    fun moveScanId(source: Uri, target: Uri, threshold: Float): String {
        val level = (threshold * 1000f).toInt()
        return sha256("EPV-NSFW-ORGANIZER-1|$source|$target|$level")
    }

    fun saveMoveFolders(source: Uri, target: Uri) {
        check(preferences.edit()
            .putString("organizer_source", source.toString())
            .putString("organizer_target", target.toString())
            .commit()) { "Cannot store organizer folders" }
    }

    fun lastMoveSource(): Uri? =
        preferences.getString("organizer_source", null)?.let(Uri::parse)

    fun lastMoveTarget(): Uri? =
        preferences.getString("organizer_target", null)?.let(Uri::parse)
}
