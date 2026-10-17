package com.example.mediabackup.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Local index of what has already been uploaded, so repeated backups only send new
 * or changed files. [fingerprint] changes when the file size or timestamp changes.
 */
@Entity(tableName = "uploaded_media")
data class UploadedMedia(
    @PrimaryKey val mediaStoreId: Long,
    val fingerprint: String,
    val s3Key: String,
    val sizeBytes: Long,
    val uploadedAtMillis: Long,
)

/** Lightweight projection used to diff a full MediaStore scan against local history. */
data class IdFingerprint(
    val mediaStoreId: Long,
    val fingerprint: String,
)

@Dao
interface UploadedMediaDao {

    @Query("SELECT mediaStoreId, fingerprint FROM uploaded_media")
    suspend fun allFingerprints(): List<IdFingerprint>

    @Query("SELECT fingerprint FROM uploaded_media WHERE mediaStoreId = :mediaStoreId")
    suspend fun fingerprintFor(mediaStoreId: Long): String?

    @Query("SELECT s3Key FROM uploaded_media WHERE mediaStoreId = :mediaStoreId")
    suspend fun s3KeyFor(mediaStoreId: Long): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: UploadedMedia)

    @Query("SELECT COUNT(*) FROM uploaded_media")
    suspend fun count(): Int

    @Query("DELETE FROM uploaded_media WHERE mediaStoreId = :mediaStoreId")
    suspend fun delete(mediaStoreId: Long)

    @Query("DELETE FROM uploaded_media")
    suspend fun clear()
}

@Database(entities = [UploadedMedia::class], version = 1, exportSchema = false)
abstract class BackupDatabase : RoomDatabase() {

    abstract fun uploadedMediaDao(): UploadedMediaDao

    companion object {
        @Volatile
        private var instance: BackupDatabase? = null

        fun get(context: Context): BackupDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BackupDatabase::class.java,
                    "media-backup.db",
                ).build().also { instance = it }
            }
    }
}
