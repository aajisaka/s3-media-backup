package com.example.mediabackup.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.mediabackup.data.BackupSettings
import java.util.concurrent.TimeUnit

/**
 * Creates, updates and cancels the WorkManager jobs that drive backups.
 *
 * Periodic work cannot run more often than every 15 minutes, and Android defers it
 * aggressively under Doze, so the schedule is best-effort by design.
 */
object BackupScheduler {

    const val NOW_WORK = "media_backup_now"
    const val PERIODIC_WORK = "media_backup_periodic"

    /** Kicks off an immediate backup. If one is already queued it is replaced. */
    fun runNow(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /** (Re)registers the periodic job to match the current settings. */
    fun applySchedule(context: Context, settings: BackupSettings) {
        val workManager = WorkManager.getInstance(context)

        if (!settings.autoBackupEnabled) {
            workManager.cancelUniqueWork(PERIODIC_WORK)
            return
        }

        val networkType =
            if (settings.requireUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .setRequiresCharging(settings.requireCharging)
            .build()

        val intervalHours = settings.autoBackupIntervalHours.coerceAtLeast(1).toLong()
        val request = PeriodicWorkRequestBuilder<BackupWorker>(intervalHours, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()

        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).run {
            cancelUniqueWork(NOW_WORK)
            cancelUniqueWork(PERIODIC_WORK)
        }
    }
}
