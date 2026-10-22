package cn.yiji.app.ml

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ModelRuntimeTest {
    @Test fun hardNegativeRegressionUsesTheActualBundledGraph() = runBlocking {
        listOf("123", "000000", "20261019", "。。。。。。", "收到，谢谢。", "作业写完了，太开心了。")
            .forEach { assertFalse(it, gate.classify(it).candidate) }
        assertTrue(gate.classify("15:30").candidate)
        assertTrue(gate.classify("原定体测调整，具体方案另行通知").candidate)
    }
    private val gate get() = TinyBertMessageGate(ApplicationProvider.getApplicationContext<Context>())
    @Test fun bundledBinaryGraphFiltersChatAndQuestions() = runBlocking {
        listOf("晚饭吃什么", "请问实验报告怎么提交", "你有3条新消息", "谢谢老师，我已经提交了")
            .forEach { assertFalse(it, gate.classify(it).candidate) }
    }
    @Test fun bundledBinaryGraphRoutesAssignmentsAndIncompleteSchedules() = runBlocking {
        listOf("C语言实验报告，明晚20:00前提交", "明天14点到301集合，带学生证", "会议时间另行通知",
            "后天18点前提交报名表", "改到周五，地点不变", "原来的活动取消了",
            "明晚交实验报告，后天参加讲座，原定班会取消")
            .forEach { assertTrue(it, gate.classify(it).candidate) }
    }
    @Test fun importantPartOfLongMessageIsNotAveragedAway() = runBlocking {
        val text = "今天食堂的饭真香。".repeat(65) + "明天14点到301集合，带学生证。" + "晚饭吃什么。".repeat(65)
        assertTrue(gate.classify(text).candidate)
    }
    @Test fun sentenceWindowsStillRejectLongCompletedOrCasualMessages() = runBlocking {
        val text = "今天食堂的饭真香。".repeat(65) + "作业写完了，太开心了。" + "收到，谢谢。".repeat(65)
        assertFalse(gate.classify(text).candidate)
    }
    @Test fun advertisementWordCannotOverrideTheLearnedRoute() = runBlocking {
        assertTrue(gate.classify("广告设计会议定于明天14:00在图书馆举行，请勿泄露验证码。").candidate)
    }
}
