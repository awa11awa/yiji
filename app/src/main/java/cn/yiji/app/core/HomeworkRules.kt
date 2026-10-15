package cn.yiji.app.core

/** Text-only homework cues. Group names need actionable body text as evidence. */
object HomeworkRules {
    const val CATEGORY = "作业"
    private val subjects = Regex(
        "作业|课后(?:练习|习题)|习题|练习题|实验(?:预习)?报告|实训报告|课程(?:论文|报告|设计)|编程(?:题|练习|任务)|上机(?:练习|任务)|实验(?:任务|练习)|homework|assignment",
        RegexOption.IGNORE_CASE)
    private const val numeral = "[零〇一二三四五六七八九十百\\d]+"
    private val exercises = Regex("(?:第?$numeral(?:\\s*[-~～—至到、,，]\\s*$numeral)*\\s*(?:小?题|道题))|(?:[Pp]\\s*\\d{1,3}|第?\\d{1,3}\\s*页)")
    private val page = Regex("[Pp]\\s*\\d{1,3}|第?\\d{1,3}\\s*页")
    private val problems = Regex("第?$numeral(?:\\s*[-~～—至到、,，]\\s*$numeral)*\\s*(?:小?题|道题)")
    private val changes = Regex("取消|改期|延期|推迟|改为|改到|改至|调整")
    private val courses = Regex("高等数学|高数|大学英语|英语|线性代数|离散数学|概率论|数据结构|大学物理|物理|[Cc]\\s*(?:语言|程序设计)|Python|Java|课程|课后|习题|练习")
    private val actions = Regex("请|务必|必须|记得|别忘|需要|布置|完成|提交|上交|补交|做完|写完|交到|交给|交齐|上传|交一下|要交|收齐|截止|截至|最迟|前交|交作业|交报告|submit|complete", RegexOption.IGNORE_CASE)
    private val otherEvents = Regex("会议|开会|讲座|考试|培训|比赛|集合|签到|上课|报名|缴费|申请|材料|交表|填表|填报")
    private val questions = Regex("请问|怎么|如何|什么|哪[个些道页]|谁|有人|有没有|[？?]|(?:吗|嘛|呢)[。！!]*$|交了没|写了没|做了没")
    private val casual = Regex("好难|太难|不会做|不会写|借我|借看|抄作业|代写|聊聊|吐槽")
    private val completed = Regex("已(?:经)?(?:完成|提交|上交|做完|写完)|写完了|做完了|完成了|交完了|交过了|提交了|上交了")
    private val instruction = Regex("请(?:大家|同学们|各位)|务必|必须|布置|要求|本(?:次|周|节)作业[：:]|作业[：:]|提交要求|截止|截至|最迟")
    private val newRequirement = Regex("还(?:需|要)|另需|新(?:的|布置)?作业|请(?:大家|同学们|各位)?(?:在|于|完成|提交|上交|补交|做)|务必|必须")

    fun isHomework(body: String, title: String = ""): Boolean {
        if (subjects.containsMatchIn(body)) return true
        if (exercises.containsMatchIn(body) && (courses.containsMatchIn(body) || actions.containsMatchIn(body) ||
                page.containsMatchIn(body) && problems.containsMatchIn(body))) return true
        return subjects.containsMatchIn(title) && (actions.containsMatchIn(body) || changes.containsMatchIn(body)) && !otherEvents.containsMatchIn(body)
    }

    /** Exercise ranges such as 1-5题 must not be parsed as January 5. */
    fun temporalText(text: String): String = problems.replace(page.replace(text, " "), " ")

    fun isAssignment(body: String, title: String, hasTime: Boolean): Boolean {
        if (!isHomework(body, title)) return false
        if (completed.containsMatchIn(body) && !newRequirement.containsMatchIn(body)) return false
        if ((questions.containsMatchIn(body) || casual.containsMatchIn(body)) &&
            !Regex("请(?:大家|同学们|各位)|务必|必须|布置|要求|作业[：:]").containsMatchIn(body)) return false
        return actions.containsMatchIn(body) || changes.containsMatchIn(body) || hasTime || instruction.containsMatchIn(body) ||
            (exercises.containsMatchIn(body) && (courses.containsMatchIn(body) || page.containsMatchIn(body) && problems.containsMatchIn(body))) ||
            Regex("^(?:本次|本周|今日|今天|课后).*?(?:作业|练习|习题)").containsMatchIn(body)
    }
}
