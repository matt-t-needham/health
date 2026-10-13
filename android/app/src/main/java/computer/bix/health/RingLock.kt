package computer.bix.health

/** The ring takes one connection at a time: syncs, pairing and live heart rate take turns. */
object RingLock {
    val lock = Any()
}
