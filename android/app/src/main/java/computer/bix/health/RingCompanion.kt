package computer.bix.health

import android.app.Activity
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.IntentSender
import android.os.Build

/**
 * The ring as an Android companion device.
 *
 * Associating the bonded ring is what makes background sync dependable: holding
 * REQUEST_COMPANION_RUN_IN_BACKGROUND with an association puts the app on the
 * power-save allowlist (Doze no longer stops a scheduled sync mid-run) and lets a
 * scheduled sync start its foreground service from the background. Presence
 * observation additionally wakes [RingPresenceService] when the ring comes back
 * into range, which restarts the process and so un-sticks WorkManager.
 *
 * The association is made once, from the "Allow background sync" button, through
 * the system's own confirmation dialog.
 */
object RingCompanion {
    const val REQUEST_CODE = 42

    private fun cdm(ctx: Context): CompanionDeviceManager? =
        ctx.getSystemService(CompanionDeviceManager::class.java)

    /** True when the paired ring (by its bond address) is associated with this app. */
    fun isAssociated(ctx: Context): Boolean {
        val address = Store.get(ctx).state("ring_address") ?: return false
        return associationId(ctx, address) != null || legacyAssociated(ctx, address)
    }

    private fun associationId(ctx: Context, address: String): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return cdm(ctx)?.myAssociations
            ?.firstOrNull { it.deviceMacAddress?.toString().equals(address, ignoreCase = true) }
            ?.id
    }

    @Suppress("DEPRECATION") // Android 12 has only the address list; myAssociations is API 33.
    private fun legacyAssociated(ctx: Context, address: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return false
        return cdm(ctx)?.associations?.any { it.equals(address, ignoreCase = true) } == true
    }

    /**
     * Shows the system dialog for the paired ring. The bonded ring is offered straight
     * away (the system lists bonded devices that match the filter before scanning).
     * Completion arrives in [onCreated] (Android 13+) or via the activity result
     * ([REQUEST_CODE]) followed by [onActivityResult].
     */
    fun associate(activity: Activity, log: (String) -> Unit) {
        val address = Store.get(activity).state("ring_address")
        if (address == null) { log("Pair the ring before allowing background sync"); return }
        val manager = cdm(activity)
        if (manager == null) { log("Background sync: this phone has no companion device service"); return }
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(address).build())
            .setSingleDevice(true)
            .build()

        fun launch(sender: IntentSender) {
            try {
                activity.startIntentSenderForResult(sender, REQUEST_CODE, null, 0, 0, 0)
            } catch (e: IntentSender.SendIntentException) {
                log("Background sync: could not open the system dialog: ${e.message}")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.associate(request, activity.mainExecutor, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) = launch(intentSender)
                override fun onAssociationCreated(associationInfo: AssociationInfo) = onCreated(activity, log)
                override fun onFailure(error: CharSequence?) { log("Background sync not allowed: ${error ?: "no reason given"}") }
            })
        } else {
            @Suppress("DEPRECATION") // The only form on Android 12.
            manager.associate(request, object : CompanionDeviceManager.Callback() {
                @Deprecated("Android 12 path")
                override fun onDeviceFound(intentSender: IntentSender) = launch(intentSender)
                override fun onFailure(error: CharSequence?) { log("Background sync not allowed: ${error ?: "no reason given"}") }
            }, null)
        }
    }

    /** Android 12 reports the outcome only as an activity result. */
    fun onActivityResult(ctx: Context, resultCode: Int, log: (String) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return // onAssociationCreated handles it
        if (resultCode == Activity.RESULT_OK) onCreated(ctx, log) else log("Background sync not allowed (dialog cancelled)")
    }

    private fun onCreated(ctx: Context, log: (String) -> Unit) {
        log("Background sync allowed")
        observePresence(ctx, log)
    }

    /** Starts presence observation for the associated ring. Safe to repeat. */
    fun observePresence(ctx: Context, log: (String) -> Unit) {
        val address = Store.get(ctx).state("ring_address") ?: return
        val manager = cdm(ctx) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                val id = associationId(ctx, address) ?: return
                manager.startObservingDevicePresence(
                    ObservingDevicePresenceRequest.Builder().setAssociationId(id).build(),
                )
            } else {
                if (!isAssociated(ctx)) return
                @Suppress("DEPRECATION") // Address form is the only one before Android 16.
                manager.startObservingDevicePresence(address)
            }
        } catch (e: Exception) {
            // Presence is a backstop; the schedule and watchdog still run without it.
            log("Warning: could not watch for the ring nearby: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
