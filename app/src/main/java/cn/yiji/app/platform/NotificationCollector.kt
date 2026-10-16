package cn.yiji.app.platform

import android.service.notification.StatusBarNotification
import android.util.Log
import cn.yiji.app.data.IngestDisposition
import cn.yiji.app.data.Repository
import kotlinx.coroutines.CancellationException

class NotificationCollector(private val repository: Repository, private val monitor: NotificationMonitor) {
    suspend fun collect(sbn: StatusBarNotification, source: String, recovery: Boolean = false) {
        val prefs = repository.preferences
        if (sbn.packageName !in prefs.packages) return
        if (!prefs.collecting) { monitor.record(source, "采集已暂停"); return }
        try {
            val texts = NotificationTextExtractor.extract(sbn.notification, sbn.postTime)
            if (texts.isEmpty()) { monitor.record(source, "收到通知，但没有可读文字；请检查来源应用的通知内容显示设置"); return }
            var pending = 0; var ignored = 0; var duplicate = 0; var failed = 0
            for (text in texts) {
                if (!prefs.collecting || sbn.packageName !in prefs.packages) break
                try {
                    when (repository.ingestWithResult(text.title, text.body, source, sbn.packageName, text.receivedAt).disposition) {
                        IngestDisposition.PENDING -> pending++
                        IngestDisposition.IGNORED -> ignored++
                        IngestDisposition.DUPLICATE -> duplicate++
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    failed++
                    Log.e("YijiNotifications", "Notification processing failed: ${e.javaClass.simpleName}")
                }
            }
            val counts = listOf(pending to "条待确认", ignored to "条已忽略", duplicate to "条重复", failed to "条处理失败（可尝试补采）")
                .filter { it.first > 0 }.joinToString("，") { "${it.first}${it.second}" }
            val textWarning = if (texts.all { it.body.isBlank() }) "；仅提供标题，通知未提供正文" else ""
            monitor.record(source, (if (recovery) "补采：" else "收到通知：") + counts.ifBlank { "采集设置已变更" } + textWarning)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            monitor.record(source, "通知读取失败，可尝试重新连接并补采")
            Log.e("YijiNotifications", "Notification read failed: ${e.javaClass.simpleName}")
        }
    }
}
