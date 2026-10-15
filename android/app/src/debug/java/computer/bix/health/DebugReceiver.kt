package computer.bix.health

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
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
        if (intent.getBooleanExtra("survey", false)) {
            val pending = goAsync()
            Thread { try { survey(ctx, store, intent.getIntExtra("seconds", 30)) } finally { pending.finish() } }.start()
            return
        }
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

    /** Unfiltered low-latency scan; logs every advertiser heard, to see what the ring sends. */
    @SuppressLint("MissingPermission")
    private fun survey(ctx: Context, store: Store, seconds: Int) {
        ActivityLog.init(ctx)
        val bond = store.state("ring_address")
        val scanner = ctx.getSystemService(BluetoothManager::class.java).adapter.bluetoothLeScanner
        val seen = LinkedHashMap<String, String>()
        val counts = HashMap<String, Int>()
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val a = r.device.address
                val rec = r.scanRecord
                val mfr = rec?.manufacturerSpecificData?.let { m -> (0 until m.size()).joinToString("/") { "%04x".format(m.keyAt(it)) } } ?: ""
                synchronized(seen) {
                    counts[a] = (counts[a] ?: 0) + 1
                    seen[a] = "$a ${r.rssi}dBm conn=${r.isConnectable} mfr=[$mfr] name=${rec?.deviceName} uuids=${rec?.serviceUuids}" + if (a == bond) " (OURS)" else ""
                }
            }
        }
        ActivityLog.add("Survey: unfiltered scan for $seconds s")
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        Thread.sleep(seconds * 1000L)
        scanner.stopScan(cb)
        synchronized(seen) {
            ActivityLog.add("Survey: ${seen.size} advertiser(s)")
            seen.forEach { (a, line) -> ActivityLog.add("Survey: x${counts[a]} $line") }
        }
    }
}
