package cn.yiji.app

import android.app.Application
import androidx.room.Room
import cn.yiji.app.data.*
import cn.yiji.app.platform.ReminderScheduler
import cn.yiji.app.platform.NotificationMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class YijiApplication : Application() {
    val notificationMonitor by lazy { NotificationMonitor(this) }
    // Reconnecting the listener must not cancel notifications already queued in this process.
    val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val repository: Repository by lazy {
        Repository(Room.databaseBuilder(this, YijiDatabase::class.java, "yiji.db").build(), Preferences(this), ReminderScheduler(this))
    }
    override fun onCreate() { super.onCreate(); ReminderScheduler(this).createChannel() }
}
