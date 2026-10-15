package cn.yiji.app.platform

import android.app.Notification
import android.content.Context
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.yiji.app.data.Preferences
import cn.yiji.app.data.Repository
import cn.yiji.app.data.YijiDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
@Suppress("DEPRECATION")
class NotificationCollectorTest {
    private lateinit var context: Context
    private lateinit var database: YijiDatabase
    private lateinit var repository: Repository
    private lateinit var monitor: NotificationMonitor
    private lateinit var collector: NotificationCollector
    private val time = java.time.ZonedDateTime.of(2026, 10, 15, 12, 0, 0, 0, java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val first = "明天14点到301集合，带学生证"
    private val second = "明天18点前提交报名表"
    private val third = "后天9点在图书馆举行讲座"

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("yiji-settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("yiji-notification-status", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, YijiDatabase::class.java).allowMainThreadQueries().build()
        repository = Repository(database, Preferences(context), ReminderScheduler(context))
        repository.preferences.collecting = true
        repository.preferences.packages = setOf("com.tencent.mm")
        monitor = NotificationMonitor(context)
        collector = NotificationCollector(repository, monitor)
    }
    @After fun teardown() { database.close() }
    private fun notification(body: String, summary: Boolean = false, title: String = "班级群"): Notification =
        Notification.Builder(context, "messages").setContentTitle(title).setContentText(body)
            .setGroup("wechat").setGroupSummary(summary).build()
    private fun sbn(notification: Notification, postTime: Long = time, pkg: String = "com.tencent.mm") =
        StatusBarNotification(pkg, pkg, 7, "conversation", 0, 0, 0, notification, Process.myUserHandle(), postTime)
    private fun conversation(vararg bodies: String): Notification {
        val style = Notification.MessagingStyle("我").setConversationTitle("班级群")
        bodies.forEachIndexed { i, body -> style.addMessage(body, time + i * 1000, "张三") }
        return Notification.Builder(context, "messages").setStyle(style).build()
    }

    @Test fun firstNotificationFollowedBySummaryUpdatesKeepsDifferentMessages() = runBlocking {
        collector.collect(sbn(notification(first)), "微信")
        collector.collect(sbn(notification(second, summary = true), time + 1000), "微信")
        collector.collect(sbn(notification(third, summary = true), time + 2000), "微信")
        assertEquals(setOf(first, second, third), repository.dao.allRaw().map { it.body }.toSet())
        assertEquals(3, repository.dao.allSuggestions().size)
    }

    @Test fun sameNotificationKeyCanBeUpdatedRepeatedly() = runBlocking {
        listOf(first, second, second, third).forEachIndexed { i, body ->
            collector.collect(sbn(notification(body), time + i * 1000), "微信")
        }
        assertEquals(3, repository.dao.allRaw().size)
        assertTrue(monitor.status.value.records.any { "1条重复" in it.result })
    }

    @Test fun growingMessagingStyleImportsEachUnreadMessageOnce() = runBlocking {
        collector.collect(sbn(conversation(first)), "微信")
        collector.collect(sbn(conversation(first, second), time + 1000), "微信")
        collector.collect(sbn(conversation(first, second, third), time + 2000), "微信")
        val raws = repository.dao.allRaw()
        assertEquals(setOf(first, second, third), raws.map { it.body }.toSet())
        assertTrue(raws.all { it.title == "班级群" })
        assertEquals(time + 1000, raws.single { it.body == second }.receivedAt)
        assertEquals(3, repository.dao.allSuggestions().size)
        assertTrue(monitor.status.value.records.first().result.contains("2条重复"))
    }

    @Test @Config(sdk = [26]) fun messagingStyleWorksOnMinimumAndroidVersion() = runBlocking {
        collector.collect(sbn(conversation(first, second), time + 1000), "微信")
        assertEquals(setOf(first, second), repository.dao.allRaw().map { it.body }.toSet())
    }

    @Test fun oldNoiseLineDoesNotDiscardNewImportantLine() = runBlocking {
        val n = notification("2条新消息", summary = true)
        n.extras.putCharSequenceArray(Notification.EXTRA_TEXT_LINES, arrayOf("登录验证码123456", second))
        collector.collect(sbn(n), "微信")
        assertEquals(second, repository.dao.allRaw().single().body)
        assertTrue(monitor.status.value.records.first().result.contains("1条被关键词过滤"))
    }

    @Test fun oldMessagingNoiseDoesNotDiscardAnotherMessage() = runBlocking {
        collector.collect(sbn(conversation("登录验证码123456", first), time + 1000), "微信")
        assertEquals(first, repository.dao.allRaw().single().body)
        assertEquals(1, repository.dao.allSuggestions().size)
    }

    @Test fun summaryAndChildWithSameTextDoNotCreateTwoSuggestions() = runBlocking {
        collector.collect(sbn(notification(first)), "微信")
        collector.collect(sbn(notification(first, summary = true), time + 1000), "微信")
        assertEquals(1, repository.dao.allRaw().size)
        assertEquals(1, repository.dao.allSuggestions().size)
        assertTrue(monitor.status.value.records.first().result.contains("1条重复"))
    }

    @Test fun unreadCountIsStoredAsIgnoredInsteadOfInventingAnEvent() = runBlocking {
        collector.collect(sbn(notification("你有3条新消息", summary = true, title = "微信")), "微信")
        assertEquals("IGNORED", repository.dao.allRaw().single().state)
        assertTrue(repository.dao.allSuggestions().isEmpty())
    }

    @Test fun titleOnlyNotificationKeepsItsOriginalText() = runBlocking {
        collector.collect(sbn(notification("", title = "班级群")), "微信")
        assertEquals("班级群", repository.dao.allRaw().single().title)
        assertEquals("IGNORED", repository.dao.allRaw().single().state)
        assertTrue(monitor.status.value.records.single().result.contains("仅提供标题"))
    }

    @Test fun tickerIsFallbackWhenExtrasHaveNoText() = runBlocking {
        val n = Notification().apply { tickerText = first }
        collector.collect(sbn(n), "微信")
        assertEquals(first, repository.dao.allRaw().single().body)
    }

    @Test fun emptyNotificationExplainsMissingText() = runBlocking {
        collector.collect(sbn(Notification()), "微信")
        assertTrue(repository.dao.allRaw().isEmpty())
        assertTrue(monitor.status.value.records.single().result.contains("没有可读文字"))
    }

    @Test fun expandedTextTakesPrecedenceOverStaleCollapsedText() = runBlocking {
        val n = notification(first)
        n.extras.putCharSequence(Notification.EXTRA_BIG_TEXT, second)
        collector.collect(sbn(n), "微信")
        assertEquals(second, repository.dao.allRaw().single().body)
    }

    @Test fun unimportantChatIsVisibleInIgnoredInbox() = runBlocking {
        collector.collect(sbn(notification("晚饭吃什么")), "微信")
        assertEquals("IGNORED", repository.dao.allRaw().single().state)
        assertTrue(monitor.status.value.records.single().result.contains("1条已忽略"))
    }

    @Test fun pausedAndUnselectedSourcesAreNotImported() = runBlocking {
        collector.collect(sbn(notification(first), pkg = "other.app"), "其他")
        assertTrue(monitor.status.value.records.isEmpty())
        repository.preferences.collecting = false
        collector.collect(sbn(notification(first)), "微信")
        assertTrue(repository.dao.allRaw().isEmpty())
        assertEquals("采集已暂停", monitor.status.value.records.single().result)
    }

    @Test fun recoveryUsesTheSameDeduplicationAndKeywordRules() = runBlocking {
        collector.collect(sbn(notification(first)), "微信")
        collector.collect(sbn(notification(first)), "微信", recovery = true)
        collector.collect(sbn(notification("验证码123456")), "微信", recovery = true)
        assertEquals(1, repository.dao.allRaw().size)
        assertTrue(monitor.status.value.records.first().result.startsWith("补采："))
        assertTrue(monitor.status.value.records.first().result.contains("被关键词过滤"))
    }

    @Test fun diagnosticHistoryIsBoundedAndConnectionIsNotRestoredAsLive() {
        monitor.connection(true)
        repeat(25) { monitor.record("微信", "结果 $it") }
        val restored = NotificationMonitor(context)
        assertFalse(restored.status.value.connected)
        assertEquals(20, restored.status.value.records.size)
        assertEquals("结果 24", restored.status.value.records.first().result)
        restored.clear()
        assertTrue(NotificationMonitor(context).status.value.records.isEmpty())
    }
}
