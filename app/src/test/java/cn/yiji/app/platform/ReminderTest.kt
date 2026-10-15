package cn.yiji.app.platform

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cn.yiji.app.data.Event
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ReminderTest {
    private lateinit var scheduler: ReminderScheduler
    private lateinit var alarms: AlarmManager
    private fun event() = Event(title = "会议", category = "会议", kind = "SCHEDULE", startAt = System.currentTimeMillis() + 3600000,
        endAt = System.currentTimeMillis() + 7200000, reminderMinutes = 30)
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("yiji-reminder-revisions", Context.MODE_PRIVATE).edit().clear().commit()
        alarms = context.getSystemService(AlarmManager::class.java)
        scheduler = ReminderScheduler(context)
    }
    @Test fun alreadyFiredRevisionIsNotScheduledAgain() {
        val e = event()
        scheduler.schedule(e); assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
        scheduler.markFired(e.id, e.updatedAt)
        scheduler.schedule(e); assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
        scheduler.schedule(e.copy(updatedAt = e.updatedAt + 1)); assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
    }
    @Test fun cancellationAndCompletionRemoveAlarm() {
        val e = event()
        scheduler.schedule(e); scheduler.schedule(e.copy(status = "CANCELLED")); assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
        scheduler.schedule(e); scheduler.schedule(e.copy(status = "DONE")); assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
    }
}
