package cn.yiji.app.qq

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.yiji.app.YijiApplication
import cn.yiji.app.data.IngestDisposition
import cn.yiji.app.data.Repository
import cn.yiji.app.platform.ExtractionWork
import dev.yiji.qqlocalprobe.QqDiagnostics
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Broadcasts are only wakeups. All message bytes remain in the committed spool. */
object QqIngress {
    private val lock=Mutex()
    suspend fun <T> exclusive(action: suspend () -> T): T = lock.withLock { action() }
    fun signal(context: Context) { context.sendBroadcast(Intent(context,QqIngressReceiver::class.java)) }
    suspend fun drain(app: YijiApplication) {
        if(!QqStore.exists(app)) return
        val count = drain(app.repository)
        if(count>0) { ExtractionWork.schedule(app);app.notificationMonitor.record("QQ","${count} 条原文已保存，等待筛选提取") }
    }
    suspend fun drain(repository: Repository): Int {
        val context = repository.preferences.context
        if(!QqStore.exists(context)) return 0
        return lock.withLock {
            QqStore(context).use { store ->
                val batch=store.pending();var count=0
                for(e in batch) {
                    if(store.selected(e.account,e.scene,e.peer)) {
                        if(repository.enqueueQq(e,false).disposition==IngestDisposition.QUEUED) count++
                    }
                    store.acknowledge(e.id)
                }
                if(batch.size==64) signal(context)
                count
            }
        }
    }
}
class QqIngressReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val result=goAsync();val app=context.applicationContext as YijiApplication
        app.notificationScope.launch {
            try { QqIngress.drain(app) }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { QqDiagnostics.error(context,"QQ-IMPORT",e) }
            finally { result.finish() }
        }
    }
}
