package dev.yiji.qqlocalprobe;

/** App-owned API across Java, Kotlin and the QQ class loader boundary. */
public interface LocalSigner {
    byte[][] sign(long uin, String command, byte[] body, int sequence) throws Exception;
    byte[] energy(long uin, String command, byte[] guid, String sdkVersion) throws Exception;
    byte[] debugId(long uin, String command) throws Exception;
    Object[] pollRequest();
    Object[] takeRequest() throws InterruptedException;
    void deliver(String command, byte[] body, int code, long callbackId) throws Exception;
    String appInfoJson() throws Exception;
}
