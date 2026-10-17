package com.example.mediabackup.data

import java.io.FilterInputStream
import java.io.InputStream

/**
 * Counts how many bytes have been read so uploads can report progress.
 *
 * The AWS SDK reads the stream in chunks while streaming the request body, so
 * wrapping the stream is enough to get byte-accurate progress without any
 * temp files on disk.
 */
class ProgressInputStream(
    input: InputStream,
    private val onBytes: (Long) -> Unit,
) : FilterInputStream(input) {

    private var reported = 0L

    override fun read(): Int {
        val b = `in`.read()
        if (b >= 0) bump(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = `in`.read(b, off, len)
        if (n > 0) bump(n.toLong())
        return n
    }

    private fun bump(n: Long) {
        reported += n
        onBytes(reported)
    }
}
