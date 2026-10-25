package dev.yiji.qqlocalprobe.bridge;

/** Compiled to a separate DEX so JNI sees the same loader as QQ's classes. */
public final class NativeBridge {
    public static void load(String path) {
        System.load(path);
    }
}
