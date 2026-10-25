package dev.yiji.qqlocalprobe;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import dev.yiji.qqlocalprobe.transport.AndroidSsoCodec;
import dev.yiji.qqlocalprobe.transport.SsoSocket;

/** Exercise public native initialization and observe outbound requests offline. */
final class NativeBootstrap {
    private final Context context;
    private final ClassLoader loader;
    private final AtomicInteger requests = new AtomicInteger();
    private final LinkedBlockingQueue<Request> pending = new LinkedBlockingQueue<>();
    private int serverResponses, deliveredResponses;
    private Object channel;

    NativeBootstrap(Context context, ClassLoader loader) {
        this.context = context.getApplicationContext();
        this.loader = loader;
    }

    void initializeContext() throws Throwable {
        log("[BEGIN NATIVE-CONTEXT] Dtn.initNativeContext 与 initUin(0)");
        Class<?> dtn = type("com.tencent.mobileqq.dt.Dtn");
        Object instance = call(dtn.getMethod("a"), null);
        File folder = new File(context.getFilesDir(), "5463306EE50FE3AA");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IllegalStateException("无法创建原生上下文目录");
        context.getDir("files", Context.MODE_PRIVATE);
        call(dtn.getMethod("initNativeContext", Context.class, String.class), instance, context, folder.getAbsolutePath());
        call(dtn.getMethod("initUin", String.class), instance, "0");
        log("[OK NATIVE-CONTEXT] 原生上下文初始化调用已返回");
    }

    void observeTokenRequests(Object signer) throws Throwable {
        log("[BEGIN CHANNEL] 注册出站回调；请求先保存在本轮内存队列");
        Class<?> base = type("com.tencent.mobileqq.channel.ChannelProxy");
        Class<?> implementation = type("dev.yiji.qqlocalprobe.bridge.ProbeChannel");
        Consumer<Object[]> observer = this::observe;
        channel = implementation.getConstructor(Consumer.class).newInstance(observer);
        if (implementation.getSuperclass() != base || implementation.getClassLoader() != loader)
            throw new IllegalStateException("回调未使用 QQ 所在加载器的 ChannelProxy");
        call(base.getMethod("sendMessageInner", String.class, byte[].class, long.class),
                channel, "QQLOCALPROBE.selftest", new byte[]{8, 0}, 0L);
        Class<?> managerClass = type("com.tencent.mobileqq.channel.ChannelManager");
        Object manager = call(managerClass.getMethod("getInstance"), null);
        call(managerClass.getMethod("init", base), manager, channel);
        call(managerClass.getMethod("setChannelProxy", base), manager, channel);
        call(managerClass.getMethod("onChannelReady"), manager);
        log("[OK CHANNEL] 原生通道注册调用已返回；联网模式会在后续 SSO 步骤处理队列");

        log("[BEGIN TOKEN] QQSecuritySign.requestToken；只观察实际回调");
        call(signer.getClass().getMethod("requestToken"), signer);
        Thread.sleep(1500);
        log("[TOKEN] requestToken 调用已返回；nativeRequests=" + requests.get() + "，尚未处理服务器回包");
    }

    void report() {
        log("[CHANNEL-SUMMARY] nativeRequests=" + requests.get() + " serverResponses=" + serverResponses
                + " deliveredResponses=" + deliveredResponses + " pending=" + pending.size() + "；不包含回调自测");
    }

    Object[] takeRequest() throws InterruptedException {
        Request request = pending.take();
        return new Object[]{request.command, request.body, request.callbackId};
    }

    Object[] pollRequest() {
        Request request = pending.poll();
        return request == null ? null : new Object[]{request.command, request.body, request.callbackId};
    }

    void deliver(String command, byte[] body, int code, long callbackId) throws Exception {
        try {
            Class<?> managerClass = type("com.tencent.mobileqq.channel.ChannelManager");
            Object manager = call(managerClass.getMethod("getInstance"), null);
            call(managerClass.getMethod("onNativeReceive", String.class, byte[].class, boolean.class, int.class, long.class),
                    manager, command, body, code == 0, code, callbackId);
            serverResponses++; deliveredResponses++;
            log("[LOGIN-CHANNEL] cmd=" + command + " code=" + code + " 回包已交给 SDK");
        } catch (Exception error) { throw error; }
        catch (Throwable error) { throw new IllegalStateException(error); }
    }

    void forwardPending(Object qsec, Object signer, Method getSign, String guid, String installedVersion, long versionCode) throws Throwable {
        if (!"9.3.75".equals(installedVersion) || versionCode != 16580)
            throw new IllegalStateException("联网封包参数目前仅核对 QQ 9.3.75 / 16580");
        Class<?> settings = type("com.tencent.common.config.AppSetting");
        int subAppId = (Integer) field(settings, "f");
        String version = field(settings, "k") + "." + field(settings, "b");
        android.content.pm.ApplicationInfo qq = context.getPackageManager().getApplicationInfo(
                "com.tencent.mobileqq", android.content.pm.PackageManager.GET_META_DATA);
        if (qq.metaData != null) {
            String parameters = qq.metaData.getString("AppSetting_params");
            if (parameters != null && !parameters.isEmpty()) subAppId = Integer.parseInt(parameters.split("#", -1)[0]);
        }
        log("[NET-METADATA] Android SSO/12 subAppId=" + subAppId + " currentVersion=" + version
                + " uin=0；参数读取自本机 QQ");
        if (pending.isEmpty()) { log("[NET] 本轮没有待处理的原生请求"); return; }
        Class<?> managerClass = type("com.tencent.mobileqq.channel.ChannelManager");
        Object manager = call(managerClass.getMethod("getInstance"), null);
        Method receive = managerClass.getMethod("onNativeReceive", String.class, byte[].class, boolean.class, int.class, long.class);
        log("[BEGIN NETWORK] 连接 msfwifi.3g.qq.com:8080，发送 SDK 生成的密钥握手请求，不执行账号登录");
        int sent = 0, idle = 0;
        long end = android.os.SystemClock.elapsedRealtime() + 35000;
        try (SsoSocket connection = new SsoSocket("msfwifi.3g.qq.com", 8080, this::log)) {
            log("[NET-CONNECTED] TCP 已连接；尚未表示 SSO 或签名被服务器接受");
            while (sent < 6 && android.os.SystemClock.elapsedRealtime() < end) {
                Request request = pending.poll();
                if (request == null) {
                    if (++idle >= 4) break;
                    Thread.sleep(250);
                    continue;
                }
                idle = 0;
                int sequence = 10000 + ++sent;
                byte[] sequenceBytes = new byte[]{(byte)(sequence >>> 24), (byte)(sequence >>> 16), (byte)(sequence >>> 8), (byte)sequence};
                Object result = call(getSign, signer, qsec, request.command, request.body, sequenceBytes, "0");
                if (result == null) throw new IllegalStateException("握手请求签名返回 null");
                AndroidSsoCodec.Signature signature = new AndroidSsoCodec.Signature(
                        (byte[]) result.getClass().getField("sign").get(result),
                        (byte[]) result.getClass().getField("token").get(result),
                        (byte[]) result.getClass().getField("extra").get(result));
                byte[] frame = AndroidSsoCodec.encode(subAppId, version, guid, sequence, request.command, request.body, signature);
                log("[NET-SEND] seq=" + sequence + " cmd=" + request.command + " bodyLength=" + request.body.length
                        + " frameLength=" + frame.length + " signLength=" + signature.sign.length);
                AndroidSsoCodec.Response response = connection.exchange(sequence, request.command, frame);
                serverResponses++;
                String detail = response.detail.length() > 160 ? response.detail.substring(0, 160) : response.detail;
                log("[NET-RESPONSE] seq=" + response.sequence + " cmd=" + response.command + " code=" + response.code
                        + " bodyLength=" + response.body.length + " detail=" + detail);
                call(receive, manager, response.command, response.body, response.code == 0, response.code, request.callbackId);
                deliveredResponses++;
                log("[NET-DELIVERED] 真实服务器回包已交给 SDK；原生接收调用已返回");
            }
        }
        log("[NETWORK-END] sent=" + sent + " serverResponses=" + serverResponses + " pending=" + pending.size());
    }

    private static Object field(Class<?> type, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static final class Request {
        final String command;
        final byte[] body;
        final long callbackId;
        Request(String command, byte[] body, long callbackId) {
            this.command = command; this.body = body == null ? new byte[0] : body.clone(); this.callbackId = callbackId;
        }
    }

    private void observe(Object[] args) {
        String command = (String) args[0];
        byte[] body = (byte[]) args[1];
        if ("QQLOCALPROBE.selftest".equals(command)) {
            log("[CHANNEL-SELFTEST] Java 回调连通性检查通过；这不是原生发送证据");
            return;
        }
        int count = requests.incrementAndGet();
        pending.add(new Request(command, body, (Long) args[2]));
        try {
            byte[] digest = body == null ? null : MessageDigest.getInstance("SHA-256").digest(body);
            StringBuilder hex = new StringBuilder();
            if (digest != null) for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            log("[CHANNEL-REQUEST " + count + "] cmd=" + command + " bodyLength=" + (body == null ? 0 : body.length)
                    + " sha256=" + hex + "；已加入本轮请求队列");
        } catch (Exception error) { log("[CHANNEL-ERROR] " + Log.getStackTraceString(error)); }
    }

    private Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name, true, loader); }
    private void log(String message) { TraceLog.line(context, message); }
    private static Object call(Method method, Object owner, Object... args) throws Throwable {
        try { return method.invoke(owner, args); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }
}
