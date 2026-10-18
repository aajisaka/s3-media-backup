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
 * One row of the library: a file on the device, plus whether the index says a
 * byte-identical copy of it is already in S3.
 *
 * Only files that still exist locally appear, because the library plays them from the
 * device. An object that was uploaded and then deleted locally is in S3 but has nothing
 * left to play, so it is deliberately absent. See README.
 */
data class LibraryEntry(
    val item: MediaItem,
    val uploaded: Boolean,
)

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

    private val _library = MutableStateFlow<List<LibraryEntry>>(emptyList())

    /**
     * Everything MediaStore holds that the current settings put in scope, newest first.
     * Separate from [status] because it is a browsing surface rather than progress: it
     * changes on its own schedule and is only collected while the library is on screen.
     */
    val library: StateFlow<List<LibraryEntry>> = _library.asStateFlow()

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
        val pending = all.filterNot { isUploaded(it.id, it.fingerprint, known) }
        val totalBytes = pending.sumOf { it.sizeBytes }
        val skipped = all.size - pending.size
        // Counts are tracked per media type so the home screen can label them honestly.
        val photosBackedUp = all.count { !it.isVideo && isUploaded(it.id, it.fingerprint, known) }
        val videosBackedUp = all.count { it.isVideo && isUploaded(it.id, it.fingerprint, known) }
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
        // Rebuild the library from the scan already in memory so the badges are honest
        // the moment a run ends, not at the next resume. Only the index needs re-reading;
        // MediaStore itself did not change while we were uploading.
        val knownAfterRun = withContext(Dispatchers.IO) {
            dao.allFingerprints().associate { it.mediaStoreId to it.fingerprint }
        }
        _library.value = libraryItems(all, knownAfterRun)

        return result
    }

    /**
     * Re-reads MediaStore and the index, then publishes the counts and the library from
     * that one scan.
     *
     * A scan is the expensive half and both answers are derived from the same two
     * inputs, so they share it rather than scanning twice for a screen that may not even
     * be open. Called on resume; nothing here is polled.
     */
    suspend fun refreshFromDevice() {
        val settings = settingsRepository.current()
        if (!Permissions.hasMediaAccess(appContext, settings)) {
            _status.update {
                it.copy(pendingCount = 0, photosBackedUp = 0, videosBackedUp = 0)
            }
            // Nothing can be read, so offering anything as browsable would be a lie.
            _library.value = emptyList()
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
                pendingCount = all.count { item -> !isUploaded(item.id, item.fingerprint, known) },
                photosBackedUp = all.count { item ->
                    !item.isVideo && isUploaded(item.id, item.fingerprint, known)
                },
                videosBackedUp = all.count { item ->
                    item.isVideo && isUploaded(item.id, item.fingerprint, known)
                },
                backedUpBytes = backedUpBytes,
                // Keep the most recent of the in-session run and the newest upload in
                // the index, so "last backup" survives an app restart instead of
                // resetting to "no backups yet".
                lastRunAtMillis = maxOf(it.lastRunAtMillis, latestUpload),
            )
        }
        _library.value = libraryItems(all, known)
    }

    /**
     * Newest first: the library exists to find something to watch, and the most recent
     * additions are what you are looking for.
     */
    private fun libraryItems(all: List<MediaItem>, known: Map<Long, String>): List<LibraryEntry> =
        all.map { LibraryEntry(it, isUploaded(it.id, it.fingerprint, known)) }
            .sortedByDescending { it.item.takenAtMillis }

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
