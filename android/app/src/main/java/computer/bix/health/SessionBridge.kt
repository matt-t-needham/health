package computer.bix.health

/** Connects the Rust session to the GATT link and the local store. */
class SessionBridge(
    private val link: RingLink,
    private val store: Store,
    private val expectedSerial: String,
    private val onProgress: (String) -> Unit = {},
) : NativeSync.Bridge {
    private var serial = ""
    var batches = 0
        private set
    var events = 0
        private set

    override fun write(frame: ByteArray) = link.write(frame)

    override fun onSerial(serial: String): Boolean {
        this.serial = serial
        return serial == expectedSerial
    }

    override fun onInfo(key: String, value: String): Boolean {
        when (key) {
            "battery" -> { store.setState("ring_battery", value); store.setState("ring_info_ms", System.currentTimeMillis().toString()) }
            "firmware" -> store.setState("ring_firmware", value)
            "feature_modes" -> store.setState("feature_modes", value)
            "features" -> {
                store.setState("ring_features", value)
                onProgress("Ring features: $value")
            }
        }
        return true
    }

    override fun onBeat(bpm: Int, ibiMs: Int) = true

    /**
     * Keeps the newest battery readings the ring logged: `battery_level_changed`
     * (tag 0x61, body `24 pct mV_lo mV_hi ...`) and its voltage-based fuel gauge
     * (tag 0x43, ASCII `FGVf%;NN`). The percent the battery command returns is often
     * far above what the voltage supports on a worn battery.
     */
    private fun noteBattery(tags: ByteArray, bodies: Array<ByteArray>) {
        var mv: Int? = null
        var gauge: String? = null
        for (i in tags.indices) {
            val b = bodies[i]
            when (tags[i].toInt() and 0xff) {
                0x61 -> if (b.size >= 4 && b[0] == 0x24.toByte()) mv = (b[2].toInt() and 0xff) or ((b[3].toInt() and 0xff) shl 8)
                0x43 -> String(b, Charsets.US_ASCII).trimEnd('\u0000').takeIf { it.startsWith("FGVf%;") }?.let { gauge = it.substringAfter(';').trim() }
            }
        }
        mv?.let { store.setState("ring_voltage_mv", it.toString()) }
        gauge?.let { store.setState("ring_gauge_pct", it) }
    }

    override fun onBatch(tags: ByteArray, timestamps: IntArray, bodies: Array<ByteArray>, nextCursor: Int, bytesLeft: Int): Boolean {
        return try {
            store.commitBatch(serial, tags, timestamps, bodies, nextCursor.toLong() and 0xffffffffL)
            noteBattery(tags, bodies)
            batches++
            events += tags.size
            onProgress("Synced $events events, about ${bytesLeft / 1024} KB left on ring")
            true
        } catch (e: Exception) {
            // Refusing the batch stops the drain before the ring is told it is done.
            false
        }
    }
}
