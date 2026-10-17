package com.example.mediabackup.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * Reads photos and videos from MediaStore. No file paths are used anywhere: the app
 * streams content through [android.content.ContentResolver.openInputStream], which is
 * what scoped storage (Android 10+) requires.
 */
object MediaScanner {

    fun scan(context: Context, includeImages: Boolean, includeVideos: Boolean): List<MediaItem> {
        if (!includeImages && !includeVideos) return emptyList()
        val out = ArrayList<MediaItem>()
        if (includeImages) {
            out += query(
                context = context,
                collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                dateTakenColumn = MediaStore.Images.Media.DATE_TAKEN,
                isVideo = false,
            )
        }
        if (includeVideos) {
            out += query(
                context = context,
                collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                dateTakenColumn = MediaStore.Video.Media.DATE_TAKEN,
                isVideo = true,
            )
        }
        // Oldest first so a large first backup makes steady progress.
        return out.sortedBy { it.takenAtMillis }
    }

    private fun query(
        context: Context,
        collection: Uri,
        dateTakenColumn: String,
        isVideo: Boolean,
    ): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED,
            dateTakenColumn,
        )

        // Trashed / still-being-written files should never be uploaded.
        val selection: String?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.MediaColumns.IS_TRASHED} = 0 AND " +
                "${MediaStore.MediaColumns.IS_PENDING} = 0"
        } else {
            selection = null
        }

        val results = ArrayList<MediaItem>()
        context.contentResolver.query(
            collection,
            projection,
            selection,
            null,
            "${MediaStore.MediaColumns.DATE_ADDED} ASC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val addedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val takenCol = cursor.getColumnIndexOrThrow(dateTakenColumn)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val size = cursor.getLong(sizeCol)
                if (id <= 0) continue

                val takenMillis = if (cursor.isNull(takenCol)) 0L else cursor.getLong(takenCol)
                // DATE_ADDED is in seconds; DATE_TAKEN is already milliseconds.
                val addedMillis = cursor.getLong(addedCol) * 1000L
                val takenAt = when {
                    takenMillis > 0 -> takenMillis
                    addedMillis > 0 -> addedMillis
                    else -> System.currentTimeMillis()
                }

                results += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    displayName = cursor.getString(nameCol) ?: "media_$id",
                    mimeType = cursor.getString(mimeCol) ?: if (isVideo) "video/*" else "image/*",
                    sizeBytes = size,
                    takenAtMillis = takenAt,
                    isVideo = isVideo,
                )
            }
        }
        return results
    }
}
