package cn.yiji.app

import android.app.Application
import androidx.room.Room
import cn.yiji.app.data.*
import cn.yiji.app.platform.ReminderScheduler

class YijiApplication : Application() {
    val repository: Repository by lazy {
        Repository(Room.databaseBuilder(this, YijiDatabase::class.java, "yiji.db").build(), Preferences(this), ReminderScheduler(this))
    }
    override fun onCreate() { super.onCreate(); ReminderScheduler(this).createChannel() }
}
