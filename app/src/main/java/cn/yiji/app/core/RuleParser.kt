package cn.yiji.app.core

import java.time.*

/** Local extraction is conservative; missing times always remain unconfirmed. */
object RuleParser {
    private val keywords = Regex("作业|报名|会议|开会|活动|比赛|竞赛|培训|考试|课程|上课|提交|材料|截止|讲座|答辩|缴费|取消|改期|延期")
    fun isImportant(text: String) = keywords.containsMatchIn(text)
    fun parse(title: String, body: String, receivedAt: Long, zone: ZoneId = ZoneId.systemDefault()): Draft {
        val original = "$title\n$body".trim()
        val change = Regex("(?:改为|调整至|调整为|延期至|推迟到|推迟至|改到)(.+)").find(body)?.groupValues?.get(1)
        val timeText = change ?: body
        val reference = Instant.ofEpochMilli(receivedAt).atZone(zone).toLocalDate()
        val issues = mutableListOf<String>()
        val date = parseDate(timeText, reference, issues)
        val deadline = Regex("截止|最迟|之前|前提交|前完成|前报名|截至").containsMatchIn(original) ||
            Regex("作业|报名|缴费|提交材料|提交报告").containsMatchIn(original) && !Regex("会议|讲座|培训|活动").containsMatchIn(original)
        val kind = if (deadline) "DEADLINE" else "SCHEDULE"
        val times = parseTimes(timeText)
        if (date == null) issues += "未识别出日期，请补充"
        if (times.isEmpty()) issues += "未识别出具体时间，请补充"
        if (kind == "SCHEDULE" && times.size < 2) issues += "缺少结束时间，请补充"
        fun timestamp(t: LocalTime) = date?.atTime(t)?.atZone(zone)?.toInstant()?.toEpochMilli()
        val start = if (kind == "SCHEDULE") times.firstOrNull()?.let(::timestamp) else null
        var end = if (kind == "SCHEDULE") times.getOrNull(1)?.let(::timestamp) else null
        if (start != null && end != null && end <= start && Regex("次日|翌日|第二天|跨夜").containsMatchIn(timeText)) {
            end = date!!.plusDays(1).atTime(times[1]).atZone(zone).toInstant().toEpochMilli()
        }
        if (start != null && end != null && end <= start) issues += "结束时间不晚于开始时间，请核对"
        val due = if (kind == "DEADLINE") times.lastOrNull()?.let(::timestamp) else null
        val category = listOf("作业", "报名", "考试", "会议", "课程", "活动", "材料").firstOrNull {
            original.contains(it) || it == "会议" && original.contains("开会") || it == "课程" && original.contains("上课") ||
                it == "活动" && Regex("竞赛|培训|讲座|比赛").containsMatchIn(original)
        } ?: "其他"
        val location = Regex("(?:地点(?:改为|调整为|改至)?[：:]?|(?:改在|改到|改至)|在)\\s*([^，。；;\\n]{2,30}?)(?:召开|举行|参加|开会|上课|[，。；;\\n]|$)")
            .find(body)?.groupValues?.get(1)?.trim() ?: ""
        val action = Regex("(?:请|需要|务必)([^。；;\\n]{2,100})").find(body)?.groupValues?.get(1)?.trim() ?: ""
        val name = extractTitle(title, body, category)
        return Draft(name, category, kind, start, end, due, location, action, body, issues.distinct())
    }

    private fun extractTitle(title: String, body: String, category: String): String {
        Regex("[《【]([^》】]{2,80})[》】]").find(body)?.let { return it.groupValues[1].removeSuffix("通知") }
        Regex("(?:召开|举行|开展)([^，。；;\\n]{2,35})").find(body)?.let {
            return it.groupValues[1].replace(Regex("^(?:一次|一场)"), "").take(120)
        }
        if (title.isNotBlank() && keywords.containsMatchIn(title) && title !in setOf("粘贴通知", "手动粘贴", "微信", "QQ", "通知", "分享文本"))
            return title.replace(Regex("^(?:关于|通知[：:])"), "").removeSuffix("通知").take(120)
        Regex("([\\p{IsHan}A-Za-z]{2,16}(?:作业|竞赛培训|培训|报名|考试|会议|讲座|答辩|课程|比赛))").find(body)?.let {
            return it.groupValues[1].replace(Regex("^(?:原定|关于|通知|请大家|本次|前提交|前完成|提交|完成)"), "").take(120)
        }
        val first = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "新事项"
        return first.replace(Regex("^通知[：:]?"), "").substringBefore('。').substringBefore('；').take(50).ifBlank { "$category 事项" }
    }

    fun parseDate(text: String, reference: LocalDate, issues: MutableList<String> = mutableListOf()): LocalDate? {
        Regex("(20\\d{2})[-/年](\\d{1,2})[-/月](\\d{1,2})日?").find(text)?.let {
            return runCatching { LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }.getOrNull()
        }
        Regex("(?<!\\d)(\\d{1,2})月(\\d{1,2})[日号]?").find(text)?.let {
            issues += "原文未写年份，已按 ${reference.year} 年解析，请核对"
            return runCatching { LocalDate.of(reference.year, it.groupValues[1].toInt(), it.groupValues[2].toInt()) }.getOrNull()
        }
        when {
            text.contains("大后天") -> return reference.plusDays(3)
            text.contains("后天") -> return reference.plusDays(2)
            text.contains("明天") || text.contains("明日") -> return reference.plusDays(1)
            text.contains("今天") || text.contains("今日") -> return reference
        }
        Regex("(下下|下|本|这)?(?:周|星期|礼拜)([一二三四五六日天])").find(text)?.let {
            val wanted = "一二三四五六日".indexOf(it.groupValues[2].replace("天", "日")) + 1
            val monday = reference.minusDays((reference.dayOfWeek.value - 1).toLong())
            val result = monday.plusDays((wanted - 1).toLong())
            return when (it.groupValues[1]) {
                "下下" -> result.plusWeeks(2)
                "下" -> result.plusWeeks(1)
                "本", "这" -> result
                else -> if (result < reference) result.plusWeeks(1) else result
            }
        }
        return null
    }

    fun parseTimes(text: String): List<LocalTime> {
        val regex = Regex("(凌晨|早上|上午|中午|下午|晚上|晚间)?\\s*(\\d{1,2})(?:[:：](\\d{2})|[点时](?:(\\d{1,2})分?|([半整]))?)(?!\\d)")
        var lastPeriod = ""
        return regex.findAll(text).mapNotNull { m ->
            val period = m.groupValues[1].ifBlank { lastPeriod }
            if (m.groupValues[1].isNotBlank()) lastPeriod = period
            var hour = m.groupValues[2].toInt()
            val minute = when {
                m.groupValues[3].isNotBlank() -> m.groupValues[3].toInt()
                m.groupValues[4].isNotBlank() -> m.groupValues[4].toInt()
                m.groupValues[5] == "半" -> 30
                else -> 0
            }
            if (period in setOf("下午", "晚上", "晚间", "中午") && hour < 12) hour += 12
            if (period == "凌晨" && hour == 12) hour = 0
            runCatching { LocalTime.of(hour, minute) }.getOrNull()
        }.toList()
    }
}
