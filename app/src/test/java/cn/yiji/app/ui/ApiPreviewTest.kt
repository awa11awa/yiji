package cn.yiji.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cn.yiji.app.MainActivity
import cn.yiji.app.YijiApplication
import cn.yiji.app.data.ApiProfile
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** Native-rendered preview of the real screens, with no real API key or network requests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = YijiApplication::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ApiPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun save(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        val view = if (dialog) ShadowDialog.getLatestDialog().window!!.decorView else compose.activity.window.decorView
        check(view.width > 0 && view.height > 0)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        val file = File("build/ui-previews/" + name + ".png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun apiConfigurationGuideAndEditorRenderOnAPhone() {
        val store = (compose.activity.application as YijiApplication).repository.apiProfiles
        store.enable(false); store.save(ApiProfile.glm, null); store.select(ApiProfile.glm.id)
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("管理 API 配置 →").performScrollTo().performClick()
        compose.onNodeWithText("云端识别").assertIsDisplayed()
        save("api-profiles")
        compose.onNodeWithText("获取免费 API · 操作教程").performClick()
        compose.onNodeWithText("01 · 智谱 GLM-4.7-Flash（默认）").assertIsDisplayed()
        save("api-guide")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("添加").performScrollTo().performClick()
        compose.onNodeWithText("保存配置").performScrollTo().assertIsDisplayed()
        save("api-editor", dialog = true)
    }
}
