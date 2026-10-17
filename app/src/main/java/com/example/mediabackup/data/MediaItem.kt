package com.example.mediabackup.data

import android.net.Uri

/**
 * A single photo or video found on the device that is a candidate for backup.
 */
data class MediaItem(
    /** MediaStore `_ID`. Stable across reboots for the same media row. */
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** Epoch millis the media was captured/added. */
    val takenAtMillis: Long,
    val isVideo: Boolean,
) {
    /** Stable fingerprint used to detect "already backed up" and "file changed" cases. */
    val fingerprint: String get() = "$id:$sizeBytes:$takenAtMillis"
}
