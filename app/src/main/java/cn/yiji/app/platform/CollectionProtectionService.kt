package cn.yiji.app.platform

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import cn.yiji.app.MainActivity
import cn.yiji.app.R
import cn.yiji.app.YijiApplication
import cn.yiji.app.data.Event
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine

/** User-visible continuous capture protection. No wake lock, network, model or alarm loop. */
class CollectionProtectionService : Service() {
    private val app get() = application as YijiApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "持续采集保护", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示通知采集状态，并提供暂停入口"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            app.repository.preferences.collecting = false
            app.notificationMonitor.record("系统", "已从常驻通知暂停采集")
            app.collectionSupervisor.refresh()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!app.collectionSupervisor.eligible() || !app.repository.preferences.protectCollection) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!showStatus(app.notificationMonitor.status.value.connected)) return START_NOT_STICKY
        app.notificationMonitor.protection(true)
        if (!observing) {
            observing = true
            scope.launch {
                combine(app.notificationMonitor.status.map { it.connected }.distinctUntilChanged(), app.repository.events) { connected, events ->
                    connected to events.filter { it.status == "ACTIVE" }
                }.distinctUntilChanged().collect { (connected, events) ->
                    showStatus(connected, events)
                }
            }
            // A non-waking check catches lost callbacks. Healthy listeners are never rescanned.
            scope.launch {
                while (isActive) {
                    app.collectionSupervisor.refresh()
                    delay(5 * 60_000L)
                }
            }
        }
        return START_STICKY
    }

    private fun showStatus(connected: Boolean, events: List<Event> = emptyList()): Boolean = try {
        ServiceCompat.startForeground(this, ID, notification(connected, events),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        true
    } catch (_: RuntimeException) {
        app.notificationMonitor.record("系统", "系统未允许采集保护，请检查后台运行设置")
        stopSelf()
        false
    }

    private fun notification(connected: Boolean, events: List<Event>): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, CollectionProtectionService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_reminder)
            .setContentTitle(if (events.isNotEmpty()) "${events.size} 项未完成待办" else if (connected) "忆迹正在采集通知" else "忆迹正在恢复通知监听")
            .setContentText((if (!connected) "监听恢复中 · " else "") + (ReminderText.next(events) ?: "仅接收已选来源；无消息时休眠"))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "暂停采集", pause).build()
    }

    override fun onDestroy() {
        scope.cancel()
        app.notificationMonitor.protection(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "yiji-collection"
        private const val ID = 2104
        const val ACTION_PAUSE = "cn.yiji.app.PAUSE_COLLECTION"
        fun start(context: Context): kotlin.Result<Unit> = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, CollectionProtectionService::class.java))
            Unit
        }
        fun stop(context: Context) { context.stopService(Intent(context, CollectionProtectionService::class.java)) }
    }
}
