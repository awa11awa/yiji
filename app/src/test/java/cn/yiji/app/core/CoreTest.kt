package cn.yiji.app.core

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class CoreTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val reference = LocalDateTime.of(2026, 10, 14, 12, 0).atZone(zone).toInstant().toEpochMilli()
    private fun time(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun parse(body: String, title: String = "") = RuleParser.parse(title, body, reference, zone)
    private fun schedule(start: Long, end: Long, title: String = "竞赛培训") = Draft(title = title, kind = "SCHEDULE", startAt = start, endAt = end)

    @Test fun afternoonRangeAndSourceTitle() {
        val d = parse("明天下午2点到3点在图书馆301召开竞赛培训。请携带电脑。", "王老师")
        assertEquals("竞赛培训", d.title); assertEquals(time(15, 14), d.startAt); assertEquals(time(15, 15), d.endAt)
        assertEquals("图书馆301", d.location); assertEquals("携带电脑", d.action); assertNull(d.validationError())
    }
    @Test fun weekdayDeadlineDoesNotCreateInterval() {
        val d = parse("请于本周五23:59前提交数学作业", "数学作业")
        assertEquals("DEADLINE", d.kind); assertEquals(time(16, 23, 59), d.dueAt)
        assertNull(d.startAt); assertNull(d.endAt)
    }
    @Test fun explicitYearAndPartialYear() {
        assertEquals(time(20, 14), parse("2026年10月20日14:00至15:00会议", "会议").startAt)
        assertTrue(parse("10月20日14:00至15:00会议", "会议").issues.any { it.contains("年份") })
    }
    @Test fun missingDateNotGuessed() {
        val d = parse("14:00至15:00召开会议")
        assertNull(d.startAt); assertNotNull(d.validationError()); assertTrue(d.issues.any { it.contains("日期") })
    }
    @Test fun missingEndTimeRequiresConfirmation() {
        val d = parse("明天14:00召开会议")
        assertEquals(time(15, 14), d.startAt); assertNull(d.endAt); assertNotNull(d.validationError())
    }
    @Test fun invalidDateDoesNotCrash() { assertNull(parse("2026年2月30日14:00至15:00会议", "会议").startAt) }
    @Test fun changeUsesNewDateAndTime() {
        val d = parse("原定今天14:00至15:00的会议延期至后天16:00至17:00", "班级会议")
        assertEquals(time(16, 16), d.startAt); assertEquals(time(16, 17), d.endAt)
    }
    @Test fun crossMidnightOnlyWhenExplicit() {
        val d = parse("明天23:00至次日01:00值班会议", "值班会议")
        assertEquals(time(16, 1), d.endAt); assertNull(d.validationError())
        assertNotNull(parse("明天23:00至01:00值班会议", "值班会议").validationError())
    }
    @Test fun weeklyReferences() {
        assertEquals(LocalDate.of(2026, 10, 23), RuleParser.parseDate("下周五", LocalDate.of(2026, 10, 14)))
        assertEquals(LocalDate.of(2026, 10, 12), RuleParser.parseDate("本周一", LocalDate.of(2026, 10, 14)))
        assertEquals(LocalDate.of(2026, 10, 19), RuleParser.parseDate("周一", LocalDate.of(2026, 10, 14)))
    }
    @Test fun halfHourAndInvalidClock() {
        assertEquals(listOf(LocalTime.of(14, 30), LocalTime.of(16, 0)), RuleParser.parseTimes("下午2点半至4点整"))
        assertTrue(RuleParser.parseTimes("25:70").isEmpty())
    }
    @Test fun noiseAndImportantClassification() {
        assertFalse(RuleParser.isImportant("你的验证码是123456")); assertTrue(RuleParser.isImportant("明天提交材料"))
    }
    @Test fun overlapAndAdjacentSchedules() {
        val list = listOf(ExistingItem("a", schedule(100, 200)), ExistingItem("b", schedule(150, 300)), ExistingItem("c", schedule(300, 400)))
        val conflicts = ConflictDetector.detect(list)
        assertEquals(listOf(TimeConflict("a", "b", 150, 200)), conflicts)
    }
    @Test fun completedCancelledAndDeadlinesExcluded() {
        val list = listOf(ExistingItem("a", schedule(100, 200)), ExistingItem("done", schedule(110, 160), "DONE"),
            ExistingItem("cancelled", schedule(120, 180), "CANCELLED"), ExistingItem("deadline", Draft("作业", kind = "DEADLINE", dueAt = 150)))
        assertTrue(ConflictDetector.detect(list).isEmpty())
    }
    @Test fun multipleOverlapsDetected() {
        val list = listOf(ExistingItem("a", schedule(100, 500)), ExistingItem("b", schedule(200, 300)), ExistingItem("c", schedule(250, 450)))
        assertEquals(3, ConflictDetector.detect(list).size)
    }
    @Test fun matchingDuplicate() {
        val draft = schedule(time(15, 14), time(15, 15))
        val result = AssociationMatcher.suggest(draft, "竞赛培训提醒", listOf(ExistingItem("a", draft)))
        assertEquals("MERGE", result.type); assertEquals("a", result.targetId)
    }
    @Test fun repeatedSameNameAtDifferentTimeIsNew() {
        val old = schedule(time(15, 14), time(15, 15))
        val fresh = schedule(time(15, 19), time(15, 20))
        assertEquals("NEW", AssociationMatcher.suggest(fresh, "竞赛培训", listOf(ExistingItem("a", old))).type)
    }
    @Test fun updateAndCancel() {
        val old = schedule(time(15, 14), time(15, 15))
        val changed = schedule(time(16, 16), time(16, 17))
        assertEquals("UPDATE", AssociationMatcher.suggest(changed, "竞赛培训延期至后天", listOf(ExistingItem("a", old))).type)
        assertEquals("CANCEL", AssociationMatcher.suggest(old.copy(startAt = null, endAt = null), "竞赛培训取消", listOf(ExistingItem("a", old))).type)
    }
    @Test fun ambiguousAndUnrelatedChangesNotLinked() {
        val old = schedule(time(15, 14), time(15, 15))
        val list = listOf(ExistingItem("a", old), ExistingItem("b", old))
        assertNull(AssociationMatcher.suggest(old, "竞赛培训延期", list).targetId)
        assertNull(AssociationMatcher.suggest(old.copy(title = "班级会议"), "班级会议取消", list).targetId)
    }
    @Test fun invalidDraftRequiresCorrection() {
        assertNotNull(Draft(title = "").validationError())
        assertNotNull(schedule(200, 100).validationError())
        assertNotNull(Draft("作业", kind = "DEADLINE").validationError())
    }
}
