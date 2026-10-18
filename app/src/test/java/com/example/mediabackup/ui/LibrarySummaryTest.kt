package com.example.mediabackup.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The library shows pending files alongside backed-up ones, so the summary has to keep
 * the two counts visibly apart instead of implying that everything listed is in S3.
 */
class LibrarySummaryTest {

    @Test
    fun `videos only omits photos entirely`() {
        // Must not read "188 videos, 0 photos".
        assertEquals(
            "188 videos · 188 uploaded",
            librarySummary(videos = 188, photos = 0, uploaded = 188),
        )
    }

    @Test
    fun `both media types are named`() {
        assertEquals(
            "188 videos, 12 photos · 190 uploaded",
            librarySummary(videos = 188, photos = 12, uploaded = 190),
        )
    }

    @Test
    fun `photos only omits videos`() {
        assertEquals(
            "12 photos · 3 uploaded",
            librarySummary(videos = 0, photos = 12, uploaded = 3),
        )
    }

    @Test
    fun `single items are not pluralised`() {
        assertEquals("1 video · 0 uploaded", librarySummary(videos = 1, photos = 0, uploaded = 0))
        assertEquals("1 photo · 1 uploaded", librarySummary(videos = 0, photos = 1, uploaded = 1))
    }
}
