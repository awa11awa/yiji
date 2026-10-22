package cn.yiji.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cn.yiji.app.core.Subtask
import cn.yiji.app.core.TimeField
import cn.yiji.app.core.Timing
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class StatusReadStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun clear() { context.getSharedPreferences("yiji-status-reads", Context.MODE_PRIVATE).edit().clear().commit() }
    private fun event() = Event(id = "one", title = "作业", category = "作业", kind = "DEADLINE", dueAt = 2000, updatedAt = 1)

    @Test fun readsSurviveRecreationAndOnlyClearTheOpenedRecords() {
        val store = StatusReadStore(context)
        val first = eventReadMarker(event(), 1000)
        val other = eventReadMarker(event().copy(id = "other", status = "DONE"), 1000)
        assertTrue(first.unread(store.state.value)); assertTrue(other.unread(store.state.value))
        store.markRead(listOf(first))
        val restored = StatusReadStore(context)
        assertFalse(first.unread(restored.state.value)); assertTrue(other.unread(restored.state.value))
    }
    @Test fun anEditCompletionOrNewlyOverdueStateBecomesUnreadAgain() {
        val store = StatusReadStore(context)
        val current = event()
        store.markRead(listOf(eventReadMarker(current, 1000)))
        assertTrue(eventReadMarker(current.copy(updatedAt = 2), 1000).unread(store.state.value))
        assertTrue(eventReadMarker(current.copy(status = "DONE", updatedAt = 2), 1000).unread(store.state.value))
        assertTrue(eventReadMarker(current, 2001).unread(store.state.value))
        assertFalse(eventReadMarker(current, 2000).unread(store.state.value))
    }
    @Test fun dateOnlyDeadlineExpiresAfterThatCalendarDayAndDoneChildrenDoNotExpireParents() {
        val zone = ZoneId.of("Asia/Shanghai")
        fun at(day: Int, hour: Int) = ZonedDateTime.of(2026, 10, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()
        val date = TimeField("SET", "DATE", "2026-10-22")
        val parent = event().copy(dueAt = null, timing = Timing(due = date))
        assertEquals("ACTIVE", eventBucket(parent, at(22, 23), zone))
        assertEquals("OVERDUE", eventBucket(parent, at(23, 0), zone))
        val child = parent.copy(timing = Timing(), subtasks = listOf(Subtask("练习", due = date)))
        assertEquals("OVERDUE", eventBucket(child, at(23, 0), zone))
        assertEquals("ACTIVE", eventBucket(child.copy(subtasks = child.subtasks.map { it.copy(done = true) }), at(23, 0), zone))
        assertEquals("DONE", eventBucket(parent.copy(status = "DONE"), at(23, 0), zone))
    }
    @Test fun amendedDraftOrCompletedExtractionMakesPreviouslyReadContentUnread() {
        val store = StatusReadStore(context)
        val raw = RawMessage(id = "r", title = "群", body = "通知", source = "测试")
        val draft = Suggestion(id = "s", rawId = raw.id, title = "作业", category = "作业", kind = "DEADLINE")
        store.markRead(listOf(suggestionReadMarker(draft), rawReadMarker(raw, emptyList())))
        assertFalse(suggestionReadMarker(draft).unread(store.state.value))
        assertTrue(suggestionReadMarker(draft.copy(title = "修订后的作业")).unread(store.state.value))
        assertTrue(rawReadMarker(raw, listOf(draft)).unread(store.state.value))
        val stored = context.getSharedPreferences("yiji-status-reads", Context.MODE_PRIVATE).getString("seen", "")!!
        assertFalse(stored.contains("通知")); assertFalse(stored.contains("作业"))
    }
}
