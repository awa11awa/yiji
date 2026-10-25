package dev.yiji.qqlocalprobe;

/** Separate Android Pad identity and tickets from the v0.3.0 phone client. */
public final class ClientProfile {
    public static final String ID = "android-pad";
    public static final String OS = "ANDROID";
    public static final String METADATA_KEY = "AppSetting_params_pad";
    public static final String SESSION_FILE = "qq-session-android-pad.bin";
    public static final String KEY_ALIAS = "qq-probe-session-android-pad-v1";

    private ClientProfile() {}

    public static int subAppId(String parameters) {
        if (parameters == null || parameters.isEmpty())
            throw new IllegalStateException("当前 QQ 未提供平板端参数，无法进行平板登录测试");
        return Integer.parseInt(parameters.split("#", -1)[0]);
    }
}
