package cn.yiji.app.core

import java.text.Normalizer
import java.time.*

/** Local extraction is conservative; missing times always remain unconfirmed. */
object RuleParser {
    private val eventWords = Regex("作业|报名|会议|开会|比赛|竞赛|培训|考试|上课|讲座|答辩|缴费|补考|面试|体检|值班")
    private val taskWords = Regex("作业|报名|缴费|提交|上交|补交|交表|交齐|交到|交给|填表|填报|填写|上传|报送|上报|申请|登记|打卡")
    private val campusActions = Regex("集合|签到|签退|报到|到场|领取|领书|领证|归还|借还|返校|离校|体检|面试|值班|提交|上交|补交|交表|交齐|交到|交给|填表|填报|填写|上传|报送|上报|登记|打卡|缴费|报名|申请")
    private val keywords = Regex("${eventWords.pattern}|${campusActions.pattern}|课程|活动|材料|截止|取消|改期|延期")
    private val directive = Regex("请|务必|必须|记得|别忘|尽快|限期|要求|通知|提醒|安排|定于|将于|须")
    private val scheduling = Regex("时间|地点|开始|举行|召开|开展|参加|待定|另行通知|取消|改期|延期|推迟|调整|更改|不再举行")
    private val changes = Regex("取消|改期|延期|推迟|调整|更改|改为|改到|改至|不再举行")
    private val generalSubjects = Regex("课程|活动|材料")
    private val future = Regex("明天|明日|后天|下周|下下周|下星期|下礼拜|下个?月|将于|即将|定于")
    private val retrospective = Regex("昨天|昨日|前天|已(?:经)?(?:完成|结束|提交|领取)|结束了|完成了|写完了|交过了|报过名")
    private val casual = Regex("好难|好玩|有趣|结果公布|聊聊|吐槽|吃什么|写完|结束|完成了")
    private val noise = Regex("验证码|校验码|动态密码|优惠券|红包|秒杀|促销|直播间")
    private val dateSignal = Regex("今天|今日|明天|明日|后天|(?:下下|下|本|这)?(?:周|星期|礼拜)[一二三四五六日天1-7]|\\d{1,2}月|(?<!\\d)\\d{1,2}[日号](?!楼|室)|\\d{1,2}[./-]\\d{1,2}|(?:下|本|这)个?月")
    private const val periods = "凌晨|清晨|早上|早晨|上午|中午|下午|傍晚|晚上|晚间"
    private val clock = Regex("(?<![\\d.:])($periods)?\\s*(\\d{1,2})(?:[:：](\\d{2})|[点时](?:(\\d{1,2})分?|([半整])|(一刻|三刻))?)(?![\\d.:])")

    /** A sender/group title supplies context, but never makes unrelated chat important by itself. */
    fun isImportant(text: String, title: String = ""): Boolean {
        val body = normalize(text).trim()
        val heading = normalize(title)
        if (body.isBlank()) return false
        if (noise.containsMatchIn(body)) return false
        val instructed = directive.containsMatchIn(body)
        if (retrospective.containsMatchIn(body) && !future.containsMatchIn(body) && !instructed) return false
        val timed = dateSignal.containsMatchIn(body) || clock.containsMatchIn(body)
        if (HomeworkRules.isHomework(body, heading)) return HomeworkRules.isAssignment(body, heading, timed)
        val subject = "$body $heading"
        if (changes.containsMatchIn(body) && (eventWords.containsMatchIn(subject) ||
                campusActions.containsMatchIn(subject) || generalSubjects.containsMatchIn(subject))) return true
        if (campusActions.containsMatchIn(body) && (timed || instructed || eventWords.containsMatchIn(body))) return true
        if (eventWords.containsMatchIn(body)) return timed || instructed || scheduling.containsMatchIn(body) ||
            (body.length <= 20 && !casual.containsMatchIn(body))
        if (generalSubjects.containsMatchIn(body) && instructed && (timed || scheduling.containsMatchIn(body))) return true
        return eventWords.containsMatchIn(heading) && (timed || instructed) && scheduling.containsMatchIn(body)
    }

    private fun normalize(text: String): String {
        var result = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .replace("今晚", "今天晚上").replace("今早", "今天早上")
            .replace("明晚", "明天晚上").replace("明早", "明天早上")
        // Only convert numerals next to date/time units; retain venue names and weekday names.
        result = Regex("[零〇一二两三四五六七八九十]{1,3}(?=月|日|号(?!楼|室)|点|时|分)").replace(result) { match ->
            val value = match.value.replace('两', '二')
            val digits = "零一二三四五六七八九"
            fun digit(c: Char) = if (c == '〇') 0 else digits.indexOf(c)
            val number = if ('十' in value) {
                val parts = value.split('十')
                (parts[0].firstOrNull()?.let(::digit) ?: 1) * 10 + (parts[1].firstOrNull()?.let(::digit) ?: 0)
            } else value.fold(0) { n, c -> n * 10 + digit(c) }
            number.toString()
        }
        // Expand a clock range's omitted first unit (2-3点), without treating a room number as time.
        return Regex("(?<![\\d.:])(\\d{1,2})(\\s*(?:到|至|[-~～—–])\\s*)(?=(?:$periods)?\\d{1,2}(?:[点时]|[:：]))")
            .replace(result) { "${it.groupValues[1]}点${it.groupValues[2]}" }
    }
    fun parse(title: String, body: String, receivedAt: Long, zone: ZoneId = ZoneId.systemDefault()): Draft {
        val normalizedBody = normalize(body)
        val normalizedTitle = normalize(title)
        val original = "$normalizedTitle\n$normalizedBody".trim()
        val homework = HomeworkRules.isHomework(normalizedBody, normalizedTitle)
        val timeTitle = if (homework) HomeworkRules.temporalText(normalizedTitle) else normalizedTitle
        // A location-only change must not discard the date/time elsewhere in the message.
        val change = Regex("(?:改为|调整至|调整为|延期至|推迟到|推迟至|改到)([^。；;\\n]+)")
            .findAll(normalizedBody).map { it.groupValues[1] }
            .firstOrNull { dateSignal.containsMatchIn(it) || clock.containsMatchIn(it) }
        // Separate the submission deadline from the time a lesson or assignment was announced.
        val submission = if (homework) normalizedBody.split(Regex("[，,。；;\\n]")).lastOrNull {
            Regex("截止|截至|最迟|之前|以前|提交|上交|补交|交齐|交到|交给|上传|前交|交作业|交报告|前\\s*$").containsMatchIn(it) &&
                (dateSignal.containsMatchIn(it) || clock.containsMatchIn(it))
        } else null
        val selectedTimeText = change ?: submission ?: normalizedBody
        val timeText = if (homework) HomeworkRules.temporalText(selectedTimeText) else selectedTimeText
        val reference = Instant.ofEpochMilli(receivedAt).atZone(zone).toLocalDate()
        val issues = mutableListOf<String>()
        val date = parseDate(timeText, reference, issues) ?: if (!dateSignal.containsMatchIn(timeText))
            parseDate(timeTitle, reference, issues) else null
        val classificationText = if (keywords.containsMatchIn(normalizedBody)) normalizedBody else original
        val deadline = homework || Regex("截止|截至|最迟|之前|以前|前提交|前完成|前报名|\\d{1,2}(?:[:：]\\d{2}|[点时](?:半|整|\\d{1,2}分?)?|[日号])\\s*前").containsMatchIn(classificationText) ||
            taskWords.containsMatchIn(classificationText) && !Regex("会议|开会|讲座|培训|活动|集合|签到|签退|报到|领取|领书|领证|面试|体检|值班|上课|考试").containsMatchIn(classificationText)
        val kind = if (deadline) "DEADLINE" else "SCHEDULE"
        val clocks = parseClocks(if (clock.containsMatchIn(timeText)) timeText else timeTitle)
        val times = clocks.map { it.time }
        if (date == null) issues += "未识别出日期，请补充"
        if (times.isEmpty()) issues += if (date == null) "未识别出具体时间，请补充" else "已识别日期 $date，请补充具体时间"
        if (kind == "SCHEDULE" && times.size < 2) issues += "缺少结束时间，请补充"
        fun timestamp(index: Int): Long? {
            val value = clocks.getOrNull(index) ?: return null
            return date?.plusDays(if (value.eveningMidnight) 1 else 0)?.atTime(value.time)?.atZone(zone)?.toInstant()?.toEpochMilli()
        }
        val start = if (kind == "SCHEDULE") timestamp(0) else null
        var end = if (kind == "SCHEDULE") timestamp(1) else null
        if (start != null && end != null && end <= start && Regex("次日|翌日|第二天|跨夜").containsMatchIn(timeText)) {
            end = date!!.plusDays(1).atTime(times[1]).atZone(zone).toInstant().toEpochMilli()
        }
        if (start != null && end != null && end <= start) issues += "结束时间不晚于开始时间，请核对"
        val due = if (kind == "DEADLINE") timestamp(times.lastIndex) else null
        val category = if (homework) HomeworkRules.CATEGORY else categoryOf(normalizedBody) ?: categoryOf(normalizedTitle) ?: "其他"
        val location = extractLocation(normalizedBody)
        val action = Regex("(?:请|需要|务必|必须|记得|别忘了)([^。；;\\n]{2,100})").find(normalizedBody)?.groupValues?.get(1)?.trim()
            ?: normalizedBody.split(Regex("[。；;\\n]")).firstOrNull { homework || campusActions.containsMatchIn(it) }?.trim()?.take(2000).orEmpty()
        val name = extractTitle(normalizedTitle, normalizedBody, category)
        return Draft(name, category, kind, start, end, due, location, action, body, issues.distinct())
    }

    private fun categoryOf(text: String): String? = listOf("作业", "报名", "考试", "会议", "课程", "活动", "材料").firstOrNull {
        text.contains(it) || it == "报名" && text.contains("申请") ||
            it == "考试" && Regex("补考|面试").containsMatchIn(text) ||
            it == "会议" && text.contains("开会") || it == "课程" && text.contains("上课") ||
            it == "活动" && Regex("竞赛|培训|讲座|比赛|集合|签到|签退|报到|体检|值班").containsMatchIn(text) ||
            it == "材料" && Regex("交表|填表|填报|填写|上传|报送|上报|领书|领证").containsMatchIn(text)
    }

    private fun extractLocation(body: String): String {
        val stop = "召开|举行|参加|开会|上课|集合|签到|签退|报到|到场|领取|领书|领证|提交|上交|交表|体检|面试|值班"
        for (prefix in listOf("地点(?:改为|调整为|改至)?[：:]?|改在|改到|改至", "到|前往", "在")) {
            val pattern = Regex("(?:$prefix)\\s*([^，。；;\\n]{2,35}?)(?:$stop|[，。；;\\n]|$)")
            val place = pattern.findAll(body).map { it.groupValues[1].trim() }.firstOrNull {
                !Regex("今天|明天|后天|\\d{1,2}月|\\d{1,2}[:：]|\\d{1,2}[点时]").containsMatchIn(it)
            }
            if (place != null) return place
        }
        return ""
    }

    private fun extractTitle(title: String, body: String, category: String): String {
        Regex("[《【]([^》】]{2,80})[》】]").find(body)?.let { return it.groupValues[1].removeSuffix("通知") }
        Regex("(?:召开|举行|开展)([^，。；;\\n]{2,35})").find(body)?.let {
            return it.groupValues[1].replace(Regex("^(?:一次|一场)"), "").take(120)
        }
        if (title.isNotBlank() && keywords.containsMatchIn(title) && !Regex("群|老师|同学").containsMatchIn(title) && title !in setOf("粘贴通知", "手动粘贴", "微信", "QQ", "通知", "分享文本"))
            return title.replace(Regex("^(?:关于|通知[：:])"), "").removeSuffix("通知").take(120)
        Regex("([\\p{IsHan}A-Za-z]{2,16}(?:作业|竞赛培训|培训|报名|考试|会议|讲座|答辩|课程|比赛))").find(body)?.let {
            return it.groupValues[1].replace(Regex("^(?:原定|关于|通知|请大家|本次|前提交|前完成|提交|完成)"), "").take(120)
        }
        val first = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "新事项"
        return first.replace(Regex("^通知[：:]?"), "").substringBefore('。').substringBefore('；').take(50).ifBlank { "$category 事项" }
    }

    fun parseDate(text: String, reference: LocalDate, issues: MutableList<String> = mutableListOf()): LocalDate? {
        val normalized = normalize(text)
        Regex("(?<!\\d)(20\\d{2})[-/.年](\\d{1,2})[-/.月](\\d{1,2})(?:日|号)?(?!\\d)").find(normalized)?.let {
            return runCatching { LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }.getOrNull()
        }
        Regex("(?<![\\d./-])(\\d{1,2})(?:月|[./-])(\\d{1,2})(?:日|号)?(?!\\d)").find(normalized)?.let {
            issues += "原文未写年份，已按 ${reference.year} 年解析，请核对"
            return runCatching { LocalDate.of(reference.year, it.groupValues[1].toInt(), it.groupValues[2].toInt()) }.getOrNull()
        }
        when {
            normalized.contains("大后天") -> return reference.plusDays(3)
            normalized.contains("后天") -> return reference.plusDays(2)
            normalized.contains("明天") || normalized.contains("明日") -> return reference.plusDays(1)
            normalized.contains("今天") || normalized.contains("今日") -> return reference
        }
        Regex("(下下|下|本|这)?(?:周|星期|礼拜)([一二三四五六日天1-7])").find(normalized)?.let {
            val day = it.groupValues[2]
            val wanted = day.toIntOrNull() ?: ("一二三四五六日".indexOf(day.replace("天", "日")) + 1)
            val monday = reference.minusDays((reference.dayOfWeek.value - 1).toLong())
            val result = monday.plusDays((wanted - 1).toLong())
            return when (it.groupValues[1]) {
                "下下" -> result.plusWeeks(2)
                "下" -> result.plusWeeks(1)
                "本", "这" -> result
                else -> if (result < reference) result.plusWeeks(1) else result
            }
        }
        Regex("(?<!\\d)(?:(下|本|这)个?月)?(\\d{1,2})[日号](?!楼|室)").find(normalized)?.let {
            val month = if (it.groupValues[1] == "下") reference.plusMonths(1) else reference
            issues += if (it.groupValues[1].isBlank()) "原文只写日号，已按 ${month.year} 年 ${month.monthValue} 月解析，请核对" else "原文未写年份，已按 ${month.year} 年解析，请核对"
            return runCatching { month.withDayOfMonth(it.groupValues[2].toInt()) }.getOrNull()
        }
        return null
    }

    fun parseTimes(text: String): List<LocalTime> = parseClocks(text).map { it.time }

    private data class ParsedTime(val time: LocalTime, val eveningMidnight: Boolean)

    private fun parseClocks(text: String): List<ParsedTime> {
        val normalized = normalize(text)
        var lastPeriod = ""
        var previousEnd = 0
        return clock.findAll(normalized).mapNotNull { m ->
            val connector = normalized.substring(previousEnd, m.range.first)
            val range = Regex("\\s*(?:至|到|[-~～—–])\\s*").matches(connector)
            val period = m.groupValues[1].ifBlank { if (range) lastPeriod else "" }
            lastPeriod = period
            previousEnd = m.range.last + 1
            var hour = m.groupValues[2].toInt()
            val minute = when {
                m.groupValues[3].isNotBlank() -> m.groupValues[3].toInt()
                m.groupValues[4].isNotBlank() -> m.groupValues[4].toInt()
                m.groupValues[5] == "半" -> 30
                m.groupValues[6] == "一刻" -> 15
                m.groupValues[6] == "三刻" -> 45
                else -> 0
            }
            if (period in setOf("下午", "傍晚", "晚上", "晚间") && hour in 1..11) hour += 12
            if (period == "中午" && hour in 1..5) hour += 12
            val eveningMidnight = period in setOf("晚上", "晚间") && hour == 12
            if (period in setOf("凌晨", "晚上", "晚间") && hour == 12) hour = 0
            runCatching { ParsedTime(LocalTime.of(hour, minute), eveningMidnight) }.getOrNull()
        }.toList()
    }
}
