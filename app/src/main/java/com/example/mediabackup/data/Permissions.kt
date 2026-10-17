package com.example.mediabackup.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime permission helpers for reading media across Android versions.
 *
 *  - API 33+ (Android 13): READ_MEDIA_IMAGES / READ_MEDIA_VIDEO
 *  - API 34+ (Android 14): READ_MEDIA_VISUAL_USER_SELECTED lets the user grant
 *    partial access; we treat "partial granted" as good enough to back up what we can see.
 *  - API <= 32: READ_EXTERNAL_STORAGE
 *
 * Only the permissions the current [BackupSettings] actually need are requested. With
 * photos turned off the app never holds READ_MEDIA_IMAGES at all, which is what you
 * want when photos live somewhere else and only movies need backing up.
 */
object Permissions {

    /**
     * The media permissions needed to read what the user has chosen to back up.
     * Empty when nothing is selected, in which case no permission is required.
     */
    fun requiredPermissions(settings: BackupSettings): Array<String> =
        mediaPermissionsFor(
            sdkInt = Build.VERSION.SDK_INT,
            backupImages = settings.backupImages,
            backupVideos = settings.backupVideos,
        )

    /** Notification permission is only needed on Android 13+ for the progress notification. */
    fun notificationPermission(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.POST_NOTIFICATIONS
        } else {
            null
        }

    fun hasMediaAccess(context: Context, settings: BackupSettings): Boolean {
        val needed = requiredPermissions(settings)
        if (needed.isEmpty()) return true

        val granted = needed.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) return true

        // Android 14+: the user may have granted "selected photos only", which is a
        // perfectly good way to back up just some of the library.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ) == PackageManager.PERMISSION_GRANTED
        }
        return false
    }

    fun hasNotificationAccess(context: Context): Boolean =
        notificationPermission()?.let {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        } ?: true
}

/**
 * Which media permissions are needed for a given Android version and selection.
 *
 * Split out from [Permissions.requiredPermissions] because `Build.VERSION.SDK_INT` is
 * pinned to 0 in JVM unit tests, which would otherwise collapse both branches into
 * one and make the API-level behaviour untestable.
 *
 * Before Android 13 a single READ_EXTERNAL_STORAGE covers both media types.
 */
internal fun mediaPermissionsFor(
    sdkInt: Int,
    backupImages: Boolean,
    backupVideos: Boolean,
): Array<String> {
    if (!backupImages && !backupVideos) return emptyArray()

    return if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        buildList {
            if (backupImages) add(Manifest.permission.READ_MEDIA_IMAGES)
            if (backupVideos) add(Manifest.permission.READ_MEDIA_VIDEO)
        }.toTypedArray()
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}
