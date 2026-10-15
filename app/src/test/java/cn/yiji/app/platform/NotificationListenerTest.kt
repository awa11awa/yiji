package cn.yiji.app.platform

import android.app.Notification
import android.content.Context
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import cn.yiji.app.YijiApplication
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationListenerService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = YijiApplication::class)
class NotificationListenerTest {
    private lateinit var app: YijiApplication
    private lateinit var controller: ServiceController<YijiNotificationListener>
    private lateinit var service: YijiNotificationListener
    private var destroyed = false
    @Before fun setup() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("yiji-settings", Context.MODE_PRIVATE).edit().clear().commit()
        app.notificationMonitor.clear()
        app.repository.clear()
        app.repository.preferences.collecting = true
        app.repository.preferences.packages = setOf("com.tencent.mm")
        controller = Robolectric.buildService(YijiNotificationListener::class.java).create()
        service = controller.get()
    }
    @After fun teardown() = runBlocking {
        drain()
        if (!destroyed) controller.destroy()
        app.notificationScope.cancel()
        app.repository.database.close()
    }
    private suspend fun drain() = app.notificationScope.coroutineContext[Job]!!.children.toList().joinAll()
    private fun sbn(body: String, summary: Boolean = false): StatusBarNotification {
        val n = Notification.Builder(app, "messages").setContentTitle("班级群").setContentText(body)
            .setGroup("wechat").setGroupSummary(summary).build()
        return StatusBarNotification("com.tencent.mm", "com.tencent.mm", 7, "conversation", 0, 0, 0, n, Process.myUserHandle(), System.currentTimeMillis())
    }

    @Test fun callbackImportsLaterSummaryAfterFirstMessage() = runBlocking {
        service.onNotificationPosted(sbn("明天14点到301集合"))
        drain()
        service.onNotificationPosted(sbn("后天18点前提交报名表", summary = true))
        drain()
        assertEquals(2, app.repository.dao.allRaw().size)
        assertEquals(2, app.repository.dao.allSuggestions().size)
    }

    @Test fun connectionRecoversActiveNotificationsWithoutRepeatingOnRescan() = runBlocking {
        shadowOf(service).addActiveNotification(sbn("明天14点到301集合", summary = true))
        service.onListenerConnected()
        drain()
        assertTrue(app.notificationMonitor.status.value.connected)
        assertEquals(1, app.repository.dao.allRaw().size)
        YijiNotificationListener.reconnectAndRecover(app)
        drain()
        assertEquals(1, app.repository.dao.allRaw().size)
        assertTrue(app.notificationMonitor.status.value.records.any { "条重复" in it.result })
    }

    @Test fun disconnectRequestsRebindAndUpdatesActualConnectionState() {
        service.onListenerConnected()
        val before = ShadowNotificationListenerService.getRebindRequestCount()
        service.onListenerDisconnected()
        assertFalse(app.notificationMonitor.status.value.connected)
        assertEquals(before + 1, ShadowNotificationListenerService.getRebindRequestCount())
    }

    @Test fun listenerDestructionDoesNotCancelQueuedNotificationWork() = runBlocking {
        service.onListenerConnected()
        service.onNotificationPosted(sbn("明天14点到301集合"))
        controller.destroy()
        destroyed = true
        drain()
        assertFalse(app.notificationMonitor.status.value.connected)
        assertTrue(app.notificationScope.coroutineContext[Job]!!.isActive)
        assertEquals(1, app.repository.dao.allRaw().size)
    }
}
