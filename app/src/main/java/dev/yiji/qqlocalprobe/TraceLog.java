package dev.yiji.qqlocalprobe;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class TraceLog {
    static File file(Context context) {
        return new File(context.getFilesDir(), "probe.log");
    }

    static void reset(Context context) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file(context))) {
            out.flush();
        }
    }

    static synchronized void line(Context context, String text) {
        String time = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date());
        byte[] bytes = (time + " " + text + "\n").getBytes(StandardCharsets.UTF_8);
        File current = file(context);
        if (current.length() > 256 * 1024) {
            File previous = new File(context.getFilesDir(), "probe-previous.log");
            previous.delete(); current.renameTo(previous);
        }
        try (FileOutputStream out = new FileOutputStream(current, true)) {
            out.write(bytes);
        } catch (IOException error) {
            Log.e("QQ_LOCAL_PROBE", "日志写入失败", error);
        }
        Log.i("QQ_LOCAL_PROBE", text);
    }

    static String read(Context context) throws IOException {
        File log = file(context);
        return log.exists() ? new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8) : "";
    }
}
