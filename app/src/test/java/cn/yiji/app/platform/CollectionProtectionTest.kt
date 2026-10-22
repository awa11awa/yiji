package cn.yiji.app.platform

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import cn.yiji.app.MainActivity
import cn.yiji.app.YijiApplication
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowNotificationListenerService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = YijiApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class CollectionProtectionTest {
    private lateinit var app: YijiApplication

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("yiji-settings", Context.MODE_PRIVATE).edit().clear().commit()
        app.repository.preferences.collecting = true
        app.repository.preferences.packages = setOf("com.tencent.mm")
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners",
            ComponentName(app, YijiNotificationListener::class.java).flattenToString())
    }

    @After fun teardown() = runBlocking {
        app.repository.preferences.collecting = false
        app.collectionSupervisor.refresh()
        app.notificationScope.coroutineContext[Job]!!.children.toList().joinAll()
        app.notificationScope.cancel()
        app.repository.database.close()
    }

    @Test fun serviceProtectsWhileEnabledAndRespectsPersistentPauseAction() {
        val controller = Robolectric.buildService(CollectionProtectionService::class.java).create()
        val service = controller.get()
        assertEquals(Service.START_STICKY, service.onStartCommand(Intent(app, service.javaClass), 0, 1))
        assertTrue(app.notificationMonitor.status.value.protectionActive)
        val notification = requireNotNull(shadowOf(service).lastForegroundNotification)
        assertEquals("忆迹正在恢复通知监听", notification.extras.getCharSequence("android.title"))
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent().setAction(CollectionProtectionService.ACTION_PAUSE), 0, 2))
        assertFalse(app.repository.preferences.collecting)
        controller.destroy()
        assertFalse(app.notificationMonitor.status.value.protectionActive)
        val restarted = Robolectric.buildService(CollectionProtectionService::class.java).create()
        assertEquals(Service.START_NOT_STICKY, restarted.get().onStartCommand(null, 0, 3))
        assertFalse(app.notificationMonitor.status.value.protectionActive)
        restarted.destroy()
    }

    @Test fun disabledProtectionDoesNotStartOnStickyRestart() {
        app.repository.preferences.protectCollection = false
        val controller = Robolectric.buildService(CollectionProtectionService::class.java).create()
        assertEquals(Service.START_NOT_STICKY, controller.get().onStartCommand(null, 0, 1))
        assertFalse(app.notificationMonitor.status.value.protectionActive)
        controller.destroy()
    }

    @Test fun appResumeRequestsConnectionWithoutOpeningSettings() {
        app.repository.preferences.protectCollection = false
        val before = ShadowNotificationListenerService.getRebindRequestCount()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ShadowNotificationListenerService.getRebindRequestCount() > before)
        controller.pause().stop().destroy()
    }
}
