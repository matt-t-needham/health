package computer.bix.health

import android.annotation.SuppressLint
import android.content.Context

/** One sync: find the paired ring, drain it into the local store, then push. */
@SuppressLint("MissingPermission")
object SyncRunner {
    private val lock = Any()

    /** Returns null on success, otherwise a one-line error. Safe to call from any worker thread. */
    fun run(ctx: Context, windowMs: Long, log: (String) -> Unit = {}): String? = synchronized(lock) {
        val store = Store.get(ctx)
        val err = ringPhase(ctx, store, windowMs, log)
        // Upload whatever is queued even if the ring phase failed: earlier batches may be waiting.
        val pushErr = Uploader.flush(ctx)
        if (err != null) return err
        return pushErr
    }

    private fun ringPhase(ctx: Context, store: Store, windowMs: Long, log: (String) -> Unit): String? {
        val keyHex = Secrets.get(ctx, "ring_key") ?: return note(store, "Not provisioned")
        val serial = Secrets.get(ctx, "ring_serial") ?: return note(store, "Ring serial not provisioned")
        val address = store.state("ring_address") ?: return note(store, "Ring not paired")
        val dev = RingFinder.bondedDevice(ctx, address) ?: return note(store, "Ring bond is gone; pair again")

        store.setState("ring_state", "connecting")
        val hit = RingFinder.find(ctx, windowMs, lowLatency = false) { it.device.address == address }
        if (hit == null) {
            store.setState("ring_state", "not seen")
            return note(store, "Ring not seen in ${windowMs / 1000} s")
        }
        store.setState("ring_seen_ms", System.currentTimeMillis().toString())

        val handle = NativeSync.nativeOpen()
        val link = RingLink(ctx, log) { NativeSync.nativePush(handle, it) }
        try {
            if (!link.open(hit.device)) return note(store, "Could not open the ring link")
            store.setState("ring_state", "connected")
            val bridge = SessionBridge(link, store, serial, log)
            val err = NativeSync.nativeSync(handle, keyHex.hexToBytes(), store.cursor().toInt(), bridge)
            if (err != null) return note(store, err)
            store.setState("last_sync_ms", System.currentTimeMillis().toString())
            store.setState("last_sync_events", bridge.events.toString())
            store.setState("last_error", "")
            return null
        } finally {
            link.close()
            NativeSync.nativeClose(handle)
            if (store.state("ring_state") == "connected") store.setState("ring_state", "disconnected")
        }
    }

    private fun note(store: Store, msg: String): String {
        store.setState("last_error", msg)
        return msg
    }
}
