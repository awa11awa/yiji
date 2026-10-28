package cn.yiji.app.platform

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class NotificationTextTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun expandedTextAndComplementaryFieldsAreKeptWithoutStaleCollapsedText() {
        val notification = Notification.Builder(context, "messages").setContentTitle("班级群")
            .setContentText("过期旧消息").setStyle(Notification.BigTextStyle().bigText("明天14点到301集合，请带电脑")).build()
        notification.extras.putCharSequence(Notification.EXTRA_SUB_TEXT, "辅导员")
        notification.extras.putCharSequence(Notification.EXTRA_INFO_TEXT, "报名截止10月22日")
        notification.extras.putCharSequence(Notification.EXTRA_SUMMARY_TEXT, "明天14点到301集合")
        val text = NotificationTextExtractor.extract(notification, 10).single()
        assertTrue(text.body.contains("请带电脑")); assertTrue(text.body.contains("辅导员"))
        assertTrue(text.body.contains("报名截止")); assertFalse(text.body.contains("过期旧消息"))
        assertEquals(1, Regex("明天14点到301集合").findAll(text.body).count())
    }
    @Test fun multipleMessagesKeepTheirOwnBodiesAndPreserveSharedMetadataSeparately() {
        val notification = Notification.Builder(context, "messages").setContentTitle("群助手").build()
        notification.extras.putCharSequenceArray(Notification.EXTRA_TEXT_LINES, arrayOf("张三：明天交作业", "李四：收到"))
        notification.extras.putCharSequence(Notification.EXTRA_INFO_TEXT, "学院通知")
        val records = NotificationTextExtractor.extract(notification, 10)
        assertEquals(listOf("张三：明天交作业", "李四：收到", "学院通知"), records.map { it.body })
    }
    @Test fun titleOnlyAndComplementaryOnlyNotificationsAreNotDropped() {
        val titleOnly = Notification.Builder(context, "messages").setContentTitle("通知标题").build()
        assertEquals("通知标题", NotificationTextExtractor.extract(titleOnly, 10).single().title)
        val infoOnly = Notification.Builder(context, "messages").build()
        infoOnly.extras.putCharSequence(Notification.EXTRA_INFO_TEXT, "截止10月22日")
        assertEquals("截止10月22日", NotificationTextExtractor.extract(infoOnly, 10).single().body)
    }
}
