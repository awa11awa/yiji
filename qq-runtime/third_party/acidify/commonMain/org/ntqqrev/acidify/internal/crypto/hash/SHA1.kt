package org.ntqqrev.acidify.internal.crypto.hash

/**
 * SHA-1 hash algorithm implementation
 * Produces 160-bit (20 byte) hash values
 */
internal object SHA1 {
    const val BLOCK_SIZE = 64  // 512 bits
    const val DIGEST_SIZE = 20 // 160 bits

    // Initial hash values (H0..H4)
    private val H0 = intArrayOf(
        0x67452301,
        0xEFCDAB89.toInt(),
        0x98BADCFE.toInt(),
        0x10325476,
        0xC3D2E1F0.toInt()
    )

    // Left rotate
    private fun rotateLeft(x: Int, n: Int): Int = (x shl n) or (x ushr (32 - n))

    // Process a single 512-bit block
    private fun processBlock(block: ByteArray, offset: Int, state: IntArray) {
        val W = IntArray(80)

        // Prepare message schedule (16 → 80 words)
        for (i in 0 until 16) {
            val idx = offset + i * 4
            W[i] = ((block[idx].toInt() and 0xFF) shl 24) or
                    ((block[idx + 1].toInt() and 0xFF) shl 16) or
                    ((block[idx + 2].toInt() and 0xFF) shl 8) or
                    (block[idx + 3].toInt() and 0xFF)
        }

        for (t in 16 until 80) {
            W[t] = rotateLeft(W[t - 3] xor W[t - 8] xor W[t - 14] xor W[t - 16], 1)
        }

        // Initialize working vars
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]

        for (t in 0 until 80) {
            val (f, k) = when (t) {
                in 0..19 -> ((b and c) or (b.inv() and d)) to 0x5A827999
                in 20..39 -> (b xor c xor d) to 0x6ED9EBA1
                in 40..59 -> ((b and c) or (b and d) or (c and d)) to 0x8F1BBCDC.toInt()
                else -> (b xor c xor d) to 0xCA62C1D6.toInt()
            }

            val temp = (rotateLeft(a, 5) + f + e + k + W[t])
            e = d
            d = c
            c = rotateLeft(b, 30)
            b = a
            a = temp
        }

        // Update state
        state[0] += a
        state[1] += b
        state[2] += c
        state[3] += d
        state[4] += e
    }

    /**
     * Compute SHA-1 hash of input data
     */
    fun hash(data: ByteArray): ByteArray {
        val state = H0.copyOf()
        val totalLength = data.size.toLong()
        var processedBytes = 0

        // Process complete 64-byte blocks
        while (processedBytes + BLOCK_SIZE <= data.size) {
            processBlock(data, processedBytes, state)
            processedBytes += BLOCK_SIZE
        }

        // Remaining bytes + padding
        val remaining = data.size - processedBytes
        val paddingBlock = ByteArray(if (remaining < BLOCK_SIZE - 8) BLOCK_SIZE else BLOCK_SIZE * 2)

        data.copyInto(paddingBlock, 0, processedBytes, processedBytes + remaining)
        paddingBlock[remaining] = 0x80.toByte()

        // Message length in bits (big-endian)
        val bitLength = totalLength * 8
        val lengthOffset = paddingBlock.size - 8
        for (i in 0 until 8) {
            paddingBlock[lengthOffset + 7 - i] = ((bitLength ushr (i * 8)) and 0xFF).toByte()
        }

        // Process padded blocks
        for (i in 0 until paddingBlock.size step BLOCK_SIZE) {
            processBlock(paddingBlock, i, state)
        }

        // Convert state to bytes (big-endian)
        val digest = ByteArray(DIGEST_SIZE)
        for (i in 0 until 5) {
            val word = state[i]
            digest[i * 4] = ((word ushr 24) and 0xFF).toByte()
            digest[i * 4 + 1] = ((word ushr 16) and 0xFF).toByte()
            digest[i * 4 + 2] = ((word ushr 8) and 0xFF).toByte()
            digest[i * 4 + 3] = (word and 0xFF).toByte()
        }

        return digest
    }

    /**
     * Compute SHA-1 hash of string
     */
    fun hash(text: String): ByteArray = hash(text.encodeToByteArray())
}

/**
 * HMAC-SHA1 implementation
 */
internal object HMACSHA1 {
    private const val BLOCK_SIZE = SHA1.BLOCK_SIZE
    private const val IPAD: Byte = 0x36
    private const val OPAD: Byte = 0x5c

    /**
     * Compute HMAC-SHA1
     * @param key Secret key
     * @param data Data to authenticate
     * @return HMAC-SHA1 digest
     */
    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val paddedKey = ByteArray(BLOCK_SIZE)

        if (key.size > BLOCK_SIZE) {
            val hashedKey = SHA1.hash(key)
            hashedKey.copyInto(paddedKey, 0, 0, hashedKey.size)
        } else {
            key.copyInto(paddedKey, 0, 0, key.size)
        }

        val keyIpad = ByteArray(BLOCK_SIZE)
        val keyOpad = ByteArray(BLOCK_SIZE)

        for (i in 0 until BLOCK_SIZE) {
            keyIpad[i] = (paddedKey[i].toInt() xor IPAD.toInt()).toByte()
            keyOpad[i] = (paddedKey[i].toInt() xor OPAD.toInt()).toByte()
        }

        val inner = keyIpad + data
        val innerDigest = SHA1.hash(inner)
        val outer = keyOpad + innerDigest
        return SHA1.hash(outer)
    }
}