package cn.yiji.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cn.yiji.app.MainActivity
import cn.yiji.app.YijiApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=YijiApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class QqNavigationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun qqSetupAndPasswordLoginAreReachableAndCanBeCancelled() {
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("管理 QQ 登录与采集会话").performScrollTo().performClick()
        compose.onNodeWithText("QQ 接收端未启用").assertIsDisplayed()
        compose.onNodeWithText("登录 QQ").performScrollTo().performClick()
        compose.onNodeWithText("QQ 号").assertIsDisplayed();compose.onNodeWithText("QQ 密码").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("QQ 密码").assertDoesNotExist()
    }
}
