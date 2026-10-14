package computer.bix.health

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug builds only. Queues a fake batch so the upload path can be tested without a ring,
 * and removes fake batches again. Refuses to add one once a ring is paired.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val store = Store.get(ctx)
        if (intent.getBooleanExtra("repair", false)) {
            repair(store)
            return
        }
        if (store.state("ring_address") != null) return
        val n = intent.getIntExtra("n", 3)
        val tags = ByteArray(n) { (0x41 + it).toByte() }
        val ts = IntArray(n) { 1000 + it }
        val bodies = Array(n) { byteArrayOf(it.toByte(), 0x7f, 0x00) }
        store.commitBatch("TESTSERIAL", tags, ts, bodies, store.cursor() + n)
        if (intent.getBooleanExtra("push", false)) Thread { Uploader.flush(ctx) }.start()
    }

    /** Drops every TESTSERIAL batch and puts the cursor back to where the first one started. */
    private fun repair(store: Store) {
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            val first = db.rawQuery("SELECT MIN(cursor_before) FROM batches WHERE serial='TESTSERIAL'", null)
                .use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
            db.execSQL("DELETE FROM events WHERE batch_id IN (SELECT id FROM batches WHERE serial='TESTSERIAL')")
            db.execSQL("DELETE FROM batches WHERE serial='TESTSERIAL'")
            if (first != null) store.setState("cursor", first.toString())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
