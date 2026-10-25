package dev.yiji.qqlocalprobe;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Android WtLogin TLV544 salt, per the pinned qsign energy reference. */
public final class EnergySalt {
    private EnergySalt() {}

    public static byte[] build(long uin, String data, byte[] guid, String sdkVersion) throws IOException {
        if (!data.startsWith("810_")) throw new IOException("Unsupported WtLogin energy command: " + data);
        int sub = Integer.parseInt(data.substring(4), 16);
        boolean v2 = sub == 9 || sub == 10 || sub == 13 || sub == 15;
        byte[] version = sdkVersion.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        if (v2) out.writeInt(0); else out.writeLong(uin);
        out.writeShort(guid.length); out.write(guid);
        out.writeShort(version.length); out.write(version);
        out.writeInt(sub);
        if (v2) out.writeInt(0);
        return bytes.toByteArray();
    }
}
