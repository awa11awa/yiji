package cn.yiji.app.data

import android.content.Context

class Preferences(context: Context) {
    internal val context = context.applicationContext
    private val prefs = context.getSharedPreferences("yiji-settings", Context.MODE_PRIVATE)
    init {
        // Retire stored v1 cloud credentials and keyword configuration on upgrade.
        if (!prefs.getBoolean("offlineV2", false)) prefs.edit()
            .remove("aiEnabled").remove("serverUrl").remove("serverToken").remove("ignoredWords")
            .putBoolean("offlineV2", true).apply()
    }
    var collecting: Boolean
        get() = prefs.getBoolean("collecting", false)
        set(value) { prefs.edit().putBoolean("collecting", value).apply() }
    var protectCollection: Boolean
        get() = prefs.getBoolean("protectCollection", true)
        set(value) { prefs.edit().putBoolean("protectCollection", value).apply() }
    var packages: Set<String>
        get() = prefs.getStringSet("packages", emptySet())!!.toSet()
        set(value) { prefs.edit().putStringSet("packages", value.toSet()).apply() }
    var defaultReminder: Int
        get() = prefs.getInt("defaultReminder", 30)
        set(value) { prefs.edit().putInt("defaultReminder", value).apply() }
    /** Zero keeps ignored records until the user deletes them. */
    var ignoredRetentionDays: Int
        get() = prefs.getInt("ignoredRetentionDays", 0)
        set(value) { require(value in 0..3650); prefs.edit().putInt("ignoredRetentionDays", value).apply() }
    fun accepts(packageName: String): Boolean = collecting && packageName in packages
}
