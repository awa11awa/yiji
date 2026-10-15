package cn.yiji.app.data

import cn.yiji.app.core.Draft
import cn.yiji.app.core.MatchSuggestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.time.ZoneId

data class Extraction(val draft: Draft, val engine: String, val association: MatchSuggestion? = null)

class AiClient(private val preferences: Preferences) {
    suspend fun extract(raw: RawMessage, existing: List<Event>): Extraction = withContext(Dispatchers.IO) {
        preferences.serverUrlError(preferences.serverUrl)?.let { error(it) }
        val connection = URI(preferences.serverUrl + "/v1/extract").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8000; connection.readTimeout = 15000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (preferences.serverToken.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer ${preferences.serverToken}")
            val request = JSONObject().put("title", raw.title).put("text", raw.body)
                .put("received_at", Instant.ofEpochMilli(raw.receivedAt).toString()).put("timezone", ZoneId.systemDefault().id)
            val candidates = existing.filter { it.status == "ACTIVE" }.take(50)
            request.put("existing_items", JSONArray(candidates.map { e ->
                fun stamp(t: Long?): Any = t?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL
                JSONObject().put("id", e.id).put("title", e.title).put("category", e.category).put("kind", e.kind)
                    .put("start_at", stamp(e.startAt)).put("end_at", stamp(e.endAt)).put("due_at", stamp(e.dueAt))
                    .put("location", e.location).put("action", e.action)
            }))
            connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
            require(connection.responseCode == 200) { "AI 服务暂不可用（${connection.responseCode}）" }
            val bytes = connection.inputStream.use { it.readLimited(128 * 1024) }
            require(bytes.size <= 128 * 1024) { "AI 返回内容过大" }
            val response = JSONObject(bytes.toString(Charsets.UTF_8))
            val o = response.getJSONObject("item")
            fun time(key: String): Long? = if (o.isNull(key)) null else Instant.parse(o.getString(key)).toEpochMilli()
            val kind = o.getString("kind")
            require(kind in setOf("SCHEDULE", "DEADLINE")) { "AI 返回的事项类型无效" }
            val issues = o.optJSONArray("issues")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            val name = o.getString("title").trim()
            require(name.isNotBlank() && name.length <= 120) { "AI 返回的标题无效" }
            val model = response.optString("engine") == "model"
            val association = response.optJSONObject("association")?.let { a ->
                val type = a.optString("type")
                val target = a.optString("target_id").takeIf { id -> candidates.any { it.id == id && it.kind == kind } }
                val score = a.optDouble("confidence", 0.0)
                if (model && score >= 0.65 && score <= 1 && type in setOf("NEW", "MERGE", "UPDATE", "CANCEL") && (type == "NEW" || target != null))
                    MatchSuggestion(type, if (type == "NEW") null else target, "模型建议：${a.optString("reason").take(500)}", score)
                else null
            }
            Extraction(Draft(name, o.optString("category", "其他"), kind, time("start_at"), time("end_at"), time("due_at"),
                o.optString("location", "").take(200), o.optString("action", "").take(2000), raw.body, issues),
                if (model) "AI 增强" else "服务端规则", association)
        } finally { connection.disconnect() }
    }
}
