package dev.yiji.qqlocalprobe;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** A challenge belongs to this process and run; results never go to disk. */
public final class LoginInteraction {
    private final Context context;
    private final ArrayBlockingQueue<String> replies = new ArrayBlockingQueue<>(1);
    private volatile String challengeId;
    private java.util.function.Consumer<String[]> observer;
    public void observe(java.util.function.Consumer<String[]> observer) { this.observer = observer; }

    public LoginInteraction(Context context) { this.context = context.getApplicationContext(); }

    public String challenge(String kind, String url, String description) throws Exception {
        replies.clear();
        challengeId = UUID.randomUUID().toString();
        state("等待验证", kind, url, description, challengeId);
        TraceLog.line(context, "[LOGIN-CHALLENGE] kind=" + kind + " 请在应用内完成验证");
        try {
            String result = replies.poll(5, TimeUnit.MINUTES);
            if (result == null) throw new IllegalStateException("验证等待超时，请重新登录");
            if ("\u0000CANCEL".equals(result)) throw new InterruptedException("验证已取消");
            return result;
        } finally { challengeId = null; update("验证已提交", "等待服务器响应"); }
    }

    public boolean submit(String id, String result) {
        return id != null && id.equals(challengeId) && replies.offer(result);
    }

    public void cancel() { replies.offer("\u0000CANCEL"); }

    public void update(String status, String detail) { state(status, "", "", detail, ""); }

    private void state(String status, String kind, String url, String detail, String id) {
        AtomicFile file = new AtomicFile(stateFile(context));
        FileOutputStream out = null;
        try {
            JSONObject json = new JSONObject().put("status", status).put("kind", kind).put("url", url)
                    .put("description", detail).put("id", id);
            out = file.startWrite(); out.write(json.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(out);
            if (observer != null) observer.accept(new String[]{status, kind, url, detail, id});
        } catch (Exception error) {
            if (out != null) file.failWrite(out);
            TraceLog.line(context, "[LOGIN-STATE-FAIL] " + error.getClass().getSimpleName());
        }
    }

    public static File stateFile(Context context) { return new File(context.getFilesDir(), "login-state.json"); }
    public static JSONObject read(Context context) throws Exception {
        File file = stateFile(context);
        return file.exists() ? new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)) : new JSONObject();
    }
}
