package computer.bix.health

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val CHANNEL = "sync"

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Ring sync", NotificationManager.IMPORTANCE_LOW))
        val n = android.app.Notification.Builder(applicationContext, CHANNEL)
            .setContentTitle("Syncing ring")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()
        return ForegroundInfo(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean("manual", false)
        val reason = inputData.getString("reason")
        ActivityLog.init(applicationContext)
        // Re-armed on every run so the backstop exists even if the app is never opened.
        Watchdog.arm(applicationContext)
        ActivityLog.add(
            when {
                manual -> "Sync started"
                reason != null -> "Catch-up sync started ($reason)"
                else -> "Scheduled sync started"
            },
        )
        // A foreground service keeps Android from stopping the run when the phone dozes
        // and keeps the process from being frozen mid-sync (2026-11-16). Starting one from
        // the background needs the companion association; without it, say so and go on.
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            ActivityLog.add("Warning: running without a foreground service (${e.javaClass.simpleName}); allow background sync in the app")
        }
        return try {
            withContext(Dispatchers.IO) {
                if (manual) Store.get(applicationContext).requeueRejected()
                // Scheduled runs scan up to 3 minutes; a manual press waits one minute, then reports.
                val err = SyncRunner.run(applicationContext, if (manual) 60_000 else 180_000) { ActivityLog.add(it) }
                ActivityLog.add(if (err == null) "Sync finished" else "Sync failed: $err")
                if (Store.get(applicationContext).pendingCount() > 0) Scheduler.retryUpload(applicationContext)
            }
            // The outcome is stored for the status screen; a missing ring is not a reason to retry hot.
            Result.success()
        } catch (e: CancellationException) {
            ActivityLog.add("Sync stopped by Android (stop reason $stopReason); the watchdog will catch up")
            throw e
        }
    }
}

class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (Uploader.flush(applicationContext) == null) Result.success() else Result.retry()
    }
}

object Scheduler {
    /** A catch-up runs when no sync attempt has finished for this long (schedule is 6 h + 2 h flex). */
    const val OVERDUE_MS = 8 * 60 * 60 * 1000L

    /** Pure so it can be unit-tested: is a catch-up due, given the last finished attempt? */
    fun isOverdue(lastAttemptMs: Long?, nowMs: Long): Boolean =
        lastAttemptMs == null || nowMs - lastAttemptMs >= OVERDUE_MS || nowMs < lastAttemptMs

    fun ensurePeriodic(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS, 2, TimeUnit.HOURS).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, req)
        Watchdog.arm(ctx)
    }

    /**
     * Starts a sync if none has finished recently. Called from the watchdog alarm and
     * when the ring comes into range; [reason] goes in the activity log.
     */
    fun catchUpIfOverdue(ctx: Context, reason: String) {
        val store = Store.get(ctx)
        val last = (store.state("last_attempt_ms") ?: store.state("last_sync_ms"))?.toLongOrNull()
        if (!isOverdue(last, System.currentTimeMillis())) return
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(androidx.work.workDataOf("reason" to reason))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("sync-catchup", ExistingWorkPolicy.KEEP, req)
        ensurePeriodic(ctx)
    }

    fun syncNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(androidx.work.workDataOf("manual" to true))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("sync-now", ExistingWorkPolicy.REPLACE, req)
    }

    fun retryUpload(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("upload", ExistingWorkPolicy.KEEP, req)
    }
}
