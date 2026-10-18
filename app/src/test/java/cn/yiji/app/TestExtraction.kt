package cn.yiji.app

import cn.yiji.app.core.Draft
import cn.yiji.app.ml.Extraction
import cn.yiji.app.ml.ScheduleExtractor
import java.time.Instant
import java.time.ZoneId

/** Fixed cloud fixtures for storage/listener tests. Actual learned routing is tested separately. */
object TestExtraction {
    val extractor = ScheduleExtractor { _, body, receivedAt ->
        val reference = Instant.ofEpochMilli(receivedAt).atZone(ZoneId.systemDefault()).toLocalDate()
        fun at(days: Long, hour: Int) = reference.plusDays(days).atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val noise = body in setOf("晚饭吃什么", "晚饭吃什么？", "请问实验报告怎么提交", "你有3条新消息", "登录验证码123456", "验证码123456", "验证码123456，五分钟内有效，请勿泄露。")
        if (noise) Extraction(emptyList(), ignored = true) else {
            val homework = body.contains("实验报告") || body.contains("习题") || body.contains("P38") || body.contains("数学作业")
            val pending = body.isBlank() || body.contains("另行通知") || homework && !body.contains("前")
            val update = body == "竞赛培训延期至后天16:00至17:00，地点改为图书馆302。"
            val cancel = body == "竞赛培训取消"
            val draft = when {
                homework -> Draft("课程作业", "作业", "DEADLINE", dueAt = if (pending) null else at(1, 20))
                body.contains("报名表") -> Draft("报名表", "报名", "DEADLINE", dueAt = at(1, 18))
                pending -> Draft("会议", "会议", "SCHEDULE")
                body.contains("集合") -> Draft("集合", "活动", "SCHEDULE", at(1, 14), null, location = "301")
                else -> Draft("竞赛培训", "会议", "SCHEDULE", at(if (update) 2 else 1, if (update) 16 else 14),
                    at(if (update) 2 else 1, if (update) 17 else 15), location = if (update) "图书馆302" else "图书馆301")
            }
            Extraction(listOf(draft), operation = if (cancel) "CANCEL" else if (update) "UPDATE" else "NEW", engine = "测试云端提取")
        }
    }
}
