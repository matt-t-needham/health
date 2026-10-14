package computer.bix.health

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One-time pairing with a factory-reset ring: bond over BLE, check the serial,
 * then install the provisioned auth key. Pick the ring by strongest signal, so
 * hold it against the phone; the serial check refuses any other ring before the
 * key is installed.
 */
@SuppressLint("MissingPermission")
object Pairer {
    /** Returns null on success, otherwise a one-line error. */
    fun pair(ctx: Context, log: (String) -> Unit): String? = synchronized(RingLock.lock) {
        val keyHex = Secrets.get(ctx, "ring_key") ?: return "Ring key not provisioned"
        val serial = Secrets.get(ctx, "ring_serial") ?: return "Ring serial not provisioned"
        val store = Store.get(ctx)
        store.setState("ring_state", "connecting")

        log("Scanning 20 s; hold the ring against the phone")
        val hit = RingFinder.find(ctx, 20_000, lowLatency = true, pickStrongest = true, settleMs = 5_000)
            ?: return fail(store, "Ring not seen")
        val dev = hit.device
        log("Using ${dev.address} at ${hit.rssi} dBm")

        if (dev.bondState != BluetoothDevice.BOND_BONDED) {
            if (!bond(ctx, dev, log)) return fail(store, "Bonding failed or was declined")
        }

        val handle = NativeSync.nativeOpen()
        val link = RingLink(ctx, log) { NativeSync.nativePush(handle, it) }
        try {
            if (!link.open(dev)) return fail(store, "Could not open the ring link")
            store.setState("ring_state", "connected")
            val bridge = SessionBridge(link, store, serial, log)
            val err = NativeSync.nativePair(handle, keyHex.hexToBytes(), bridge)
            if (err != null) return fail(store, err)
            store.setState("ring_address", dev.address)
            store.resetCursor()
            store.setState("last_error", "")
            return null
        } finally {
            link.close()
            NativeSync.nativeClose(handle)
            if (store.state("ring_address") != null) store.setState("ring_state", "disconnected")
        }
    }

    private fun fail(store: Store, msg: String): String {
        store.setState("last_error", msg)
        store.setState("ring_state", if (store.state("ring_address") == null) "not paired" else "disconnected")
        return msg
    }

    private fun bond(ctx: Context, dev: BluetoothDevice, log: (String) -> Unit): Boolean {
        val done = CountDownLatch(1)
        var ok = false
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (d?.address != dev.address) return
                when (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                    BluetoothDevice.BOND_BONDED -> { ok = true; done.countDown() }
                    BluetoothDevice.BOND_NONE -> done.countDown()
                }
            }
        }
        ctx.registerReceiver(rx, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        try {
            log("Bonding; accept the pairing prompt if one appears")
            if (!dev.createBond()) return false
            done.await(60, TimeUnit.SECONDS)
        } finally {
            ctx.unregisterReceiver(rx)
        }
        log("bond result ok=$ok")
        return ok
    }
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
