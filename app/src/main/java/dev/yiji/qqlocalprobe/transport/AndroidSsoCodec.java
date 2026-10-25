package dev.yiji.qqlocalprobe.transport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.InflaterInputStream;

/**
 * Pre-login protocol-12 encoder / protocol-12+13 response decoder, adapted
 * from Acidify SsoCodec at d203aa5075ee0e62d687c3f4de72b5ebbde2d546.
 * GPL-3.0; see LICENSE and THIRD_PARTY_NOTICES.md.
 */
public final class AndroidSsoCodec {
    public static final int MAX_FRAME = 4 * 1024 * 1024;
    private static final byte[] EMPTY_KEY = new byte[16];
    private AndroidSsoCodec() {}

    public static final class Signature {
        public final byte[] sign, token, extra;
        public Signature(byte[] sign, byte[] token, byte[] extra) {
            this.sign = sign == null ? new byte[0] : sign;
            this.token = token == null ? new byte[0] : token;
            this.extra = extra == null ? new byte[0] : extra;
        }
    }

    public static final class Response {
        public final int sequence, code;
        public final String command, detail;
        public final byte[] body;
        public Response(int sequence, int code, String command, String detail, byte[] body) {
            this.sequence = sequence; this.code = code; this.command = command; this.detail = detail; this.body = body;
        }
    }

    public static byte[] encode(int subAppId, String version, String guid, int sequence,
                                String command, byte[] payload, Signature signature) throws IOException {
        if (signature.sign.length == 0) throw new IOException("Empty native signature");
        Bytes header = new Bytes();
        header.out.writeInt(sequence);
        header.out.writeInt(subAppId);
        header.out.writeInt(2052);
        header.out.write(new byte[]{2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        header.pref32(new byte[0]); // pre-login A2
        header.pref32(utf8(command));
        header.pref32(new byte[0]);
        header.pref32(utf8(guid));
        header.pref32(new byte[0]);
        byte[] clientVersion = utf8(version);
        header.out.writeShort(clientVersion.length + 2);
        header.out.write(clientVersion);
        header.pref32(reserved(signature));
        Bytes sso = new Bytes();
        sso.pref32(header.data()); sso.pref32(payload);
        Bytes outer = new Bytes();
        outer.out.writeInt(12);
        outer.out.writeByte(2); // actual pre-login empty-key TEA mode
        outer.pref32(new byte[0]);
        outer.out.writeByte(0);
        outer.pref32(utf8("0"));
        outer.out.write(QQTea.encrypt(sso.data(), EMPTY_KEY));
        Bytes frame = new Bytes(); frame.pref32(outer.data());
        return frame.data();
    }

    private static byte[] reserved(Signature signature) throws IOException {
        Bytes secure = new Bytes();
        secure.field(1, signature.sign); secure.field(2, signature.token); secure.field(3, signature.extra);
        Bytes reserved = new Bytes();
        String trace = "00-" + UUID.randomUUID().toString().replace("-", "") + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "-01";
        reserved.field(15, utf8(trace)); reserved.field(16, new byte[0]);
        reserved.number(21, 0); reserved.field(24, secure.data()); reserved.number(26, 100);
        return reserved.data();
    }

    /** The TCP length prefix has already been removed. */
    public static Response decode(byte[] frame) throws IOException {
        Reader outer = new Reader(frame);
        int protocol = outer.in.readInt(), mode = outer.in.readUnsignedByte();
        if (protocol != 12 && protocol != 13) throw new IOException("Unexpected SSO protocol " + protocol);
        outer.in.readByte(); outer.pref32();
        byte[] encrypted = outer.bytes(outer.in.available());
        byte[] plain;
        if (mode == 0) plain = encrypted;
        else if (mode == 1 || mode == 2) plain = QQTea.decrypt(encrypted, EMPTY_KEY);
        else throw new IOException("Unexpected SSO encryption mode " + mode);
        Reader sso = new Reader(plain);
        int headerLength = sso.in.readInt();
        if (headerLength < 4 || headerLength > plain.length) throw new IOException("Invalid SSO header length");
        int sequence = sso.in.readInt(), code = sso.in.readInt();
        String detail = text(sso.pref32()), command = text(sso.pref32());
        sso.pref32(); // cookie
        int compression = sso.in.readInt();
        sso.bytes(sso.in.readInt()); // this prefix excludes its own four bytes
        int position = plain.length - sso.in.available();
        if (position > headerLength) throw new IOException("SSO header fields exceed header length");
        sso.bytes(headerLength - position);
        byte[] body = sso.pref32();
        if (compression == 1) {
            try (InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(body))) {
                ByteArrayOutputStream decoded = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192]; int count;
                while ((count = inflater.read(buffer)) != -1) {
                    if (decoded.size() + count > MAX_FRAME) throw new IOException("Inflated SSO response is too large");
                    decoded.write(buffer, 0, count);
                }
                body = decoded.toByteArray();
            }
        }
        return new Response(sequence, code, command, detail, body);
    }

    private static final class Reader {
        final DataInputStream in;
        Reader(byte[] bytes) { in = new DataInputStream(new ByteArrayInputStream(bytes)); }
        byte[] bytes(int length) throws IOException {
            if (length < 0 || length > in.available()) throw new IOException("Invalid SSO field length " + length);
            byte[] value = new byte[length]; in.readFully(value); return value;
        }
        byte[] pref32() throws IOException { return bytes(in.readInt() - 4); }
    }
    private static final class Bytes {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bytes);
        byte[] data() { return bytes.toByteArray(); }
        void pref32(byte[] value) throws IOException { out.writeInt(value.length + 4); out.write(value); }
        void varint(int value) throws IOException {
            while ((value & ~127) != 0) { out.writeByte((value & 127) | 128); value >>>= 7; }
            out.writeByte(value);
        }
        void field(int id, byte[] value) throws IOException { varint((id << 3) | 2); varint(value.length); out.write(value); }
        void number(int id, int value) throws IOException { varint(id << 3); varint(value); }
    }
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value, StandardCharsets.UTF_8); }
}
