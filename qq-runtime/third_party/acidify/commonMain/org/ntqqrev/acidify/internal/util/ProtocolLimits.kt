package org.ntqqrev.acidify.internal.util

import dev.karmakrafts.kompress.inflating
import kotlinx.io.*

/** Bounds apply before allocation/decoding, including the pre-authentication frame header. */
object ProtocolLimits {
    const val MAX_FRAME_BYTES = 4 * 1024 * 1024
    const val MAX_INFLATED_BYTES = 8 * 1024 * 1024

    fun frameBodySize(length: Long): Int {
        require(length in 4L..MAX_FRAME_BYTES.toLong()) { "QQ frame length exceeds limits" }
        return length.toInt() - 4
    }

    fun readBounded(source: Source, limit: Int = MAX_INFLATED_BYTES): ByteArray {
        val output = Buffer()
        val chunk = ByteArray(8192)
        while (true) {
            val count = source.readAtMostTo(chunk)
            if (count < 0) break
            require(output.size + count <= limit) { "QQ expanded content exceeds limits" }
            output.write(chunk, 0, count)
        }
        return output.readByteArray()
    }

    fun inflate(payload: ByteArray): ByteArray =
        Buffer().apply { write(payload) }.inflating(raw = false).buffered().use { readBounded(it) }
}
