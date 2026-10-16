package cn.yiji.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.yiji.app.core.Draft
import cn.yiji.app.platform.ReminderScheduler
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class RepositoryTest {
    private lateinit var database: YijiDatabase
    private lateinit var repository: Repository
    private val reference = java.time.ZonedDateTime.of(2026, 10, 14, 12, 0, 0, 0, java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("yiji-settings", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, YijiDatabase::class.java).allowMainThreadQueries().build()
        repository = Repository(database, Preferences(context), ReminderScheduler(context))
    }
    @After fun teardown() { database.close() }
    private suspend fun initialEvent(): Pair<Event, RawMessage> {
        val rawId = repository.ingest("竞赛培训", "明天下午2点到3点在图书馆301召开竞赛培训。请携带电脑。", "学院通知", receivedAt = reference)
        val suggestion = repository.dao.allSuggestions().single()
        val id = repository.confirm(suggestion.id, suggestion.draft(), "NEW", null, 30)
        return repository.dao.event(id)!! to repository.dao.raw(rawId)!!
    }
    @Test fun ingestNeedsExplicitConfirmation() = runBlocking {
        repository.ingest("竞赛培训", "明天14:00至15:00召开竞赛培训", "学院通知", receivedAt = reference)
        assertTrue(repository.dao.allEvents().isEmpty()); assertEquals(1, repository.dao.allSuggestions().size)
    }
    @Test fun newMergeUpdateAndCancelPreserveSourcesAndHistory() = runBlocking {
        val (old, _) = initialEvent()
        repository.ingest("竞赛培训", "明天14:00至15:00召开竞赛培训", "班级群", receivedAt = reference)
        val duplicate = repository.dao.allSuggestions().single { it.state == "PENDING" }
        assertEquals("MERGE", duplicate.type)
        repository.confirm(duplicate.id, duplicate.draft(), duplicate.type, old.id, 30)
        assertEquals(2, repository.dao.sources(old.id).size); assertEquals(1, repository.dao.allEvents().size)
        repository.ingest("竞赛培训", "竞赛培训延期至后天16:00至17:00，地点改为图书馆302。", "学院通知", receivedAt = reference)
        val update = repository.dao.allSuggestions().single { it.state == "PENDING" }
        assertEquals("UPDATE", update.type)
        repository.confirm(update.id, update.draft(), "UPDATE", old.id, 60)
        assertEquals("图书馆302", repository.dao.event(old.id)!!.location)
        repository.ingest("竞赛培训", "竞赛培训取消", "学院通知", receivedAt = reference)
        val cancel = repository.dao.allSuggestions().single { it.state == "PENDING" }
        assertEquals("CANCEL", cancel.type)
        repository.confirm(cancel.id, cancel.draft(), "CANCEL", old.id, 60)
        assertEquals("CANCELLED", repository.dao.event(old.id)!!.status)
        assertEquals(4, repository.dao.sources(old.id).size); assertEquals(4, repository.dao.history(old.id).size)
    }
    @Test fun invalidConfirmationDoesNotMutateDatabase() = runBlocking {
        repository.ingest("会议", "会议时间另行通知", "学院通知", receivedAt = reference)
        val suggestion = repository.dao.allSuggestions().single()
        assertTrue(runCatching { repository.confirm(suggestion.id, suggestion.draft(), "NEW", null, 30) }.isFailure)
        assertTrue(repository.dao.allEvents().isEmpty()); assertEquals("PENDING", repository.dao.suggestion(suggestion.id)!!.state)
    }
    @Test fun confirmationCannotRunTwice() = runBlocking {
        initialEvent()
        val suggestion = repository.dao.allSuggestions().single()
        assertTrue(runCatching { repository.confirm(suggestion.id, suggestion.draft(), "NEW", null, 30) }.isFailure)
        assertEquals(1, repository.dao.allEvents().size)
    }
    @Test fun staleEditRejected() = runBlocking {
        val (old, _) = initialEvent()
        repository.setStatus(old.id, "DONE")
        assertTrue(runCatching { repository.save(old.draft(), 30, old.id, old.updatedAt - 1) }.isFailure)
        assertEquals("DONE", repository.dao.event(old.id)!!.status)
    }
    @Test fun exportRestoreRoundTripAndInvalidRestoreAtomic() = runBlocking {
        val (old, _) = initialEvent()
        val text = repository.export()
        repository.clear(); assertTrue(repository.dao.allEvents().isEmpty())
        repository.restore(text)
        assertEquals(old, repository.dao.event(old.id)); assertEquals(1, repository.dao.sources(old.id).size)
        assertTrue(runCatching { repository.restore(text.replace("cn.yiji.backup", "invalid")) }.isFailure)
        assertEquals(old, repository.dao.event(old.id)); assertEquals(1, repository.dao.history(old.id).size)
    }
    @Test fun notificationRepostsDeduplicated() = runBlocking {
        repository.ingest("会议", "明天14:00至15:00会议", "课程应用", "test.app", reference)
        repository.ingest("会议", "明天14:00至15:00会议", "课程应用", "test.app", reference + 5000)
        assertEquals(1, repository.dao.allRaw().size); assertEquals(1, repository.dao.allSuggestions().size)
    }
    @Test fun ignoredNoiseStoredWithoutSuggestion() = runBlocking {
        val id = repository.ingest("新消息", "晚饭吃什么", "测试应用", "test.app", reference)
        assertEquals("IGNORED", repository.dao.raw(id)!!.state); assertTrue(repository.dao.allSuggestions().isEmpty())
    }
    @Test fun campusNotificationCreatesSuggestionWithBundledModel() = runBlocking {
        val id = repository.ingest("班级群", "明天14点到301集合，带学生证", "微信", "test.app", reference)
        assertEquals("PENDING", repository.dao.raw(id)!!.state)
        val suggestion = repository.dao.allSuggestions().single()
        assertEquals("TinyBERT 离线模型 v2", suggestion.engine)
        assertEquals("301", suggestion.location)
        assertNotNull(suggestion.startAt)
        assertNull(suggestion.endAt)
        assertTrue(repository.dao.allEvents().isEmpty())
    }
    @Test fun groupNameDoesNotPromoteOrdinaryChat() = runBlocking {
        val id = repository.ingest("数学作业群", "晚饭吃什么", "微信", "test.app", reference)
        assertEquals("IGNORED", repository.dao.raw(id)!!.state)
        assertTrue(repository.dao.allSuggestions().isEmpty())
    }
    @Test fun deletingEventPreservesRawAndRemovesDependentRecords() = runBlocking {
        val (old, raw) = initialEvent()
        repository.delete(old.id)
        assertNotNull(repository.dao.raw(raw.id)); assertTrue(repository.dao.allHistory().isEmpty()); assertTrue(repository.dao.allLinks().isEmpty())
    }
    @Test fun selectingSourcesOnlyUsesSourceAndPauseState() {
        val prefs = repository.preferences
        prefs.collecting = true; prefs.packages = setOf("school.app")
        assertTrue(prefs.accepts("school.app")); assertFalse(prefs.accepts("other.app"))
        prefs.collecting = false; assertFalse(prefs.accepts("school.app"))
    }
}
