package dev.yiji.qqlocalprobe;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.security.MessageDigest;

/** The certificate is pinned from the independently downloaded Tencent APK, not the installed package. */
public final class QqPackageTrust {
    public static final String PACKAGE = "com.tencent.mobileqq";
    public static final String CERTIFICATE_SHA256 = "ea6e97ad6c34f7039a9c6daba732c97d0e098e83ede2b4d52c76eb0184ac7a38";
    private QqPackageTrust() {}

    public static PackageInfo requireTrusted(Context context) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(PACKAGE,
                Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
        Signature[] signatures = Build.VERSION.SDK_INT >= 28
                ? (info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners()) : info.signatures;
        verify(info, signatures);
        return info;
    }

    public static void verify(PackageInfo info, Signature[] signatures) throws Exception {
        if (!PACKAGE.equals(info.packageName) || signatures == null || signatures.length != 1)
            throw new SecurityException("无法验证 QQ 官方签名，请安装官方 QQ 9.3.75");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray());
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        if (!CERTIFICATE_SHA256.equals(hex.toString()))
            throw new SecurityException("QQ 签名不可信，已停止接入；请安装官方 QQ 9.3.75");
        if (!"9.3.75".equals(info.versionName))
            throw new IllegalStateException("当前仅支持官方 QQ 9.3.75，已停止加载其他版本");
    }
}
