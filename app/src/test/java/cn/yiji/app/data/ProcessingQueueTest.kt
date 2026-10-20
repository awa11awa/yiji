package cn.yiji.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.yiji.app.core.Draft
import cn.yiji.app.ml.Extraction
import cn.yiji.app.ml.ScheduleExtractor
import cn.yiji.app.platform.ReminderScheduler
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ProcessingQueueTest {
    private lateinit var context: Context
    private lateinit var database: YijiDatabase
    private val draft = Draft("提交报告", "作业", "DEADLINE", dueAt = 1900000000000)
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("yiji-settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("yiji-api-profiles", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, YijiDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun close() { database.close() }
    private fun repo(extractor: ScheduleExtractor) = Repository(database, Preferences(context), ReminderScheduler(context), extractor)
    @Test fun enqueuePersistsRawBeforeAnyModelCall() = runBlocking {
        var calls = 0
        val repository = repo { _, _, _ -> calls++; Extraction(listOf(draft)) }
        val result = repository.enqueueWithResult("课程群", "明晚交报告", "微信")
        assertEquals(IngestDisposition.QUEUED, result.disposition)
        assertNotNull(repository.dao.raw(result.id)); assertEquals("QUEUED", repository.dao.job(result.id)!!.state)
        assertEquals(0, calls)
        repository.processQueued()
        assertEquals(1, calls); assertEquals(1, repository.dao.allSuggestions().size)
    }
    @Test fun slowInferenceDoesNotHoldTheEditingLockAndCancellationIsRecoverable() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val repository = repo { _, _, _ ->
            if (++calls == 1) { entered.complete(Unit); release.await() }
            Extraction(listOf(draft))
        }
        val processing = launch { repository.ingest("通知", "明晚交报告", "微信") }
        entered.await()
        val raw = repository.dao.allRaw().single()
        assertEquals("PROCESSING", repository.dao.job(raw.id)!!.state)
        withTimeout(2000) { repository.save(draft.copy(title = "手动事项"), -1) }
        processing.cancelAndJoin()
        repository.dao.recoverJobs()
        repository.processQueued()
        assertEquals("DONE", repository.dao.job(raw.id)!!.state)
        assertEquals(1, repository.dao.allRaw().size)
        assertEquals(1, repository.dao.allSuggestions().size)
    }
    @Test fun missingApiAndRateLimitsHaveDifferentDurableStates() = runBlocking {
        val waiting = repo { _, _, _ -> throw ExtractionFailure("请先配置 API", waitingForApi = true) }
        val id = waiting.ingest("通知", "明晚交报告", "微信")
        assertEquals("WAITING_API", waiting.dao.job(id)!!.state)
        val retrying = repo { _, _, _ -> throw ExtractionFailure("服务限流", retryable = true) }
        retrying.retry(id)
        assertEquals("RETRY", retrying.dao.job(id)!!.state)
        assertTrue(retrying.dao.job(id)!!.nextAttemptAt > System.currentTimeMillis())
        assertNotNull(retrying.dao.raw(id))
    }
    @Test fun switchingAProviderCanResumeSavedCandidates() = runBlocking {
        val waiting = repo { _, _, _ -> throw ExtractionFailure("请先配置 API", waitingForApi = true) }
        val id = waiting.ingest("通知", "明晚交报告", "微信")
        val ready = repo { _, _, _ -> Extraction(listOf(draft)) }
        assertEquals(1, ready.resumeWaiting())
        ready.processQueued()
        assertEquals("DONE", ready.dao.job(id)!!.state)
        assertEquals(1, ready.dao.allSuggestions().size)
    }
    @Test fun repeatedQueueProcessingCannotDuplicateDrafts() = runBlocking {
        var calls = 0
        val repository = repo { _, _, _ -> calls++; Extraction(listOf(draft)) }
        repository.enqueueWithResult("通知", "明晚交报告", "微信", "test.app")
        repository.enqueueWithResult("通知", "明晚交报告", "微信", "test.app")
        repository.processQueued(); repository.processQueued()
        assertEquals(1, calls); assertEquals(1, repository.dao.allRaw().size)
        assertEquals(1, repository.dao.allSuggestions().size)
    }
    @Test fun deletingRawDuringAnInflightCallDoesNotResurrectIt() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = repo { _, _, _ -> entered.complete(Unit); release.await(); Extraction(listOf(draft)) }
        val processing = launch { repository.ingest("通知", "明晚交报告", "微信") }
        entered.await()
        repository.deleteRaw(repository.dao.allRaw().single().id)
        release.complete(Unit); processing.join()
        assertTrue(repository.dao.allRaw().isEmpty()); assertTrue(repository.dao.allSuggestions().isEmpty())
        assertTrue(repository.dao.allJobs().isEmpty())
    }
    @Test fun contextIsLimitedToTheSameAppTitleAndPastReceptionTimes() = runBlocking {
        val store = ApiProfileStore(context, MemoryApiSecrets())
        val collected = mutableListOf<ExtractionInput>()
        val extractor = object : cn.yiji.app.ml.ContextualScheduleExtractor {
            override suspend fun extract(input: ExtractionInput): Extraction {
                collected += input; return Extraction(listOf(draft))
            }
        }
        val repository = Repository(database, Preferences(context), ReminderScheduler(context), extractor, store)
        val at = System.currentTimeMillis()
        val prior = RawMessage(title = "A群", body = "过去同群", source = "微信", packageName = "wechat", receivedAt = at - 1000)
        database.dao().putRaw(prior)
        database.dao().putJob(MessageJob(prior.id, state = "DONE", candidate = true))
        database.dao().putRaw(RawMessage(title = "A群", body = "同群普通聊天", source = "微信", packageName = "wechat", state = "IGNORED", receivedAt = at - 1000))
        database.dao().putRaw(RawMessage(title = "B群", body = "其他群隐私", source = "微信", packageName = "wechat", receivedAt = at - 1000))
        database.dao().putRaw(RawMessage(title = "A群", body = "其他应用隐私", source = "其他", packageName = "other", receivedAt = at - 1000))
        database.dao().putRaw(RawMessage(title = "A群", body = "未来消息", source = "微信", packageName = "wechat", receivedAt = at + 1000))
        repository.ingestWithResult("A群", "改到周五", "微信", "wechat", at)
        assertEquals(listOf("过去同群"), collected.single().context.map { it.body })
    }
    @Test fun apiConfigurationIsNotIncludedInBackup() = runBlocking {
        val store = ApiProfileStore(context, MemoryApiSecrets())
        store.save(ApiProfile.glm, "fixture-key-not-for-backup")
        val repository = Repository(database, Preferences(context), ReminderScheduler(context),
            ScheduleExtractor { _, _, _ -> Extraction(listOf(draft)) }, store)
        repository.ingest("通知", "明晚交报告", "微信")
        val backup = repository.export()
        assertFalse(backup.contains("fixture-key-not-for-backup")); assertFalse(backup.contains("open.bigmodel.cn"))
    }

    @Test fun uncertainCloudResultStaysPendingWithoutAutomaticRepeatedCalls() = runBlocking {
        var calls = 0
        val repository = repo { _, _, _ -> calls++; Extraction(emptyList(), needsReview = true, info = "待核对") }
        val id = repository.ingest("讲坛通知", "学院将举办数智创业大讲坛，具体时间另行通知", "微信")
        repository.processQueued()
        assertEquals("PENDING", repository.dao.raw(id)!!.state)
        assertEquals("DONE", repository.dao.job(id)!!.state)
        assertTrue(repository.dao.allSuggestions().isEmpty()); assertEquals(1, calls)
    }

    @Test fun repeatedRetryCannotReplaceAnInflightJobOrCreateDuplicateDrafts() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var calls = 0
        val repository = repo { _, _, _ -> calls++; entered.complete(Unit); release.await(); Extraction(listOf(draft)) }
        val processing = launch { repository.ingest("通知", "明晚交报告", "微信") }
        entered.await()
        val id = repository.dao.allRaw().single().id
        assertTrue(runCatching { repository.enqueueRetry(id, forceCloud = true) }.isFailure)
        assertTrue(runCatching { repository.completeBody(id, "通知", "完整正文") }.isFailure)
        assertEquals("PROCESSING", repository.dao.job(id)!!.state)
        release.complete(Unit); processing.join()
        assertEquals(1, calls); assertEquals(1, repository.dao.allSuggestions().size)
    }

    @Test fun retryAfterAndAttemptLimitAreDurable() = runBlocking {
        var calls = 0
        val repository = repo { _, _, _ -> calls++; throw ExtractionFailure("服务限流", retryable = true, retryAfterMillis = 120_000) }
        val before = System.currentTimeMillis()
        val id = repository.ingest("通知", "明晚交报告", "微信")
        assertTrue(repository.dao.job(id)!!.nextAttemptAt >= before + 120_000)
        repeat(2) {
            repository.dao.putJob(repository.dao.job(id)!!.copy(nextAttemptAt = 0))
            repository.processQueued()
        }
        repository.processQueued()
        assertEquals(3, calls); assertEquals("FAILED", repository.dao.job(id)!!.state)
        assertEquals("PENDING", repository.dao.raw(id)!!.state)
        assertTrue(repository.dao.raw(id)!!.info.contains("自动重试已停止"))
    }

    @Test fun completingBodyReusesTheOriginalRecordAndDiscardsUnconfirmedDrafts() = runBlocking {
        val seen = mutableListOf<String>()
        val repository = repo { _, body, _ -> seen += body; Extraction(listOf(draft)) }
        val id = repository.ingest("讲坛通知", "摘要...", "微信", "wechat")
        val at = repository.dao.raw(id)!!.receivedAt
        repository.completeBody(id, "完整讲坛通知", "明天14点在图书馆参加数智创业大讲坛，这是正文末尾")
        assertEquals(1, repository.dao.allRaw().size)
        assertTrue(repository.dao.allSuggestions().isEmpty())
        assertEquals(at, repository.dao.raw(id)!!.receivedAt)
        assertTrue(repository.dao.job(id)!!.forceCloud)
        repository.processQueued()
        assertTrue(seen.last().endsWith("这是正文末尾")); assertEquals(1, repository.dao.allSuggestions().size)
        val suggestion = repository.dao.allSuggestions().single()
        repository.confirm(suggestion.id, suggestion.draft(), "NEW", null, -1)
        assertTrue(runCatching { repository.completeBody(id, "新标题", "正文") }.isFailure)
        assertTrue(repository.dao.raw(id)!!.body.endsWith("这是正文末尾"))
    }

    @Test fun upgradeRescuesAutomaticIgnoresAndLeavesManualDecisionsAlone() = runBlocking {
        val repository = repo { _, _, _ -> error("rescue must not call the provider") }
        val cloud = RawMessage(title = "通知", body = "讲坛通知", source = "微信", state = "IGNORED", info = "云端复核为聊天或非待办，原文已保留 · 智谱")
        val local = cloud.copy(id = newId(), body = "原定体测将调整，方案另行通知", info = "TinyBERT 二分类：聊天或非日程消息，原文已保留")
        val chat = local.copy(id = newId(), body = "晚饭吃什么")
        val manual = cloud.copy(id = newId(), info = "已由你忽略，原文保留")
        listOf(cloud, local, chat, manual).forEach { repository.dao.putRaw(it) }
        assertEquals(2, repository.recoverMisclassifiedNotices())
        assertEquals("PENDING", repository.dao.raw(cloud.id)!!.state)
        assertEquals("PENDING", repository.dao.raw(local.id)!!.state)
        assertEquals("IGNORED", repository.dao.raw(chat.id)!!.state)
        assertEquals("IGNORED", repository.dao.raw(manual.id)!!.state)
        assertEquals(0, repository.recoverMisclassifiedNotices())
        repository.dismiss(cloud.id)
        assertEquals("IGNORED", repository.dao.raw(cloud.id)!!.state)
        assertEquals(0, repository.recoverMisclassifiedNotices())
    }

    @Test fun manualSuggestionCanBeConfirmedWithOriginalSourceAndNoModel() = runBlocking {
        val repository = repo { _, _, _ -> error("manual entry must not call the provider") }
        val id = repository.enqueueWithResult("通知", "讲坛内容摘要...", "微信").id
        val suggestion = repository.manualSuggestion(id)
        assertNull(repository.dao.job(id))
        assertEquals("讲坛内容摘要...", repository.dao.suggestion(suggestion)!!.note)
        val event = repository.confirm(suggestion, draft, "NEW", null, -1)
        assertEquals(event, repository.dao.allLinks().single().eventId)
        assertEquals(id, repository.dao.allLinks().single().rawId)
        assertEquals("CONFIRMED", repository.dao.raw(id)!!.state)
    }
}
