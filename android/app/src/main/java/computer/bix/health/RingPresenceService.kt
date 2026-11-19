package computer.bix.health

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Bound by the system when the associated ring comes into range. Being bound starts
 * the app process, which by itself lets WorkManager reset a sync that a killed
 * process left marked as running; on top of that an overdue sync is started here.
 *
 * Each Android version calls exactly one of these: [onDevicePresenceEvent] (16+),
 * [onDeviceAppeared] with an AssociationInfo (13–15), with an address (12).
 */
class RingPresenceService : CompanionDeviceService() {
    // Only Android 16+ calls this, so the API-36 getter is safe here.
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (event.event == DevicePresenceEvent.EVENT_BLE_APPEARED ||
            event.event == DevicePresenceEvent.EVENT_BT_CONNECTED
        ) {
            appeared()
        }
    }

    @Deprecated("Android 13-15 callback")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) = appeared()

    @Deprecated("Android 12 callback")
    override fun onDeviceAppeared(address: String) = appeared()

    private fun appeared() {
        ActivityLog.init(applicationContext)
        Scheduler.catchUpIfOverdue(applicationContext, "ring nearby")
    }
}
