package com.example.mediabackup.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Longest edge of a decoded tile. Larger than a tile needs so it stays crisp at 3x. */
private const val THUMBNAIL_EDGE_PX = 256

/**
 * Bounded by bytes rather than entries: a thumbnail follows its source's aspect ratio,
 * so a count-based bound would let a grid of portrait films hold several times the memory
 * of a grid of landscape ones.
 */
private const val MAX_CACHE_BYTES = 8 * 1024 * 1024

/**
 * Process-wide, because `LazyVerticalGrid` recycles cells: without this, every scroll
 * back over a tile would ask MediaStore to decode its frame again.
 *
 * Also acts as the in-flight collision guard — tiles are keyed by content URI, and a URI
 * can only be on screen once at a time.
 */
private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

/**
 * Decodes a grid thumbnail for [uri], or null when the platform cannot produce one.
 *
 * `ContentResolver.loadThumbnail` is API 29+. Below that, MediaStore's only other route
 * is the deprecated `MediaStore.Video.Thumbnails` API, which is not worth keeping alive
 * for a cosmetic tile — those devices get the placeholder icon instead.
 */
internal suspend fun loadThumbnail(resolver: ContentResolver, uri: Uri): Bitmap? =
    withContext(Dispatchers.IO) {
        val key = uri.toString()
        cache.get(key)?.let { return@withContext it }

        decode(resolver, uri)?.also { cache.put(key, it) }
    }

private fun decode(resolver: ContentResolver, uri: Uri): Bitmap? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    return try {
        resolver.loadThumbnail(uri, Size(THUMBNAIL_EDGE_PX, THUMBNAIL_EDGE_PX), null)
    } catch (_: IOException) {
        // Deleted or unreadable since the scan, or nothing decodable in the container.
        // Cosmetic, so the tile falls back to its placeholder rather than taking the
        // library down with it.
        null
    } catch (_: SecurityException) {
        // Media permission was revoked after the scan that produced this tile.
        null
    }
}
