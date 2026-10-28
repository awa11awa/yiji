package cn.yiji.app.data

import android.app.AlarmManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.yiji.app.core.*
import cn.yiji.app.ml.Extraction
import cn.yiji.app.ml.ScheduleExtractor
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
class WorkflowV212Test {
    private lateinit var database: YijiDatabase
    private lateinit var context: Context
    private lateinit var alarms: AlarmManager
    private lateinit var scheduler: ReminderScheduler
    private val future get() = System.currentTimeMillis() + 3600000
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, YijiDatabase::class.java).allowMainThreadQueries().build()
        context.getSharedPreferences("yiji-reminder-revisions", Context.MODE_PRIVATE).edit().clear().commit()
        scheduler = ReminderScheduler(context); alarms = context.getSystemService(AlarmManager::class.java)
    }
    @After fun close() { database.close() }
    private fun repo(extraction: Extraction) = Repository(database, Preferences(context), scheduler,
        ScheduleExtractor { _, _, _ -> extraction })
    @Test fun confirmedStartOnlyScheduleIsSavedAndGetsAnAlarm() = runBlocking {
        val draft = Draft("培训", startAt = future)
        val repository = repo(Extraction(listOf(draft)))
        repository.ingest("群", "培训开始时间已公布", "测试")
        val suggestion = database.dao().allSuggestions().single()
        val id = repository.confirm(suggestion.id, suggestion.draft(), "NEW", null, 15)
        val event = database.dao().event(id)!!
        assertEquals(draft.startAt, event.startAt); assertNull(event.endAt)
        assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
    }
    @Test fun ignoredDecisionDoesNotLeaveAPendingMessageOrDraft() = runBlocking {
        val repository = repo(Extraction(emptyList(), ignored = true))
        repository.ingest("群", "123", "测试")
        assertEquals("IGNORED", database.dao().allRaw().single().state)
        assertTrue(database.dao().allSuggestions().isEmpty()); assertTrue(database.dao().allEvents().isEmpty())
    }
    @Test fun confirmingPendingUpdateRemovesAlarmAndKeepsHistory() = runBlocking {
        val old = Event(title = "体测", category = "活动", kind = "SCHEDULE", startAt = future, endAt = future + 3600000)
        database.dao().putEvent(old); scheduler.schedule(old)
        assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
        val pending = Draft("体测", "活动", timing = Timing(start = TimeField.pending(), end = TimeField.pending()))
        val repository = repo(Extraction(listOf(pending), operations = listOf("UPDATE"), targetIds = listOf(old.id)))
        repository.ingest("群", "体测调整，时间待定", "测试")
        val s = database.dao().allSuggestions().single()
        assertEquals(old.startAt, database.dao().event(old.id)!!.startAt)
        repository.confirm(s.id, s.draft(), "UPDATE", old.id, 30, old.updatedAt)
        assertNull(database.dao().event(old.id)!!.startAt)
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
        assertTrue(database.dao().allHistory().single().before.contains(old.startAt.toString()))
    }
    @Test fun openingAndClosingAlarmsAreIndependentAndCancelledTogether() {
        val event = Event(title = "讲坛报名", category = "报名", kind = "DEADLINE", opensAt = future, dueAt = future + 7200000)
        scheduler.schedule(event)
        assertEquals(2, shadowOf(alarms).scheduledAlarms.size)
        scheduler.schedule(event.copy(dueAt = null, updatedAt = event.updatedAt + 1))
        assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
        scheduler.cancel(event.id); assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
    }
    @Test fun completingOneChildPreservesOtherChildAndOneParentRecord() = runBlocking {
        val first = Subtask("练习一", dueAt = future)
        val second = Subtask("阅读二", optional = true, dueAt = future + 3600000)
        val event = Event(title = "第7周作业", category = "作业", kind = "DEADLINE", subtasks = listOf(first, second))
        database.dao().putEvent(event); scheduler.schedule(event)
        assertEquals(2, shadowOf(alarms).scheduledAlarms.size)
        val repository = repo(Extraction(emptyList(), ignored = true))
        repository.setSubtaskDone(event.id, first.id, true)
        assertEquals(1, database.dao().allEvents().size)
        assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
        val saved = database.dao().event(event.id)!!
        assertTrue(saved.subtasks[0].done); assertFalse(saved.subtasks[1].done)
        val restored = BackupCodec.decode(repository.export()).events.single()
        assertEquals(saved.subtasks, restored.subtasks)
    }
    @Test fun backupPreservesDatePrecisionPendingStateAndOpening() = runBlocking {
        val draft = Draft("待定活动", timing = Timing(start = TimeField.pending(), end = TimeField.cleared()))
        val registration = Draft("报名", category = "报名", kind = "DEADLINE", opensAt = future,
            timing = Timing(open = TimeField.exact(), due = TimeField("SET", "DATE", "2026-10-25")))
        val repository = repo(Extraction(emptyList(), ignored = true))
        repository.save(draft, 30); repository.save(registration, 30)
        val restored = BackupCodec.decode(repository.export()).events
        assertEquals(2, restored.size)
        assertEquals("TBD", restored.first { it.title == "待定活动" }.timing.start.precision)
        assertEquals("2026-10-25", restored.first { it.title == "报名" }.timing.due.date)
        assertNotNull(restored.first { it.title == "报名" }.opensAt)
    }
    @Test fun partialReviewIsNotLostAfterConfirmingTheValidPart() = runBlocking {
        val repository = repo(Extraction(listOf(Draft("已验证事项", startAt = future)), needsReview = true))
        repository.ingest("群", "这里有一条有效事项和一条无法核对的事项", "测试")
        val suggestion = database.dao().allSuggestions().single()
        repository.confirm(suggestion.id, suggestion.draft(), "NEW", null, 30)
        assertEquals("PENDING", database.dao().allRaw().single().state)
        repository.dismiss(database.dao().allRaw().single().id)
        assertTrue(database.dao().allRaw().isEmpty())
        assertEquals(1, database.dao().allEvents().size)
        val archived = database.dao().allIgnored().single()
        repository.restoreIgnored(archived.id)
        assertEquals("CONFIRMED", database.dao().allRaw().single().state)
    }
    @Test fun unrelatedUpdateKeepsChildrenAndExplicitRemovalIsRespected() {
        val first = Subtask("练习", done = true)
        val previous = Draft("作业", category = "作业", kind = "DEADLINE", subtasks = listOf(first))
        val unrelated = Draft("作业", category = "作业", kind = "DEADLINE", location = "线上", keepSubtasks = true)
        assertEquals(previous.subtasks, unrelated.resolvedAgainst(previous).subtasks)
        assertTrue(unrelated.copy(keepSubtasks = false).resolvedAgainst(previous).subtasks.isEmpty())
        val updated = previous.copy(subtasks = listOf(Subtask("练习"))).resolvedAgainst(previous)
        assertTrue(updated.subtasks.single().done); assertEquals(first.id, updated.subtasks.single().id)
    }
}
