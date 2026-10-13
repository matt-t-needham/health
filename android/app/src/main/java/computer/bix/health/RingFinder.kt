package computer.bix.health

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

const val OURA_MANUFACTURER = 0x02B2

/** Bounded filtered scans for the ring. Blocking; call from a worker thread. */
@SuppressLint("MissingPermission")
object RingFinder {
    private fun scanner(ctx: Context) =
        ctx.getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner

    /**
     * Waits up to [windowMs] for an advert accepted by [accept]. With [pickStrongest]
     * it keeps scanning for [settleMs] after the first hit and returns the strongest.
     */
    fun find(
        ctx: Context,
        windowMs: Long,
        lowLatency: Boolean,
        pickStrongest: Boolean = false,
        settleMs: Long = 0,
        accept: (ScanResult) -> Boolean = { true },
    ): ScanResult? {
        val scanner = scanner(ctx) ?: return null
        var best: ScanResult? = null
        val first = CountDownLatch(1)
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                if (!accept(r)) return
                synchronized(this) {
                    if (best == null || (pickStrongest && r.rssi > best!!.rssi)) best = r
                }
                first.countDown()
            }
        }
        val filter = ScanFilter.Builder().setManufacturerData(OURA_MANUFACTURER, byteArrayOf(), byteArrayOf()).build()
        val mode = if (lowLatency) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_LOW_POWER
        scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(mode).build(), cb)
        try {
            if (first.await(windowMs, TimeUnit.MILLISECONDS) && pickStrongest && settleMs > 0) {
                Thread.sleep(settleMs)
            }
        } finally {
            scanner.stopScan(cb)
        }
        return synchronized(cb) { best }
    }

    fun bondedDevice(ctx: Context, address: String): BluetoothDevice? =
        ctx.getSystemService(BluetoothManager::class.java).adapter?.bondedDevices?.firstOrNull { it.address == address }
}
