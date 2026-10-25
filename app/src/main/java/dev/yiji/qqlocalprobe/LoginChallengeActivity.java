package dev.yiji.qqlocalprobe;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.webkit.JsPromptResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;

public final class LoginChallengeActivity extends Activity {
    private WebView web;
    private String id, kind;
    private boolean submitted;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            JSONObject challenge = LoginInteraction.read(this);
            id = challenge.optString("id"); kind = challenge.optString("kind");
            if (id.isEmpty() || kind.isEmpty()) { finish(); return; }
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL); root.setPadding(20, 48, 20, 24);
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                v.setPadding(20, 20 + insets.getSystemWindowInsetTop(), 20, 20 + insets.getSystemWindowInsetBottom()); return insets;
            });
            TextView description = new TextView(this); description.setTextSize(16);
            description.setText(challenge.optString("description")); root.addView(description);
            String url = challenge.optString("url");
            if (!url.isEmpty() && allowed(url)) {
                web = new WebView(this); web.getSettings().setJavaScriptEnabled(true); web.getSettings().setDomStorageEnabled(true);
                web.getSettings().setAllowFileAccess(false); web.getSettings().setAllowContentAccess(false);
                web.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                        String scheme = request.getUrl().getScheme();
                        if (!request.isForMainFrame() && !"qqprobe".equals(scheme) && !"jsbridge".equals(scheme)) return false;
                        return navigate(request.getUrl());
                    }
                    @Override public boolean shouldOverrideUrlLoading(WebView view, String target) { return navigate(Uri.parse(target)); }
                    @Override public void onPageFinished(WebView view, String target) {
                        if ("captcha".equals(kind) && allowed(target)) {
                            view.evaluateJavascript("(function(){var m=window.mqq=window.mqq||{};var old=m.invoke;m.invoke=function(ns,fn,p){if(fn==='onVerifyCAPTCHA'){location.href='qqprobe://captcha?result='+encodeURIComponent(JSON.stringify(p));return;}if(old)return old.apply(this,arguments);};window.onVerifyCAPTCHA=function(p){location.href='qqprobe://captcha?result='+encodeURIComponent(JSON.stringify(p));};})()", null);
                        }
                    }
                });
                web.setWebChromeClient(new WebChromeClient() {
                    @Override public boolean onJsPrompt(WebView view, String target, String message, String defaultValue, JsPromptResult result) {
                        if (allowed(target) && extractTicket(message)) { result.confirm(""); return true; }
                        return super.onJsPrompt(view, target, message, defaultValue, result);
                    }
                });
                root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1)); web.loadUrl(url);
            }
            EditText input = new EditText(this); input.setSaveEnabled(false);
            if ("device".equals(kind)) input.setVisibility(android.view.View.GONE);
            input.setHint("sms".equals(kind) ? "短信验证码" : "自动回传未完成时，粘贴 ticket 或验证码返回 JSON");
            input.setInputType("sms".equals(kind) ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            root.addView(input);
            Button send = new Button(this); send.setText("提交验证"); send.setOnClickListener(v -> {
                if ("device".equals(kind)) { submit(""); return; }
                String value = input.getText().toString().trim();
                if (value.isEmpty()) { Toast.makeText(this, "请先输入验证结果", Toast.LENGTH_SHORT).show(); return; }
                if ("captcha".equals(kind) && value.startsWith("{")) {
                    if (!extractTicket(value)) Toast.makeText(this, "JSON 中未找到 ticket", Toast.LENGTH_SHORT).show();
                    return;
                }
                submit(value);
            });
            if ("device".equals(kind)) send.setText("已完成设备验证，继续登录");
            root.addView(send);
            if ("sms".equals(kind) && !url.isEmpty()) {
                Button manual = new Button(this); manual.setText("已完成网页验证，继续登录"); manual.setOnClickListener(v -> submit("")); root.addView(manual);
            }
            Button cancel = new Button(this); cancel.setText("取消本次验证"); cancel.setOnClickListener(v -> submit("\u0000CANCEL")); root.addView(cancel);
            setContentView(root);
        } catch (Exception error) { Toast.makeText(this, "验证页面打开失败：" + error.getClass().getSimpleName(), Toast.LENGTH_LONG).show(); finish(); }
    }

    private boolean navigate(Uri uri) {
        if ("qqprobe".equals(uri.getScheme()) || "jsbridge".equals(uri.getScheme())) {
            String data = uri.getQueryParameter("result");
            if (data == null) data = uri.getQueryParameter("p");
            if (data == null) data = uri.getQueryParameter("data");
            if (data != null) extractTicket(data);
            return true;
        }
        return !allowed(uri.toString());
    }

    private boolean extractTicket(String json) {
        try {
            JSONObject data = new JSONObject(json);
            if (data.optJSONObject("data") != null) data = data.getJSONObject("data");
            String ticket = data.optString("ticket");
            if (!ticket.isEmpty()) { submit(ticket); return true; }
        } catch (Exception ignored) { }
        return false;
    }

    private static boolean allowed(String url) {
        Uri uri = Uri.parse(url); String host = uri.getHost();
        return "https".equals(uri.getScheme()) && host != null && (host.equals("qq.com") || host.endsWith(".qq.com")
                || host.equals("tencent.com") || host.endsWith(".tencent.com"));
    }

    private void submit(String result) {
        if (submitted) return;
        submitted = true;
        startService(new Intent(this, cn.yiji.app.qq.QqCaptureService.class).setAction(cn.yiji.app.qq.QqCaptureService.ACTION_REPLY).putExtra("id", id).putExtra("result", result));
        finish();
    }
    @Override protected void onDestroy() { if (web != null) { web.stopLoading(); web.destroy(); } super.onDestroy(); }
}
