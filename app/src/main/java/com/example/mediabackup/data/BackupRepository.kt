package com.example.mediabackup.data

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Live view of what a backup is doing, shared by the worker and the UI. */
data class BackupStatus(
    val isRunning: Boolean = false,
    val phase: String = "",
    val currentFileName: String = "",
    val currentFileBytes: Long = 0L,
    val currentFileSize: Long = 0L,
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val uploadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val pendingCount: Int = 0,
    val photosBackedUp: Int = 0,
    val videosBackedUp: Int = 0,
    /** Cumulative bytes uploaded, summed from the local index. */
    val backedUpBytes: Long = 0L,
    val lastRunAtMillis: Long = 0L,
    val lastResultMessage: String = "",
    val lastErrors: List<String> = emptyList(),
) {
    /**
     * Totals across both media types. The per-type counts above are what the home
     * screen shows, because an icon labelled "photos" next to a combined total is
     * misleading.
     */
    val backedUpCount: Int get() = photosBackedUp + videosBackedUp

    val fractionOfFile: Float
        get() = if (currentFileSize <= 0L) 0f else (currentFileBytes.toFloat() / currentFileSize)

    val overallFraction: Float
        get() = if (totalBytes <= 0L) 0f else (uploadedBytes.toFloat() / totalBytes)

    val hasErrors: Boolean get() = lastErrors.isNotEmpty()
}

data class BackupResult(
    val uploaded: Int,
    val skipped: Int,
    val failed: Int,
    val uploadedBytes: Long,
    val errors: List<String>,
) {
    val summary: String
        get() = buildString {
            append("$uploaded uploaded")
            if (skipped > 0) append(", $skipped already backed up")
            if (failed > 0) append(", $failed failed")
        }
}

/**
 * Owns a backup run: scan MediaStore, diff against the local index, upload new or
 * changed files, and publish progress through [status] so the notification and the
 * home screen stay in sync without any callback plumbing.
 */
class BackupRepository(
    context: Context,
    private val settingsRepository: SettingsRepository,
) {

    private val appContext = context.applicationContext
    private val dao = BackupDatabase.get(appContext).uploadedMediaDao()

    private val _status = MutableStateFlow(BackupStatus())
    val status: StateFlow<BackupStatus> = _status.asStateFlow()

    private var lastProgressAt = 0L

    suspend fun runBackup(): BackupResult {
        val settings = settingsRepository.current()

        if (!settings.isConfigured) {
            val message = "S3 settings are incomplete"
            finishWithError(message)
            return BackupResult(0, 0, 0, 0, listOf(message))
        }
        if (!settings.backupImages && !settings.backupVideos) {
            finishWithMessage("Nothing to back up — photos and videos are both turned off")
            return BackupResult(0, 0, 0, 0, emptyList())
        }
        if (!Permissions.hasMediaAccess(appContext, settings)) {
            val message = "Media permission not granted"
            finishWithError(message)
            return BackupResult(0, 0, 0, 0, listOf(message))
        }

        _status.update {
            it.copy(
                isRunning = true,
                phase = "Scanning device",
                currentFileName = "",
                currentFileBytes = 0,
                currentFileSize = 0,
                completedCount = 0,
                totalCount = 0,
                uploadedBytes = 0,
                totalBytes = 0,
                lastErrors = emptyList(),
            )
        }

        val known = withContext(Dispatchers.IO) {
            dao.allFingerprints().associate { it.mediaStoreId to it.fingerprint }
        }
        val all = withContext(Dispatchers.IO) {
            MediaScanner.scan(appContext, settings.backupImages, settings.backupVideos)
        }
        val pending = all.filter { known[it.id] != it.fingerprint }
        val totalBytes = pending.sumOf { it.sizeBytes }
        val skipped = all.size - pending.size
        // Counts are tracked per media type so the home screen can label them honestly.
        val photosBackedUp = all.count { !it.isVideo && known[it.id] == it.fingerprint }
        val videosBackedUp = all.count { it.isVideo && known[it.id] == it.fingerprint }
        val backedUpBytesAtStart = withContext(Dispatchers.IO) { dao.totalUploadedBytes() } ?: 0L
        // Resolved over the whole scan so a new file can never land on the key of one
        // that is already backed up.
        val keys = settings.s3KeysFor(all)

        _status.update {
            it.copy(
                phase = if (pending.isEmpty()) "Nothing to back up" else "Uploading",
                totalCount = pending.size,
                totalBytes = totalBytes,
                pendingCount = pending.size,
                photosBackedUp = photosBackedUp,
                videosBackedUp = videosBackedUp,
                backedUpBytes = backedUpBytesAtStart,
            )
        }
        if (pending.isEmpty()) {
            val result = BackupResult(0, skipped, 0, 0, emptyList())
            _status.update {
                it.copy(
                    isRunning = false,
                    phase = "Idle",
                    lastRunAtMillis = System.currentTimeMillis(),
                    lastResultMessage = "Everything is backed up",
                )
            }
            return result
        }

        // Constructing the client validates the region, so keep it inside the
        // error path instead of letting it escape the run.
        val uploader = try {
            S3Uploader(appContext, settings)
        } catch (e: Exception) {
            val message = e.message ?: "Could not create the S3 client"
            finishWithError(message)
            return BackupResult(0, skipped, pending.size, 0, listOf(message))
        }

        val errors = ArrayList<String>()
        var uploaded = 0
        var uploadedPhotos = 0
        var uploadedVideos = 0
        var uploadedBytes = 0L
        var completed = 0
        var bytesBeforeThisFile = 0L

        try {
            for (item in pending) {
                val key = keys[item.id] ?: settings.s3KeyFor(item)
                _status.update {
                    it.copy(
                        currentFileName = item.displayName,
                        currentFileSize = item.sizeBytes,
                        currentFileBytes = 0,
                        uploadedBytes = bytesBeforeThisFile,
                    )
                }

                try {
                    withContext(Dispatchers.IO) {
                        uploader.uploadBlocking(item, key) { bytes ->
                            publishProgress(bytes, bytesBeforeThisFile, item.sizeBytes)
                        }
                    }
                    withContext(Dispatchers.IO) {
                        dao.insert(
                            UploadedMedia(
                                mediaStoreId = item.id,
                                fingerprint = item.fingerprint,
                                s3Key = key,
                                sizeBytes = item.sizeBytes,
                                uploadedAtMillis = System.currentTimeMillis(),
                            ),
                        )
                    }
                    uploaded++
                    if (item.isVideo) uploadedVideos++ else uploadedPhotos++
                    uploadedBytes += item.sizeBytes
                } catch (e: Exception) {
                    val label = e.message ?: e.javaClass.simpleName
                    if (errors.size < MAX_ERRORS) errors += "${item.displayName}: $label"
                }

                completed++
                bytesBeforeThisFile += item.sizeBytes
                _status.update {
                    it.copy(
                        completedCount = completed,
                        uploadedBytes = bytesBeforeThisFile,
                        currentFileBytes = item.sizeBytes,
                        lastErrors = errors.toList(),
                        // Grows as the run progresses so the UI total is live.
                        backedUpBytes = backedUpBytesAtStart + uploadedBytes,
                    )
                }
            }
        } finally {
            uploader.shutdown()
        }

        val result = BackupResult(
            uploaded = uploaded,
            skipped = skipped,
            failed = completed - uploaded,
            uploadedBytes = uploadedBytes,
            errors = errors.toList(),
        )
        _status.update {
            it.copy(
                isRunning = false,
                phase = "Idle",
                currentFileName = "",
                currentFileBytes = 0,
                currentFileSize = 0,
                lastRunAtMillis = System.currentTimeMillis(),
                lastResultMessage = result.summary,
                lastErrors = result.errors,
                photosBackedUp = photosBackedUp + uploadedPhotos,
                videosBackedUp = videosBackedUp + uploadedVideos,
                backedUpBytes = backedUpBytesAtStart + uploadedBytes,
            )
        }
        return result
    }

    /** Recomputes the pending and per-media-type backed-up counts without uploading. */
    suspend fun refreshCounts() {
        val settings = settingsRepository.current()
        if (!Permissions.hasMediaAccess(appContext, settings)) {
            _status.update {
                it.copy(pendingCount = 0, photosBackedUp = 0, videosBackedUp = 0)
            }
            return
        }
        val known = withContext(Dispatchers.IO) {
            dao.allFingerprints().associate { it.mediaStoreId to it.fingerprint }
        }
        val all = withContext(Dispatchers.IO) {
            MediaScanner.scan(appContext, settings.backupImages, settings.backupVideos)
        }
        val latestUpload = withContext(Dispatchers.IO) { dao.latestUploadAtMillis() } ?: 0L
        // Comes straight from the index, so it is unaffected by which media types are
        // currently enabled and stays correct across restarts.
        val backedUpBytes = withContext(Dispatchers.IO) { dao.totalUploadedBytes() } ?: 0L
        _status.update {
            it.copy(
                pendingCount = all.count { item -> known[item.id] != item.fingerprint },
                photosBackedUp = all.count { item ->
                    !item.isVideo && known[item.id] == item.fingerprint
                },
                videosBackedUp = all.count { item ->
                    item.isVideo && known[item.id] == item.fingerprint
                },
                backedUpBytes = backedUpBytes,
                // Keep the most recent of the in-session run and the newest upload in
                // the index, so "last backup" survives an app restart instead of
                // resetting to "no backups yet".
                lastRunAtMillis = maxOf(it.lastRunAtMillis, latestUpload),
            )
        }
    }

    /**
     * Coalesces the byte-level callbacks from the SDK so the UI and the notification
     * update a few times a second instead of on every 256 KB chunk. Publishing through
     * the StateFlow is what keeps the notification moving during a long movie upload.
     */
    private fun publishProgress(fileBytes: Long, bytesBefore: Long, fileSize: Long) {
        val now = SystemClock.elapsedRealtime()
        val isFinal = fileBytes >= fileSize
        if (!isFinal && now - lastProgressAt < PROGRESS_INTERVAL_MS) return
        lastProgressAt = now
        _status.update {
            it.copy(currentFileBytes = fileBytes, uploadedBytes = bytesBefore + fileBytes)
        }
    }

    private fun finishWithError(message: String) {
        _status.update {
            it.copy(
                isRunning = false,
                phase = "Idle",
                currentFileName = "",
                lastRunAtMillis = System.currentTimeMillis(),
                lastResultMessage = message,
                lastErrors = listOf(message),
            )
        }
    }

    /** Ends a run that legitimately had nothing to do, without raising an error card. */
    private fun finishWithMessage(message: String) {
        _status.update {
            it.copy(
                isRunning = false,
                phase = "Idle",
                currentFileName = "",
                lastRunAtMillis = System.currentTimeMillis(),
                lastResultMessage = message,
                lastErrors = emptyList(),
            )
        }
    }

    private companion object {
        const val MAX_ERRORS = 20
        const val PROGRESS_INTERVAL_MS = 250L
    }
}
