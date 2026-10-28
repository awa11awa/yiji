package cn.yiji.app.qq

import org.junit.Assert.*
import org.junit.Test
import org.ntqqrev.acidify.internal.util.ProtocolLimits
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

class QqResourceLimitsTest {
    private fun deflate(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { out ->
        DeflaterOutputStream(out).use { it.write(bytes) }; out.toByteArray()
    }
    @Test fun untrustedFrameLengthsAreCheckedBeforeConversionOrReading() {
        listOf(0L, 3L, ProtocolLimits.MAX_FRAME_BYTES.toLong() + 1L, 0x80000000L, 0xffffffffL, Long.MAX_VALUE).forEach { size ->
            assertThrows(IllegalArgumentException::class.java) { ProtocolLimits.frameBodySize(size) }
        }
        assertEquals(0, ProtocolLimits.frameBodySize(4L))
        assertEquals(ProtocolLimits.MAX_FRAME_BYTES - 4, ProtocolLimits.frameBodySize(ProtocolLimits.MAX_FRAME_BYTES.toLong()))
    }
    @Test fun validZlibResponseIsPreservedButExpansionBeyondTheLimitStops() {
        val normal = "QQ 正常长消息🙂".repeat(3000).toByteArray()
        assertArrayEquals(normal, ProtocolLimits.inflate(deflate(normal)))
        val bomb = deflate(ByteArray(ProtocolLimits.MAX_INFLATED_BYTES + 1))
        assertThrows(IllegalArgumentException::class.java) { ProtocolLimits.inflate(bomb) }
    }
    @Test fun longMessageGzipUsesTheSameOutputLimit() {
        fun compressed(bytes: ByteArray) = ByteArrayOutputStream().use { out ->
            GZIPOutputStream(out).use { it.write(bytes) }; out.toByteArray()
        }
        val decode = Class.forName("org.ntqqrev.acidify.internal.util.GzipKt").getDeclaredMethod("gzipUncompress", ByteArray::class.java)
        val normal = "正常转发正文".repeat(3000).toByteArray()
        assertArrayEquals(normal, decode.invoke(null, compressed(normal)) as ByteArray)
        val failure = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
            decode.invoke(null, compressed(ByteArray(ProtocolLimits.MAX_INFLATED_BYTES + 1)))
        }
        assertTrue(failure.cause is IllegalArgumentException)
    }
    @Test fun malformedInternalFieldLengthsCannotAllocateBeyondThePacket() {
        val type = Class.forName("org.ntqqrev.acidify.internal.util.BinaryReader")
        val reader = type.getConstructor(ByteArray::class.java).newInstance(byteArrayOf(1, 2))
        val read = type.getMethod("readBytes", Int::class.javaPrimitiveType)
        val failure = assertThrows(java.lang.reflect.InvocationTargetException::class.java) { read.invoke(reader, Int.MAX_VALUE) }
        assertTrue(failure.cause is IllegalArgumentException)
        assertArrayEquals(byteArrayOf(1, 2), read.invoke(reader, 2) as ByteArray)
    }
}
