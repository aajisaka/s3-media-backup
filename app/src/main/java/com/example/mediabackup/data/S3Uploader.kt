package com.example.mediabackup.data

import android.content.Context
import android.net.Uri
import com.amazonaws.AmazonClientException
import com.amazonaws.AmazonServiceException
import com.amazonaws.auth.CognitoCachingCredentialsProvider
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.AbortMultipartUploadRequest
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.model.PartETag
import com.amazonaws.services.s3.model.PutObjectRequest
import com.amazonaws.services.s3.model.UploadPartRequest
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

class S3UploadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Uploads media to S3 using credentials from an unauthenticated Amazon Cognito
 * Identity Pool.
 *
 * Two upload paths are used so that media is never copied to a temp file and memory
 * stays bounded regardless of how big the movie is:
 *
 *  - up to [SINGLE_PUT_LIMIT]: a single streaming `PutObject`. Because Content-Length is
 *    always set, the AWS SDK streams the request body straight from the `ContentResolver`
 *    instead of buffering it.
 *  - above that: a multipart upload, each part held in memory long enough to be retried,
 *    with an abort if anything fails.
 *
 * Retries always re-open the media stream, so a retry can never send a partially
 * consumed body.
 */
class S3Uploader(
    private val context: Context,
    private val settings: BackupSettings,
) {

    private val s3: AmazonS3Client

    init {
        val regions = try {
            Regions.fromName(settings.region.trim())
        } catch (e: IllegalArgumentException) {
            throw S3UploadException("Unknown AWS region: ${settings.region}", e)
        }
        val credentials = CognitoCachingCredentialsProvider(
            context.applicationContext,
            settings.identityPoolId.trim(),
            regions,
        )
        s3 = AmazonS3Client(credentials, Region.getRegion(regions))
    }

    fun shutdown() = runCatching { s3.shutdown() }

    /**
     * Blocking upload. Call from a background dispatcher.
     *
     * @param onProgress receives the running total of bytes written for this file.
     */
    fun uploadBlocking(item: MediaItem, key: String, onProgress: (Long) -> Unit) {
        val size = item.sizeBytes
        try {
            when {
                size <= 0 -> uploadUnknownLength(item, key, onProgress)
                size <= SINGLE_PUT_LIMIT -> uploadSinglePut(item, key, size, onProgress)
                else -> uploadMultipart(item, key, size, onProgress)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw S3UploadException("Upload cancelled", e)
        }
    }

    // ------------------------------------------------------------------ single put

    private fun uploadSinglePut(
        item: MediaItem,
        key: String,
        size: Long,
        onProgress: (Long) -> Unit,
    ) {
        val metadata = metadataFor(item, size)
        withRetry("PUT s3://${settings.bucket}/$key") {
            onProgress(0)
            withStream(item.uri) { raw ->
                val counting = ProgressInputStream(raw, onProgress)
                s3.putObject(PutObjectRequest(settings.bucket, key, counting, metadata))
            }
        }
        onProgress(size)
    }

    // ------------------------------------------------------------------ multipart

    private fun uploadMultipart(
        item: MediaItem,
        key: String,
        size: Long,
        onProgress: (Long) -> Unit,
    ) {
        val partSize = partSizeFor(size)
        if (partSize > MAX_PART_BUFFER_BYTES) {
            throw S3UploadException(
                "${item.displayName} is too large to upload " +
                    "(${size / MB} MB, limit ${MAX_UPLOAD_BYTES / MB} MB)",
            )
        }
        val partCount = ((size + partSize - 1) / partSize).toInt()

        val uploadId = try {
            withRetry("start multipart upload for $key") {
                val request = InitiateMultipartUploadRequest(settings.bucket, key)
                    .withObjectMetadata(metadataFor(item, size))
                s3.initiateMultipartUpload(request).uploadId
            }
        } catch (e: Exception) {
            throw S3UploadException("Could not start multipart upload: ${e.message}", e)
        }

        val etags = ArrayList<PartETag>(partCount)
        var totalSent = 0L

        try {
            withStream(item.uri) { raw ->
                val source = BufferedInputStream(raw, BUFFER_BYTES)
                val scratch = ByteArray(BUFFER_BYTES)
                var partIndex = 0

                while (partIndex < partCount) {
                    val wanted = minOf(partSize, size - totalSent).toInt()
                    val partBytes = readFully(source, scratch, wanted)
                    if (partBytes.isEmpty()) break

                    val partNumber = partIndex + 1
                    val request = UploadPartRequest()
                        .withBucketName(settings.bucket)
                        .withKey(key)
                        .withUploadId(uploadId)
                        .withPartNumber(partNumber)
                        .withPartSize(partBytes.size.toLong())
                        .withLastPart(partNumber == partCount)
                        .withInputStream(partBytes.inputStream())

                    // Part bytes are already in memory, so a retry is safe and cheap.
                    val result = withRetry("upload part $partNumber/$partCount of $key") {
                        s3.uploadPart(request)
                    }
                    etags += result.partETag

                    partIndex = partNumber
                    totalSent += partBytes.size
                    onProgress(totalSent)
                }
            }

            withRetry("finish multipart upload for $key") {
                s3.completeMultipartUpload(
                    CompleteMultipartUploadRequest(settings.bucket, key, uploadId, etags),
                )
            }
            onProgress(totalSent)
        } catch (e: Exception) {
            runCatching {
                s3.abortMultipartUpload(AbortMultipartUploadRequest(settings.bucket, key, uploadId))
            }
            throw S3UploadException("Multipart upload failed: ${e.message}", e)
        }
    }

    /**
     * Reads up to [wanted] bytes into a standalone array so each part can be retried
     * independently of the source stream.
     */
    private fun readFully(input: InputStream, scratch: ByteArray, wanted: Int): ByteArray {
        val out = ByteArray(wanted)
        var offset = 0
        while (offset < wanted) {
            val n = input.read(scratch, 0, minOf(wanted - offset, scratch.size))
            if (n < 0) break
            System.arraycopy(scratch, 0, out, offset, n)
            offset += n
        }
        return if (offset == wanted) out else out.copyOf(offset)
    }

    // ------------------------------------------------------------------ unknown size

    /**
     * MediaStore occasionally reports no size. This client must know Content-Length
     * before sending, so buffer once to the cache dir purely to learn the length and
     * upload from there. This is the only path that touches the filesystem.
     */
    private fun uploadUnknownLength(item: MediaItem, key: String, onProgress: (Long) -> Unit) {
        val tmp = File.createTempFile("upload-", ".bin", context.cacheDir)
        try {
            val size = withStream(item.uri) { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                    }
                    total
                }
            }
            if (size <= 0) throw S3UploadException("${item.displayName} is empty, skipping")
            uploadSinglePut(item, key, size, onProgress)
        } finally {
            tmp.delete()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun metadataFor(item: MediaItem, size: Long): ObjectMetadata = ObjectMetadata().apply {
        contentLength = size
        contentType = item.mimeType
        addUserMetadata("media-store-id", item.id.toString())
    }

    private fun <T> withStream(uri: Uri, block: (InputStream) -> T): T {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw S3UploadException("Could not open $uri")
        return stream.use(block)
    }

    private fun partSizeFor(size: Long): Long {
        val needed = (size + MAX_PARTS - 1) / MAX_PARTS
        val rounded = ((needed + MB - 1) / MB) * MB
        return maxOf(MIN_PART_BYTES, rounded)
    }

    private fun isRetryable(e: Exception): Boolean {
        if (e is InterruptedException) return false
        if (e is AmazonServiceException) {
            val status = e.statusCode
            return status == 500 || status == 502 || status == 503 || status == 509
        }
        return e is AmazonClientException || e is IOException
    }

    private fun <T> withRetry(what: String, attempts: Int = 3, block: () -> T): T {
        var last: Exception? = null
        repeat(attempts) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                if (!isRetryable(e)) throw e
                last = e
                if (attempt < attempts - 1) Thread.sleep(BACKOFF_MS * (1L shl attempt))
            }
        }
        throw S3UploadException("$what failed after $attempts attempts: ${last?.message}", last)
    }

    private companion object {
        const val MB = 1024L * 1024L

        /** S3 caps an object at 10,000 parts; stay well under that. */
        const val MAX_PARTS = 9_000L
        const val MIN_PART_BYTES = 8L * MB

        /** Each part is buffered so it can be retried; cap it to bound memory. */
        const val MAX_PART_BUFFER_BYTES = 128L * MB
        const val MAX_UPLOAD_BYTES = MAX_PART_BUFFER_BYTES * MAX_PARTS

        /** Above this, restarting one big request on failure is too expensive. */
        const val SINGLE_PUT_LIMIT = 64L * MB

        const val BUFFER_BYTES = 256 * 1024
        const val BACKOFF_MS = 750L
    }
}
