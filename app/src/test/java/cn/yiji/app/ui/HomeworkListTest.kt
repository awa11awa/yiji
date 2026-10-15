package cn.yiji.app.ui

import cn.yiji.app.data.Event
import cn.yiji.app.data.Suggestion
import org.junit.Assert.*
import org.junit.Test

class HomeworkListTest {
    private fun event(id: String, due: Long?, status: String = "ACTIVE", category: String = "作业") =
        Event(id = id, title = id, category = category, kind = "DEADLINE", dueAt = due, status = status)

    @Test fun homeworkHasItsOwnCategoryAndDeadlineOrder() {
        val events = listOf(event("later", 2000), event("unresolved", null), event("meeting", 500, category = "会议"),
            event("earlier", 1000), event("done", 100, "DONE"))
        assertEquals(listOf("earlier", "later", "unresolved"), homeworkEvents(events, "ACTIVE", "", 0).map { it.id })
    }

    @Test fun overdueExcludesFinishedCancelledAndUnknownTimes() {
        val events = listOf(event("late", 999), event("exact", 1000), event("future", 1001), event("unknown", null),
            event("done", 1, "DONE"), event("cancelled", 1, "CANCELLED"), event("other", 1, category = "材料"))
        assertEquals(listOf("late"), homeworkEvents(events, "OVERDUE", "", 1000).map { it.id })
        assertEquals(listOf("late", "exact"), homeworkEvents(events, "OVERDUE", "", 1001).map { it.id })
    }

    @Test fun statusFiltersKeepCompletedWorkAccessible() {
        val events = listOf(event("active", 10), event("done", 20, "DONE"), event("cancelled", 30, "CANCELLED"))
        assertEquals(listOf("done"), homeworkEvents(events, "DONE", "", 0).map { it.id })
        assertEquals(listOf("cancelled"), homeworkEvents(events, "CANCELLED", "", 0).map { it.id })
        assertEquals(3, homeworkEvents(events, "ALL", "", 0).size)
    }

    @Test fun searchIncludesCourseAndSubmissionRequirements() {
        val events = listOf(event("one", 100).copy(action = "上传学习通", note = "C语言第3章"),
            event("two", 200).copy(action = "纸质提交"))
        assertEquals(listOf("one"), homeworkEvents(events, "ACTIVE", " c语言 ", 0).map { it.id })
        assertEquals(listOf("one"), homeworkEvents(events, "ACTIVE", "学习通", 0).map { it.id })
    }

    @Test fun incompleteSuggestionsAreVisibleButHandledSuggestionsDisappear() {
        val pending = Suggestion(id = "one", rawId = "raw", title = "实验报告", category = "作业", kind = "DEADLINE")
        val suggestions = listOf(pending, pending.copy(id = "done", state = "CONFIRMED"),
            pending.copy(id = "dismissed", state = "DISMISSED"), pending.copy(id = "other", category = "材料"))
        assertEquals(listOf("one"), pendingHomework(suggestions).map { it.id })
        assertNull(pendingHomework(suggestions).single().dueAt)
        assertTrue(pendingHomework(suggestions, "高数").isEmpty())
        assertEquals(1, pendingHomework(suggestions, "实验").size)
    }
}
