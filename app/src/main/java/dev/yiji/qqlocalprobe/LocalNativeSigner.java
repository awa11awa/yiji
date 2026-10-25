package dev.yiji.qqlocalprobe;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import org.json.JSONObject;

final class LocalNativeSigner implements LocalSigner {
    private final Context context;
    private final ClassLoader loader;
    private final Object qsec, signer;
    private final Method getSign;
    private final NativeBootstrap bootstrap;

    LocalNativeSigner(Context context, ClassLoader loader, Object qsec, Object signer,
                      Method getSign, NativeBootstrap bootstrap) {
        this.context = context; this.loader = loader; this.qsec = qsec; this.signer = signer;
        this.getSign = getSign; this.bootstrap = bootstrap;
    }

    void bindAccount(long uin) throws Exception {
        invoke(qsec.getClass().getMethod("updateUserID", String.class), qsec, Long.toString(uin));
        Class<?> dtn = type("com.tencent.mobileqq.dt.Dtn");
        Object instance = invoke(dtn.getMethod("a"), null);
        invoke(dtn.getMethod("initUin", String.class), instance, Long.toString(uin));
        TraceLog.line(context, "[LOGIN-NATIVE] 已绑定本次账号，登录签名使用同一设备身份");
    }

    @Override public synchronized byte[][] sign(long uin, String command, byte[] body, int sequence) throws Exception {
        byte[] seq = {(byte)(sequence >>> 24), (byte)(sequence >>> 16), (byte)(sequence >>> 8), (byte)sequence};
        Object result = invoke(getSign, signer, qsec, command, body, seq, Long.toString(uin));
        if (result == null) throw new IllegalStateException("本地 sign 返回 null");
        byte[][] fields = new byte[3][];
        String[] names = {"sign", "token", "extra"};
        for (int i = 0; i < 3; i++) {
            fields[i] = (byte[]) result.getClass().getField(names[i]).get(result);
            if (fields[i] == null) fields[i] = new byte[0];
        }
        if (fields[0].length == 0) throw new IllegalStateException("本地 sign 为空");
        TraceLog.line(context, "[LOGIN-SIGN] cmd=" + command + " seq=" + sequence + " signLength=" + fields[0].length
                + " tokenLength=" + fields[1].length + " extraLength=" + fields[2].length);
        return fields;
    }

    @Override public synchronized byte[] energy(long uin, String command, byte[] guid, String sdkVersion) throws Exception {
        Class<?> dandelion = type("com.tencent.mobileqq.qsec.qsecdandelionsdk.Dandelion");
        Object instance = invoke(dandelion.getMethod("getInstance"), null);
        invoke(dandelion.getMethod("init"), instance);
        byte[] result = (byte[]) invoke(dandelion.getMethod("fly", String.class, byte[].class), instance,
                command, EnergySalt.build(uin, command, guid, sdkVersion));
        if (result == null || result.length == 0) throw new IllegalStateException("本地 energy 为空");
        TraceLog.line(context, "[LOGIN-ENERGY] cmd=" + command + " length=" + result.length);
        return result;
    }

    @Override public synchronized byte[] debugId(long uin, String command) throws Exception {
        // QQ's getFeKitAttach forwards the account string to this private native
        // method. Its command/subcommand arguments are used only in logging.
        Method nativeId = qsec.getClass().getDeclaredMethod("getXwDebugID", String.class);
        nativeId.setAccessible(true);
        byte[] result = (byte[]) invoke(nativeId, qsec, Long.toString(uin));
        if (result == null) result = new byte[0];
        TraceLog.line(context, "[LOGIN-DEBUG-ID] cmd=" + command + " length=" + result.length);
        return result;
    }

    @Override public Object[] takeRequest() throws InterruptedException { return bootstrap.takeRequest(); }
    @Override public Object[] pollRequest() { return bootstrap.pollRequest(); }
    @Override public synchronized void deliver(String command, byte[] body, int code, long callbackId) throws Exception {
        bootstrap.deliver(command, body, code, callbackId);
    }

    @Override public String appInfoJson() throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo("com.tencent.mobileqq",
                Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
        if (!"9.3.75".equals(info.versionName)) throw new IllegalStateException("登录参数当前核对 QQ 9.3.75");
        Class<?> setting = type("com.tencent.common.config.AppSetting");
        android.content.pm.ApplicationInfo application = context.getPackageManager().getApplicationInfo(
                "com.tencent.mobileqq", PackageManager.GET_META_DATA);
        String parameters = application.metaData == null ? null : application.metaData.getString(ClientProfile.METADATA_KEY);
        int subAppId = ClientProfile.subAppId(parameters);
        Class<?> util = type("oicq.wlogin_sdk.tools.util");
        String sdkVersion = (String) staticField(util, "SDK_VERSION");
        long buildTime = ((Number) staticField(util, "BUILD_TIME")).longValue();
        int ssoVersion = ((Number) staticField(util, "SSO_VERSION")).intValue();
        byte[] cert = Build.VERSION.SDK_INT >= 28 ? info.signingInfo.getApkContentsSigners()[0].toByteArray()
                : info.signatures[0].toByteArray();
        byte[] md5 = MessageDigest.getInstance("MD5").digest(cert);
        long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        JSONObject json = new JSONObject();
        json.put("Os", ClientProfile.OS).put("Kernel", "").put("VendorOs", "android")
                .put("Qua", "V1_AND_SQ_" + info.versionName + "_" + code + "_YYB_D")
                .put("CurrentVersion", staticField(setting, "k") + "." + staticField(setting, "b"))
                .put("PtVersion", info.versionName).put("SsoVersion", ssoVersion)
                .put("PackageName", "com.tencent.mobileqq").put("ApkSignatureMd5", new org.json.JSONArray(md5))
                .put("AppId", 16).put("SubAppId", subAppId).put("AppClientVersion", 0)
                .put("SdkInfo", new JSONObject().put("SdkBuildTime", buildTime).put("SdkVersion", sdkVersion)
                        .put("MiscBitMap", 150470524L).put("SubSigMap", 66560L).put("MainSigMap", 16724722L));
        TraceLog.line(context, "[LOGIN-METADATA] profile=" + ClientProfile.ID + " source=" + ClientProfile.METADATA_KEY
                + " subAppId=" + subAppId + " sdk=" + sdkVersion
                + " sso=" + ssoVersion + " buildTime=" + buildTime);
        return json.toString();
    }

    private Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name, true, loader); }
    private static Object staticField(Class<?> type, String name) throws Exception {
        java.lang.reflect.Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(null);
    }
    private static Object invoke(Method method, Object owner, Object... args) throws Exception {
        try { return method.invoke(owner, args); }
        catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException(cause);
        }
    }
}
