package com.example.mediabackup.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backup diff and the library's per-tile badge are both answered by [isUploaded], so
 * a change here that only one caller follows would show a film as backed up on one screen
 * and pending on the other.
 */
class UploadedStateTest {

    private val known = mapOf(7L to "7:100:200", 9L to "9:300:400")

    @Test
    fun `matching fingerprint counts as uploaded`() {
        assertTrue(isUploaded(7L, "7:100:200", known))
    }

    @Test
    fun `id that is not in the index is not uploaded`() {
        assertFalse(isUploaded(11L, "11:100:200", known))
    }

    @Test
    fun `known id with a changed fingerprint is not uploaded`() {
        // Same MediaStore row, different file. Looking the id up alone would call this
        // backed up, and the truncated re-upload would never happen.
        assertFalse(isUploaded(7L, "7:999:200", known))
    }

    @Test
    fun `empty index reports nothing as uploaded`() {
        assertFalse(isUploaded(7L, "7:100:200", emptyMap()))
    }
}
