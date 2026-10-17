package com.example.mediabackup.ui

import java.util.Date
import java.util.Locale

/**
 * Display formatting helpers.
 *
 * Deliberately kept out of the Compose files: they are pure Kotlin with no Android
 * dependencies, so they can be unit tested on the JVM.
 */

/**
 * Human-readable byte size using binary units.
 *
 * Only one decimal place is shown, and values below 1 KB are rendered as a whole
 * number of bytes, so the UI never reads "0.5 B".
 */
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "${bytes} B" else String.format(Locale.US, "%.1f %s", value, units[unit])
}

/**
 * Locale-aware date and time. Using a fixed pattern with a locale-dependent month
 * name mixes conventions (e.g. "10月 18, 2026" on a Japanese device), so let the
 * platform pick the ordering for the device locale.
 */
private val dateFormat: java.text.DateFormat =
    java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.MEDIUM,
        java.text.DateFormat.SHORT,
        Locale.getDefault(),
    )

internal fun formatWhen(millis: Long): String = dateFormat.format(Date(millis))
