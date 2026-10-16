package cn.yiji.app.ml

import cn.yiji.app.core.DateTimeResolver
import cn.yiji.app.core.Draft
import java.time.Instant
import java.time.ZoneId

/** Converts model spans into editable drafts. No text-based relevance/category rules. */
object DraftAssembler {
    fun assemble(prediction: ModelPrediction, text: String, heading: String, receivedAt: Long,
        zone: ZoneId = ZoneId.systemDefault()): List<Draft> {
        val entities = prediction.entities.sortedBy { it.start }
        val starts = entities.filter { it.label == "START_TIME" }
        val ends = entities.filter { it.label == "END_TIME" }
        val deadlines = entities.filter { it.label == "DUE_TIME" }
        val events = entities.filter { it.label == "EVENT" }
        val places = entities.filter { it.label == "LOCATION" }
        val reference = Instant.ofEpochMilli(receivedAt).atZone(zone).toLocalDate()
        val category = prediction.category.takeUnless { it == "IGNORE" } ?: "其他"
        val sharedIssues = mutableListOf<String>()
        if (prediction.confidence < .70 || prediction.category == "IGNORE") sharedIssues += "模型分类把握较低，请核对类别与原文"
        fun event(position: Int) = events.lastOrNull { it.start <= position } ?: events.firstOrNull()
        fun name(position: Int) = (event(position)?.text ?: heading.ifBlank { text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty() }).trim().take(120).ifBlank { "待核对事项" }
        fun location(position: Int) = places.minByOrNull { kotlin.math.abs(it.start - position) }?.text.orEmpty()
        val drafts = mutableListOf<Draft>()
        starts.forEachIndexed { index, start ->
            val issues = sharedIssues.toMutableList()
            val nextStart = starts.getOrNull(index + 1)?.start ?: Int.MAX_VALUE
            val finish = ends.firstOrNull { it.start >= start.end && it.start < nextStart }
            val startAt = DateTimeResolver.resolve(start.text, reference, issues, zone)
            val startDate = startAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            val endAt = finish?.let { DateTimeResolver.resolve(it.text, startDate ?: reference, issues, zone, inheritDate = startDate != null, precedingTime = start.text) }
            if (startAt == null) issues += "开始日期或时间不完整，请补充"
            if (endAt == null) issues += "缺少结束日期或时间，请补充"
            if (startAt != null && endAt != null && endAt <= startAt) issues += "结束时间不晚于开始时间，请核对"
            drafts += Draft(name(start.start), category, "SCHEDULE", startAt, endAt, location = location(start.start), note = text, issues = issues.distinct())
        }
        deadlines.forEach { deadline ->
            val issues = sharedIssues.toMutableList()
            val dueAt = DateTimeResolver.resolve(deadline.text, reference, issues, zone)
            if (dueAt == null) issues += "截止日期或时间不完整，请补充"
            drafts += Draft(name(deadline.start).take(114) + " · 截止", category, "DEADLINE", dueAt = dueAt,
                note = text, issues = issues.distinct())
        }
        val pendingSchedule = prediction.state in setOf("TO_BE_ANNOUNCED", "BOOKING_REQUIRED") && starts.isEmpty() && prediction.kind != "DEADLINE"
        if (drafts.isEmpty() || pendingSchedule) {
            val kind = if (prediction.kind == "DEADLINE") "DEADLINE" else "SCHEDULE"
            val issue = when (prediction.state) {
                "BOOKING_REQUIRED" -> "需要自行预约；通知中的启动日期不代表个人参加时间"
                "TO_BE_ANNOUNCED" -> "具体时间或地点尚待通知，请补充后确认"
                else -> "模型未提取到完整时间，请核对原文并补充"
            }
            drafts += Draft(name(0), category, kind, location = location(0), note = text, issues = (sharedIssues + issue).distinct())
        }
        return drafts.distinctBy { listOf(it.title, it.kind, it.startAt, it.endAt, it.dueAt) }
    }
}
