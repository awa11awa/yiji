package cn.yiji.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cn.yiji.app.MainActivity
import cn.yiji.app.YijiApplication
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = YijiApplication::class)
class IgnoredNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun ignoredAreaIsReachableAndRetentionCanBeChanged() {
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("查看忽略区与自动清理设置"))
        compose.onNodeWithText("查看忽略区与自动清理设置").performClick()
        compose.onNodeWithText("自动清理").assertIsDisplayed()
        compose.onNodeWithText("7 天").performScrollTo().performClick()
        val preferences = (compose.activity.application as YijiApplication).repository.preferences
        compose.runOnIdle { assertEquals(7, preferences.ignoredRetentionDays) }
        compose.onNodeWithText("自定义").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("14")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertEquals(14, preferences.ignoredRetentionDays) }
        compose.onNodeWithText("不自动清理").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, preferences.ignoredRetentionDays) }
    }
}
