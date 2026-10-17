package com.example.mediabackup.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Everything the app needs to talk to S3 plus the user's backup preferences.
 *
 * The Cognito Identity Pool ID and bucket name are not secrets (the IAM role attached
 * to the pool is what authorises writes), so plain SharedPreferences is acceptable here.
 */
data class BackupSettings(
    val region: String = DEFAULT_REGION,
    val identityPoolId: String = "",
    val bucket: String = "",
    val keyPrefix: String = "media",
    val backupImages: Boolean = true,
    val backupVideos: Boolean = true,
    val autoBackupEnabled: Boolean = false,
    val autoBackupIntervalHours: Int = 24,
    val requireUnmetered: Boolean = true,
    val requireCharging: Boolean = false,
) {
    val isConfigured: Boolean
        get() = identityPoolId.isNotBlank() && bucket.isNotBlank() && region.isNotBlank()

    /**
     * Object key for one media file in isolation, e.g. `media/photos/2026/10/IMG_0001.jpg`.
     *
     * Prefer [s3KeysFor] when handling a whole scan: this function cannot tell that two
     * different files share a display name.
     */
    fun s3KeyFor(item: MediaItem): String {
        val (year, month) = yearMonthOf(item.takenAtMillis)
        val folder = if (item.isVideo) "videos" else "photos"
        val prefix = keyPrefix.trim().trim('/')
        val base = if (prefix.isEmpty()) "" else "$prefix/"
        return "$base$folder/$year/$month/${safeFileName(item.displayName)}"
    }

    /**
     * Assigns a key to every item in a scan, resolving collisions.
     *
     * Two different files in the same month can share a display name — think of two
     * downloads both called `image.jpg`. Sending both to the same key would silently
     * destroy one of them in S3 with no error anywhere, so the second and later
     * members of a collision group get a `~<mediaStoreId>` suffix before the
     * extension:
     *
     * ```
     * media/photos/2026/10/image.jpg        <- lowest id, keeps the plain key
     * media/photos/2026/10/image~84721.jpg  <- the colliding one
     * ```
     *
     * The lowest id keeps the plain key so that a name which is unique today does not
     * change key just because a same-named file is added later — the already-uploaded
     * object stays valid and only the newcomer is written elsewhere.
     *
     * Must be called with the full scan result, not just the files pending upload:
     * otherwise a new file would collide with one already in S3.
     */
    fun s3KeysFor(items: List<MediaItem>): Map<Long, String> =
        resolveKeys(items.map { it.id to s3KeyFor(it) })

    companion object {
        const val DEFAULT_REGION = "us-east-1"
    }
}

/**
 * Pure collision resolution, split out from [BackupSettings.s3KeysFor] so it can be
 * unit tested without an Android runtime.
 *
 * @param entries `mediaStoreId` to the plain object key, one per file in the scan.
 * @return mediaStoreId to the final key, unique across the whole input.
 */
internal fun resolveKeys(entries: List<Pair<Long, String>>): Map<Long, String> {
    val keys = HashMap<Long, String>(entries.size)
    for ((base, ids) in entries.groupBy({ it.second }, { it.first })) {
        val ordered = ids.sorted()
        ordered.forEachIndexed { index, id ->
            keys[id] = if (index == 0) base else disambiguateKey(base, id)
        }
    }
    return keys
}

private fun yearMonthOf(millis: Long): Pair<String, String> {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = if (millis > 0) millis else System.currentTimeMillis()
    val year = cal.get(java.util.Calendar.YEAR).toString()
    val month = (cal.get(java.util.Calendar.MONTH) + 1).toString().padStart(2, '0')
    return year to month
}

/** S3 keys must not contain path separators or characters that break tooling. */
private fun safeFileName(name: String): String {
    val cleaned = name
        .replace('/', '_')
        .replace('\\', '_')
        .replace(Regex("\\s+"), " ")
        .trim()
    return cleaned.ifEmpty { "unnamed" }
}

/**
 * Inserts `~<id>` before the file extension so a colliding name becomes unique
 * without changing how the object is identified by type.
 */
private fun disambiguateKey(baseKey: String, mediaStoreId: Long): String {
    val slash = baseKey.lastIndexOf('/')
    val dir = baseKey.substring(0, slash + 1)
    val name = baseKey.substring(slash + 1)
    val dot = name.lastIndexOf('.')
    return if (dot > 0) {
        "${dir}${name.substring(0, dot)}~$mediaStoreId${name.substring(dot)}"
    } else {
        "${dir}$name~$mediaStoreId"
    }
}

class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<BackupSettings> = _settings.asStateFlow()

    fun current(): BackupSettings = _settings.value

    fun update(transform: (BackupSettings) -> BackupSettings) {
        val updated = transform(_settings.value)
        prefs.edit {
            putString(KEY_REGION, updated.region)
            putString(KEY_POOL, updated.identityPoolId)
            putString(KEY_BUCKET, updated.bucket)
            putString(KEY_PREFIX, updated.keyPrefix)
            putBoolean(KEY_IMAGES, updated.backupImages)
            putBoolean(KEY_VIDEOS, updated.backupVideos)
            putBoolean(KEY_AUTO, updated.autoBackupEnabled)
            putInt(KEY_INTERVAL, updated.autoBackupIntervalHours)
            putBoolean(KEY_UNMETERED, updated.requireUnmetered)
            putBoolean(KEY_CHARGING, updated.requireCharging)
        }
        _settings.value = updated
    }

    private fun read() = BackupSettings(
        region = prefs.getString(KEY_REGION, BackupSettings.DEFAULT_REGION) ?: BackupSettings.DEFAULT_REGION,
        identityPoolId = prefs.getString(KEY_POOL, "").orEmpty(),
        bucket = prefs.getString(KEY_BUCKET, "").orEmpty(),
        keyPrefix = prefs.getString(KEY_PREFIX, "media").orEmpty(),
        backupImages = prefs.getBoolean(KEY_IMAGES, true),
        backupVideos = prefs.getBoolean(KEY_VIDEOS, true),
        autoBackupEnabled = prefs.getBoolean(KEY_AUTO, false),
        autoBackupIntervalHours = prefs.getInt(KEY_INTERVAL, 24),
        requireUnmetered = prefs.getBoolean(KEY_UNMETERED, true),
        requireCharging = prefs.getBoolean(KEY_CHARGING, false),
    )

    private companion object {
        const val PREFS_NAME = "backup_settings"
        const val KEY_REGION = "region"
        const val KEY_POOL = "identity_pool_id"
        const val KEY_BUCKET = "bucket"
        const val KEY_PREFIX = "key_prefix"
        const val KEY_IMAGES = "backup_images"
        const val KEY_VIDEOS = "backup_videos"
        const val KEY_AUTO = "auto_backup"
        const val KEY_INTERVAL = "interval_hours"
        const val KEY_UNMETERED = "require_unmetered"
        const val KEY_CHARGING = "require_charging"
    }
}
