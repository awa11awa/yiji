package cn.yiji.app.platform

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import cn.yiji.app.YijiApplication
import kotlinx.coroutines.*

class YijiNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(200)
        val body = listOfNotNull(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n"))
            .filter { it.isNotBlank() }.distinct().joinToString("\n").take(16000)
        if (body.isBlank()) return
        val repository = (application as YijiApplication).repository
        if (!repository.preferences.accepts(sbn.packageName, "$title $body")) return
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        scope.launch { runCatching { repository.ingest(title, body, label, sbn.packageName, sbn.postTime) } }
    }
    override fun onListenerDisconnected() { requestRebind(ComponentName(this, javaClass)) }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
