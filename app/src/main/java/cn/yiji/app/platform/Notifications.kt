package cn.yiji.app.platform

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import cn.yiji.app.YijiApplication
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

class YijiNotificationListener : NotificationListenerService() {
    private val app get() = application as YijiApplication
    private var connected = false

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        instance = WeakReference(this)
        app.notificationMonitor.connection(true)
        app.notificationMonitor.record("系统", "通知监听已连接")
        recoverActiveNotifications()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Summary updates can be the only notification carrying a later WeChat message.
        enqueue(sbn)
    }

    private fun enqueue(sbn: StatusBarNotification, recovery: Boolean = false) {
        if (sbn.packageName == packageName || sbn.packageName !in app.repository.preferences.packages) return
        val application = app
        application.notificationScope.launch {
            val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }
                .getOrDefault(sbn.packageName)
            NotificationCollector(application.repository, application.notificationMonitor).collect(sbn, label, recovery)
        }
    }

    private fun recoverActiveNotifications() {
        if (!connected || !app.repository.preferences.collecting) return
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
        app.notificationMonitor.connection(false)
        app.notificationMonitor.record("系统", "通知监听已断开，正在请求重新连接")
        requestConnection(this)
    }

    override fun onDestroy() {
        if (instance?.get() === this) {
            instance = null
            app.notificationMonitor.connection(false)
        }
        connected = false
        // Queued work belongs to the application and continues through service recreation.
        super.onDestroy()
    }

    companion object {
        @Volatile private var instance: WeakReference<YijiNotificationListener>? = null

        /** Call on the main thread. Only request a rebind when the listener is disconnected. */
        fun reconnectAndRecover(context: Context) {
            val listener = instance?.get()
            if (listener?.connected == true) listener.recoverActiveNotifications()
            else requestConnection(context)
        }

        private fun requestConnection(context: Context) {
            val monitor = (context.applicationContext as YijiApplication).notificationMonitor
            runCatching { requestRebind(ComponentName(context, YijiNotificationListener::class.java)) }
                .onSuccess { monitor.record("系统", "已请求重新连接，等待系统响应") }
                .onFailure { monitor.record("系统", "重新连接请求失败，请在系统设置中重新开启通知读取权限") }
        }
    }
}
