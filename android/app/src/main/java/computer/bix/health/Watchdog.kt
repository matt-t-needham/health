package computer.bix.health

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Backstop for the 6-hourly WorkManager schedule, which can go silent: on 2026-11-16
 * a scheduled sync was stopped mid-run, the process was frozen and then killed, and
 * WorkManager kept the work marked as running with no system job left to run it.
 * Nothing synced for 42 hours, until the app was opened.
 *
 * An inexact repeating alarm (no exact-alarm permission; the design rules them out)
 * is independent of WorkManager. Its broadcast starts the process, and process start
 * is when WorkManager repairs work left running; [Scheduler.catchUpIfOverdue] then
 * starts a sync if the last finished attempt is too old.
 */
object Watchdog {
    private const val INTERVAL_MS = 3 * 60 * 60 * 1000L

    private fun intent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, WatchdogReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Arms (or re-arms) the alarm. Alarms do not survive a reboot or an app update. */
    fun arm(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + INTERVAL_MS,
            INTERVAL_MS,
            intent(ctx),
        )
    }
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        ActivityLog.init(ctx.applicationContext)
        Scheduler.catchUpIfOverdue(ctx.applicationContext, "watchdog")
    }
}

/** Re-arms the watchdog after a reboot or an app update, without the app being opened. */
class RearmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Watchdog.arm(ctx.applicationContext)
        // WorkManager reschedules its own work at boot; this only makes sure it exists.
        Scheduler.ensurePeriodic(ctx.applicationContext)
    }
}
