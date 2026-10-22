package cn.yiji.app.platform

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import cn.yiji.app.YijiApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Main-thread recovery state. Healthy listeners need no timer or repeated notification scan. */
class CollectionSupervisor(private val app: YijiApplication) {
    private val handler = Handler(Looper.getMainLooper())
    private var retry: Runnable? = null
    private var attempts = 0
    private var lastRequestAt: Long? = null
    private var scheduled: Boolean? = null
    private var startFailureRecorded = false

    fun authorized(): Boolean = NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)
    fun eligible(): Boolean = app.repository.preferences.let { it.collecting && it.packages.isNotEmpty() } && authorized()

    /** Foreground starts are allowed only at a visible user action or an OS-exempt boot/update. */
    fun refresh(allowForegroundStart: Boolean = false, recover: Boolean = false, manual: Boolean = false) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val active = eligible()
        if (scheduled != active) {
            scheduled = active
            CollectionRecoveryWork.schedule(app, active)
        }
        if (!active) {
            cancelRetry()
            lastRequestAt = null
            YijiNotificationListener.resetRecovery()
            CollectionProtectionService.stop(app)
            return
        }
        if (!app.repository.preferences.protectCollection) CollectionProtectionService.stop(app)
        else if (allowForegroundStart && !app.notificationMonitor.status.value.protectionActive) {
            CollectionProtectionService.start(app).onSuccess { startFailureRecorded = false }
                .onFailure {
                    if (!startFailureRecorded) app.notificationMonitor.record("系统", "采集保护未启动，请打开应用并检查后台运行设置")
                    startFailureRecorded = true
                }
        }
        if (YijiNotificationListener.isConnected()) {
            cancelRetry()
            if (recover) YijiNotificationListener.recover(manual)
            return
        }
        // Guard checks and repeated UI resumes must not bypass an outstanding backoff.
        if (retry != null && !manual) return
        if (manual) { retry?.let { handler.removeCallbacks(it) }; retry = null }
        val now = SystemClock.elapsedRealtime()
        if (lastRequestAt == null || now - lastRequestAt!! >= 2_000) {
            lastRequestAt = now
            runCatching { NotificationListenerService.requestRebind(ComponentName(app, YijiNotificationListener::class.java)) }
                .onSuccess {
                    // Keep useful receipt diagnostics instead of filling all 20 slots with retries.
                    if (attempts == 0) app.notificationMonitor.record("系统", "已请求重新连接，等待系统响应；失败后将自动重试")
                }.onFailure {
                    if (attempts == 0) app.notificationMonitor.record("系统", "重新连接请求失败，将自动重试；请检查通知读取权限")
                }
        }
        if (retry == null) {
            val delay = RETRY_DELAYS[minOf(attempts, RETRY_DELAYS.lastIndex)]
            attempts++
            retry = Runnable { retry = null; refresh() }.also { handler.postDelayed(it, delay) }
        }
    }

    fun connected() { cancelRetry() }

    private fun cancelRetry() {
        retry?.let { handler.removeCallbacks(it) }
        retry = null
        attempts = 0
    }

    companion object {
        private val RETRY_DELAYS = longArrayOf(2_000, 10_000, 30_000, 60_000, 300_000)
    }
}

/** Inexact, batched safety net after process loss; never starts an FGS from a Worker. */
class CollectionRecoveryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        withContext(Dispatchers.Main) { (applicationContext as YijiApplication).collectionSupervisor.refresh() }
        return Result.success()
    }
}

object CollectionRecoveryWork {
    private const val NAME = "yiji-listener-recovery"
    fun schedule(context: Context, enabled: Boolean) {
        val work = WorkManager.getInstance(context)
        if (!enabled) { work.cancelUniqueWork(NAME); return }
        work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CollectionRecoveryWorker>(30, TimeUnit.MINUTES, 10, TimeUnit.MINUTES)
                .build())
    }
}
