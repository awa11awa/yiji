package cn.yiji.app.data

import android.app.AlarmManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.yiji.app.core.*
import cn.yiji.app.ml.Extraction
import cn.yiji.app.platform.ReminderScheduler
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class IgnoredAreaTest {
    private lateinit var context: Context
    private lateinit var database: YijiDatabase
    private lateinit var repository: Repository
    private val future get() = System.currentTimeMillis() + 3_600_000
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("yiji-settings", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, YijiDatabase::class.java).allowMainThreadQueries().build()
        repository = Repository(database, Preferences(context), ReminderScheduler(context), { _, body, _ ->
            Extraction(listOf(Draft("班会", startAt = future, note = body)))
        })
    }
    @After fun close() { database.close() }
    private suspend fun event(): Pair<String, String> {
        val raw = repository.ingest("班级群", "班会原文", "微信")
        val suggestion = repository.dao.allSuggestions().single()
        return repository.confirm(suggestion.id, suggestion.draft(), "NEW", null, 0) to raw
    }

    @Test fun archivedEventRestoresSourcesHistoryAndFutureReminders() = runBlocking {
        val (id, raw) = event()
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        assertEquals(1, alarms.scheduledAlarms.size)
        repository.delete(id)
        assertNull(repository.dao.event(id)); assertTrue(alarms.scheduledAlarms.isEmpty())
        assertNotNull(repository.dao.raw(raw)); assertEquals(1, repository.dao.allIgnored().size)
        repository.restoreIgnored(repository.dao.allIgnored().single().id)
        assertNotNull(repository.dao.event(id)); assertEquals(1, repository.dao.history(id).size)
        assertEquals(raw, repository.dao.sources(id).single().id)
        assertEquals(1, alarms.scheduledAlarms.size); assertTrue(repository.dao.allIgnored().isEmpty())
    }

    @Test fun restoringPastEventNeverFiresAnOldReminder() = runBlocking {
        val id = repository.save(Draft("过期事项", startAt = System.currentTimeMillis() - 60_000), 0)
        repository.delete(id)
        repository.restoreIgnored(repository.dao.allIgnored().single().id)
        assertNotNull(repository.dao.event(id))
        assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
    }

    @Test fun ignoredRawRestoresItsDraftAndStructuredArchive() = runBlocking {
        val id = repository.ingest("原通知", "需要保留的原文", "QQ")
        repository.dao.putRaw(repository.dao.raw(id)!!.copy(qqMetadata = "structured-fixture"))
        val draft = repository.dao.allSuggestions().single()
        repository.dismiss(id)
        assertNull(repository.dao.raw(id)); assertNull(repository.dao.suggestion(draft.id))
        repository.restoreIgnored(repository.dao.allIgnored().single().id)
        assertEquals("structured-fixture", repository.dao.raw(id)!!.qqMetadata)
        assertEquals("PENDING", repository.dao.suggestion(draft.id)!!.state)
    }

    @Test fun ignoringOneSiblingKeepsTheOtherDraftAndConfirmedEvent() = runBlocking {
        val (eventId, rawId) = event()
        val sibling = Suggestion(rawId = rawId, title = "补交材料", category = "材料", kind = "DEADLINE")
        repository.dao.putSuggestion(sibling)
        repository.dao.putRaw(repository.dao.raw(rawId)!!.copy(state = "PENDING"))
        repository.dismissSuggestion(sibling.id)
        assertNotNull(repository.dao.event(eventId)); assertNotNull(repository.dao.raw(rawId))
        assertEquals("CONFIRMED", repository.dao.raw(rawId)!!.state)
        repository.restoreIgnored(repository.dao.allIgnored().single().id)
        assertEquals("PENDING", repository.dao.suggestion(sibling.id)!!.state)
        assertEquals("PENDING", repository.dao.raw(rawId)!!.state)
        assertEquals(1, repository.dao.allEvents().size)
    }

    @Test fun archivedNotificationsDoNotReappearOnReposts() = runBlocking {
        val id = repository.ingest("通知", "相同原文", "微信", "com.tencent.mm")
        repository.dismiss(id)
        val result = repository.enqueueWithResult("通知", "相同原文", "微信", "com.tencent.mm")
        assertEquals(IngestDisposition.IGNORED, result.disposition)
        assertTrue(repository.dao.allRaw().isEmpty()); assertEquals(1, repository.dao.allIgnored().size)
    }

    @Test fun backupIncludesRecoveryCopiesAndRestoresThem() = runBlocking {
        val (id, _) = event()
        repository.delete(id)
        val backup = repository.export()
        repository.clear(); assertTrue(repository.dao.allIgnored().isEmpty())
        repository.restore(backup)
        repository.restoreIgnored(repository.dao.allIgnored().single().id)
        assertNotNull(repository.dao.event(id)); assertEquals(1, repository.dao.history(id).size)
    }

    @Test fun cleanupIsOffByDefaultAndOnlyDeletesExpiredRecoveryCopies() = runBlocking {
        val active = repository.save(Draft("正常待办"), -1)
        val archived = repository.save(Draft("已忽略待办"), -1)
        repository.delete(archived)
        val item = repository.dao.allIgnored().single()
        val now = 1_900_000_000_000L
        repository.dao.putIgnored(item.copy(ignoredAt = now - 7 * 86_400_000L - 1))
        assertEquals(0, repository.purgeExpiredIgnored(now))
        repository.preferences.ignoredRetentionDays = 7
        val edge = item.copy(id = newId(), ignoredAt = now - 7 * 86_400_000L)
        repository.dao.putIgnored(edge)
        assertEquals(1, repository.purgeExpiredIgnored(now))
        assertEquals(edge.id, repository.dao.allIgnored().single().id)
        assertNotNull(repository.dao.event(active))
        assertEquals(0, repository.purgeExpiredIgnored(now))
        repository.preferences.ignoredRetentionDays = 0
        assertEquals(0, repository.purgeExpiredIgnored(now + 365 * 86_400_000L))
    }

    @Test fun quickAddSavesEveryRoleAndItsOriginalSourceWithoutCallingModels() = runBlocking {
        val body = "【报名通知】活动时间：10月24日18:30\n报名截止：10月22日20:00\n地点：创新楼102"
        val drafts = RuleDrafts.extract(body, System.currentTimeMillis())
        val ids = repository.quickAdd("报名通知", body, "手动粘贴", drafts)
        assertEquals(2, ids.size); assertTrue(repository.dao.allJobs().isEmpty())
        ids.forEach { assertEquals(body, repository.dao.sources(it).single().body) }
        assertEquals(setOf("SCHEDULE", "DEADLINE"), repository.dao.allEvents().map { it.kind }.toSet())
    }
}
