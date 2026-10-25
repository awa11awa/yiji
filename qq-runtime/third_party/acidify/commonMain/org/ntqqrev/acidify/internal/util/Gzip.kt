package org.ntqqrev.acidify.internal.util

import dev.karmakrafts.kompress.deflating
import dev.karmakrafts.kompress.inflating
import kotlinx.io.*

private const val GZIP_ID1: Byte = 0x1f
private const val GZIP_ID2: Byte = 0x8b.toByte()
private const val GZIP_CM_DEFLATE: Byte = 0x08

// FLG bits
private const val FTEXT = 1 shl 0
private const val FHCRC = 1 shl 1
private const val FEXTRA = 1 shl 2
private const val FNAME = 1 shl 3
private const val FCOMMENT = 1 shl 4

internal fun gzipCompress(input: ByteArray): ByteArray {
    val crc = Crc32()
    crc.update(input)
    val crcValue = crc.value()

    val deflated = Buffer().apply { writeBytes(input) }.deflating()
    val out = Buffer().apply {
        writeByte(GZIP_ID1)
        writeByte(GZIP_ID2)
        writeByte(GZIP_CM_DEFLATE)
        writeByte(0) // FLG = 0 (no extra fields)
        writeIntLe(0) // MTIME = 0
        writeByte(0) // XFL = 0
        writeByte(0xff.toByte()) // OS = 255 (unknown)
        transferFrom(deflated)
        writeIntLe(crcValue)
        writeIntLe(input.size) // ISIZE mod 2^32; Int is fine
    }

    return out.readByteArray()
}

internal fun gzipUncompress(gz: ByteArray): ByteArray {
    val gzBuf = Buffer().apply { writeBytes(gz) }
    val id1 = gzBuf.readByte()
    val id2 = gzBuf.readByte()

    if (id1 != GZIP_ID1 || id2 != GZIP_ID2) {
        throw IllegalArgumentException("Not a gzip stream (bad magic)")
    }

    val cm = gzBuf.readByte()
    if (cm != GZIP_CM_DEFLATE) {
        throw IllegalArgumentException("Unsupported compression method: $cm")
    }

    val flg = gzBuf.readByte().toInt()
    gzBuf.skip(6) // MTIME(4) + XFL(1) + OS(1)

    // Optional fields
    if ((flg and FEXTRA) != 0) {
        val xlen = gzBuf.readShortLe()
        gzBuf.skip(xlen.toLong())
    }
    if ((flg and FNAME) != 0) {
        gzBuf.skipUntilZero()
    }
    if ((flg and FCOMMENT) != 0) {
        gzBuf.skipUntilZero()
    }
    if ((flg and FHCRC) != 0) {
        // header CRC16 present (we don't validate; just consume)
        gzBuf.skip(2)
    }

    val compressed = Buffer()
    gzBuf.readTo(compressed, byteCount = gzBuf.size - 8)
    val uncompressed = compressed.inflating().buffered().use { source ->
        source.readByteArray()
    }

    // Read trailer
    val expectedCrc = gzBuf.readIntLe()
    val expectedISize = gzBuf.readIntLe()

    // Verify CRC32
    val crc = Crc32()
    crc.update(uncompressed)
    val actualCrc = crc.value()
    if (actualCrc != expectedCrc) {
        throw IllegalArgumentException(
            "GZip CRC32 mismatch: expected=0x${expectedCrc.toUInt().toString(16)}, actual=0x${
                actualCrc.toUInt().toString(16)
            }"
        )
    }

    // Verify ISIZE (mod 2^32)
    if (uncompressed.size != expectedISize) {
        throw IllegalArgumentException(
            "GZip ISIZE mismatch: expected=$expectedISize, actual=${uncompressed.size}"
        )
    }

    return uncompressed
}

private fun Source.skipUntilZero() {
    while (!this.exhausted()) {
        val b = this.readByte()
        if (b == 0.toByte()) return
    }
}

private class Crc32 {
    private var crc: Int = -1

    fun update(data: ByteArray) {
        var c = crc
        for (b in data) {
            val idx = (c xor (b.toInt() and 0xFF)) and 0xFF
            c = (c ushr 8) xor TABLE[idx]
        }
        crc = c
    }

    fun value(): Int = crc xor -1

    companion object {
        private val TABLE: IntArray = IntArray(256).also { table ->
            for (n in 0 until 256) {
                var c = n
                for (k in 0 until 8) {
                    c = if ((c and 1) != 0) 0xEDB88320.toInt() xor (c ushr 1) else (c ushr 1)
                }
                table[n] = c
            }
        }
    }
}