package cn.yiji.app.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class RuleDraftsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val received = LocalDate.of(2026, 10, 19).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private fun extract(text: String) = RuleDrafts.extract(text, received, zone = zone)

    @Test fun activityAndDeadlineStaySeparateEvenOnOneLine() {
        val drafts = extract("【培训通知】活动时间：10月24日18:30 报名截止：10月22日20:00 地点：创新楼102 参会对象：全体同学")
        assertEquals(2, drafts.size)
        val schedule = drafts.single { it.kind == "SCHEDULE" }
        val deadline = drafts.single { it.kind == "DEADLINE" }
        assertEquals("创新楼102", schedule.location)
        assertTrue(schedule.startAt!! > deadline.dueAt!!)
        assertNull(schedule.dueAt); assertNull(deadline.startAt)
    }

    @Test fun aDeadlineIsNeverReusedAsTheActivityStart() {
        val drafts = extract("【报名通知】报名截止时间：10月22日20:00")
        assertEquals(1, drafts.size); assertEquals("DEADLINE", drafts.single().kind)
        assertNotNull(drafts.single().dueAt); assertNull(drafts.single().startAt)
    }

    @Test fun openingAndDeadlineRemainIndependentReminderPoints() {
        val draft = extract("【竞赛报名】报名开放时间：10月20日12:00\n报名截止时间：10月22日20:00").single()
        assertNotNull(draft.opensAt); assertNotNull(draft.dueAt)
        assertTrue(draft.opensAt!! < draft.dueAt!!)
        assertEquals(setOf("open", "due"), draft.reminderPoints().map { it.role }.toSet())
    }

    @Test fun dateOnlyEvidenceIsKeptWithoutInventingAClock() {
        val draft = extract("【班会通知】活动时间：10月24日\n地点：创新楼102").single()
        assertNull(draft.startAt); assertEquals("DATE", draft.timing.start.precision)
        assertEquals("2026-10-24", draft.timing.start.date)
        assertTrue(draft.reminderPoints().isEmpty()); assertNull(draft.validationError())
    }

    @Test fun unknownFieldsDoNotBlockSaving() {
        val draft = extract("【事项通知】具体安排另行通知").single()
        assertNull(draft.startAt); assertEquals("", draft.location)
        assertNull(draft.validationError()); assertTrue(draft.reminderPoints().isEmpty())
    }

    @Test fun supplementalRulesKeepKnownOrExplicitlyPendingFields() {
        val original = Draft("云端标题", startAt = 1_900_000_000_000, location = "云端地点",
            timing = Timing(start = TimeField.exact(), end = TimeField.pending()))
        val result = RuleDrafts.supplement(original, "【通知】活动时间：10月24日18:30至19:30\n地点：创新楼102", received)
        assertEquals(original.title, result.title); assertEquals(original.startAt, result.startAt)
        assertEquals(original.location, result.location); assertEquals(original.timing.end, result.timing.end)
        assertNull(result.endAt)
    }

    @Test fun supplementalRulesFillOnlyMissingFields() {
        val result = RuleDrafts.supplement(Draft("云端标题"), "【通知】活动时间：10月24日18:30\n地点：创新楼102", received)
        assertEquals("云端标题", result.title); assertEquals("创新楼102", result.location)
        assertNotNull(result.startAt); assertEquals("EXACT", result.timing.start.precision)
    }

    @Test fun twentyFourHundredMeansTheNextMidnight() {
        val issues = mutableListOf<String>()
        val at = DateTimeResolver.resolve("10月24日24:00", LocalDate.of(2026, 10, 19), issues, zone)
        assertEquals(LocalDate.of(2026, 10, 25).atStartOfDay(zone).toInstant().toEpochMilli(), at)
    }
}
