package cn.yiji.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cn.yiji.app.ml.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class CloudExtractionTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val at = ZonedDateTime.of(2026, 10, 18, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun raw(body: String) = RawMessage(title = "班级群", body = body, source = "微信", receivedAt = at)
    private fun ref(text: String, source: String = "current") = JSONObject().put("text", text).put("sourceId", source)
    private fun item(evidence: String, kind: String = "SCHEDULE", operation: String = "NEW") = JSONObject()
        .put("title", "班级安排").put("category", "会议").put("kind", kind).put("operation", operation)
        .put("targetId", JSONObject.NULL).put("evidence", ref(evidence))
    private fun answer(vararg items: JSONObject) = JSONObject().put("isSchedule", true).put("items", JSONArray(items.toList())).toString()
    private fun response(content: String) = JSONObject().put("choices", JSONArray().put(JSONObject()
        .put("finish_reason", "stop").put("message", JSONObject().put("content", content)))).toString()
    @Test fun separateItemsKeepIndependentCategoriesOperationsAndTargets() {
        val text = "明晚20:00前交实验报告，后天14:00至15:00参加讲座，原定班会取消。"
        val existing = Event(title = "班会", category = "会议", kind = "SCHEDULE", startAt = at, endAt = at + 3600000)
        val homework = item("明晚20:00前交实验报告", "DEADLINE").put("category", "作业").put("due", ref("明晚20:00"))
        val lecture = item("后天14:00至15:00参加讲座").put("category", "活动").put("start", ref("后天14:00")).put("end", ref("15:00"))
        val cancel = item("原定班会取消", operation = "CANCEL").put("targetId", existing.id)
        val result = CloudExtractionClient.parse(answer(homework, lecture, cancel), ExtractionInput(raw(text), candidates = listOf(existing)), zone)
        assertEquals(listOf("作业", "活动", "会议"), result.drafts.map { it.category })
        assertEquals(listOf("NEW", "NEW", "CANCEL"), result.operations)
        assertEquals(existing.id, result.targetIds.last())
        assertEquals(3600000L, result.drafts[1].endAt!! - result.drafts[1].startAt!!)
        assertEquals(19, Instant.ofEpochMilli(result.drafts[0].dueAt!!).atZone(zone).dayOfMonth)
    }
    @Test fun missingOrFabricatedTimesRemainEmpty() {
        val input = ExtractionInput(raw("会议时间另行通知"))
        val result = CloudExtractionClient.parse(answer(item("会议时间另行通知").put("start", ref("明天14:00"))), input, zone)
        assertNull(result.drafts.single().startAt)
        assertNull(result.drafts.single().endAt)
        assertTrue(result.drafts.single().issues.any { it.contains("原文依据") })
    }
    @Test fun dateWithoutClockDoesNotBecome2359OrAnAllDayEvent() {
        val input = ExtractionInput(raw("请在10月25日前提交材料"))
        val result = CloudExtractionClient.parse(answer(item("请在10月25日前提交材料", "DEADLINE").put("due", ref("10月25日"))), input, zone)
        assertNull(result.drafts.single().dueAt)
    }
    @Test fun unknownTargetCannotMutateAnExistingEvent() {
        val input = ExtractionInput(raw("原定班会取消"))
        assertTrue(runCatching { CloudExtractionClient.parse(answer(item("原定班会取消", operation = "CANCEL").put("targetId", "invented-id")), input, zone) }.isFailure)
    }
    @Test fun historicalMessageCannotBeReturnedAsANewCurrentEvent() {
        val input = ExtractionInput(raw("收到谢谢"), context = listOf(raw("明天14:00开班会")))
        val generated = item("明天14:00开班会").put("evidence", ref("明天14:00开班会", input.context.single().id))
        assertTrue(runCatching { CloudExtractionClient.parse(answer(generated), input, zone) }.isFailure)
    }
    @Test fun locationOnlyUpdatePreservesExistingTimes() {
        val existing = Event(title = "班会", category = "会议", kind = "SCHEDULE", startAt = at + 86400000, endAt = at + 90000000, location = "301")
        val input = ExtractionInput(raw("地点改为图书馆302，时间不变"), candidates = listOf(existing))
        val generated = item("地点改为图书馆302，时间不变", operation = "UPDATE").put("targetId", existing.id).put("location", ref("图书馆302"))
        val draft = CloudExtractionClient.parse(answer(generated), input, zone).drafts.single()
        assertEquals(existing.startAt, draft.startAt); assertEquals(existing.endAt, draft.endAt)
        assertEquals("图书馆302", draft.location)
    }
    @Test fun dateOnlyUpdatePreservesClockAndDurationWithVisibleIssue() {
        val start = ZonedDateTime.of(2026, 10, 19, 14, 0, 0, 0, zone).toInstant().toEpochMilli()
        val existing = Event(title = "班会", category = "会议", kind = "SCHEDULE", startAt = start, endAt = start + 3600000)
        val input = ExtractionInput(raw("改到周五，地点不变"), candidates = listOf(existing))
        val generated = item("改到周五，地点不变", operation = "UPDATE").put("targetId", existing.id).put("start", ref("周五"))
        val draft = CloudExtractionClient.parse(answer(generated), input, zone).drafts.single()
        val changed = Instant.ofEpochMilli(draft.startAt!!).atZone(zone)
        assertEquals(23, changed.dayOfMonth); assertEquals(14, changed.hour)
        assertEquals(3600000L, draft.endAt!! - draft.startAt!!)
        assertTrue(draft.issues.any { it.contains("沿用") })
    }
    @Test fun relativeTimeInContextUsesThatMessagesReceptionDate() {
        val previous = raw("明天14:00开班会").copy(receivedAt = at - 86400000)
        val input = ExtractionInput(raw("请按上条时间到场"), context = listOf(previous))
        val generated = item("请按上条时间到场").put("start", ref("明天14:00", previous.id))
        val draft = CloudExtractionClient.parse(answer(generated), input, zone).drafts.single()
        assertEquals(18, Instant.ofEpochMilli(draft.startAt!!).atZone(zone).dayOfMonth)
    }
    @Test fun invalidJsonAndTooManyItemsAreRejected() {
        assertTrue(runCatching { CloudExtractionClient.parse("not json", ExtractionInput(raw("开会"))) }.isFailure)
        assertTrue(runCatching { CloudExtractionClient.parse(answer(*Array(21) { item("开会") }), ExtractionInput(raw("开会"))) }.isFailure)
    }
    @Test fun chatIsNeverSentToTheCloud() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("yiji-api-profiles", Context.MODE_PRIVATE).edit().clear().commit()
        val store = ApiProfileStore(context, MemoryApiSecrets())
        store.save(ApiProfile.glm, "fixture-key"); store.enable(true)
        var calls = 0
        val client = CloudExtractionClient { _, _, _ -> calls++; error("must not call") }
        val hybrid = HybridScheduleExtractor(context, store, client, MessageGate { GatePrediction(false, .001, false) })
        assertTrue(hybrid.extract(ExtractionInput(raw("晚饭吃什么"))).ignored)
        assertEquals(0, calls)
    }
    @Test fun candidateWithoutKeyWaitsForConfiguration() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("yiji-api-profiles", Context.MODE_PRIVATE).edit().clear().commit()
        val hybrid = HybridScheduleExtractor(context, ApiProfileStore(context, MemoryApiSecrets()), gate = MessageGate { GatePrediction(true, .99, false) })
        val error = runCatching { hybrid.extract(ExtractionInput(raw("明天开会"))) }.exceptionOrNull() as ExtractionFailure
        assertTrue(error.waitingForApi)
    }
    @Test fun explicitReviewCanBypassTheLocalChatDecision() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("yiji-api-profiles", Context.MODE_PRIVATE).edit().clear().commit()
        val store = ApiProfileStore(context, MemoryApiSecrets())
        store.save(ApiProfile.glm, "fixture-key"); store.enable(true)
        var calls = 0
        val client = CloudExtractionClient { url, key, body ->
            calls++; assertTrue(url.endsWith("/chat/completions")); assertEquals("fixture-key", key)
            assertEquals("glm-4.7-flash", JSONObject(body!!).getString("model"))
            assertTrue(JSONObject(body).getBoolean("stream"))
            assertEquals("disabled", JSONObject(body).getJSONObject("thinking").getString("type"))
            ApiResponse(200, response("{\"isSchedule\":false,\"items\":[]}"))
        }
        val hybrid = HybridScheduleExtractor(context, store, client, MessageGate { GatePrediction(false, .001, false) })
        hybrid.extract(ExtractionInput(raw("这条需要复核"), forceCloud = true))
        assertEquals(1, calls)
    }
    @Test fun rateLimitErrorsAreRetryableAndNeverExposeResponseSecrets() = runBlocking {
        val client = CloudExtractionClient { _, _, _ -> ApiResponse(429, "fixture-sensitive-server-body") }
        val failure = runCatching { client.test(ApiProfile.glm, "fixture-key") }.exceptionOrNull() as ExtractionFailure
        assertTrue(failure.retryable); assertFalse(failure.message!!.contains("fixture-sensitive"))
    }
    @Test fun connectionTestActuallyRequestsModelOutputAndModelListCanBeRead() = runBlocking {
        val client = CloudExtractionClient { url, _, body ->
            if (body == null) { assertTrue(url.endsWith("/models")); ApiResponse(200, "{\"data\":[{\"id\":\"model-b\"},{\"id\":\"model-a\"}]}") }
            else ApiResponse(200, response("{\"ok\":true}"))
        }
        assertTrue(client.test(ApiProfile.glm, "fixture-key").contains("成功"))
        assertEquals(listOf("model-a", "model-b"), client.models(ApiProfile.glm, "fixture-key"))
    }

    @Test fun cloudNegativeKeepsLectureAndScheduleChangeForReview() {
        listOf("【关于开展第四期数智创业大讲坛的通知】各位同学：主题：什么样的人生才幸福，讲座内容：...",
            "因10月19日的课程调整至10月24日，原定10月24日大学城校区的体测将进行相应的调整，具体调整方案另行通知。")
            .forEach { text ->
                val result = CloudExtractionClient.parse("{\"isSchedule\":false,\"items\":[]}", ExtractionInput(raw(text)))
                assertFalse(result.ignored); assertTrue(result.needsReview); assertTrue(result.drafts.isEmpty())
            }
    }

    @Test fun lectureWithNoTimeStillProducesAReviewableDraft() {
        val text = "【关于开展第四期数智创业大讲坛的通知】主题：什么样的人生才幸福"
        val result = CloudExtractionClient.parse(answer(item(text).put("category", "活动")), ExtractionInput(raw(text)))
        assertEquals(1, result.drafts.size)
        assertNull(result.drafts.single().startAt); assertNull(result.drafts.single().endAt)
        assertTrue(result.drafts.single().issues.isNotEmpty())
    }

    @Test fun courseAndFitnessChangesCanBeKeptSeparatelyWithoutInventingNewFitnessTime() {
        val text = "因10月19日的课程调整至10月24日，原定10月24日大学城校区的体测将进行相应的调整，具体调整方案另行通知。"
        val course = item("10月19日的课程调整至10月24日", operation = "UPDATE").put("category", "课程")
            .put("title", "课程调整").put("start", ref("10月24日"))
        val fitness = item("原定10月24日大学城校区的体测将进行相应的调整", operation = "UPDATE").put("category", "活动").put("title", "体测安排调整")
        val result = CloudExtractionClient.parse(answer(course, fitness), ExtractionInput(raw(text)))
        assertEquals(listOf("课程", "活动"), result.drafts.map { it.category })
        assertEquals(listOf("UPDATE", "UPDATE"), result.operations)
        assertTrue(result.drafts.all { it.startAt == null && it.endAt == null })
        assertTrue(result.targetIds.all { it == null })
        assertTrue(result.drafts.all { it.issues.any { issue -> issue.contains("关联事项") } })
    }

    @Test fun localChatPredictionCannotDiscardFormalLectureOrAdjustment() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("yiji-api-profiles", Context.MODE_PRIVATE).edit().clear().commit()
        val store = ApiProfileStore(context, MemoryApiSecrets())
        store.save(ApiProfile.glm, "fixture-key"); store.enable(true)
        var calls = 0
        val client = CloudExtractionClient { _, _, _ -> calls++; ApiResponse(200, response("{\"isSchedule\":false,\"items\":[]}")) }
        val hybrid = HybridScheduleExtractor(context, store, client, MessageGate { GatePrediction(false, .001, false) })
        listOf("关于开展第四期数智创业大讲坛的通知，各位同学：讲座内容：...", "原定10月24日体测调整，具体方案另行通知。")
            .forEach { assertTrue(hybrid.extract(ExtractionInput(raw(it))).needsReview) }
        assertEquals(2, calls)
        assertTrue(hybrid.extract(ExtractionInput(raw("晚饭吃什么").copy(title = "课程通知群"))).ignored)
        assertEquals(2, calls)
    }

    @Test fun truncatedLocalChatIsKeptWithoutAutomaticallySendingPrivateChat() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val hybrid = HybridScheduleExtractor(context, gate = MessageGate { GatePrediction(false, .001, false) })
        val result = hybrid.extract(ExtractionInput(raw("我们刚才聊到...")))
        assertTrue(result.needsReview); assertFalse(result.ignored)
    }

    @Test fun providerRetryAfterIsPropagatedWithoutExposingItsBody() = runBlocking {
        val client = CloudExtractionClient { _, _, _ -> ApiResponse(429, "sensitive-details", 120_000) }
        val failure = runCatching { client.test(ApiProfile.glm, "fixture-key") }.exceptionOrNull() as ExtractionFailure
        assertEquals(120_000L, failure.retryAfterMillis)
        assertFalse(failure.userMessage.contains("sensitive"))
    }
}
