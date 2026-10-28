package cn.yiji.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.yiji.app.platform.ReminderScheduler
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class HomeworkRepositoryTest {
    private lateinit var database: YijiDatabase
    private lateinit var repository: Repository
    private val reference = java.time.LocalDateTime.of(2026, 10, 15, 12, 0)
        .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("yiji-settings", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, YijiDatabase::class.java).allowMainThreadQueries().build()
        repository = Repository(database, Preferences(context), ReminderScheduler(context), cn.yiji.app.TestExtraction.extractor)
    }
    @After fun teardown() { database.close() }

    private suspend fun importHomework(text: String): Suggestion {
        val result = repository.ingestWithResult("课程群", text, "微信", "com.tencent.mm", reference)
        assertEquals(IngestDisposition.PENDING, result.disposition)
        return repository.dao.allSuggestions().single { it.rawId == result.id }
    }

    @Test fun notificationHomeworkReachesConfirmationWithBundledModel() = runBlocking {
        val suggestion = importHomework("C语言实验报告，明晚20:00前提交")
        assertEquals("作业", suggestion.category)
        assertEquals("DEADLINE", suggestion.kind)
        assertNotNull(suggestion.dueAt)
        assertTrue(repository.dao.allEvents().isEmpty())
        val id = repository.confirm(suggestion.id, suggestion.draft(), "NEW", null, 30)
        assertEquals("作业", repository.dao.event(id)!!.category)
        assertEquals(1, repository.dao.sources(id).size)
        assertEquals("CONFIRMED", repository.dao.suggestion(suggestion.id)!!.state)
    }

    @Test fun incompleteAssignmentCanBeSavedAndTimeCompletedLater() = runBlocking {
        val suggestion = importHomework("请完成高数课后习题第1、3、5题")
        assertNull(suggestion.dueAt)
        val id = repository.confirm(suggestion.id, suggestion.draft(), "NEW", null, 30)
        assertEquals("CONFIRMED", repository.dao.suggestion(suggestion.id)!!.state)
        val event = repository.dao.event(id)!!
        assertNull(event.dueAt)
        repository.save(event.draft().copy(dueAt = reference + 86_400_000L), 30, id, event.updatedAt)
        assertNotNull(repository.dao.event(id)!!.dueAt)
    }

    @Test fun differentHomeworkFromTheSameGroupIsNotLost() = runBlocking {
        val first = importHomework("高数 P38 第1-5题，明晚20:00前交")
        val second = importHomework("请完成C语言实验报告，下周一20:00前提交")
        assertNotEquals(first.rawId, second.rawId)
        assertEquals(2, repository.dao.allSuggestions().size)
    }

    @Test fun casualQuestionDoesNotProduceHomework() = runBlocking {
        val result = repository.ingestWithResult("高数作业群", "请问实验报告怎么提交", "微信", "com.tencent.mm", reference)
        assertEquals(IngestDisposition.IGNORED, result.disposition)
        assertTrue(repository.dao.allSuggestions().isEmpty())
    }

    @Test fun completionAndBackupPreserveHomeworkCategory() = runBlocking {
        val suggestion = importHomework("C语言实验报告，明晚20:00前提交")
        val id = repository.confirm(suggestion.id, suggestion.draft(), "NEW", null, 30)
        repository.setStatus(id, "DONE")
        val backup = repository.export()
        repository.clear()
        repository.restore(backup)
        val saved = repository.dao.event(id)!!
        assertEquals("作业", saved.category)
        assertEquals("DONE", saved.status)
        assertEquals(suggestion.dueAt, saved.dueAt)
        assertEquals(1, repository.dao.sources(id).size)
    }

    @Test fun ignoringHomeworkRemovesPendingSuggestion() = runBlocking {
        val suggestion = importHomework("请完成课后习题第1、3题")
        repository.dismiss(suggestion.rawId)
        assertNull(repository.dao.suggestion(suggestion.id))
        assertNull(repository.dao.raw(suggestion.rawId))
        val archived = repository.dao.allIgnored().single()
        assertEquals(suggestion.rawId, archived.traceId)
        assertTrue(repository.dao.allEvents().isEmpty())
        repository.restoreIgnored(archived.id)
        assertEquals("PENDING", repository.dao.suggestion(suggestion.id)!!.state)
    }
}
