package cn.yiji.app.platform

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class NotificationRecord(val at: Long, val source: String, val result: String)
data class NotificationStatus(val connected: Boolean = false, val records: List<NotificationRecord> = emptyList())

/** Connection state is live only; bounded diagnostics contain no notification text. */
class NotificationMonitor(context: Context) {
    private val prefs = context.getSharedPreferences("yiji-notification-status", Context.MODE_PRIVATE)
    private val mutableStatus = MutableStateFlow(NotificationStatus(records = readRecords()))
    val status = mutableStatus.asStateFlow()

    @Synchronized fun connection(connected: Boolean) {
        mutableStatus.value = mutableStatus.value.copy(connected = connected)
    }

    @Synchronized fun record(source: String, result: String) {
        val records = (listOf(NotificationRecord(System.currentTimeMillis(), source.take(100), result.take(300))) +
            mutableStatus.value.records).take(20)
        mutableStatus.value = mutableStatus.value.copy(records = records)
        val json = JSONArray()
        records.forEach { json.put(JSONObject().put("at", it.at).put("source", it.source).put("result", it.result)) }
        prefs.edit().putString("records", json.toString()).apply()
    }

    @Synchronized fun clear() {
        mutableStatus.value = mutableStatus.value.copy(records = emptyList())
        prefs.edit().remove("records").apply()
    }

    private fun readRecords(): List<NotificationRecord> = runCatching {
        val json = JSONArray(prefs.getString("records", "[]"))
        (0 until minOf(json.length(), 20)).map { index ->
            val item = json.getJSONObject(index)
            NotificationRecord(item.getLong("at"), item.getString("source"), item.getString("result"))
        }
    }.getOrDefault(emptyList())
}
