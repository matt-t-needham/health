package computer.bix.health

import android.annotation.SuppressLint
import android.content.Context

/** One sync: find the paired ring, drain it into the local store, then push. */
@SuppressLint("MissingPermission")
object SyncRunner {
    /** Returns null on success, otherwise a one-line error. Safe to call from any worker thread. */
    fun run(ctx: Context, windowMs: Long, log: (String) -> Unit = {}): String? = synchronized(RingLock.lock) {
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
        val dev = RingFinder.bondedDevice(ctx, address) ?: run {
            store.setState("ring_state", "not paired")
            return note(store, "Ring bond is gone; pair again")
        }

        store.setState("ring_state", "connecting")
        val seen = HashSet<String>()
        val hit = RingFinder.find(ctx, windowMs, lowLatency = false, onSeen = { x ->
            if (seen.add(x.device.address)) log("advert ${x.device.address} ${x.rssi} dBm" + if (x.device.address == address) " (ours)" else " (other)")
        }) { it.device.address == address }
        // After a link drops mid-session the ring advertises only to its bonded central,
        // without manufacturer data, until a session completes. The filtered scan never
        // sees those adverts; a background connect to the bond does.
        if (hit == null) log("ring not seen in ${windowMs / 1000} s; trying to connect to the bond directly")
        val target = hit?.device ?: dev

        val handle = NativeSync.nativeOpen()
        val link = RingLink(ctx, log) { NativeSync.nativePush(handle, it) }
        try {
            if (!link.open(target, timeoutSec = if (hit == null) 60 else 45, autoConnect = hit == null)) {
                if (hit == null) store.setState("ring_state", "not seen")
                return note(store, if (hit == null) "Ring not seen in ${windowMs / 1000} s, and a direct connect failed" else "Could not open the ring link")
            }
            store.setState("ring_seen_ms", System.currentTimeMillis().toString())
            store.setState("ring_state", "connected")
            val bridge = SessionBridge(link, store, serial, log)
            val lastBatchBefore = store.maxBatchId()
            val err = NativeSync.nativeSync(handle, keyHex.hexToBytes(), store.cursor().toInt(), bridge)
            if (err != null) return note(store, err)
            // Same rule as `oura sync`'s phone anchor: newest ring timestamp paired with wall-clock time.
            val cursor = store.cursor()
            if (bridge.events > 0 && cursor > 0) store.setAnchor(lastBatchBefore, cursor - 1, System.currentTimeMillis() / 1000)
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
