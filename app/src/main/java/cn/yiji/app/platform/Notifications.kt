package cn.yiji.app.platform

import android.content.Context
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import cn.yiji.app.YijiApplication
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

class YijiNotificationListener : NotificationListenerService() {
    private val app get() = application as YijiApplication
    private var connected = false
    private var lastRecoveryAt: Long? = null
    private var recoveredPackages: Set<String> = emptySet()

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        instance = WeakReference(this)
        app.notificationMonitor.connection(true)
        app.collectionSupervisor.connected()
        app.notificationMonitor.record("系统", "通知监听已连接")
        lastRecoveryAt = null
        recoverActiveNotifications()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Summary updates can be the only notification carrying a later WeChat message.
        enqueue(sbn)
    }

    private fun enqueue(sbn: StatusBarNotification, recovery: Boolean = false) {
        if (sbn.packageName == packageName || !app.repository.preferences.accepts(sbn.packageName)) return
        val application = app
        application.notificationScope.launch {
            val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }
                .getOrDefault(sbn.packageName)
            NotificationCollector(application.repository, application.notificationMonitor).collect(sbn, label, recovery)
        }
    }

    private fun recoverActiveNotifications(manual: Boolean = false) {
        if (!connected || !app.repository.preferences.collecting) return
        val packages = app.repository.preferences.packages
        val now = SystemClock.elapsedRealtime()
        val minimumInterval = if (manual) 1_000L else 30_000L
        if (packages == recoveredPackages && lastRecoveryAt?.let { now - it < minimumInterval } == true) return
        lastRecoveryAt = now
        recoveredPackages = packages
        runCatching { activeNotifications.orEmpty() }
            .onSuccess { notifications ->
                val selected = notifications.filter { it.packageName in app.repository.preferences.packages && it.packageName != packageName }
                app.notificationMonitor.record("系统", "补采通知栏：${selected.size} 条选定来源通知")
                selected.forEach { enqueue(it, recovery = true) }
            }.onFailure { app.notificationMonitor.record("系统", "无法补采通知栏，请重新开启系统通知读取权限") }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        connected = false
        if (instance?.get() !== this) return
        app.notificationMonitor.connection(false)
        app.notificationMonitor.record("系统", "通知监听已断开，正在请求重新连接")
        app.collectionSupervisor.refresh()
    }

    override fun onDestroy() {
        if (instance?.get() === this) {
            instance = null
            app.notificationMonitor.connection(false)
            app.collectionSupervisor.refresh()
        }
        connected = false
        // Queued work belongs to the application and continues through service recreation.
        super.onDestroy()
    }

    companion object {
        @Volatile private var instance: WeakReference<YijiNotificationListener>? = null

        fun isConnected(): Boolean = instance?.get()?.connected == true

        internal fun resetRecovery() { instance?.get()?.lastRecoveryAt = null }

        internal fun recover(manual: Boolean = false) {
            instance?.get()?.recoverActiveNotifications(manual)
        }

        /** Manual UI action; automatic recovery is owned by the application supervisor. */
        fun reconnectAndRecover(context: Context) {
            (context.applicationContext as YijiApplication).collectionSupervisor.refresh(
                allowForegroundStart = true, recover = true, manual = true)
        }
    }
}
