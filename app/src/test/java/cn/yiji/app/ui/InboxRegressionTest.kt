package cn.yiji.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import cn.yiji.app.MainActivity
import cn.yiji.app.YijiApplication
import cn.yiji.app.core.NoticeSafety
import cn.yiji.app.data.MessageJob
import cn.yiji.app.data.RawMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = YijiApplication::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class InboxRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val repository get() = (compose.activity.application as YijiApplication).repository
    @Before fun reset() = runBlocking {
        repository.apiProfiles.enable(false)
        repository.clear()
    }
    private fun put(body: String, jobState: String = "DONE"): RawMessage = runBlocking {
        val raw = RawMessage(title = "班级群", body = body, source = "微信", packageName = "com.tencent.mm",
            info = "云端未提取到明确事项，已保留待核对")
        repository.dao.putRaw(raw)
        repository.dao.putJob(MessageJob(raw.id, state = jobState))
        raw
    }
    private fun openInbox() { compose.onNodeWithText("通知箱").performClick() }
    private fun savePreview() {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        val file = File("build/ui-previews/inbox-v2.1.1.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun expandedBodyHasNoEllipsizedLinesAndPreservesTheLastCharacter() {
        val full = "【关于开展第四期数智创业大讲坛的通知】\n" + "各位同学，请阅读讲座安排和准备要求。\n".repeat(50) + "这是全文最后一行。"
        put(full); openInbox()
        compose.onNodeWithText("查看原文").performScrollTo().performClick()
        compose.onNodeWithText(full).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { get ->
            val results = mutableListOf<TextLayoutResult>(); assertTrue(get(results))
            val layout = results.single()
            assertFalse(layout.hasVisualOverflow)
            assertEquals(full.length, layout.getLineEnd(layout.lineCount - 1))
        }
        compose.onNodeWithText("已采集正文 ${full.length} 字").performScrollTo().assertIsDisplayed()
    }

    @Test fun incompleteBodyCanBeCompletedInPlaceFromTheInbox() {
        val abbreviated = "【关于开展第四期数智创业大讲坛的通知】各位同学：讲座内容：..."
        val raw = put(abbreviated); openInbox()
        compose.onNodeWithText(NoticeSafety.INCOMPLETE_HINT).assertExists()
        savePreview()
        compose.onNodeWithText("补全正文").performScrollTo().performClick()
        compose.onNodeWithText("补全通知正文").assertIsDisplayed()
        val full = "明天14点到15点在图书馆301参加数智创业大讲坛，这是补全后的全文末尾。"
        compose.onNode(hasSetTextAction() and hasText(abbreviated)).performTextReplacement(full)
        compose.onNodeWithText("保存完整正文并重新识别").performScrollTo().performClick()
        compose.waitUntil(5000) { runBlocking { repository.dao.raw(raw.id)?.body == full } }
        runBlocking { assertEquals(1, repository.dao.allRaw().size); assertEquals(raw.receivedAt, repository.dao.raw(raw.id)!!.receivedAt) }
    }

    @Test fun processingDisablesDuplicateRequestsWhileManualReviewRemainsReachable() {
        put("原定10月24日体测调整，具体方案另行通知。", "PROCESSING"); openInbox()
        compose.onNodeWithText("识别中…").assertIsNotEnabled()
        compose.onNodeWithText("送云端复核").assertIsNotEnabled()
        compose.onNodeWithText("补全正文").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("手动整理").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onNodeWithText("识别确认").isDisplayed() }
        compose.onNodeWithText("识别确认").assertIsDisplayed()
        runBlocking {
            assertNull(repository.dao.allJobs().firstOrNull())
            assertEquals("原定10月24日体测调整，具体方案另行通知。", repository.dao.allSuggestions().single().note)
        }
    }
}
