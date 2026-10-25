package com.tencent.mobileqq.channel;

/** Compile-only API declaration. Never packaged in either DEX or the APK. */
public abstract class ChannelProxy {
    public abstract void sendMessageInner(String command, byte[] body, long callbackId);
}
