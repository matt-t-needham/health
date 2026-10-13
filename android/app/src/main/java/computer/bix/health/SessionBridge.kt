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

    override fun onBatch(tags: ByteArray, timestamps: IntArray, bodies: Array<ByteArray>, nextCursor: Int, bytesLeft: Int): Boolean {
        return try {
            store.commitBatch(serial, tags, timestamps, bodies, nextCursor.toLong() and 0xffffffffL)
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
