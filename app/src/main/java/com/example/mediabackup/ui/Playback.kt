package com.example.mediabackup.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.example.mediabackup.data.MediaItem

/**
 * Hands a file to whatever the device opens it with — the system video player for a
 * movie — instead of bundling a decoder. The app only has to show the grid and start
 * playback; seeking, subtitles, audio focus and orientation are then the player's
 * problem, and hardware decoding comes for free.
 *
 * Returns false when nothing on the device accepts the file, which is the one outcome
 * the caller has to say something about.
 */
internal fun playMedia(context: Context, item: MediaItem): Boolean {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(
            item.uri,
            item.mimeType.ifBlank { if (item.isVideo) "video/*" else "image/*" },
        )
        // The player runs in another process and holds no media permission of its own.
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
