package dev.yiji.qqlocalprobe;

import android.content.Context;
import dalvik.system.DexClassLoader;

/** Preserve the class name that JNI initialization may otherwise hide. */
final class DiagnosticClassLoader extends DexClassLoader {
    private final Context context;

    DiagnosticClassLoader(Context context, String dexPath, String libraryPath) {
        super(dexPath, context.getCodeCacheDir().getAbsolutePath(), libraryPath, context.getClassLoader());
        this.context = context;
    }

    @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
        try { return super.findClass(name); }
        catch (ClassNotFoundException | LinkageError error) {
            TraceLog.line(context, "[CLASS-FAIL] " + name + " " + error);
            throw error;
        }
    }
}
