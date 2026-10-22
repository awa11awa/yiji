package cn.yiji.app.data

import androidx.room.TypeConverter
import cn.yiji.app.core.*
import org.json.JSONArray
import org.json.JSONObject

object StructuredFields {
    fun fieldJson(f: TimeField) = JSONObject().put("mode", f.mode).put("precision", f.precision)
        .put("date", f.date).put("text", f.text).put("sourceId", f.sourceId).put("sourceStart", f.sourceStart).put("sourceEnd", f.sourceEnd)
    fun field(o: JSONObject?) = if (o == null) TimeField() else TimeField(o.optString("mode", "AUTO"),
        o.optString("precision", "NONE"), o.optString("date"), o.optString("text"), o.optString("sourceId"),
        o.optInt("sourceStart", -1), o.optInt("sourceEnd", -1))
    @TypeConverter @JvmStatic fun timingJson(t: Timing): String = JSONObject().put("start", fieldJson(t.start))
        .put("end", fieldJson(t.end)).put("due", fieldJson(t.due)).put("open", fieldJson(t.open)).toString()
    @TypeConverter @JvmStatic fun timing(text: String): Timing = JSONObject(text).let {
        Timing(field(it.optJSONObject("start")), field(it.optJSONObject("end")), field(it.optJSONObject("due")), field(it.optJSONObject("open")))
    }
    @TypeConverter @JvmStatic fun subtasksJson(tasks: List<Subtask>): String = JSONArray(tasks.map { task ->
        JSONObject().put("id", task.id).put("title", task.title).put("optional", task.optional).put("done", task.done)
            .put("dueAt", task.dueAt ?: JSONObject.NULL).put("due", fieldJson(task.due)).put("evidence", task.evidence)
    }).toString()
    @TypeConverter @JvmStatic fun subtasks(text: String): List<Subtask> = JSONArray(text).let { array ->
        require(array.length() <= 40) { "子任务过多" }
        (0 until array.length()).map { index -> array.getJSONObject(index).let {
            Subtask(it.getString("title"), it.getString("id"), it.optBoolean("optional"), it.optBoolean("done"),
                if (it.isNull("dueAt")) null else it.getLong("dueAt"), field(it.optJSONObject("due")), it.optString("evidence"))
        } }
    }
}
