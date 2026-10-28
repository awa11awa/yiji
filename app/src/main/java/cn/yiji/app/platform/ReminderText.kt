package cn.yiji.app.platform

import cn.yiji.app.core.ReminderPoint
import cn.yiji.app.core.reminderPoints
import cn.yiji.app.data.Event
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ReminderText {
    fun body(event: Event, point: ReminderPoint? = null): String {
        val at = point?.at ?: event.anchor
        val time = at?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm")) } ?: "未知时间"
        val place = event.location.replace(Regex("\\s+"), " ").trim().take(40).ifBlank { "未知地址" }
        val subject = when {
            point?.role == "open" -> "报名开放：${event.title}"
            point?.role == "due" -> "截止：${event.title}"
            point?.role?.startsWith("task-") == true -> point.message
            else -> event.title
        }.replace(Regex("\\s+"), " ").trim().take(24)
        return "$time · $place · $subject"
    }

    fun next(events: List<Event>, now: Long = System.currentTimeMillis()): String? = events.filter { it.status == "ACTIVE" }
        .flatMap { event -> event.draft().reminderPoints().filter { it.at > now }.map { event to it } }
        .minByOrNull { it.second.at }?.let { body(it.first, it.second) }
}
