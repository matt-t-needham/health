package computer.bix.health

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Debug builds only: queues a fake batch so the upload path can be tested without a ring. */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val store = Store.get(ctx)
        val n = intent.getIntExtra("n", 3)
        val tags = ByteArray(n) { (0x41 + it).toByte() }
        val ts = IntArray(n) { 1000 + it }
        val bodies = Array(n) { byteArrayOf(it.toByte(), 0x7f, 0x00) }
        store.commitBatch("TESTSERIAL", tags, ts, bodies, store.cursor() + n)
        if (intent.getBooleanExtra("push", false)) Thread { Uploader.flush(ctx) }.start()
    }
}
