package cn.yiji.app.core

import java.time.LocalDate
import java.util.UUID

/** A missing update is KEEP; an explicitly unknown new time is TBD. */
data class TimeField(
    val mode: String = "AUTO",
    val precision: String = "NONE",
    val date: String = "",
    val text: String = "",
    val sourceId: String = "",
    val sourceStart: Int = -1,
    val sourceEnd: Int = -1,
) {
    fun valid(): Boolean = mode in setOf("AUTO", "KEEP", "SET", "TBD", "CLEAR") &&
        precision in setOf("NONE", "EXACT", "DATE", "TBD") &&
        (date.isBlank() || runCatching { LocalDate.parse(date) }.isSuccess)
    companion object {
        fun exact() = TimeField("SET", "EXACT")
        fun cleared() = TimeField("CLEAR")
        fun pending() = TimeField("TBD", "TBD")
    }
}

data class Timing(
    val start: TimeField = TimeField(),
    val end: TimeField = TimeField(),
    val due: TimeField = TimeField(),
    val open: TimeField = TimeField(),
) {
    fun fields() = listOf(start, end, due, open)
}

data class Subtask(
    val title: String,
    val id: String = UUID.randomUUID().toString(),
    val optional: Boolean = false,
    val done: Boolean = false,
    val dueAt: Long? = null,
    val due: TimeField = TimeField(),
    val evidence: String = "",
)

data class ReminderPoint(val role: String, val at: Long, val message: String)

fun Draft.reminderPoints(): List<ReminderPoint> = buildList {
    if (kind == "SCHEDULE") startAt?.let { add(ReminderPoint("start", it, "即将开始")) }
    opensAt?.let { add(ReminderPoint("open", it, "报名即将开放")) }
    if (kind == "DEADLINE") dueAt?.let { add(ReminderPoint("due", it, if (category == "报名") "报名即将截止" else "即将截止")) }
    subtasks.filter { !it.done }.forEach { task ->
        task.dueAt?.let { add(ReminderPoint("task-${task.id}", it, "${if (task.optional) "选做 · " else ""}${task.title} 即将截止")) }
    }
}

/** Used both when choosing a target in the editor and inside the commit transaction. */
fun Draft.resolvedAgainst(previous: Draft?): Draft {
    if (previous == null) return this
    fun value(own: Long?, old: Long?, field: TimeField) = when (field.mode) {
        "KEEP" -> old
        "AUTO" -> own ?: old
        "TBD", "CLEAR" -> null
        else -> own
    }
    fun field(own: TimeField, old: TimeField) = if (own.mode == "KEEP") old.copy(mode = "KEEP") else own
    return copy(
        startAt = value(startAt, previous.startAt, timing.start),
        endAt = value(endAt, previous.endAt, timing.end),
        dueAt = value(dueAt, previous.dueAt, timing.due),
        opensAt = value(opensAt, previous.opensAt, timing.open),
        timing = Timing(field(timing.start, previous.timing.start), field(timing.end, previous.timing.end),
            field(timing.due, previous.timing.due), field(timing.open, previous.timing.open)),
        location = location.ifBlank { previous.location }, action = action.ifBlank { previous.action },
        category = if (category == "其他") previous.category else category,
        subtasks = if (keepSubtasks) previous.subtasks else subtasks.map { task ->
            val old = previous.subtasks.singleOrNull { it.title == task.title }
            if (old == null) task else task.copy(id = old.id, done = old.done,
                dueAt = if (task.due.mode == "KEEP") old.dueAt else task.dueAt,
                due = if (task.due.mode == "KEEP") old.due else task.due)
        },
    )
}
