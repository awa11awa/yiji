package cn.yiji.app.platform

import cn.yiji.app.core.ReminderPoint
import cn.yiji.app.data.Event
import org.junit.Assert.*
import org.junit.Test

class ReminderTextTest {
    @Test fun remindersContainOnlyTimePlaceAndShortSummary() {
        val event = Event(title = "这是一个较长的事项标题".repeat(8), category = "会议", kind = "SCHEDULE",
            location = "会议室A", note = "不应该出现的通知原文", action = "不应该出现的冗长要求")
        val text = ReminderText.body(event, ReminderPoint("start", 1_900_000_000_000, "即将开始"))
        assertTrue(text.contains("会议室A")); assertTrue(text.length < 110)
        assertEquals(2, text.count { it == '·' }); assertFalse(text.contains("不应该出现"))
    }
    @Test fun unknownFieldsAndDeadlineRolesAreExplicit() {
        val event = Event(title = "填写报名表", category = "报名", kind = "DEADLINE")
        assertTrue(ReminderText.body(event).startsWith("未知时间 · 未知地址 · "))
        assertTrue(ReminderText.body(event, ReminderPoint("open", 1_900_000_000_000, "开放")).contains("报名开放"))
        assertNull(ReminderText.next(listOf(event)))
    }
}
