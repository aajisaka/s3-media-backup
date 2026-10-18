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

/**
 * Whether the local index says this exact version of a file is in S3.
 *
 * A matching [mediaStoreId] alone is not enough: MediaStore reuses and rewrites rows, so
 * [MediaItem.fingerprint] is what actually decides it. A file edited in place keeps its
 * id but changes fingerprint, and must be uploaded again.
 *
 * The backup diff and the library's per-tile badge both go through here, so the library
 * can never disagree with the status card about what is pending.
 */
internal fun isUploaded(mediaStoreId: Long, fingerprint: String, known: Map<Long, String>): Boolean =
    known[mediaStoreId] == fingerprint
