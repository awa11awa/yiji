package cn.yiji.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cn.yiji.app.MainActivity
import cn.yiji.app.YijiApplication
import cn.yiji.app.core.Subtask
import cn.yiji.app.data.RawMessage
import cn.yiji.app.data.Suggestion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = YijiApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ConfirmationV212Test {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun startOnlyActivityCanBeConfirmedThroughTheEditor() {
        val repository = (compose.activity.application as YijiApplication).repository
        runBlocking {
            val raw = RawMessage(title = "培训通知", body = "专题培训下午开始，结束尚未公布", source = "测试")
            repository.dao.putRaw(raw)
            repository.dao.putSuggestion(Suggestion(rawId = raw.id, title = "专题培训", category = "活动", kind = "SCHEDULE", startAt = System.currentTimeMillis() + 3600000))
        }
        compose.onNodeWithText("通知箱").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("核对确认").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("核对确认").performScrollTo().performClick()
        compose.onNodeWithText("确认新建事项").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("事项详情").fetchSemanticsNodes().isNotEmpty() }
        runBlocking {
            val event = repository.dao.allEvents().single()
            assertNotNull(event.startAt); assertNull(event.endAt)
        }
    }
    @Test fun assignmentIsConfirmedOnceAndChildrenCanBeCompletedIndependently() {
        val repository = (compose.activity.application as YijiApplication).repository
        runBlocking {
            val raw = RawMessage(title = "课程群", body = "第7周作业：预习、填空、选做阅读", source = "测试")
            repository.dao.putRaw(raw)
            repository.dao.putSuggestion(Suggestion(rawId = raw.id, title = "第7周作业", category = "作业", kind = "DEADLINE",
                subtasks = listOf(Subtask("预习"), Subtask("填空"), Subtask("阅读", optional = true))))
        }
        compose.onNodeWithText("作业").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("核对作业").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("核对作业").performScrollTo().performClick()
        compose.onNodeWithText("确认新建事项").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("事项详情").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(isToggleable())[0].performScrollTo().performClick()
        compose.waitUntil(10_000) {
            runBlocking { repository.dao.allEvents().singleOrNull()?.subtasks?.firstOrNull()?.done == true }
        }
        runBlocking {
            val event = repository.dao.allEvents().single()
            assertEquals(3, event.subtasks.size)
            assertTrue(event.subtasks.first().done); assertFalse(event.subtasks[1].done); assertTrue(event.subtasks[2].optional)
        }
    }
}
