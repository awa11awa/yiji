package cn.yiji.app.core

import java.time.Instant
import java.time.ZoneId

/** Shared by paste preview and missing-field supplementation after cloud extraction. */
object RuleDrafts {
    fun extract(text: String, receivedAt: Long, heading: String = "", zone: ZoneId = ZoneId.systemDefault()): List<Draft> {
        val fields = RuleExtractor.extract(text, receivedAt, zone)
        val reference = Instant.ofEpochMilli(receivedAt).atZone(zone).toLocalDate()
        fun time(value: String, at: Long?): TimeField {
            if (value.isBlank()) return TimeField()
            val span = EvidenceMatcher.match(text, value)
            val date = DateTimeResolver.parseDate(value, reference)
            val pending = Regex("待定|另行通知|以现场|后续通知").containsMatchIn(value)
            return TimeField(if (pending && at == null) "TBD" else "SET",
                if (at != null) "EXACT" else if (pending) "TBD" else if (date != null) "DATE" else "NONE",
                date?.toString().orEmpty(), value, "current", span?.start ?: -1, span?.end ?: -1)
        }
        val title = fields.homeworkName.ifBlank { fields.title }.ifBlank { heading }
            .ifBlank { text.lineSequence().firstOrNull(String::isNotBlank).orEmpty() }.ifBlank { "未命名事项" }.take(120)
        val hasTask = fields.deadlineText.isNotBlank() || fields.openText.isNotBlank() || fields.isHomework
        val hasSchedule = fields.timeText.isNotBlank() && !fields.isHomework
        return buildList {
            if (hasSchedule || !hasTask) add(Draft(title = title, kind = "SCHEDULE", startAt = fields.timeAt,
                endAt = fields.endAt, location = fields.location, note = text, issues = fields.notes,
                timing = Timing(start = time(fields.timeText, fields.timeAt),
                    end = if (fields.endAt != null) time(fields.timeText, fields.endAt) else TimeField())))
            if (hasTask) add(Draft(title = (title + if (hasSchedule) " · 截止" else "").take(120),
                category = if (fields.isHomework) Categories.HOMEWORK else if (text.contains("报名")) "报名" else "其他",
                kind = "DEADLINE", dueAt = fields.deadlineAt, opensAt = fields.opensAt, location = fields.location,
                note = text, issues = fields.notes, timing = Timing(due = time(fields.deadlineText, fields.deadlineAt),
                    open = time(fields.openText, fields.opensAt))))
        }
    }

    /** Never changes known fields, date-only/TBD evidence, or an UPDATE/CANCEL operation. */
    fun supplement(draft: Draft, text: String, receivedAt: Long, heading: String = ""): Draft {
        if (Regex("【[^】]+】").findAll(text).take(2).count() > 1) return draft
        val fallback = extract(text, receivedAt, heading).singleOrNull { it.kind == draft.kind } ?: return draft
        fun missing(at: Long?, field: TimeField) = at == null && field.mode in setOf("AUTO", "CLEAR") &&
            field.precision == "NONE" && field.text.isBlank()
        val start = draft.kind == "SCHEDULE" && missing(draft.startAt, draft.timing.start)
        val end = draft.kind == "SCHEDULE" && missing(draft.endAt, draft.timing.end) &&
            (fallback.endAt == null || fallback.endAt > (draft.startAt ?: fallback.startAt ?: Long.MIN_VALUE))
        val due = draft.kind == "DEADLINE" && missing(draft.dueAt, draft.timing.due)
        val open = draft.kind == "DEADLINE" && missing(draft.opensAt, draft.timing.open)
        return draft.copy(startAt = if (start) fallback.startAt else draft.startAt,
            endAt = if (end) fallback.endAt else draft.endAt, dueAt = if (due) fallback.dueAt else draft.dueAt,
            opensAt = if (open) fallback.opensAt else draft.opensAt, location = draft.location.ifBlank { fallback.location },
            timing = Timing(if (start) fallback.timing.start else draft.timing.start,
                if (end) fallback.timing.end else draft.timing.end, if (due) fallback.timing.due else draft.timing.due,
                if (open) fallback.timing.open else draft.timing.open),
            issues = (draft.issues + fallback.issues).distinct())
    }
}
