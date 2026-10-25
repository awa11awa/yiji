package org.ntqqrev.acidify.common

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlin.js.JsExport

/**
 * 通过 [ByteArray] 构造的 [MediaSource] 实现。
 * 该实现会直接使用提供的 [ByteArray] 作为数据源，因此在构造时需要保证该 [ByteArray] 不会被修改，以免导致数据不一致。
 * 在调用 [readByteArray] 时会直接返回该 [ByteArray]。
 */
@JsExport
class ByteArrayMediaSource(private val data: ByteArray) : MediaSource() {
    override val size: Long
        get() = data.size.toLong()

    @JsExport.Ignore
    override fun openRawSource(): RawSource {
        return ByteArrayRawSource(data)
    }

    override fun dispose() {
        // No-op, let GC handle it
    }

    override fun readByteArray(): ByteArray {
        return data // reduce reallocation
    }

    override fun toString(): String {
        return "ByteArrayMediaSource(size=$size)"
    }

    private class ByteArrayRawSource(
        private val data: ByteArray,
    ) : RawSource {
        private var offset: Int = 0
        private var closed: Boolean = false

        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            check(!closed) { "Source is closed." }
            require(byteCount >= 0L) { "byteCount: $byteCount" }

            if (offset >= data.size) {
                return -1L
            }

            val readCount = minOf(byteCount, (data.size - offset).toLong()).toInt()
            sink.write(data, offset, offset + readCount)
            offset += readCount
            return readCount.toLong()
        }

        override fun close() {
            closed = true
        }
    }
}