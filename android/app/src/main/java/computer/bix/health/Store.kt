package computer.bix.health

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local queue. Events are held only until the HTPC acknowledges them. A batch's
 * events and the sync cursor are written in one transaction, so the cursor never
 * moves past data that is not stored.
 */
class Store private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "health.db", null, 2) {
    class PendingBatch(
        val id: Long,
        val serial: String,
        val cursorBefore: Long,
        val cursorAfter: Long,
        val tags: ByteArray,
        val timestamps: IntArray,
        val bodies: List<ByteArray>,
        /** Wall-clock pairing for the newest ring timestamp, set on the last batch of a sync. */
        val anchorRingTs: Long?,
        val anchorUnix: Long?,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE state (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE batches (id INTEGER PRIMARY KEY AUTOINCREMENT, serial TEXT NOT NULL, " +
                "cursor_before INTEGER NOT NULL, cursor_after INTEGER NOT NULL, created_ms INTEGER NOT NULL, " +
                "anchor_ts INTEGER, anchor_unix INTEGER, rejected INTEGER NOT NULL DEFAULT 0, rejected_reason TEXT)",
        )
        db.execSQL(
            "CREATE TABLE events (batch_id INTEGER NOT NULL, tag INTEGER NOT NULL, ts INTEGER NOT NULL, body BLOB NOT NULL)",
        )
        db.execSQL("CREATE INDEX events_batch ON events(batch_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) {
            db.execSQL("ALTER TABLE batches ADD COLUMN anchor_ts INTEGER")
            db.execSQL("ALTER TABLE batches ADD COLUMN anchor_unix INTEGER")
            db.execSQL("ALTER TABLE batches ADD COLUMN rejected INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE batches ADD COLUMN rejected_reason TEXT")
        }
    }

    fun state(key: String): String? =
        readableDatabase.rawQuery("SELECT v FROM state WHERE k=?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    fun setState(key: String, value: String) {
        writableDatabase.insertWithOnConflict(
            "state", null,
            ContentValues().apply { put("k", key); put("v", value) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun cursor(): Long = state("cursor")?.toLongOrNull() ?: 0L

    /** Stores one drained batch and advances the cursor atomically. */
    fun commitBatch(serial: String, tags: ByteArray, ts: IntArray, bodies: Array<ByteArray>, nextCursor: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val before = cursor()
            if (tags.isNotEmpty()) {
                val id = db.insertOrThrow(
                    "batches", null,
                    ContentValues().apply {
                        put("serial", serial)
                        put("cursor_before", before)
                        put("cursor_after", nextCursor)
                        put("created_ms", System.currentTimeMillis())
                    },
                )
                for (i in tags.indices) {
                    db.insertOrThrow(
                        "events", null,
                        ContentValues().apply {
                            put("batch_id", id)
                            put("tag", tags[i].toInt() and 0xff)
                            put("ts", ts[i].toLong() and 0xffffffffL)
                            put("body", bodies[i])
                        },
                    )
                }
            }
            setState("cursor", nextCursor.toString())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun pendingCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM batches WHERE rejected=0", null).use { it.moveToFirst(); it.getInt(0) }

    fun rejectedCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM batches WHERE rejected=1", null).use { it.moveToFirst(); it.getInt(0) }

    fun maxBatchId(): Long =
        readableDatabase.rawQuery("SELECT COALESCE(MAX(id), 0) FROM batches", null).use { it.moveToFirst(); it.getLong(0) }

    /** Attaches the sync's anchor to its newest batch, if this sync stored one (id > [afterId]). */
    fun setAnchor(afterId: Long, ringTs: Long, unix: Long) {
        writableDatabase.execSQL(
            "UPDATE batches SET anchor_ts=?, anchor_unix=? WHERE id=(SELECT MAX(id) FROM batches) AND id>?",
            arrayOf<Any>(ringTs, unix, afterId),
        )
    }

    /** Moves a batch the HTPC permanently refused out of the queue. Its data stays here. */
    fun markRejected(id: Long, reason: String) {
        writableDatabase.execSQL("UPDATE batches SET rejected=1, rejected_reason=? WHERE id=?", arrayOf<Any>(reason, id))
    }

    /** Gives rejected batches another try (on a manual sync). */
    fun requeueRejected() {
        writableDatabase.execSQL("UPDATE batches SET rejected=0 WHERE rejected=1")
    }

    fun nextPending(): PendingBatch? {
        val db = readableDatabase
        val head = db.rawQuery(
            "SELECT id, serial, cursor_before, cursor_after, anchor_ts, anchor_unix FROM batches WHERE rejected=0 ORDER BY id LIMIT 1", null,
        ).use {
            if (it.moveToFirst()) {
                listOf(
                    it.getLong(0), it.getString(1), it.getLong(2), it.getLong(3),
                    if (it.isNull(4)) null else it.getLong(4), if (it.isNull(5)) null else it.getLong(5),
                )
            } else null
        }
            ?: return null
        val tags = ArrayList<Byte>()
        val stamps = ArrayList<Int>()
        val bodies = ArrayList<ByteArray>()
        db.rawQuery("SELECT tag, ts, body FROM events WHERE batch_id=? ORDER BY rowid", arrayOf(head[0].toString())).use {
            while (it.moveToNext()) {
                tags.add(it.getInt(0).toByte())
                stamps.add(it.getLong(1).toInt())
                bodies.add(it.getBlob(2))
            }
        }
        return PendingBatch(
            head[0] as Long, head[1] as String, head[2] as Long, head[3] as Long,
            tags.toByteArray(), stamps.toIntArray(), bodies,
            head[4] as Long?, head[5] as Long?,
        )
    }

    fun deleteBatch(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("events", "batch_id=?", arrayOf(id.toString()))
            db.delete("batches", "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Pairing with a reset ring restarts the ring's clock-relative cursor. */
    fun resetCursor() = setState("cursor", "0")

    companion object {
        @Volatile private var instance: Store? = null
        fun get(ctx: Context): Store =
            instance ?: synchronized(this) { instance ?: Store(ctx.applicationContext).also { instance = it } }
    }
}
