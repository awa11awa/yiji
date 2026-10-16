package cn.yiji.app.core

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class CoreTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val reference = LocalDateTime.of(2026, 10, 14, 12, 0).atZone(zone).toInstant().toEpochMilli()
    private fun time(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun schedule(start: Long, end: Long, title: String = "竞赛培训") = Draft(title = title, kind = "SCHEDULE", startAt = start, endAt = end)

    @Test fun overlapAndAdjacentSchedules() {
        val list = listOf(ExistingItem("a", schedule(100, 200)), ExistingItem("b", schedule(150, 300)), ExistingItem("c", schedule(300, 400)))
        val conflicts = ConflictDetector.detect(list)
        assertEquals(listOf(TimeConflict("a", "b", 150, 200)), conflicts)
    }
    @Test fun completedCancelledAndDeadlinesExcluded() {
        val list = listOf(ExistingItem("a", schedule(100, 200)), ExistingItem("done", schedule(110, 160), "DONE"),
            ExistingItem("cancelled", schedule(120, 180), "CANCELLED"), ExistingItem("deadline", Draft("作业", kind = "DEADLINE", dueAt = 150)))
        assertTrue(ConflictDetector.detect(list).isEmpty())
    }
    @Test fun multipleOverlapsDetected() {
        val list = listOf(ExistingItem("a", schedule(100, 500)), ExistingItem("b", schedule(200, 300)), ExistingItem("c", schedule(250, 450)))
        assertEquals(3, ConflictDetector.detect(list).size)
    }
    @Test fun matchingDuplicate() {
        val draft = schedule(time(15, 14), time(15, 15))
        val result = AssociationMatcher.suggest(draft, "NEW", listOf(ExistingItem("a", draft)))
        assertEquals("MERGE", result.type); assertEquals("a", result.targetId)
    }
    @Test fun repeatedSameNameAtDifferentTimeIsNew() {
        val old = schedule(time(15, 14), time(15, 15))
        val fresh = schedule(time(15, 19), time(15, 20))
        assertEquals("NEW", AssociationMatcher.suggest(fresh, "NEW", listOf(ExistingItem("a", old))).type)
    }
    @Test fun updateAndCancel() {
        val old = schedule(time(15, 14), time(15, 15))
        val changed = schedule(time(16, 16), time(16, 17))
        assertEquals("UPDATE", AssociationMatcher.suggest(changed, "UPDATE", listOf(ExistingItem("a", old))).type)
        assertEquals("CANCEL", AssociationMatcher.suggest(old.copy(startAt = null, endAt = null), "CANCEL", listOf(ExistingItem("a", old))).type)
    }
    @Test fun ambiguousAndUnrelatedChangesNotLinked() {
        val old = schedule(time(15, 14), time(15, 15))
        val list = listOf(ExistingItem("a", old), ExistingItem("b", old))
        assertNull(AssociationMatcher.suggest(old, "UPDATE", list).targetId)
        assertNull(AssociationMatcher.suggest(old.copy(title = "班级会议"), "CANCEL", list).targetId)
    }
    @Test fun invalidDraftRequiresCorrection() {
        assertNotNull(Draft(title = "").validationError())
        assertNotNull(schedule(200, 100).validationError())
        assertNotNull(Draft("作业", kind = "DEADLINE").validationError())
    }
}
