package cn.yiji.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cn.yiji.app.MainActivity
import cn.yiji.app.YijiApplication
import cn.yiji.app.data.*
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
class StatusBadgesTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as YijiApplication
    private fun waitForDot(label: String, present: Boolean) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("$label 有未读更新", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty() == present
        }
    }

    @Test fun pendingHomeworkIsExclusiveAndReadingItClearsOnlyItsUnreadDot() {
        val raw = RawMessage(title = "课程群", body = "布置作业", source = "测试")
        val pending = Suggestion(rawId = raw.id, title = "待核对练习", category = "作业", kind = "DEADLINE")
        val current = Event(title = "已确认练习", category = "作业", kind = "DEADLINE")
        runBlocking { app.repository.dao.putRaw(raw); app.repository.dao.putSuggestion(pending); app.repository.dao.putEvent(current) }
        compose.onNodeWithText("作业").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("已确认练习").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("待核对练习").assertDoesNotExist()
        waitForDot("待确认", true)
        compose.onNodeWithText("待确认", substring = false).performClick()
        compose.onNodeWithText("待核对练习").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("已确认练习").assertDoesNotExist()
        waitForDot("待确认", false)
        assertFalse(suggestionReadMarker(pending).unread(StatusReadStore(app).state.value))
    }

    @Test fun completedAndOverdueTabsHaveIndependentReadReceipts() {
        val late = Event(title = "逾期练习", category = "作业", kind = "DEADLINE", dueAt = System.currentTimeMillis() - 3600000)
        val done = Event(title = "完成练习", category = "作业", kind = "DEADLINE", status = "DONE")
        runBlocking { app.repository.dao.putEvent(late); app.repository.dao.putEvent(done) }
        compose.onNodeWithText("作业").performClick()
        waitForDot("已逾期", true); waitForDot("已完成", true)
        compose.onNodeWithText("逾期练习").assertDoesNotExist()
        compose.onNodeWithText("已逾期", substring = false).performClick()
        compose.onNodeWithText("逾期练习").performScrollTo().assertIsDisplayed()
        waitForDot("已逾期", false); waitForDot("已完成", true)
        compose.onNodeWithText("已完成", substring = false).performClick()
        compose.onNodeWithText("完成练习").performScrollTo().assertIsDisplayed()
        waitForDot("已完成", false)
        val seen = StatusReadStore(app).state.value
        assertFalse(eventReadMarker(late, System.currentTimeMillis()).unread(seen))
        assertFalse(eventReadMarker(done, System.currentTimeMillis()).unread(seen))
    }

    @Test fun unseenInboxStatesKeepTheirDotUntilOpened() {
        val ignored = RawMessage(title = "闲聊", body = "谢谢", source = "测试", state = "IGNORED")
        runBlocking { app.repository.dao.putRaw(ignored) }
        compose.onNodeWithText("通知箱").performClick()
        waitForDot("已忽略", true)
        compose.onNodeWithText("已忽略", substring = false).performClick()
        compose.onNodeWithText("闲聊").performScrollTo().assertIsDisplayed()
        waitForDot("已忽略", false)
    }
}
