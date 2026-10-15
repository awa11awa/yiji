package cn.yiji.app.core

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class HomeworkRuleTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val reference = LocalDateTime.of(2026, 10, 15, 12, 0).atZone(zone).toInstant().toEpochMilli()
    private fun parse(body: String, title: String = "课程群") = RuleParser.parse(title, body, reference, zone)
    private fun time(day: Int, hour: Int) = LocalDateTime.of(2026, 10, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test fun exercisesAndReportsAreHomeworkWithoutLiteralKeyword() {
        listOf("请完成高数课后习题第1、3、5题", "C语言实验报告，明晚20:00前提交",
            "高数 P38 第1-5题，明晚20:00前交", "请完成第3章第2、4题",
            "本周作业：第3章第1-5题", "P38 第1、2、3题",
            "学习通高数作业已发布，请于周五22:00前完成", "请完成课程论文").forEach { text ->
            assertTrue(text, RuleParser.isImportant(text, "课程群"))
            val draft = parse(text)
            assertEquals(text, "作业", draft.category)
            assertEquals(text, "DEADLINE", draft.kind)
            assertNull(text, draft.startAt)
            assertNull(text, draft.endAt)
        }
    }

    @Test fun ordinaryQuestionsAndCompletedHomeworkAreIgnored() {
        listOf("我的作业已经提交了", "作业好难", "请问实验报告怎么提交",
            "作业什么时候交", "明天交作业吗", "实验报告截止了吗",
            "这道习题谁会做", "今晚作业代写", "实验报告写完了").forEach { text ->
            assertFalse(text, RuleParser.isImportant(text, "高数作业群"))
        }
    }

    @Test fun groupTitleCannotOverrideAnotherEventOrPromoteChat() {
        assertFalse(RuleParser.isImportant("今晚吃什么", "高数作业群"))
        val draft = parse("请明天14点到15点开会", "高数作业群")
        assertEquals("会议", draft.category)
        assertEquals("SCHEDULE", draft.kind)
    }

    @Test fun actionableBodyCanUseHomeworkTitleAsContext() {
        val draft = parse("明晚20点前提交", "实验报告")
        assertTrue(RuleParser.isImportant("明晚20点前提交", "实验报告"))
        assertEquals("作业", draft.category)
        assertEquals(time(16, 20), draft.dueAt)
    }

    @Test fun submissionTimeWinsOverLessonTime() {
        val text = "今天上午8点上课，实验报告下周一20点前提交"
        val draft = parse(text)
        assertEquals(time(19, 20), draft.dueAt)
        assertEquals(text, draft.note)
        assertFalse(draft.action.isBlank())
    }

    @Test fun incompleteHomeworkStaysPendingWithoutInventedTimes() {
        listOf("请完成高数课后习题第1、3、5题", "请于下周一上课前交作业").forEach { text ->
            val draft = parse(text)
            assertTrue(text, RuleParser.isImportant(text))
            assertEquals("作业", draft.category)
            assertEquals("DEADLINE", draft.kind)
            assertNull(draft.dueAt)
            assertNotNull(draft.validationError())
            assertTrue(draft.issues.isNotEmpty())
            assertFalse(draft.issues.any { it.contains("结束时间") })
        }
    }

    @Test fun dateOnlyDeadlineRequiresExactTime() {
        val draft = parse("请于10月20日前提交实验报告")
        assertNull(draft.dueAt)
        assertTrue(draft.issues.any { it.contains("2026-10-20") })
    }

    @Test fun homeworkChangesAndCancellationAreNotDropped() {
        assertTrue(RuleParser.isImportant("本次作业取消"))
        assertTrue(RuleParser.isImportant("延期到明晚20点", "高数作业"))
        val draft = parse("实验报告延期至下周二20点提交")
        assertEquals(time(20, 20), draft.dueAt)
        assertEquals("作业", draft.category)
        assertEquals("DEADLINE", draft.kind)
    }

    @Test fun anOldAssignmentWithAFutureSubmissionIsStillRecognized() {
        val text = "前天布置实验报告，明晚20点前提交"
        assertTrue(RuleParser.isImportant(text))
        assertEquals(time(16, 20), parse(text).dueAt)
    }

    @Test fun exerciseRangesNeverBecomeCalendarDates() {
        val incomplete = parse("请完成高数 P38 第1-5题")
        assertNull(incomplete.dueAt)
        assertTrue(incomplete.issues.any { it.contains("未识别出日期") })
        assertFalse(incomplete.issues.any { it.contains("2026-01-05") })
        val complete = parse("请完成高数第1-5题，周五20:00交")
        assertEquals(time(16, 20), complete.dueAt)
    }

    @Test fun administrativeBodyTakesPriorityOverHomeworkGroup() {
        assertNotEquals("作业", parse("请明晚20点前上传奖学金申请表", "高数作业群").category)
    }

    @Test fun genericAdministrativeReportsAreNotHomework() {
        val draft = parse("请于明晚20点前上传奖学金申请表")
        assertTrue(RuleParser.isImportant("请于明晚20点前上传奖学金申请表"))
        assertNotEquals("作业", draft.category)
    }
}
