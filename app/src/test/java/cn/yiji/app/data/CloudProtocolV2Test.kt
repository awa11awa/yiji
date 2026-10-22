package cn.yiji.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cn.yiji.app.core.*
import cn.yiji.app.ml.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class CloudProtocolV2Test {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val at = ZonedDateTime.of(2026, 10, 17, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun raw(text: String) = RawMessage(title = "班级群", body = text, source = "测试", receivedAt = at)
    private fun ref(text: String) = JSONObject().put("text", text).put("sourceId", "current")
    private fun time(text: String, mode: String = "SET") = ref(text).put("mode", mode)
    private fun item(text: String, category: String = "活动", kind: String = "SCHEDULE", operation: String = "NEW") = JSONObject()
        .put("title", "通知事项").put("category", category).put("kind", kind).put("operation", operation).put("targetId", JSONObject.NULL).put("evidence", ref(text))
    private fun answer(vararg items: JSONObject, decision: String = "EXTRACT") = JSONObject().put("protocolVersion", 2)
        .put("decision", decision).put("items", JSONArray(items.toList())).toString()
    private fun parse(text: String, vararg items: JSONObject, candidates: List<Event> = emptyList()) =
        CloudExtractionClient.parse(answer(*items), ExtractionInput(raw(text), candidates = candidates), zone)

    @Test fun definiteNegativeIsIgnoredButIncompleteOrImportantNegativeNeedsReview() {
        val ignored = CloudExtractionClient.parse(answer(decision = "IGNORE"), ExtractionInput(raw("123")), zone)
        assertTrue(ignored.ignored); assertFalse(ignored.needsReview)
        listOf("课程安排调整，具体时间另行通知。", "通知正文尚未加载...").forEach {
            val result = CloudExtractionClient.parse(answer(decision = "IGNORE"), ExtractionInput(raw(it)), zone)
            assertFalse(result.ignored); assertTrue(result.needsReview)
        }
        val old = CloudExtractionClient.parse("{\"isSchedule\":false,\"items\":[]}", ExtractionInput(raw("收到，谢谢。")), zone)
        assertTrue(old.ignored)
    }
    @Test fun numericNoiseDoesNotLoadTheModelOrCallTheCloudButContextIsPreserved() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val hybrid = HybridScheduleExtractor(context, gate = MessageGate { error("Noise must be filtered first") })
        listOf("123", "000000", "。。。。。。", "收到，谢谢。").forEach { assertTrue(hybrid.extract(ExtractionInput(raw(it))).ignored) }
        assertFalse(NoticeSafety.isNoise("15:30", false))
        assertFalse(NoticeSafety.isNoise("123", true))
    }
    @Test fun registrationOpeningCannotBecomeADeadlineEvenInLegacyModelOutput() {
        val text = "收集表将于10月17日20:30开始，已设置填写份数限制，填写成功即视为报名成功。"
        val generated = item(text, "报名", "DEADLINE").put("due", time("10月17日20:30"))
        val draft = parse(text, generated).drafts.single()
        assertNull(draft.dueAt)
        assertEquals(LocalDateTime.of(2026, 10, 17, 20, 30), Instant.ofEpochMilli(draft.opensAt!!).atZone(zone).toLocalDateTime())
        assertEquals(listOf("open"), draft.reminderPoints().map { it.role })
        assertNull(draft.validationError())
    }
    @Test fun openingAndClosingProduceSeparateRolesInOneRegistrationTask() {
        val text = "报名于10月17日20:30开放，10月18日12:00截止。"
        val draft = parse(text, item(text, "报名", "DEADLINE").put("opens", time("10月17日20:30")).put("due", time("10月18日12:00"))).drafts.single()
        assertEquals(setOf("open", "due"), draft.reminderPoints().map { it.role }.toSet())
        assertTrue(draft.opensAt!! < draft.dueAt!!)
    }
    @Test fun ambiguousRegistrationTimeRequiresCorrectionBeforeConfirmation() {
        val text = "报名时间10月17日20:30。"
        val draft = parse(text, item(text, "报名", "DEADLINE").put("due", time("10月17日20:30"))).drafts.single()
        assertNotNull(draft.validationError()); assertTrue(draft.reminderPoints().isEmpty())
    }
    @Test fun pendingChangeClearsOldTimeInsteadOfKeepingAnOldReminderAnchor() {
        val text = "原定10月24日的体测调整，具体方案另行通知。"
        val existing = Event(title = "体测", category = "活动", kind = "SCHEDULE", startAt = at + 7 * 86400000, endAt = at + 7 * 86400000 + 3600000)
        val generated = item(text, operation = "UPDATE").put("targetId", existing.id)
            .put("start", time("另行通知", "TBD")).put("end", time("另行通知", "TBD"))
        val draft = parse(text, generated, candidates = listOf(existing)).drafts.single()
        assertNull(draft.startAt); assertNull(draft.endAt)
        assertEquals("TBD", draft.timing.start.precision)
        assertTrue(draft.reminderPoints().isEmpty()); assertNull(draft.validationError())
        assertNull(draft.resolvedAgainst(existing.draft()).startAt)
    }
    @Test fun legacyNullChangeCannotKeepSupersededCourseOrFitnessTimes() {
        val text = "因10月19日的课程调整至10月24日，原定10月24日大学城校区的体测将进行相应的调整，具体调整方案另行通知。"
        val course = Event(title = "课程", category = "课程", kind = "SCHEDULE", startAt = at, endAt = at + 3600000)
        val fitness = course.copy(id = newId(), title = "体测", category = "活动", startAt = at + 86400000)
        val a = item("10月19日的课程调整至10月24日", "课程", operation = "UPDATE").put("targetId", course.id)
        val b = item("原定10月24日大学城校区的体测将进行相应的调整", operation = "UPDATE").put("targetId", fitness.id)
        val result = parse(text, a, b, candidates = listOf(course, fitness))
        assertEquals(2, result.drafts.size)
        assertNull(result.drafts[0].startAt); assertEquals("2026-10-24", result.drafts[0].timing.start.date)
        assertNull(result.drafts[1].startAt); assertEquals("TBD", result.drafts[1].timing.start.precision)
    }
    @Test fun locationOnlyUpdateKeepsTimesWhenTargetIsChosenAfterParsing() {
        val text = "地点改为创新楼102，时间不变。"
        val generated = item(text, operation = "UPDATE").put("location", ref("创新楼102"))
        val draft = parse(text, generated).drafts.single()
        val target = Draft("培训", startAt = at + 3600000, endAt = at + 7200000)
        assertEquals(target.startAt, draft.resolvedAgainst(target).startAt)
        assertEquals(target.endAt, draft.resolvedAgainst(target).endAt)
    }
    @Test fun endClockUsesTheReplacementDateRatherThanTheOldEventDate() {
        val text = "课程调整至10月24日，16点结束，开始时刻另行通知。"
        val existing = Event(title = "课程", category = "课程", kind = "SCHEDULE", startAt = at, endAt = at + 3600000)
        val generated = item(text, "课程", operation = "UPDATE").put("targetId", existing.id)
            .put("start", time("10月24日")).put("end", time("16点"))
        val draft = parse(text, generated, candidates = listOf(existing)).drafts.single()
        assertNull(draft.startAt)
        assertEquals("2026-10-24", draft.timing.start.date)
        assertEquals(LocalDateTime.of(2026, 10, 24, 16, 0), Instant.ofEpochMilli(draft.endAt!!).atZone(zone).toLocalDateTime())
        assertTrue(draft.reminderPoints().isEmpty())
    }
    @Test fun endClockCannotReuseAnOldDateWhenTheNewStartIsPending() {
        val text = "体测改期，日期另行通知，16点结束。"
        val existing = Event(title = "体测", category = "活动", kind = "SCHEDULE", startAt = at, endAt = at + 3600000)
        val generated = item(text, operation = "UPDATE").put("targetId", existing.id)
            .put("start", time("另行通知", "TBD")).put("end", time("16点"))
        val draft = parse(text, generated, candidates = listOf(existing)).drafts.single()
        assertNull(draft.startAt); assertNull(draft.endAt)
        assertTrue(draft.reminderPoints().isEmpty())
    }
    @Test fun typographicalWhitespaceAndFullWidthColonMapBackToOriginalEvidence() {
        val text = "活动时间：2026年10月19日16：00\n活动内容：1.\u00a0梳理学习问题；2.\u00a0指导实践。"
        val quote = text.replace('\u00a0', ' ').replace('：', ':')
        val draft = parse(text, item(quote).put("start", time("2026年10月19日16:00"))).drafts.single()
        assertEquals(16, Instant.ofEpochMilli(draft.startAt!!).atZone(zone).hour)
        assertEquals("2026年10月19日16：00", draft.timing.start.text)
        assertEquals(draft.timing.start.text, text.substring(draft.timing.start.sourceStart, draft.timing.start.sourceEnd))
        assertNull(draft.endAt); assertNull(draft.validationError())
    }
    @Test fun alteredNumbersAndAmbiguousEvidenceCannotBeAccepted() {
        val text = "2026年10月19日16:00在102室开会。"
        val forged = parse(text, item(text).put("start", time("2026年10月19日18:00"))).drafts.single()
        assertNotNull(forged.validationError()); assertNull(forged.startAt)
        val repeated = parse("集合，集合", item("集合"))
        assertTrue(repeated.needsReview); assertTrue(repeated.drafts.isEmpty())
    }
    @Test fun invalidItemDoesNotDiscardOtherVerifiedItems() {
        val text = "2026年10月19日16:00开会，另有一份作业。"
        val result = parse(text, item("2026年10月19日16:00开会").put("start", time("2026年10月19日16:00")), item("不存在的事项"))
        assertEquals(1, result.drafts.size); assertTrue(result.needsReview); assertFalse(result.ignored)
    }
    @Test fun oneAssignmentKeepsIndependentAndOptionalSubtaskDeadlines() {
        val text = "第7周作业:\n预习U2。选词填空请于10月19日前完成。选做：阅读2篇。P.S.本周内完成U校园学习。"
        val tasks = JSONArray().put(JSONObject().put("title", "预习U2").put("evidence", ref("预习U2")))
            .put(JSONObject().put("title", "选词填空").put("due", time("10月19日前")).put("evidence", ref("选词填空请于10月19日前完成")))
            .put(JSONObject().put("title", "阅读2篇").put("optional", true).put("evidence", ref("选做：阅读2篇")))
            .put(JSONObject().put("title", "U校园学习").put("due", time("本周内")).put("evidence", ref("本周内完成U校园学习")))
        val result = parse(text, item(text, "作业", "DEADLINE").put("title", "第7周作业").put("subtasks", tasks))
        val draft = result.drafts.single()
        assertEquals(4, draft.subtasks.size); assertNull(draft.dueAt)
        assertEquals("NONE", draft.subtasks[0].due.precision)
        assertEquals("2026-10-19", draft.subtasks[1].due.date)
        assertTrue(draft.subtasks[2].optional); assertEquals("NONE", draft.subtasks[2].due.precision)
        assertEquals("2026-10-18", draft.subtasks[3].due.date)
        assertTrue(draft.reminderPoints().isEmpty()); assertNull(draft.validationError())
    }
    @Test fun repeatedAssignmentHeadingCanRecoverFlatLegacyResults() {
        val text = "第7周作业:\n预习U2。完成选词填空，10月19日前提交。"
        val a = item("预习U2", "作业", "DEADLINE").put("title", "第7周作业：预习U2")
        val b = item("完成选词填空，10月19日前提交", "作业", "DEADLINE").put("title", "第7周作业：选词填空").put("due", time("10月19日前"))
        val draft = parse(text, a, b).drafts.single()
        assertEquals("第7周作业", draft.title); assertEquals(2, draft.subtasks.size); assertNull(draft.dueAt)
    }
    @Test fun academicWeekIsNotConvertedToANearbyCalendarTuesday() {
        val text = "第8周周二小测，题型见10月15日通知。"
        val draft = parse(text, item(text, "考试").put("start", time("第8周周二"))).drafts.single()
        assertNull(draft.startAt); assertEquals("第8周周二", draft.timing.start.text)
    }
    @Test fun missingCourseInAPairedChangeRemainsPendingForReview() {
        val text = "因10月19日的课程调整至10月24日，原定10月24日大学城校区的体测将进行相应的调整，具体调整方案另行通知。"
        val result = parse(text, item("体测将进行相应的调整，具体调整方案另行通知").put("title", "体测调整"))
        assertEquals(1, result.drafts.size); assertTrue(result.needsReview)
    }
}
