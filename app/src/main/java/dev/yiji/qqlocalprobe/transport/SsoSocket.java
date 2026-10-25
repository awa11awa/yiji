package dev.yiji.qqlocalprobe.transport;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.function.Consumer;

/** One explicit pre-login SSO connection; no credential/session emulation. */
public final class SsoSocket implements Closeable {
    private final Socket socket = new Socket();
    private final Consumer<String> log;
    private final DataInputStream in;
    private final DataOutputStream out;

    public SsoSocket(String host, int port, Consumer<String> log) throws IOException {
        this.log = log;
        try {
            socket.connect(new InetSocketAddress(host, port), 8000);
            socket.setTcpNoDelay(true);
            in = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());
        } catch (IOException error) { socket.close(); throw error; }
    }

    public AndroidSsoCodec.Response exchange(int sequence, String command, byte[] packet) throws IOException {
        out.write(packet); out.flush();
        long deadline = System.nanoTime() + 12_000_000_000L;
        while (true) {
            long millis = (deadline - System.nanoTime()) / 1_000_000L;
            if (millis <= 0) throw new java.net.SocketTimeoutException("SSO response timed out");
            socket.setSoTimeout((int) millis);
            byte[] frame = readFrame(in);
            AndroidSsoCodec.Response response = AndroidSsoCodec.decode(frame);
            if (response.sequence == sequence && response.command.equals(command)) return response;
            log.accept("[NET-OTHER] seq=" + response.sequence + " cmd=" + response.command + " code=" + response.code);
        }
    }

    public static byte[] readFrame(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 4 || size > AndroidSsoCodec.MAX_FRAME) throw new IOException("Invalid SSO frame length " + size);
        byte[] frame = new byte[size - 4]; in.readFully(frame); return frame;
    }

    @Override public void close() throws IOException { socket.close(); }
}
