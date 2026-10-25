package cn.yiji.app

import android.app.Application
import androidx.room.Room
import cn.yiji.app.data.*
import cn.yiji.app.platform.ReminderScheduler
import cn.yiji.app.platform.NotificationMonitor
import cn.yiji.app.platform.ExtractionWork
import cn.yiji.app.platform.CollectionSupervisor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class YijiApplication : Application(), androidx.work.Configuration.Provider {
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build()
    val notificationMonitor by lazy { NotificationMonitor(this) }
    val collectionSupervisor by lazy { CollectionSupervisor(this) }
    // Reconnecting the listener must not cancel notifications already queued in this process.
    val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val repository: Repository by lazy {
        Repository(Room.databaseBuilder(this, YijiDatabase::class.java, "yiji.db")
            .addMigrations(YijiDatabase.MIGRATION_1_2, YijiDatabase.MIGRATION_2_3, YijiDatabase.MIGRATION_3_4).build(), Preferences(this), ReminderScheduler(this))
    }
    override fun onCreate() {
        super.onCreate()
        val processName=if(android.os.Build.VERSION.SDK_INT>=28) Application.getProcessName()
            else getSystemService(android.app.ActivityManager::class.java).runningAppProcesses?.firstOrNull { it.pid==android.os.Process.myPid() }?.processName.orEmpty()
        if(processName.endsWith(":qq_capture")) return
        notificationScope.launch { cn.yiji.app.qq.QqIngress.drain(this@YijiApplication) }
        ReminderScheduler(this).createChannel()
        notificationScope.launch { repository.recoverMisclassifiedNotices() }
        ExtractionWork.schedule(this)
        collectionSupervisor.refresh()
    }
}
