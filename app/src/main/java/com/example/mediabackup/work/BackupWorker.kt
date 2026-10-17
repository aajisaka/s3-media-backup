package com.example.mediabackup.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.mediabackup.BackupApp
import com.example.mediabackup.R
import com.example.mediabackup.data.BackupStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs one backup pass. Used both for "Back up now" (one-time) and for the periodic
 * schedule. Runs as a foreground service so Android does not kill it mid-upload.
 *
 * Progress reaches the notification by observing the repository's status flow, so it
 * keeps moving even while a single multi-gigabyte movie is streaming.
 */
class BackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val app: BackupApp get() = applicationContext as BackupApp

    override suspend fun doWork(): Result {
        createChannel()

        val notifyScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val notifier = notifyScope.launch {
            var lastNotifyAt = 0L
            app.repository.status.collect { status ->
                val now = System.currentTimeMillis()
                if (now - lastNotifyAt >= NOTIFY_INTERVAL_MS || !status.isRunning) {
                    lastNotifyAt = now
                    runCatching { setForeground(createForegroundInfo(status)) }
                }
            }
        }

        try {
            val result = app.repository.runBackup()
            return if (result.failed > 0 && result.uploaded == 0) {
                Result.retry()
            } else {
                Result.success()
            }
        } finally {
            notifier.cancel()
            notifyScope.cancel()
        }
    }

    /** Used by WorkManager when it has to promote the work to the foreground itself. */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        createForegroundInfo(app.repository.status.value)

    private fun createForegroundInfo(status: BackupStatus): ForegroundInfo {
        val title = if (status.isRunning) {
            "Backing up photos and videos"
        } else {
            "Media backup"
        }

        val text = when {
            status.phase == "Scanning device" -> "Scanning device…"
            status.currentFileName.isNotEmpty() ->
                "${status.completedCount} of ${status.totalCount} · ${status.currentFileName}"
            status.lastResultMessage.isNotEmpty() -> status.lastResultMessage
            else -> status.phase.ifEmpty { "Working…" }
        }

        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(status.isRunning)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        if (status.totalCount > 0) {
            val percent = (status.overallFraction * 100).toInt().coerceIn(0, 100)
            builder.setProgress(100, percent, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                builder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, builder.build())
        }
    }

    private fun createChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = applicationContext.getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "media-backup"
        const val NOTIFICATION_ID = 1001
        const val NOTIFY_INTERVAL_MS = 1_000L
    }
}
