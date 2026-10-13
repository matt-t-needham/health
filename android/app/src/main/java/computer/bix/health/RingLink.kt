package computer.bix.health

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * One GATT connection to the ring. Blocking API, meant to be driven from a worker
 * thread. Notification frames go to [onFrame]; [write] returns once the stack
 * confirms the write, which the Rust transport relies on for ordering.
 */
@SuppressLint("MissingPermission")
class RingLink(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onFrame: (ByteArray) -> Unit,
) {
    private val service = UUID.fromString("98ed0001-a541-11e4-b6a0-0002a5d5c51b")
    private val writeUuid = UUID.fromString("98ed0002-a541-11e4-b6a0-0002a5d5c51b")
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val connected = CountDownLatch(1)
    private val discovered = CountDownLatch(1)
    private val writeDone = Semaphore(0)
    private val descriptorDone = Semaphore(0)
    @Volatile private var lastWriteOk = false
    @Volatile var mtu = 23
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("conn state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected.countDown()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected.countDown()
                discovered.countDown()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            this@RingLink.mtu = mtu
            log("mtu=$mtu status=$status")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            log("services discovered status=$status")
            discovered.countDown()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            lastWriteOk = status == BluetoothGatt.GATT_SUCCESS
            if (!lastWriteOk) log("write status=$status")
            writeDone.release()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            log("subscribe ${d.characteristic.uuid.toString().substring(4, 8)} status=$status")
            descriptorDone.release()
        }

        @Deprecated("API 33 callback still used on older stacks")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") onFrame(c.value)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onFrame(value)
        }
    }

    /** Connects, negotiates MTU, discovers services and subscribes. False on any failure. */
    fun open(device: BluetoothDevice, timeoutSec: Long = 45): Boolean {
        log("bondState=${device.bondState}")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (!connected.await(timeoutSec, TimeUnit.SECONDS)) {
            log("connect timed out after ${timeoutSec}s")
            return false
        }
        val g = gatt ?: return false
        g.requestMtu(247)
        if (!discovered.await(15, TimeUnit.SECONDS)) {
            log("service discovery timed out")
            return false
        }
        val svc = g.getService(service)
        if (svc == null) {
            log("Oura service not found; services: ${g.services.map { it.uuid }}")
            return false
        }
        log("characteristics: " + svc.characteristics.joinToString { "${it.uuid.toString().substring(4, 8)}:props=${it.properties}" })
        writeChar = svc.getCharacteristic(writeUuid)
        for (c in svc.characteristics) {
            val p = c.properties
            val notify = p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            val indicate = p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            if (!notify && !indicate) continue
            g.setCharacteristicNotification(c, true)
            val d = c.getDescriptor(cccd) ?: continue
            val v = if (notify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            g.writeDescriptor(d, v)
            if (!descriptorDone.tryAcquire(10, TimeUnit.SECONDS)) {
                log("subscribe timed out")
                return false
            }
        }
        return writeChar != null
    }

    fun write(frame: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = writeChar ?: return false
        if (g.writeCharacteristic(c, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) != 0) return false
        if (!writeDone.tryAcquire(10, TimeUnit.SECONDS)) return false
        return lastWriteOk
    }

    fun close() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }
}
