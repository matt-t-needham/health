package computer.bix.health

/** Kotlin side of the Rust bridge in android/rust. */
object NativeSync {
    init {
        System.loadLibrary("ouralink")
    }

    /** Receives the ring link and each drained batch; implemented by the BLE layer. */
    interface Bridge {
        /** Send one request frame; return only once the GATT write has completed. */
        fun write(frame: ByteArray): Boolean

        fun onSerial(serial: String): Boolean

        /** Persist the batch and [nextCursor] durably, then return true to continue. */
        fun onBatch(
            tags: ByteArray,
            timestamps: IntArray,
            bodies: Array<ByteArray>,
            nextCursor: Int,
            bytesLeft: Int,
        ): Boolean
    }

    @JvmStatic external fun nativeOpen(): Long
    @JvmStatic external fun nativeClose(handle: Long)
    @JvmStatic external fun nativePush(handle: Long, frame: ByteArray)

    /** Pairs with a factory-reset ring and installs [key]. Blocks. Null on success, else a one-line error. */
    @JvmStatic external fun nativePair(handle: Long, key: ByteArray, bridge: Bridge): String?

    /** Blocks. Returns null on success, otherwise a one-line error. */
    @JvmStatic external fun nativeSync(handle: Long, key: ByteArray, cursor: Int, bridge: Bridge): String?
}
