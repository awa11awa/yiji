package cn.yiji.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.yiji.app.core.Draft
import cn.yiji.app.ml.Extraction
import cn.yiji.app.ml.ScheduleExtractor
import cn.yiji.app.platform.ReminderScheduler
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ModelFlowTest {
    private lateinit var context: Context
    private lateinit var database: YijiDatabase
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("yiji-settings", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, YijiDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun teardown() { database.close() }
    private fun repository(extractor: ScheduleExtractor) = Repository(database, Preferences(context), ReminderScheduler(context), extractor)
    private val draft = Draft("模型输出事项", "课程", "DEADLINE", dueAt = 1_900_000_000_000)

    @Test fun noKeywordCanBypassOrOverrideTheModel() = runBlocking {
        val inputs = mutableListOf<String>()
        val repo = repository { _, body, _ -> inputs += body; Extraction(listOf(draft)) }
        val body = "验证码、广告和优惠券都是今天的讨论内容"
        val id = repo.ingest("会议作业群", body, "微信", "com.tencent.mm")
        assertEquals(listOf(body), inputs)
        assertEquals("PENDING", repo.dao.raw(id)!!.state)
        assertEquals("课程", repo.dao.allSuggestions().single().category)
    }
    @Test fun modelCanIgnoreATimedNoticeWithoutRulePromotion() = runBlocking {
        val repo = repository { _, _, _ -> Extraction(emptyList(), ignored = true) }
        val result = repo.ingestWithResult("会议通知", "明天14点到15点在301开会", "手动粘贴")
        assertEquals(IngestDisposition.IGNORED, result.disposition)
        assertTrue(repo.dao.allSuggestions().isEmpty())
    }
    @Test fun siblingDraftsRemainPendingUntilIndividuallyHandled() = runBlocking {
        val repo = repository { _, _, _ -> Extraction(listOf(draft, draft.copy(title = "报名截止"))) }
        val rawId = repo.ingest("通知", "同一通知里的两个事项", "微信")
        val suggestions = repo.dao.allSuggestions()
        repo.confirm(suggestions[0].id, suggestions[0].draft(), "NEW", null, -1)
        assertEquals("PENDING", repo.dao.raw(rawId)!!.state)
        assertEquals("PENDING", repo.dao.suggestion(suggestions[1].id)!!.state)
        repo.dismissSuggestion(suggestions[1].id)
        assertEquals("CONFIRMED", repo.dao.raw(rawId)!!.state)
        assertEquals(1, repo.dao.allEvents().size)
        assertEquals(1, repo.dao.allLinks().size)
        assertTrue(runCatching { repo.dismissSuggestion(suggestions[0].id) }.isFailure)
    }
    @Test fun failedModelKeepsOriginalForRetryWithoutFallback() = runBlocking {
        var attempts = 0
        val repo = repository { _, _, _ -> if (++attempts == 1) error("model unavailable") else Extraction(listOf(draft)) }
        val id = repo.ingest("通知", "明天14点开会", "微信", "test.app")
        assertEquals("PENDING", repo.dao.raw(id)!!.state)
        assertTrue(repo.dao.raw(id)!!.info.contains("重试"))
        assertTrue(repo.dao.allSuggestions().isEmpty())
        repo.retry(id)
        assertEquals(2, attempts); assertEquals(1, repo.dao.allRaw().size)
        assertEquals(1, repo.dao.allSuggestions().size)
    }
    @Test fun upgradeErasesOldCloudAndKeywordPreferences() {
        val stored = context.getSharedPreferences("yiji-settings", Context.MODE_PRIVATE)
        stored.edit().putBoolean("aiEnabled", true).putString("serverToken", "old-test-token")
            .putString("serverUrl", "https://example.org").putString("ignoredWords", "会议")
            .putBoolean("collecting", true).putStringSet("packages", setOf("test.app")).commit()
        val preferences = Preferences(context)
        assertTrue(preferences.collecting); assertEquals(setOf("test.app"), preferences.packages)
        listOf("aiEnabled", "serverUrl", "serverToken", "ignoredWords").forEach { assertFalse(stored.contains(it)) }
    }
}
