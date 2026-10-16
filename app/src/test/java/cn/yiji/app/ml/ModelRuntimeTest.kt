package cn.yiji.app.ml

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

/** Runs the bundled INT8 graph through the same Java API used on Android; no fake model. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ModelRuntimeTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val reference = ZonedDateTime.of(2026, 10, 16, 12, 0, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()
    private suspend fun extract(body: String, title: String = "班级群") = LocalScheduleExtractor(context).extract(title, body, reference)

    @Test fun actualModelFiltersChatRegardlessOfGroupTitle() = runBlocking {
        assertTrue(extract("晚饭吃什么", "数学作业会议考试群").ignored)
        assertTrue(extract("验证码123456，五分钟内有效，请勿泄露。").ignored)
        assertTrue(extract("请问实验报告怎么提交").ignored)
    }
    @Test fun actualModelRecognizesHomeworkWithoutEndOfDayGuess() = runBlocking {
        val draft = extract("C语言实验报告，明晚20:00前提交").drafts.single()
        assertEquals("作业", draft.category); assertEquals("DEADLINE", draft.kind)
        assertNotNull(draft.dueAt)
        val pending = extract("请完成C语言实验报告").drafts.single()
        assertEquals("作业", pending.category); assertEquals("DEADLINE", pending.kind)
        assertNull(pending.dueAt)
    }
    @Test fun actualModelSeparatesRangeEndFromNewEvent() = runBlocking {
        val result = extract("明天14:00至15:00在图书馆301召开竞赛培训。")
        assertFalse(result.ignored)
        val draft = result.drafts.single()
        assertEquals("SCHEDULE", draft.kind); assertNotNull(draft.startAt); assertNotNull(draft.endAt)
        assertEquals(3_600_000L, draft.endAt!! - draft.startAt!!)
    }
    @Test fun actualModelPredictsChangeIntent() = runBlocking {
        assertEquals("UPDATE", extract("竞赛培训延期至后天16:00至17:00，地点改为图书馆302。").operation)
        assertEquals("CANCEL", extract("竞赛培训取消").operation)
    }
    @Test fun selectedNotificationWithFormerIgnoredWordsReachesModel() = runBlocking {
        val result = extract("广告设计会议定于明天14:00在图书馆举行，请勿泄露验证码。")
        assertFalse(result.ignored); assertTrue(result.drafts.isNotEmpty())
    }
}
