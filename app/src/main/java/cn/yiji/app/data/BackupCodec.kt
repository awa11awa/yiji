package cn.yiji.app.data

import org.json.JSONArray
import org.json.JSONObject

data class Backup(val events: List<Event>, val raw: List<RawMessage>, val suggestions: List<Suggestion>, val links: List<SourceLink>, val history: List<History>,
    val ignored: List<IgnoredItem> = emptyList())

object BackupCodec {
    private fun JSONObject.nullableLong(key: String): Long? = if (isNull(key)) null else getLong(key)
    private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)
    fun eventJson(e: Event): JSONObject = JSONObject().apply {
        put("id", e.id); put("title", e.title); put("category", e.category); put("kind", e.kind)
        put("startAt", e.startAt ?: JSONObject.NULL); put("endAt", e.endAt ?: JSONObject.NULL); put("dueAt", e.dueAt ?: JSONObject.NULL)
        put("location", e.location); put("action", e.action); put("note", e.note); put("status", e.status)
        put("opensAt", e.opensAt ?: JSONObject.NULL); put("timing", JSONObject(StructuredFields.timingJson(e.timing))); put("subtasks", JSONArray(StructuredFields.subtasksJson(e.subtasks)))
        put("reminderMinutes", e.reminderMinutes); put("createdAt", e.createdAt); put("updatedAt", e.updatedAt)
    }
    private fun event(o: JSONObject) = Event(o.getString("id"), o.getString("title"), o.getString("category"), o.getString("kind"),
        o.nullableLong("startAt"), o.nullableLong("endAt"), o.nullableLong("dueAt"), o.getString("location"), o.getString("action"),
        o.getString("note"), o.getString("status"), o.getInt("reminderMinutes"), o.getLong("createdAt"), o.getLong("updatedAt"), o.nullableLong("opensAt"),
        StructuredFields.timing(o.optJSONObject("timing")?.toString() ?: "{}"), StructuredFields.subtasks(o.optJSONArray("subtasks")?.toString() ?: "[]"))
    fun rawJson(r: RawMessage): JSONObject = JSONObject().apply {
        put("id", r.id); put("title", r.title); put("body", r.body); put("source", r.source); put("packageName", r.packageName)
        put("receivedAt", r.receivedAt); put("state", r.state); put("fingerprint", r.fingerprint); put("info", r.info); if(r.qqMetadata.isNotEmpty()) put("qqMetadata",r.qqMetadata)
    }
    fun encode(b: Backup): String = JSONObject().apply {
        put("format", "cn.yiji.backup"); put("version", 3); put("exportedAt", System.currentTimeMillis())
        put("events", JSONArray(b.events.map(::eventJson)))
        put("raw", JSONArray(b.raw.map(::rawJson)))
        put("suggestions", JSONArray(b.suggestions.map { s -> JSONObject().apply {
            put("id", s.id); put("rawId", s.rawId); put("type", s.type); put("targetId", s.targetId ?: JSONObject.NULL)
            put("title", s.title); put("category", s.category); put("kind", s.kind)
            put("startAt", s.startAt ?: JSONObject.NULL); put("endAt", s.endAt ?: JSONObject.NULL); put("dueAt", s.dueAt ?: JSONObject.NULL)
            put("location", s.location); put("action", s.action); put("note", s.note); put("issues", s.issues)
            put("keepSubtasks", s.keepSubtasks); put("opensAt", s.opensAt ?: JSONObject.NULL); put("timing", JSONObject(StructuredFields.timingJson(s.timing))); put("subtasks", JSONArray(StructuredFields.subtasksJson(s.subtasks)))
            put("reason", s.reason); put("state", s.state); put("engine", s.engine)
        } }))
        put("links", JSONArray(b.links.map { JSONObject().put("eventId", it.eventId).put("rawId", it.rawId) }))
        put("history", JSONArray(b.history.map { JSONObject().put("id", it.id).put("eventId", it.eventId)
            .put("changedAt", it.changedAt).put("action", it.action).put("before", it.before).put("after", it.after) }))
        put("ignored", JSONArray(b.ignored.map { item -> JSONObject().put("id", item.id).put("traceId", item.traceId)
            .put("kind", item.kind).put("title", item.title).put("source", item.source).put("preview", item.preview)
            .put("payload", item.payload).put("fingerprint", item.fingerprint).put("ignoredAt", item.ignoredAt) }))
    }.toString(2).also { text ->
        require(text.toByteArray().size <= 10 * 1024 * 1024) { "备份超过 10 MB，请先清理不再需要的原始通知" }
        require(listOf(b.events.size, b.raw.size, b.suggestions.size, b.links.size, b.history.size, b.ignored.size).all { it <= 20_000 }) { "备份记录数量超过限制" }
    }

    private fun <T> rows(root: JSONObject, key: String, parse: (JSONObject) -> T): List<T> {
        val array = root.getJSONArray(key)
        require(array.length() <= 20_000) { "备份记录数量过多" }
        return (0 until array.length()).map { parse(array.getJSONObject(it)) }
    }
    fun decode(text: String): Backup {
        require(text.toByteArray().size <= 10 * 1024 * 1024) { "备份最大为 10 MB" }
        val root = JSONObject(text)
        require(root.getString("format") == "cn.yiji.backup" && root.getInt("version") in 1..3) { "不是支持的忆迹备份" }
        val events = rows(root, "events", ::event)
        val raw = rows(root, "raw") { o -> RawMessage(o.getString("id"), o.getString("title"), o.getString("body"), o.getString("source"),
            o.getString("packageName"), o.getLong("receivedAt"), o.getString("state"), o.getString("fingerprint"), o.getString("info"),o.optString("qqMetadata")) }
        val suggestions = rows(root, "suggestions") { o -> Suggestion(o.getString("id"), o.getString("rawId"), o.getString("type"), o.nullableString("targetId"),
            o.getString("title"), o.getString("category"), o.getString("kind"), o.nullableLong("startAt"), o.nullableLong("endAt"), o.nullableLong("dueAt"),
            o.getString("location"), o.getString("action"), o.getString("note"), o.getString("issues"), o.getString("reason"), o.getString("state"), o.getString("engine"), o.nullableLong("opensAt"),
            StructuredFields.timing(o.optJSONObject("timing")?.toString() ?: "{}"), StructuredFields.subtasks(o.optJSONArray("subtasks")?.toString() ?: "[]"), o.optBoolean("keepSubtasks")) }
        val links = rows(root, "links") { SourceLink(it.getString("eventId"), it.getString("rawId")) }
        val history = rows(root, "history") { History(it.getString("id"), it.getString("eventId"), it.getLong("changedAt"),
            it.getString("action"), it.getString("before"), it.getString("after")) }
        val ignored = if (!root.has("ignored")) emptyList() else rows(root, "ignored") { o ->
            IgnoredItem(o.getString("id"), o.getString("traceId"), o.getString("kind"), o.getString("title"),
                o.getString("source"), o.getString("preview"), o.getString("payload"), o.getString("fingerprint"), o.getLong("ignoredAt"))
        }
        val eventIds = events.map { it.id }.toSet(); val rawIds = raw.map { it.id }.toSet()
        require(eventIds.size == events.size && rawIds.size == raw.size && suggestions.map { it.id }.distinct().size == suggestions.size &&
            history.map { it.id }.distinct().size == history.size && links.distinct().size == links.size) { "备份包含重复记录" }
        require((eventIds + rawIds).all { runCatching { java.util.UUID.fromString(it) }.isSuccess }) { "备份标识无效" }
        require(events.all { it.draft().validationError() == null && it.status in setOf("ACTIVE", "DONE", "CANCELLED") && it.reminderMinutes in -1..10080 }) { "备份中的事项字段无效" }
        require(raw.all { (it.packageName.startsWith("qq-protocol:") || it.body.length <= 16000) && it.title.length <= 200 && it.state in setOf("PENDING", "CONFIRMED", "IGNORED") }) { "备份中的通知字段无效" }
        require(suggestions.all { it.rawId in rawIds && it.type in setOf("NEW", "MERGE", "UPDATE", "CANCEL") && it.state in setOf("PENDING", "CONFIRMED", "DISMISSED") }) { "备份中的建议无效" }
        require(links.all { it.eventId in eventIds && it.rawId in rawIds } && history.all { it.eventId in eventIds }) { "备份来源或历史关联无效" }
        require(ignored.map { it.id }.distinct().size == ignored.size && ignored.all {
            it.kind in setOf("EVENT", "RAW", "SUGGESTION") && it.payload.isNotBlank() &&
                runCatching { java.util.UUID.fromString(it.id); java.util.UUID.fromString(it.traceId) }.isSuccess
        }) { "备份中的忽略记录无效" }
        return Backup(events, raw, suggestions, links, history, ignored)
    }
}
