package dev.yiji.qqlocalprobe;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ProbeRunner {
    private final Context context;
    private final boolean network;
    private String loginGuid;
    private LocalNativeSigner access;

    public ProbeRunner(Context context, boolean network) { this.context = context; this.network = network; }

    private void log(String message) { TraceLog.line(context, message); }

    public LocalSigner prepareLogin(long uin, String guid) throws Exception {
        loginGuid = guid;
        try { run(); }
        catch (Exception error) { throw error; }
        catch (Throwable error) { throw new IllegalStateException(error); }
        access.bindAccount(uin);
        return access;
    }

    void run() throws Throwable {
        log("[START] QQLOCALPROBE v0.3.2 pid=" + Process.myPid());
        log("[DEVICE] " + Build.MANUFACTURER + " " + Build.MODEL + " Android " + Build.VERSION.RELEASE
                + " API=" + Build.VERSION.SDK_INT + " ABI=" + Arrays.toString(Build.SUPPORTED_ABIS)
                + " process64=" + Process.is64Bit());
        log(network ? "[SCOPE] 联网握手：SDK 密钥请求经 Android SSO 发往 QQ 服务器；UIN=0，不执行账号登录。"
                : "[SCOPE] 本轮仅初始化与合成签名，不启用 SSO 发送；UIN=0。");
        if (!Process.is64Bit()) throw new IllegalStateException("此版本需要 64 位 Android 进程。");

        log("[BEGIN 1] 读取已安装 QQ 的包信息");
        PackageInfo info = context.getPackageManager().getPackageInfo("com.tencent.mobileqq", 0);
        ApplicationInfo qq = info.applicationInfo;
        long version = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        log("[QQ] version=" + info.versionName + " code=" + version + " apk=" + qq.sourceDir);
        if (!"9.3.75".equals(info.versionName)) log("[NOTE] 静态接口基于 QQ 9.3.75；当前版本仍继续实际测试。");
        List<String> apks = new ArrayList<>();
        apks.add(qq.sourceDir);
        if (qq.splitSourceDirs != null) apks.addAll(Arrays.asList(qq.splitSourceDirs));

        log("[BEGIN 2] 复制本机 QQ 的 arm64 原生库");
        File nativeDir = new File(context.getCodeCacheDir(), "qq-native");
        if (!nativeDir.isDirectory() && !nativeDir.mkdirs()) throw new IOException("无法创建 " + nativeDir);
        File fekitLib = copyLibrary("libfekit.so", qq, apks, nativeDir);
        File mmkvLib = copyLibrary("libmmkv.so", qq, apks, nativeDir);
        log("[MODULE] 直接测试 libfekit.so；libQSec.so 对应 qqprotect/QSecFramework，不作为签名初始化前置项。");

        log("[BEGIN 3] 创建 QQ 类加载器");
        File bridge = new File(context.getCodeCacheDir(), "native_bridge.dex");
        try (InputStream in = context.getAssets().open("native_bridge.dex")) { copyReadOnly(in, bridge); }
        StringBuilder dexPath = new StringBuilder(bridge.getAbsolutePath());
        for (String apk : apks) dexPath.append(File.pathSeparator).append(apk);
        DiagnosticClassLoader loader = new DiagnosticClassLoader(context, dexPath.toString(), nativeDir.getAbsolutePath());
        Thread.currentThread().setContextClassLoader(loader);
        Class<?> bridgeClass = Class.forName("dev.yiji.qqlocalprobe.bridge.NativeBridge", true, loader);
        log("[LOADER] bridge owner=" + bridgeClass.getClassLoader().getClass().getName());
        if (bridgeClass.getClassLoader() != loader) throw new IllegalStateException("NativeBridge 未进入 QQ 类加载器");
        Method load = bridgeClass.getMethod("load", String.class);

        log("[BEGIN 4] 检查签名入口的 Java 类和方法");
        Class<?> qsecClass = Class.forName("com.tencent.mobileqq.qsec.qsecurity.QSec", false, loader);
        Class<?> signClass = Class.forName("com.tencent.mobileqq.sign.QQSecuritySign", false, loader);
        for (String name : new String[]{
                "com.tencent.mobileqq.qsec.qsecurity.QSec",
                "com.tencent.mobileqq.sign.QQSecuritySign",
                "com.tencent.mobileqq.sign.QQSecuritySign$SignResult",
                "com.tencent.mobileqq.qsec.qsecprotocol.ByteData",
                "com.tencent.mobileqq.qsec.qsecdandelionsdk.Dandelion",
                "com.tencent.mobileqq.channel.ChannelManager"}) {
            Class<?> type = Class.forName(name, false, loader);
            log("[CLASS] " + name + " owner=" + type.getClassLoader().getClass().getName());
            if (type.getClassLoader() != loader) throw new IllegalStateException("QQ 类未进入签名加载器：" + name);
        }
        Method initialize = qsecClass.getMethod("init", Context.class, String.class, String.class, String.class, String.class, String.class);
        Method getSign = signClass.getMethod("getSign", qsecClass, String.class, byte[].class, byte[].class, String.class);
        log("[OK 4] 签名接口可解析；尚未执行 Java 静态初始化或原生注册");
        log("[BEGIN 5] System.load(libfekit.so)，执行 JNI_OnLoad");
        call(load, null, fekitLib.getAbsolutePath());
        log("[OK 5] libfekit.so 已加载");

        log("[BEGIN HOST] 设置 FEKit/Dtc Context，并初始化本应用私有的 QMMKV 存储");
        boolean hostReady = false;
        try {
            call(load, null, mmkvLib.getAbsolutePath());
            new HostEnvironment(context, loader).prepare();
            hostReady = true;
            log("[OK HOST] 宿主 Context 与真实本地存储已验证");
        } catch (Throwable error) {
            log("[HOST-FAIL] " + Log.getStackTraceString(error));
            log("[HOST] 环境修复尚未全部通过，继续记录实际签名输出以便对比");
        }

        log("[BEGIN 6] 获取 QSec 与 QQSecuritySign 实例");
        Object qsec = call(qsecClass.getMethod("getInstance"), null);
        Object signer = call(signClass.getMethod("getInstance"), null);
        DeviceIdentity identity = new DeviceIdentity(context);
        String guid = loginGuid == null ? identity.guid : loginGuid;
        String qua = "V1_AND_SQ_" + info.versionName + "_" + version + "_YYB_D";

        NativeBootstrap bootstrap = new NativeBootstrap(context, loader);
        try { bootstrap.initializeContext(); }
        catch (Throwable error) { log("[NATIVE-CONTEXT-FAIL] " + Log.getStackTraceString(error)); }

        log("[BEGIN 7] QSec.init(本应用 Context, 预登录 UIN=0, 固定 GUID, 设备 q36, 对应版本 QUA)");
        call(initialize, qsec, context.getApplicationContext(), "0", guid, "", identity.qimei, qua);
        call(signClass.getMethod("init", String.class), signer, qua);
        log("[SIGNER] QQSecuritySign.init 已传入与 QSec 一致的测试 QUA");
        log("[OK 7] Java 初始化调用已返回；不等同于签名可用");

        try { bootstrap.observeTokenRequests(signer); }
        catch (Throwable error) { log("[BOOTSTRAP-FAIL] " + Log.getStackTraceString(error)); }

        if (network) {
            try { bootstrap.forwardPending(qsec, signer, getSign, guid, info.versionName, version); }
            catch (Throwable error) {
                log("[NETWORK-FAIL] " + Log.getStackTraceString(error));
                if (loginGuid != null) throw error;
            }

        }
        bootstrap.report();
        access = new LocalNativeSigner(context, loader, qsec, signer, getSign, bootstrap);
    }

    private byte[] inspectResult(String label, Object result) throws Exception {
        if (result == null) {
            log("[RESULT " + label + "] 返回 null");
            return null;
        }
        byte[] sign = null;
        for (Field field : result.getClass().getFields()) {
            if (field.getType() == byte[].class) {
                byte[] value = (byte[]) field.get(result);
                int length = value == null ? 0 : value.length;
                log("[RESULT " + label + "] " + field.getName() + " length=" + length
                        + (length > 0 ? " sha256=" + hex(MessageDigest.getInstance("SHA-256").digest(value)) : ""));
                if ("sign".equals(field.getName())) sign = value;
            }
        }
        return sign;
    }

    private static String compare(byte[] first, byte[] second) {
        if (first == null || first.length == 0 || second == null || second.length == 0) return "empty-or-null";
        return Arrays.equals(first, second) ? "same" : "different";
    }

    private File copyLibrary(String name, ApplicationInfo qq, List<String> apks, File destDir) throws IOException {
        File dest = new File(destDir, name);
        if (qq.nativeLibraryDir != null) {
            File installed = new File(qq.nativeLibraryDir, name);
            if (installed.isFile() && qq.nativeLibraryDir.contains("arm64")) {
                try (InputStream in = new FileInputStream(installed)) { copyReadOnly(in, dest); }
                log("[LIB] " + name + " bytes=" + dest.length() + " sha256=" + digestFile(dest) + " source=" + installed);
                return dest;
            }
        }
        String entryName = "lib/arm64-v8a/" + name;
        for (String apk : apks) {
            try (ZipFile zip = new ZipFile(apk)) {
                ZipEntry entry = zip.getEntry(entryName);
                if (entry == null) continue;
                try (InputStream in = zip.getInputStream(entry)) { copyReadOnly(in, dest); }
                log("[LIB] " + name + " bytes=" + dest.length() + " sha256=" + digestFile(dest) + " source=" + apk + "!/" + entryName);
                return dest;
            }
        }
        throw new IOException("当前 QQ 安装包中未找到 " + entryName);
    }

    // Android 14+ requires dynamically loaded DEX files to be read-only.
    // Open the write FD first, then make the path read-only before writing.
    private static void copyReadOnly(InputStream in, File dest) throws IOException {
        if (dest.exists() && !dest.delete()) throw new IOException("无法替换 " + dest);
        try (FileOutputStream out = new FileOutputStream(dest)) {
            if (!dest.setReadOnly()) throw new IOException("无法将动态代码设为只读: " + dest);
            byte[] buffer = new byte[32768];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        }
    }

    private static Object call(Method method, Object owner, Object... args) throws Throwable {
        try { return method.invoke(owner, args); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }

    private static String digestFile(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[32768];
                int count;
                while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return hex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
}
