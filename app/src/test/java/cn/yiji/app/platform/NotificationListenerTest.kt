package cn.yiji.app.platform

import android.app.Notification
import android.content.Context
import android.content.ComponentName
import android.os.Looper
import android.os.Process
import android.provider.Settings
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
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowNotificationListenerService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = YijiApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
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
        app.repository.preferences.protectCollection = false
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners",
            ComponentName(app, YijiNotificationListener::class.java).flattenToString())
        controller = Robolectric.buildService(YijiNotificationListener::class.java).create()
        service = controller.get()
    }
    @After fun teardown() = runBlocking {
        app.repository.preferences.collecting = false
        app.collectionSupervisor.refresh()
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
        assertEquals(2, app.repository.dao.allJobs().size)
        assertTrue(app.repository.dao.allSuggestions().isEmpty())
    }

    @Test fun connectionRecoversActiveNotificationsWithoutRepeatingOnRescan() = runBlocking {
        shadowOf(service).addActiveNotification(sbn("明天14点到301集合", summary = true))
        service.onListenerConnected()
        drain()
        assertTrue(app.notificationMonitor.status.value.connected)
        assertEquals(1, app.repository.dao.allRaw().size)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
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

    @Test fun automaticForegroundRecoveryDoesNotRepeatedlyScanHealthyListener() = runBlocking {
        shadowOf(service).addActiveNotification(sbn("明天14点到301集合"))
        service.onListenerConnected()
        drain()
        repeat(5) { app.collectionSupervisor.refresh(recover = true) }
        drain()
        assertEquals(1, app.notificationMonitor.status.value.records.count { "补采通知栏" in it.result })
        assertEquals(1, app.repository.dao.allRaw().size)
    }

    @Test fun failedRebindAutomaticallyRetriesWithBackoffAndStopsWhenConnected() {
        val before = ShadowNotificationListenerService.getRebindRequestCount()
        app.collectionSupervisor.refresh()
        repeat(5) { app.collectionSupervisor.refresh() }
        assertEquals(before + 1, ShadowNotificationListenerService.getRebindRequestCount())
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals(before + 2, ShadowNotificationListenerService.getRebindRequestCount())
        shadowOf(Looper.getMainLooper()).idleFor(9, TimeUnit.SECONDS)
        assertEquals(before + 2, ShadowNotificationListenerService.getRebindRequestCount())
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        assertEquals(before + 3, ShadowNotificationListenerService.getRebindRequestCount())
        shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.SECONDS)
        assertEquals(before + 4, ShadowNotificationListenerService.getRebindRequestCount())
        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.SECONDS)
        assertEquals(before + 5, ShadowNotificationListenerService.getRebindRequestCount())
        shadowOf(Looper.getMainLooper()).idleFor(299, TimeUnit.SECONDS)
        assertEquals(before + 5, ShadowNotificationListenerService.getRebindRequestCount())
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        assertEquals(before + 6, ShadowNotificationListenerService.getRebindRequestCount())
        shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.SECONDS)
        assertEquals(before + 7, ShadowNotificationListenerService.getRebindRequestCount())
        service.onListenerConnected()
        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MINUTES)
        assertEquals(before + 7, ShadowNotificationListenerService.getRebindRequestCount())
    }

    @Test fun pausingCancelsRecoveryAndPreventsNewImports() = runBlocking {
        app.collectionSupervisor.refresh()
        val before = ShadowNotificationListenerService.getRebindRequestCount()
        app.repository.preferences.collecting = false
        app.collectionSupervisor.refresh()
        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MINUTES)
        service.onNotificationPosted(sbn("明天14点到301集合"))
        drain()
        assertEquals(before, ShadowNotificationListenerService.getRebindRequestCount())
        assertTrue(app.repository.dao.allRaw().isEmpty())
    }

    @Test fun revokedPermissionStopsRebindRetries() {
        app.collectionSupervisor.refresh()
        val before = ShadowNotificationListenerService.getRebindRequestCount()
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", "")
        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MINUTES)
        assertEquals(before, ShadowNotificationListenerService.getRebindRequestCount())
    }

    @Test fun resumeAfterPauseImmediatelyRecoversRetainedNotifications() = runBlocking {
        service.onListenerConnected()
        app.repository.preferences.collecting = false
        app.collectionSupervisor.refresh()
        shadowOf(service).addActiveNotification(sbn("后天18点前提交报名表"))
        app.repository.preferences.collecting = true
        app.collectionSupervisor.refresh(recover = true)
        drain()
        assertEquals(1, app.repository.dao.allRaw().size)
    }

    @Test fun staleServiceDestructionDoesNotDisconnectNewListener() {
        service.onListenerConnected()
        val newer = Robolectric.buildService(YijiNotificationListener::class.java).create()
        newer.get().onListenerConnected()
        controller.destroy()
        destroyed = true
        assertTrue(YijiNotificationListener.isConnected())
        assertTrue(app.notificationMonitor.status.value.connected)
        newer.destroy()
    }
}
