package dev.yiji.qqlocalprobe;

import android.content.Context;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Configure the SDK's public host hooks and real MMKV in this app's sandbox. */
final class HostEnvironment {
    private final Context context;
    private final ClassLoader loader;
    private final Map<String, Object> stores = new HashMap<>();
    private final Method openStore;
    private final Constructor<?> closeableStore;

    HostEnvironment(Context context, ClassLoader loader) throws Exception {
        this.context = context.getApplicationContext();
        this.loader = loader;
        Class<?> mmkv = type("com.tencent.mmkv.MMKV");
        openStore = mmkv.getMethod("W", String.class, int.class);
        closeableStore = type("com.tencent.mobileqq.qmmkv.v2.CloseableMMKV").getConstructor(String.class, mmkv);
    }

    void prepare() throws Throwable {
        Class<?> fe = type("com.tencent.mobileqq.fe.FEKit");
        Object instance = call(fe.getMethod("getInstance"), null);
        call(fe.getMethod("setContext", Context.class), instance, context);
        if (call(fe.getMethod("getContext"), instance) != context)
            throw new IllegalStateException("FEKit Context 未绑定到本应用");
        Class<?> dtc = type("com.tencent.mobileqq.dt.app.Dtc");
        call(dtc.getMethod("setContext", Context.class), null, context);
        call(dtc.getMethod("setBaseDir", String.class), null, context.getFilesDir().getParent());
        if (call(dtc.getMethod("getContext"), null) != context)
            throw new IllegalStateException("Dtc Context 未绑定到本应用");
        log("[HOST] FEKit 与 Dtc 已绑定本应用 Context，文件位于本应用私有目录");

        Class<?> cacheType = type("com.tencent.mobileqq.qmmkv.v2.k");
        Object cache = Proxy.newProxyInstance(loader, new Class<?>[]{cacheType}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
            if (method.getName().equals("c")) {
                String id = fileKey((String) args[0], (Boolean) args[2]);
                return open(id, (Integer) args[1]);
            }
            if (method.getName().equals("b")) {
                String id = (String) args[0], key = (String) args[1];
                if (!id.equals(key)) throw new UnsupportedOperationException("测试存储未实现账号分片");
                return open(id, (Integer) args[2]);
            }
            if (method.getName().equals("d")) return fileKey((String) args[0], (Boolean) args[1]);
            throw new UnsupportedOperationException("未调用过的存储接口：" + method);
        });
        Class<?> handlerType = type("com.tencent.mobileqq.qmmkv.MMKVHandlerBase");
        Object warning = type("com.tencent.mmkv.MMKVLogLevel").getField("LevelWarning").get(null);
        Object discard = type("com.tencent.mmkv.MMKVRecoverStrategic").getField("OnErrorDiscard").get(null);
        Object handler = Proxy.newProxyInstance(loader, new Class<?>[]{handlerType}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
            switch (method.getName()) {
                case "getLogLevel": return warning;
                case "wantLogRedirecting": return false;
                case "provideMemoryCache": return cache;
                case "isFileAvailable": return allowed((String) args[0]);
                case "onMMKVCRCCheckFail":
                case "onMMKVFileLengthError": return discard;
                default:
                    if (method.getReturnType() == void.class) return null;
                    throw new UnsupportedOperationException("未实现的存储回调：" + method);
            }
        });
        Class<?> qmmkv = type("com.tencent.mobileqq.qmmkv.QMMKV");
        call(qmmkv.getMethod("initializeInternal", Context.class, handlerType), null, context, handler);

        Method from = qmmkv.getMethod("from", Context.class, String.class);
        Object probe = null;
        for (String id : new String[]{"qq_local_probe_storage", "common_mmkv_configurations", "qsec_mmkv_configurations"}) {
            Object store = call(from, null, context, id);
            if (!Boolean.TRUE.equals(call(store.getClass().getMethod("isAccessible"), store)))
                throw new IllegalStateException("QMMKV 不可访问：" + id);
            if (id.equals("qq_local_probe_storage")) probe = store;
        }
        String value = UUID.randomUUID().toString();
        call(probe.getClass().getMethod("encodeString", String.class, String.class), probe, "roundtrip", value);
        Object read = call(probe.getClass().getMethod("decodeString", String.class, String.class), probe, "roundtrip", "");
        if (!value.equals(read)) throw new IllegalStateException("QMMKV 实际写入/读回不一致");
        log("[HOST-STORAGE] QMMKV 初始化、文件可访问及真实 MMKV 写入/读回通过");
    }

    private synchronized Object open(String id, int mode) throws Throwable {
        if (!allowed(id)) throw new IllegalArgumentException("未登记的测试文件：" + id);
        String key = id + ":" + mode;
        Object value = stores.get(key);
        if (value == null) {
            Object nativeStore = call(openStore, null, id, mode);
            if (nativeStore == null) throw new IllegalStateException("MMKV 未创建文件：" + id);
            try { value = closeableStore.newInstance(id, nativeStore); }
            catch (InvocationTargetException error) { throw error.getCause(); }
            stores.put(key, value);
        }
        return value;
    }

    private static boolean allowed(String id) {
        return "common_mmkv_configurations".equals(id) || "qsec_mmkv_configurations".equals(id)
                || "qq_local_probe_storage".equals(id);
    }

    private static String fileKey(String id, boolean withAccount) {
        if (withAccount) throw new UnsupportedOperationException("测试未登录，未实现账号分片存储");
        return id;
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        if (method.getName().equals("toString")) return "QQ Local Probe host storage";
        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
        return proxy == args[0];
    }

    private Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name, false, loader); }
    private void log(String message) { TraceLog.line(context, message); }

    private static Object call(Method method, Object owner, Object... args) throws Throwable {
        try { return method.invoke(owner, args); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }
}
