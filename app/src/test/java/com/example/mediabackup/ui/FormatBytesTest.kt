package com.example.mediabackup.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the byte formatter used for the "uploaded" total on the home screen.
 *
 * Values are binary (1 KB = 1024 B) and sub-kilobyte values are rendered as whole
 * bytes, so the UI never shows something like "0.5 B".
 */
class FormatBytesTest {

    @Test
    fun zeroAndNegativeRenderAsZero() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("0 B", formatBytes(-1))
        assertEquals("0 B", formatBytes(Long.MIN_VALUE))
    }

    @Test
    fun subKilobyteValuesAreWholeBytes() {
        assertEquals("1 B", formatBytes(1))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1023 B", formatBytes(1023))
    }

    @Test
    fun oneKilobyteIsTheFirstValueWithADecimal() {
        assertEquals("1.0 KB", formatBytes(1024))
    }

    @Test
    fun eachUnitIsCrossedExactlyAtItsPowerOf1024() {
        assertEquals("1.0 KB", formatBytes(1024L))
        assertEquals("1.0 MB", formatBytes(1024L * 1024))
        assertEquals("1.0 GB", formatBytes(1024L * 1024 * 1024))
        assertEquals("1.0 TB", formatBytes(1024L * 1024 * 1024 * 1024))
    }

    @Test
    fun crossingAUnitBoundaryRoundsUpRatherThanChangingUnit() {
        // A %.1f rounding artifact, not intended behaviour: one byte below 1 TB
        // formats as "1024.0 GB" rather than "1.0 TB". It only occurs within 0.05 of
        // a unit boundary and is pinned here so the quirk is not a surprise.
        assertEquals("1024.0 GB", formatBytes(1024L * 1024 * 1024 * 1024 - 1))
    }

    @Test
    fun valuesAreRoundedToOneDecimalPlace() {
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("1.1 KB", formatBytes(1100))
    }

    @Test
    fun realisticTotalsReadCorrectly() {
        val gigabyte = 1024L * 1024 * 1024
        assertEquals("240.0 GB", formatBytes(240 * gigabyte))
        // 1200 GB = 1.171875 TB, so it must render as 1.2 TB and not 1.1 or 1.3.
        assertEquals("1.2 TB", formatBytes(1200 * gigabyte))
    }

    @Test
    fun extremelyLargeValuesSaturateAtTerabytes() {
        // One petabyte: the unit list stops at TB, so the number keeps growing
        // instead of the unit. Documented behaviour, not a crash.
        val petabyte = 1024L * 1024 * 1024 * 1024 * 1024
        assertEquals("1024.0 TB", formatBytes(petabyte))
    }

    @Test
    fun outputAlwaysHasAUnitSuffix() {
        val units = listOf(" B", " KB", " MB", " GB", " TB")
        listOf(0L, 1L, 1024L, 1048576L, 1073741824L, 1099511627776L).forEach { bytes ->
            val formatted = formatBytes(bytes)
            assertEquals(
                "no recognised unit in '$formatted'",
                true,
                units.any { formatted.endsWith(it) },
            )
        }
    }
}
