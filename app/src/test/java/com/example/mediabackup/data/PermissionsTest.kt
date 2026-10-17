package com.example.mediabackup.data

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The permission request must track the media types the user actually wants to back
 * up. With photos turned off the app must never ask for READ_MEDIA_IMAGES — the
 * least-privilege behaviour for the common case where photos live in an unlimited
 * photo service and only movies need backing up to S3.
 */
class PermissionsTest {

    @Test
    fun nothingSelectedNeedsNoPermissionOnAnyApiLevel() {
        assertEquals(0, mediaPermissionsFor(26, backupImages = false, backupVideos = false).size)
        assertEquals(0, mediaPermissionsFor(33, backupImages = false, backupVideos = false).size)
        assertEquals(0, mediaPermissionsFor(36, backupImages = false, backupVideos = false).size)
    }

    @Test
    fun videosOnlyAsksForVideoPermissionAndNotPhotoPermission() {
        val permissions = mediaPermissionsFor(36, backupImages = false, backupVideos = true)

        assertEquals(listOf("android.permission.READ_MEDIA_VIDEO"), permissions.toList())
    }

    @Test
    fun imagesOnlyAsksForPhotoPermissionAndNotVideoPermission() {
        val permissions = mediaPermissionsFor(36, backupImages = true, backupVideos = false)

        assertEquals(listOf("android.permission.READ_MEDIA_IMAGES"), permissions.toList())
    }

    @Test
    fun bothSelectedAsksForBothPermissions() {
        val permissions = mediaPermissionsFor(33, backupImages = true, backupVideos = true).toList()

        assertEquals(
            listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO"),
            permissions,
        )
    }

    @Test
    fun android13IsTheVersionThatSplitsThePermissions() {
        assertEquals(
            listOf("android.permission.READ_MEDIA_VIDEO"),
            mediaPermissionsFor(Build.VERSION_CODES.TIRAMISU, false, true).toList(),
        )
    }

    @Test
    fun beforeAndroid13OnePermissionCoversBothMediaTypes() {
        val images = mediaPermissionsFor(26, backupImages = true, backupVideos = false)
        val videos = mediaPermissionsFor(32, backupImages = false, backupVideos = true)

        assertEquals(listOf("android.permission.READ_EXTERNAL_STORAGE"), images.toList())
        assertEquals(listOf("android.permission.READ_EXTERNAL_STORAGE"), videos.toList())
    }

    @Test
    fun requestingNothingIsDistinctFromRequestingEverything() {
        val none = mediaPermissionsFor(36, false, false)
        val both = mediaPermissionsFor(36, true, true)

        assertTrue(none.isEmpty())
        assertTrue(both.isNotEmpty())
    }
}
