package cn.yiji.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cn.yiji.app.MainActivity
import cn.yiji.app.YijiApplication
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = YijiApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class HomeworkNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun homeworkTabOpensAnIndependentList() {
        compose.onNodeWithText("作业").performClick()
        compose.onNodeWithText("还没有作业").assertIsDisplayed()
        compose.onNodeWithText("待完成", substring = false).assertIsDisplayed()
        compose.onNodeWithText("已逾期", substring = false).assertIsDisplayed()
    }

    @Test fun addingFromHomeworkDefaultsToHomeworkAndDeadline() {
        compose.onNodeWithText("作业").performClick()
        compose.onNodeWithContentDescription("添加作业").performClick()
        compose.onNodeWithText("添加作业").assertIsDisplayed()
        compose.onNodeWithText("作业").assertIsSelected()
        compose.onNodeWithText("任务 · 可设截止").assertIsSelected()
        compose.onNodeWithText("截止时间（选填）：未提供").assertIsDisplayed()
    }

    @Test fun incompleteNotificationAppearsOnHomeworkTab() {
        val repository = (compose.activity.application as YijiApplication).repository
        runBlocking {
            val raw = cn.yiji.app.data.RawMessage(title = "课程群", body = "请完成C语言实验报告", source = "微信")
            repository.dao.putRaw(raw)
            repository.dao.putSuggestion(cn.yiji.app.data.Suggestion(rawId = raw.id, title = "C语言实验报告", category = "作业", kind = "DEADLINE"))
        }
        compose.onNodeWithText("作业").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("待确认作业").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("待确认作业").assertIsDisplayed()
        compose.onNodeWithText("未提供截止时间").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("核对作业").performScrollTo().performClick()
        compose.onNodeWithText("识别确认").assertIsDisplayed()
    }
}
