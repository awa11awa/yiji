package cn.yiji.app.data

import android.content.Context
import cn.yiji.app.core.TimeField
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class ReadMarker(val key: String, val revision: String) {
    fun unread(seen: Map<String, String>) = seen[key] != revision
}

/** Read receipts store only identifiers and revision hashes, never notification text. */
class StatusReadStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("yiji-status-reads", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(runCatching {
        val json = JSONObject(prefs.getString("seen", "{}").orEmpty())
        json.keys().asSequence().associateWith { json.getString(it) }
    }.getOrDefault(emptyMap()))
    val state = mutable.asStateFlow()

    @Synchronized fun markRead(markers: List<ReadMarker>) {
        val changes = markers.filter { it.unread(mutable.value) }
        if (changes.isEmpty()) return
        val next = mutable.value + changes.associate { it.key to it.revision }
        prefs.edit().putString("seen", JSONObject(next).toString()).apply()
        mutable.value = next
    }
}

private fun readHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

fun eventBucket(event: Event, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (event.status != "ACTIVE" || event.kind != "DEADLINE") return event.status
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    fun late(at: Long?, time: TimeField): Boolean = if (at != null) at < now else
        time.precision == "DATE" && runCatching { LocalDate.parse(time.date) < today }.getOrDefault(false)
    return if (late(event.dueAt, event.timing.due) || event.subtasks.any { !it.done && late(it.dueAt, it.due) }) "OVERDUE" else "ACTIVE"
}

fun eventReadMarker(event: Event, now: Long): ReadMarker =
    ReadMarker("event:${event.id}:${eventBucket(event, now)}", event.updatedAt.toString())

fun suggestionReadMarker(suggestion: Suggestion): ReadMarker =
    ReadMarker("suggestion:${suggestion.id}:${suggestion.state}", readHash(suggestion.toString()))

fun rawReadMarker(raw: RawMessage, suggestions: List<Suggestion>): ReadMarker =
    ReadMarker("raw:${raw.id}:${raw.state}", readHash(raw.toString() + suggestions.filter { it.rawId == raw.id }.sortedBy { it.id }))
