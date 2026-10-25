package dev.yiji.qqlocalprobe.transport;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;

/** QQ's 16-round TEA framing; interoperates with the pinned Acidify codec. */
public final class QQTea {
    private static final SecureRandom RANDOM = new SecureRandom();
    private QQTea() {}

    public static byte[] encrypt(byte[] input, byte[] key) {
        if (key.length != 16) throw new IllegalArgumentException("TEA key must contain 16 bytes");
        int padding = (8 - (input.length + 10) % 8) % 8;
        byte[] plain = new byte[input.length + padding + 10];
        byte[] salt = new byte[padding + 3];
        RANDOM.nextBytes(salt);
        System.arraycopy(salt, 0, plain, 0, salt.length);
        plain[0] = (byte) ((plain[0] & 0xf8) | padding);
        System.arraycopy(input, 0, plain, salt.length, input.length);
        byte[] out = new byte[plain.length], previousCipher = new byte[8], previousX = new byte[8];
        for (int offset = 0; offset < plain.length; offset += 8) {
            byte[] x = new byte[8];
            for (int i = 0; i < 8; i++) x[i] = (byte) (plain[offset + i] ^ previousCipher[i]);
            byte[] encoded = block(x, key, false);
            for (int i = 0; i < 8; i++) out[offset + i] = (byte) (encoded[i] ^ previousX[i]);
            previousCipher = Arrays.copyOfRange(out, offset, offset + 8);
            previousX = x;
        }
        return out;
    }

    public static byte[] decrypt(byte[] input, byte[] key) throws IOException {
        if (key.length != 16 || input.length < 16 || input.length % 8 != 0)
            throw new IOException("Invalid QQ TEA key or ciphertext length");
        byte[] plain = new byte[input.length], previousCipher = new byte[8], previousX = new byte[8];
        for (int offset = 0; offset < input.length; offset += 8) {
            byte[] mixed = new byte[8];
            for (int i = 0; i < 8; i++) mixed[i] = (byte) (input[offset + i] ^ previousX[i]);
            byte[] x = block(mixed, key, true);
            for (int i = 0; i < 8; i++) plain[offset + i] = (byte) (x[i] ^ previousCipher[i]);
            previousCipher = Arrays.copyOfRange(input, offset, offset + 8);
            previousX = x;
        }
        int start = (plain[0] & 7) + 3, end = plain.length - 7;
        if (start > end) throw new IOException("Invalid QQ TEA padding");
        for (int i = end; i < plain.length; i++) if (plain[i] != 0) throw new IOException("QQ TEA trailer mismatch");
        return Arrays.copyOfRange(plain, start, end);
    }

    private static byte[] block(byte[] input, byte[] key, boolean decrypt) {
        int a = integer(input, 0), b = integer(input, 4);
        int k0 = integer(key, 0), k1 = integer(key, 4), k2 = integer(key, 8), k3 = integer(key, 12);
        int sum = decrypt ? 0xe3779b90 : 0;
        for (int i = 0; i < 16; i++) {
            if (decrypt) {
                b -= ((a << 4) + k2) ^ (a + sum) ^ ((a >>> 5) + k3);
                a -= ((b << 4) + k0) ^ (b + sum) ^ ((b >>> 5) + k1);
                sum -= 0x9e3779b9;
            } else {
                sum += 0x9e3779b9;
                a += ((b << 4) + k0) ^ (b + sum) ^ ((b >>> 5) + k1);
                b += ((a << 4) + k2) ^ (a + sum) ^ ((a >>> 5) + k3);
            }
        }
        byte[] out = new byte[8];
        writeInt(out, 0, a); writeInt(out, 4, b);
        return out;
    }

    private static int integer(byte[] a, int p) {
        return (a[p] & 255) << 24 | (a[p + 1] & 255) << 16 | (a[p + 2] & 255) << 8 | a[p + 3] & 255;
    }
    private static void writeInt(byte[] out, int p, int value) {
        out[p] = (byte) (value >>> 24); out[p + 1] = (byte) (value >>> 16);
        out[p + 2] = (byte) (value >>> 8); out[p + 3] = (byte) value;
    }
}
