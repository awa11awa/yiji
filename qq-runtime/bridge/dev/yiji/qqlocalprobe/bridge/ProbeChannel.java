package dev.yiji.qqlocalprobe.bridge;

import com.tencent.mobileqq.channel.ChannelProxy;
import java.util.function.Consumer;

/** Uses QQ's runtime base class; delivers requests to an offline observer. */
public final class ProbeChannel extends ChannelProxy {
    private final Consumer<Object[]> observer;

    public ProbeChannel(Consumer<Object[]> observer) { this.observer = observer; }

    @Override public void sendMessageInner(String command, byte[] body, long callbackId) {
        observer.accept(new Object[]{command, body, callbackId});
    }
}
