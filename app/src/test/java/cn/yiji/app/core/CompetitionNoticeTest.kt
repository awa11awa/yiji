package cn.yiji.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 用户提供的真实通知：赛制说明（项目展示 + 团队答辩）。
 *
 * 这条文本的价值在于它**没有任何日期和地点**，却塞满了
 * 「①②③」「1、2、」「限时6分钟」「至多三人」这类数字噪声。
 * 规则层在这里的正确行为是：**什么都不猜**——不把时长当时间、不把序号当日期、
 * 不把「比赛形式」当地点。
 */
class CompetitionNoticeTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val receivedAt = LocalDate.of(2026, 10, 19).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private val notice = """
        全体成员  现将本次比赛形式通知如下：
        比赛形式:
        1、项目展示环节
        ①展示主体:每支参赛团队派1 名代表上台:围绕项目核心内容进行讲解。
        ②展示载体:以PPT为主要展示形式，可辅助播放视频、音频或展示实物模型
        ③时间限制:限时6分钟(剩余1分钟有提示)。
        2、团队答辩环节
        ①参与人员:展示结束后，团队成员需共同上台参与答辩，人数限制为至多三人。
        ②答辩形式:评委结合项目示内容及书面材料(创业计划书)提问，问题聚焦项目可行性、创新性、实践性、逻辑思维及语言表达能力。
        ③时间限制:统一限时5分钟(含评委提问与团队回答时间)。
    """.trimIndent()

    @Test
    fun durationsAreNotMistakenForClockTimes() {
        val fields = RuleExtractor.extract(notice, receivedAt, zone)
        println("timeAt=${fields.timeAt} timeText='${fields.timeText}' vague=${fields.timeVague} loc='${fields.location}' title='${fields.title}'")
        println("notes=${fields.notes}")

        // 「限时6分钟」「限时5分钟」「剩余1分钟」都是时长，不是时刻
        assertEquals("不该解析出时间", null, fields.timeAt)
        assertTrue("不该把时长当成时间文本，实际 '${fields.timeText}'", fields.timeText.isBlank())
        assertEquals("不该凭空抽地点", "", fields.location)
        // 没有任何日期/时刻字段，属于「未知时间」，而不是「时间不具体」
        assertFalse("原文没有时间字段，不该标记为不具体", fields.timeVague)
        assertEquals("不该判定为作业", false, fields.isHomework)
        assertTrue("标题不该为空", fields.title.isNotBlank())
    }

    /** 时长与时刻混排时，只认真正的时刻。 */
    @Test
    fun mixedDurationsAndClockTimesPickTheClock() {
        val text = "答辩限时5分钟，请于10月24日下午2:30到创新楼102教室参加。"
        val fields = RuleExtractor.extract(text, receivedAt, zone)
        val expected = LocalDate.of(2026, 10, 24).atTime(14, 30).atZone(zone).toInstant().toEpochMilli()
        println("mixed: timeAt=${fields.timeAt} loc='${fields.location}'")
        assertEquals("应只认 14:30 这个时刻", expected, fields.timeAt)
        assertEquals("创新楼102教室", fields.location)
    }
}
