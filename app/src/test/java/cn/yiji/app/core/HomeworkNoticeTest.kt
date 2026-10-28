package cn.yiji.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 作业类通知（学习通/超星）的真实格式。
 *
 * 样本来自用户手机截图（学习通通知列表），这几条是**没有「截止」字样**的真实作业通知，
 * 恰好是旧规则会整类漏判的形态：旧判定要求「学术名词 + 期限词」，
 * 而这类通知的截止时间在 App 内部，标题里根本没有期限词。
 */
class HomeworkNoticeTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val receivedAt = LocalDate.of(2026, 10, 19).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    /** 截图里真实出现的标题 */
    private val realTitles = listOf(
        "作业：《高等数学A》3.1 微分中值定理A",
        "作业：《高等数学A》第二章 测验（副本）",
        "作业：《高等数学A》2.5函数的微分 作业（副本）",
        "作业：《高等数学A》2.4隐函数及由参数方程所确定的函数的导数",
        "作业：《高等数学A》2.3高阶导数 作业（副本）",
    )

    @Test
    fun chaoxingHomeworkIsRecognizedWithoutDeadlineWord() {
        realTitles.forEach { title ->
            val f = RuleExtractor.extract(title, receivedAt, zone)
            assertTrue("应判为作业：$title", f.isHomework)
            assertTrue("作业名应带课程名：$title -> '${f.homeworkName}'", f.homeworkName.contains("高等数学A"))
        }
    }

    @Test
    fun homeworkNameCarriesCourseAndChapter() {
        val f = RuleExtractor.extract("作业：《高等数学A》3.1 微分中值定理A", receivedAt, zone)
        assertTrue("应包含章节：${f.homeworkName}", f.homeworkName.contains("3.1"))
    }

    /** 「作业结束提醒」只是提醒，不是新的作业。 */
    @Test
    fun homeworkEndReminderIsNotANewHomework() {
        val f = RuleExtractor.extract("作业结束提醒", receivedAt, zone)
        assertFalse("不该判成作业", f.isHomework)
    }

    /** 「集合地点为北区田径场（…）」：地点要用「为」连接，且括号说明不属于地点。 */
    @Test
    fun locationConnectedByWeiIsExtracted() {
        val text = "我院将于10月17日(本周六)召开第二十二届院运会。大一 大二各班务必于7:20之前到达参加开幕式，" +
            "集合地点为北区田径场（届时场地有地贴和志愿者引导）"
        val f = RuleExtractor.extract(text, receivedAt, zone)
        assertEquals("北区田径场", f.location)
    }
}
