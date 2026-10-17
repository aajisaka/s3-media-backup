package com.example.mediabackup.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the S3 key collision resolution: two different media files in the same month
 * can share a display name, and writing both to the same key would silently destroy
 * one of them in S3.
 */
class S3KeyResolutionTest {

    @Test
    fun uniqueNamesKeepPlainKeys() {
        val keys = resolveKeys(
            listOf(
                1L to "media/photos/2026/10/a.jpg",
                2L to "media/photos/2026/10/b.jpg",
            ),
        )

        assertEquals("media/photos/2026/10/a.jpg", keys[1L])
        assertEquals("media/photos/2026/10/b.jpg", keys[2L])
    }

    @Test
    fun collidingNamesGetAnIdSuffix() {
        val keys = resolveKeys(
            listOf(
                10L to "media/photos/2026/10/image.jpg",
                20L to "media/photos/2026/10/image.jpg",
            ),
        )

        assertEquals("media/photos/2026/10/image.jpg", keys[10L])
        assertEquals("media/photos/2026/10/image~20.jpg", keys[20L])
    }

    @Test
    fun lowestIdKeepsThePlainKeyRegardlessOfInputOrder() {
        val keys = resolveKeys(
            listOf(
                20L to "media/photos/2026/10/image.jpg",
                10L to "media/photos/2026/10/image.jpg",
            ),
        )

        assertEquals("media/photos/2026/10/image.jpg", keys[10L])
        assertEquals("media/photos/2026/10/image~20.jpg", keys[20L])
    }

    @Test
    fun everyMemberOfALargeCollisionGroupIsUnique() {
        val keys = resolveKeys(
            listOf(
                3L to "v/x/photo.png",
                1L to "v/x/photo.png",
                2L to "v/x/photo.png",
            ),
        )

        assertEquals(
            mapOf(1L to "v/x/photo.png", 2L to "v/x/photo~2.png", 3L to "v/x/photo~3.png"),
            keys,
        )
    }

    @Test
    fun fileExtensionIsPreserved() {
        val keys = resolveKeys(listOf(5L to "a/b/clip.mp4", 9L to "a/b/clip.mp4"))

        assertEquals("a/b/clip.mp4", keys[5L])
        assertEquals("a/b/clip~9.mp4", keys[9L])
    }

    @Test
    fun filesWithoutAnExtensionGetTheSuffixAtTheEnd() {
        val keys = resolveKeys(listOf(5L to "a/b/README", 9L to "a/b/README"))

        assertEquals("a/b/README~9", keys[9L])
    }

    @Test
    fun dotsInDirectoryNamesDoNotConfuseTheExtension() {
        val keys = resolveKeys(listOf(1L to "v1.2/photo", 2L to "v1.2/photo"))

        assertEquals("v1.2/photo~2", keys[2L])
    }

    @Test
    fun sameNameInADifferentMonthIsNotACollision() {
        val keys = resolveKeys(
            listOf(
                1L to "media/photos/2026/10/image.jpg",
                2L to "media/photos/2026/11/image.jpg",
            ),
        )

        assertEquals("media/photos/2026/10/image.jpg", keys[1L])
        assertEquals("media/photos/2026/11/image.jpg", keys[2L])
    }

    @Test
    fun photosAndVideosOfTheSameNameDoNotCollide() {
        val keys = resolveKeys(
            listOf(
                1L to "media/photos/2026/10/clip.jpg",
                2L to "media/videos/2026/10/clip.jpg",
            ),
        )

        assertEquals("media/photos/2026/10/clip.jpg", keys[1L])
        assertEquals("media/videos/2026/10/clip.jpg", keys[2L])
    }

    @Test
    fun outputIsAlwaysUniqueForAnArbitraryCollidingSet() {
        // Brute force: whatever the mix of names, no two files may share a key.
        val entries = (1L..50L).map { id ->
            id to "media/photos/2026/10/duplicate-${id % 7}.jpg"
        }

        val keys = resolveKeys(entries)

        assertEquals(entries.size, keys.size)
        assertEquals(entries.size, keys.values.toSet().size)
        assertTrue(keys.values.all { it.startsWith("media/photos/2026/10/duplicate-") })
    }

    @Test
    fun emptyScanProducesNoKeys() {
        assertEquals(emptyMap<Long, String>(), resolveKeys(emptyList()))
    }
}
