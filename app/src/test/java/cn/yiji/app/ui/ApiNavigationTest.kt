package cn.yiji.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cn.yiji.app.MainActivity
import cn.yiji.app.YijiApplication
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = YijiApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ApiNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun resetApiProfiles() {
        val store = (compose.activity.application as YijiApplication).repository.apiProfiles
        store.enable(false)
        store.save(cn.yiji.app.data.ApiProfile.glm, null)
        store.select(cn.yiji.app.data.ApiProfile.glm.id)
    }
    private fun openApi() {
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("管理 API 配置 →").performScrollTo().performClick()
    }
    @Test fun defaultGlmConfigurationAndOfflineTutorialAreReachable() {
        openApi()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("glm-4.7-flash"))
        compose.onNodeWithText("glm-4.7-flash").assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("获取免费 API · 操作教程"))
        compose.onNodeWithText("获取免费 API · 操作教程").performClick()
        compose.onNodeWithText("用自己的 Key，接入云端识别").assertIsDisplayed()
        compose.onNodeWithText("01 · 智谱 GLM-4.7-Flash（默认）").assertIsDisplayed()
    }
    @Test fun apiEditorHasPresetsModelSelectionAndConnectionTest() {
        openApi()
        compose.onNodeWithText("添加").performScrollTo().performClick()
        compose.onNodeWithText("添加 API 配置").assertIsDisplayed()
        compose.onNodeWithText("自定义").performClick()
        compose.onNodeWithText("API Key").assertIsDisplayed()
        compose.onNodeWithText("获取模型列表").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("测试连接").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("保存配置").performScrollTo().assertIsDisplayed()
    }
}
