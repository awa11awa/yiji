package cn.yiji.app.ml

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class DraftAssemblerTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val receivedAt = ZonedDateTime.of(2026, 10, 16, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun entity(text: String, value: String, label: String) = text.indexOf(value).let { ModelEntity(label, it, it + value.length, value, .99) }
    @Test fun independentScheduleAndDeadlineAndBackgroundDate() {
        val text = "后台启动日期10月11日。音乐会于10月23日18:00至10月30日20:00在艺术中心举行，报名截至今晚23.52。"
        val prediction = ModelPrediction("活动", "MIXED", "KNOWN", "NEW", .99, listOf(
            entity(text, "10月11日", "REFERENCE_TIME"), entity(text, "音乐会", "EVENT"),
            entity(text, "10月23日18:00", "START_TIME"), entity(text, "10月30日20:00", "END_TIME"),
            entity(text, "艺术中心", "LOCATION"), entity(text, "今晚23.52", "DUE_TIME")))
        val drafts = DraftAssembler.assemble(prediction, text, "通知", receivedAt, zone)
        assertEquals(2, drafts.size)
        assertEquals("SCHEDULE", drafts[0].kind); assertEquals("DEADLINE", drafts[1].kind)
        assertEquals(23, Instant.ofEpochMilli(drafts[0].startAt!!).atZone(zone).dayOfMonth)
        assertEquals(30, Instant.ofEpochMilli(drafts[0].endAt!!).atZone(zone).dayOfMonth)
        assertEquals(LocalTime.of(23, 52), Instant.ofEpochMilli(drafts[1].dueAt!!).atZone(zone).toLocalTime())
        assertEquals("艺术中心", drafts[0].location)
    }
    @Test fun bookingDoesNotUseLaunchDate() {
        val text = "体测10月11日启动，请预约个人场次。"
        val prediction = ModelPrediction("考试", "SCHEDULE", "BOOKING_REQUIRED", "NEW", .99, listOf(entity(text, "体测", "EVENT"), entity(text, "10月11日", "REFERENCE_TIME")))
        val draft = DraftAssembler.assemble(prediction, text, "通知", receivedAt, zone).single()
        assertNull(draft.startAt); assertNull(draft.endAt); assertTrue(draft.issues.any { "预约" in it })
    }
    @Test fun pendingInterviewIsKeptBesideSubmissionDeadline() {
        val text = "干部纳新面试，材料提交于10月18日20:00前，面试安排另行通知。"
        val prediction = ModelPrediction("报名", "MIXED", "TO_BE_ANNOUNCED", "NEW", .99,
            listOf(entity(text, "干部纳新面试", "EVENT"), entity(text, "10月18日20:00", "DUE_TIME")))
        val drafts = DraftAssembler.assemble(prediction, text, "", receivedAt, zone)
        assertEquals(2, drafts.size); assertNotNull(drafts.single { it.kind == "DEADLINE" }.dueAt)
        assertNull(drafts.single { it.kind == "SCHEDULE" }.startAt)
    }
    @Test fun categoryComesFromTheModelEvenWhenTextContainsOtherCategories() {
        val text = "广告设计作业群：明天讨论考试材料。"
        val draft = DraftAssembler.assemble(ModelPrediction("会议", "SCHEDULE", "KNOWN", "NEW", .99, emptyList()), text, "作业群", receivedAt, zone).single()
        assertEquals("会议", draft.category); assertEquals("SCHEDULE", draft.kind)
    }
}
