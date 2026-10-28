package cn.yiji.app.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 规则抽取结果（完全不用模型）。
 *
 * 对有明确字段标签的通知抽取时间、地点、标题；用于粘贴预览和云端结果的缺失字段补充。
 */
data class RuleFields(
    val title: String = "",
    /** 活动/会议本身的开始时间 */
    val timeAt: Long? = null,
    val timeText: String = "",
    /** 有「时间」字段但不够具体（如「中午及下午」「以现场安排为准」），不硬编一个时间 */
    val timeVague: Boolean = false,
    /** 报名/提交截止时间（校园通知里几乎必有一条，单独留着给截止类事项） */
    val deadlineAt: Long? = null,
    val deadlineText: String = "",
    val location: String = "",
    val isHomework: Boolean = false,
    val homeworkName: String = "",
    val notes: List<String> = emptyList(),
    val opensAt: Long? = null,
    val openText: String = "",
    val endAt: Long? = null,
)

/**
 * 校园通知的规则抽取器。
 *
 * 三条设计原则：
 * 1. **先找结构锚点**（emoji、`时间：`、`地点：`、`【】`），再回落到正文扫描；
 * 2. **按字段边界截断**——单行通知里字段之间只有空格，不截断就会把整段吞成一个地点；
 * 3. **区分时间角色**：一条通知常同时出现「开放填写 12:00」和「截止 20:00」，
 *    真正的活动时间往往在正文里（「兹定于…晚18:30」）。抽不到就留空，不猜。
 */
object RuleExtractor {
    private const val TAIL = "(?:(?!(?:(?:活动|会议|培训|报名|集合|比赛)?(?:时间|日期)|(?:报名|提交)?截止(?:时间|日期)?|(?:报名|填写)?开放(?:时间|日期)?|地点|地址)\\s*[:：]|[⏰📅📍])[^\\n⏎]){0,80}"

    private val timeLabel = Regex(
        "(?:⏰|📅|🕐|⌚|🕒)(?:\\s*(?:活动|会议|培训|报名|集合|比赛)?(?:时间|日期)\\s*[:：])?($TAIL)" +
            "|(?:报名|提交|上交)?截止(?:时间|日期)?\\s*[:：]($TAIL)" +
            "|(?:报名|填写)?开放(?:时间|日期)?\\s*[:：]($TAIL)" +
            "|(?:时间|日期)\\s*[:：]($TAIL)" +
            "|(?:活动|会议|培训|报名|训练|比赛|讲座|集合|考试|面试|笔试|接种|开业|签到)\\s*时间\\s*[:：]($TAIL)" +
            "|(?:活动|会议|培训|报名)\\s*日期\\s*[:：]($TAIL)" +
            // 分节写法：「二、活动时间」换行后才是值，且没有冒号，校园通知里极常见
            "|(?:活动|会议|培训|报名|集合|训练|讲座|考试|面试|笔试)?\\s*(?:时间|日期)\\s*[\\u3000 ]*[⏎\\n]\\s*([^⏎\\n]{1,60})"
    )

    private val locationLabel = Regex(
        "(?:📍|🏠|🏫|🏢|🎯)(?:\\s*(?:活动|会议|培训|集合|训练|讲座|报到|笔试|面试|比赛)?(?:地点|地址)\\s*[:：为是])?($TAIL)" +
            "|(?:活动|会议|培训|集合|训练|讲座|报到|笔试|面试|比赛)\\s*(?:地点|地址)\\s*[:：]($TAIL)" +
            "|(?:地点|地址)\\s*[:：]($TAIL)" +
            // 「集合地点为北区田径场」——用「为/是」连接，而不是冒号
            "|(?:活动|会议|培训|集合|训练|讲座|报到|笔试|面试|比赛)?\\s*(?:地点|地址)\\s*[为是]\\s*([^，,。；;\\n⏎]{1,40})" +
            // 同上：「三、活动地点」换行后才是值
            "|(?:活动|会议|培训|集合|训练|讲座|报到|笔试|面试|比赛)?\\s*(?:地点|地址)\\s*[\\u3000 ]*[⏎\\n]\\s*([^⏎\\n]{1,60})"
    )

    /** 「兹定于2026年10月24日（周六）晚18:30」这种没有标签的写法 */
    private val inlineDateTime = Regex(
        "(?:20\\d{2}\\s*年\\s*)?\\d{1,2}\\s*月\\s*\\d{1,2}\\s*[日号]\\s*(?:[（(]\\s*周?[一二三四五六日天]\\s*[）)])?\\s*" +
            "(?:上午|下午|早上|中午|晚上|晚间|晚|凌晨)?\\s*\\d{1,2}\\s*[:：]\\s*\\d{2}"
    )

    /** 「在福建农林大学…大礼堂举办」「到创新楼102教室参加」这类没有「地点：」标签的写法 */
    private val inlineLocation = Regex(
        "在\\s*([^，,。；;\\n⏎]{3,30}?)\\s*(?:举办|举行|召开|集合)" +
            "|到\\s*([^，,。；;\\n⏎]{3,30}?)\\s*(?:参加|集合|报到|签到|开会|上课|面试|笔试)"
    )

    private val titleBracket = Regex("【([^】]{2,80})】")
    private val homeworkObject = Regex("([\\u4e00-\\u9fa5A-Za-z0-9]{2,14}(?:作业|论文|报告|实验|实习|课程设计|读书笔记|心得体会|练习题))")

    /**
     * 学习通/超星的作业通知格式：`作业：《课程名》章节标题`。
     *
     * 这类通知**通常不带「截止」字样**（截止时间在 App 内部），
     * 所以不能沿用「学术名词 + 期限词」的判定，否则整类作业都会被漏判。
     */
    private val chaoxingHomework = Regex("^\\s*作业\\s*[:：]\\s*(?:《([^》]{2,24})》)?\\s*(.{1,40})$")
    private val dueWords = Regex("前|截止|之前|以前|deadline", RegexOption.IGNORE_CASE)
    /**
     * 截止类时间的判定。
     *
     * 真实校园通知里更常见的写法是「11月17日24:00**前**」「10月22日**前**发送」，
     * 而不是「截止」二字，所以「前」也要算——但要排除「前进/前面」这类词。
     */
    private val deadlineRole = Regex("截止|ddl|前(?![进面往])", RegexOption.IGNORE_CASE)
    private val openRole = Regex("开放|开始报名|报名开始|开放填写|开放时间")
    private val vagueWords = Regex("以现场|待定|另行通知|为准|未定|后续通知")

    /** 字段边界：撞到这些词就截断，避免把后面整段内容当成地点/时间 */
    private val fieldBreaks = listOf(
        "参会对象", "参加对象", "活动对象", "参与对象", "报名对象", "对象", "招募人数", "人数",
        "活动内容", "服务内容", "主要任务", "报名方式", "报名要求", "报名链接", "主办单位", "承办单位",
        "联系", "备注", "注意", "签到", "⏰", "📍", "🏠", "📣", "📋", "✅", "⚠", "❗", "‼", "1⃣", "2⃣", "3⃣", "4⃣", "5⃣", "【",
    )

    private val emojiStrip = Regex("[⏰📅🕐⌚🕒📍🏠🏫🏢🎯‼️⚠️✨🌟✅❗📢👥📝📋💖🙏🎉👇👉🈶]")

    /**
     * `📍 会议地点：田家炳409` 这类写法，emoji 分支会连「会议地点：」一起吞进来。
     * 注意要支持「前缀词 + 字段名」的组合（会议地点 / 活动时间 / 报名日期）。
     */
    private val labelPrefix = Regex(
        "^\\s*(?:活动|会议|培训|报名|集合|训练|讲座|比赛|考试|面试|笔试|签到|接种|服务)?" +
            "\\s*(?:日期|时间|地点|地址|方式)\\s*[:：]\\s*"
    )

    private fun cutAtBreak(value: String): String {
        var cut = value.length
        fieldBreaks.forEach { token ->
            val index = value.indexOf(token)
            if (index in 1 until cut) cut = index
        }
        return value.substring(0, cut)
    }

    private fun stripLabel(value: String): String {
        var result = value
        repeat(3) {
            val next = labelPrefix.replace(result, "")
            if (next == result) return result
            result = next
        }
        return result
    }

    private fun clean(value: String, maxLength: Int = 40): String {
        val stripped = stripLabel(value.replace(emojiStrip, " "))
        val cut = cutAtBreak(stripped)
        return stripLabel(cut)
            .replace(Regex("^[\\s:：、,，.。;；\\-—]+"), "")
            .replace(Regex("[\\s,，。;；]+$"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .take(maxLength)
    }

    private fun firstGroup(match: MatchResult?): String {
        if (match == null) return ""
        return match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.let { clean(it) }.orEmpty()
    }

    fun extract(text: String, receivedAt: Long, zone: ZoneId = ZoneId.systemDefault()): RuleFields {
        val notes = mutableListOf<String>()
        val issues = mutableListOf<String>()
        val reference: LocalDate = Instant.ofEpochMilli(receivedAt).atZone(zone).toLocalDate()

        val title = titleBracket.find(text)?.groupValues?.get(1)?.let { clean(it, 80) }
            ?: text.lineSequence().firstOrNull { it.isNotBlank() }?.let { clean(it, 24) }
            ?: ""

        // ---- 时间：按角色分类 ----
        data class Slot(val text: String, val deadline: Boolean, val open: Boolean)
        val timeMatches = timeLabel.findAll(text).toList()
        val slots = timeMatches.mapNotNull { m ->
            val raw = m.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return@mapNotNull null
            val value = clean(raw, 40)
            if (value.isBlank()) null
            else Slot(value, deadlineRole.containsMatchIn(m.value),
                openRole.containsMatchIn(m.value) || Regex("^报名(?:时间|日期)").containsMatchIn(m.value))
        }

        val eventSlot = slots.firstOrNull { !it.deadline && !it.open }
        val deadlineSlot = slots.firstOrNull { it.deadline }
        val openSlot = slots.firstOrNull { it.open && !it.deadline }

        var timeText = eventSlot?.text.orEmpty()
        var timeAt: Long? = null
        var timeVague = false

        if (timeText.isNotBlank()) {
            timeAt = DateTimeResolver.resolve(timeText, reference, issues, zone)
        }
        if (timeAt == null) {
            // 标签里没有活动时间（常见：只有报名开放/截止）→ 回落到正文的「…日…18:30」
            val inline = inlineDateTime.findAll(text).firstOrNull { candidate ->
                timeMatches.none { candidate.range.first in it.range } &&
                    !deadlineRole.containsMatchIn(text.substring(maxOf(0, candidate.range.first - 12), candidate.range.first)) &&
                    !openRole.containsMatchIn(text.substring(maxOf(0, candidate.range.first - 12), candidate.range.first)) &&
                    !Regex("^\\s*(?:前|截止)").containsMatchIn(text.substring(candidate.range.last + 1).take(6))
            }
            if (inline != null) {
                val candidate = DateTimeResolver.resolve(clean(inline.value, 40), reference, issues, zone)
                if (candidate != null) {
                    timeAt = candidate
                    timeText = clean(inline.value, 40)
                    notes += "活动时间取自正文（「时间：」标签里只有报名开放/截止）"
                }
            }
        }
        if (timeAt == null) {
            if (timeText.isNotBlank()) {
                timeVague = true
                notes += "时间字段存在但不具体：$timeText"
            } else if (vagueWords.containsMatchIn(text)) {
                timeVague = true
                notes += "原文提到时间待定"
            }
        }

        val deadlineAt = deadlineSlot?.let { DateTimeResolver.resolve(it.text, reference, issues, zone) }
        val deadlineText = deadlineSlot?.text.orEmpty()
        if (deadlineAt == null && deadlineText.isNotBlank()) notes += "截止时间不具体：$deadlineText"
        val openText = openSlot?.text.orEmpty()
        val opensAt = openSlot?.let { DateTimeResolver.resolve(it.text, reference, issues, zone) }
        val endAt = timeAt?.let { start ->
            DateTimeResolver.parseTimes(timeText).takeIf { it.size >= 2 }?.last()?.let { end ->
                Instant.ofEpochMilli(start).atZone(zone).toLocalDate().atTime(end).atZone(zone).toInstant().toEpochMilli()
                    .takeIf { it > start }
            }
        }

        // ---- 地点 ----
        var location = ""
        locationLabel.findAll(text).forEach { m ->
            if (location.isNotBlank()) return@forEach
            val raw = m.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return@forEach
            val value = clean(raw, 40)
            if (value.isNotBlank() && !vagueWords.containsMatchIn(value)) location = value
        }
        if (location.isBlank()) {
            // 没有「地点：」标签时，回落到「在……举办/举行/召开」
            inlineLocation.find(text)?.let { m ->
                val raw = m.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return@let
                val value = clean(raw, 40)
                if (value.isNotBlank() && !vagueWords.containsMatchIn(value)) {
                    location = value
                    notes += "地点取自正文（无「地点：」标签）"
                }
            }
        }
        if (location.isBlank() && vagueWords.containsMatchIn(text)) notes += "原文提到地点待定"
        // 「北区田径场（届时场地有地贴和志愿者引导）」——括号里是补充说明，不属于地点
        location = location.substringBefore("（").substringBefore("(").trim().take(40)

        // ---- 作业 ----
        // 两条互不依赖的路径：①「学术名词 + 期限词」；②学习通「作业：《课程》章节」
        val cx = chaoxingHomework.find(title.trim()) ?: chaoxingHomework.find(text.trim())
        val homeworkHit = homeworkObject.find(text)
        val isHomework = cx != null || (homeworkHit != null && dueWords.containsMatchIn(text))
        val homeworkName = when {
            cx != null -> clean(
                listOfNotNull(
                    cx.groupValues[1].takeIf { it.isNotBlank() }?.let { "《${it.trim()}》" },
                    cx.groupValues[2].trim(),
                ).joinToString(" "), 30)
            homeworkHit != null -> clean(homeworkHit.groupValues[1], 20)
            else -> ""
        }

        return RuleFields(
            title = title,
            timeAt = timeAt,
            timeText = timeText,
            timeVague = timeVague,
            deadlineAt = deadlineAt,
            deadlineText = deadlineText,
            location = location,
            isHomework = isHomework,
            homeworkName = homeworkName,
            notes = (notes + issues).distinct(),
            opensAt = opensAt, openText = openText, endAt = endAt,
        )
    }

}
