package cn.yiji.app.data

import android.content.Context
import cn.yiji.app.BuildConfig
import java.net.URI

class Preferences(context: Context) {
    private val prefs = context.getSharedPreferences("yiji-settings", Context.MODE_PRIVATE)
    var collecting: Boolean
        get() = prefs.getBoolean("collecting", false)
        set(value) { prefs.edit().putBoolean("collecting", value).apply() }
    var packages: Set<String>
        get() = prefs.getStringSet("packages", emptySet())!!.toSet()
        set(value) { prefs.edit().putStringSet("packages", value.toSet()).apply() }
    var ignoredWords: String
        get() = prefs.getString("ignoredWords", "验证码\n广告\n优惠券")!!
        set(value) { prefs.edit().putString("ignoredWords", value).apply() }
    var defaultReminder: Int
        get() = prefs.getInt("defaultReminder", 30)
        set(value) { prefs.edit().putInt("defaultReminder", value).apply() }
    var aiEnabled: Boolean
        get() = prefs.getBoolean("aiEnabled", false)
        set(value) { prefs.edit().putBoolean("aiEnabled", value).apply() }
    var serverUrl: String
        get() = prefs.getString("serverUrl", "http://10.0.2.2:8000")!!
        set(value) { prefs.edit().putString("serverUrl", value.trim().trimEnd('/')).apply() }
    var serverToken: String
        get() = prefs.getString("serverToken", "")!!
        set(value) { prefs.edit().putString("serverToken", value.trim()).apply() }
    fun accepts(packageName: String, text: String): Boolean = collecting && packageName in packages &&
        ignoredWords.lines().filter { it.isNotBlank() }.none { text.contains(it.trim(), ignoreCase = true) }
    fun serverUrlError(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return "服务地址格式不正确"
        if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) return "请输入完整服务地址，例如 https://example.com"
        if (uri.scheme != "https" && !(BuildConfig.DEBUG && uri.scheme == "http")) return "正式版本需要 HTTPS 地址"
        return null
    }
}
