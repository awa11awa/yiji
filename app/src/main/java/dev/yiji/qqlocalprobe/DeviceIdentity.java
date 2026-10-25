package dev.yiji.qqlocalprobe;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import java.security.SecureRandom;

final class DeviceIdentity {
    final String guid, androidId, qimei, name;

    DeviceIdentity(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("device_identity", 0);
        guid = value(prefs, "guid", 16);
        String scopedId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        androidId = scopedId == null ? value(prefs, "android_id", 8) : scopedId;
        qimei = value(prefs, "qimei", 18);
        name = "Yiji-" + Build.MODEL;
    }

    private static String value(SharedPreferences prefs, String key, int length) {
        String existing = prefs.getString(key, "");
        if (!existing.isEmpty()) return existing;
        byte[] bytes = new byte[length]; new SecureRandom().nextBytes(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        String value = hex.toString();
        if (!prefs.edit().putString(key, value).commit()) throw new IllegalStateException("设备信息保存失败");
        return value;
    }
}
