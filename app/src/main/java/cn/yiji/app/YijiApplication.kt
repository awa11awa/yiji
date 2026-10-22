package cn.yiji.app

import android.app.Application
import androidx.room.Room
import cn.yiji.app.data.*
import cn.yiji.app.platform.ReminderScheduler
import cn.yiji.app.platform.NotificationMonitor
import cn.yiji.app.platform.ExtractionWork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class YijiApplication : Application(), androidx.work.Configuration.Provider {
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build()
    val notificationMonitor by lazy { NotificationMonitor(this) }
    // Reconnecting the listener must not cancel notifications already queued in this process.
    val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val repository: Repository by lazy {
        Repository(Room.databaseBuilder(this, YijiDatabase::class.java, "yiji.db")
            .addMigrations(YijiDatabase.MIGRATION_1_2, YijiDatabase.MIGRATION_2_3).build(), Preferences(this), ReminderScheduler(this))
    }
    override fun onCreate() {
        super.onCreate()
        ReminderScheduler(this).createChannel()
        notificationScope.launch { repository.recoverMisclassifiedNotices() }
        ExtractionWork.schedule(this)
    }
}
