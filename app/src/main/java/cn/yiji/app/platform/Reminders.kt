package cn.yiji.app.platform

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cn.yiji.app.*
import cn.yiji.app.data.Event
import kotlinx.coroutines.*

class ReminderScheduler(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val fired = context.getSharedPreferences("yiji-reminder-revisions", Context.MODE_PRIVATE)
    fun createChannel() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("yiji-reminders", "事项提醒", NotificationManager.IMPORTANCE_HIGH).apply { description = "日程和截止事项的提前提醒" })
    }
    fun exactAllowed(): Boolean = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
    private fun pending(id: String, revision: Long = 0L): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderReceiver::class.java).setData(Uri.parse("yiji://reminder/$id"))
            .putExtra("eventId", id).putExtra("revision", revision), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun cancel(id: String) {
        alarms.cancel(pending(id)); NotificationManagerCompat.from(context).cancel(id, 1)
        fired.edit().remove(id).apply()
    }
    fun markFired(id: String, revision: Long) { fired.edit().putLong(id, revision).apply() }
    fun schedule(event: Event) {
        val anchor = event.anchor
        if (anchor == null || event.status != "ACTIVE" || event.reminderMinutes < 0 || anchor <= System.currentTimeMillis()) {
            cancel(event.id); return
        }
        if (fired.getLong(event.id, -1) == event.updatedAt) { alarms.cancel(pending(event.id)); return }
        cancel(event.id)
        val whenAt = maxOf(System.currentTimeMillis() + 1000L, anchor - event.reminderMinutes * 60_000L)
        val operation = pending(event.id, event.updatedAt)
        try {
            if (exactAllowed()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenAt, operation)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenAt, operation)
        } catch (_: SecurityException) { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenAt, operation) }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("eventId") ?: return
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val event = (context.applicationContext as YijiApplication).repository.dao.event(id) ?: return@launch
                if (event.status != "ACTIVE" || event.updatedAt != intent.getLongExtra("revision", -1) || event.reminderMinutes < 0) return@launch
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return@launch
                if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return@launch
                if (context.getSystemService(NotificationManager::class.java).getNotificationChannel("yiji-reminders")?.importance == NotificationManager.IMPORTANCE_NONE) return@launch
                val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
                    .setData(Uri.parse("yiji://event/$id")).putExtra("eventId", id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val verb = if (event.kind == "DEADLINE") "即将截止" else "即将开始"
                val notification = NotificationCompat.Builder(context, "yiji-reminders").setSmallIcon(R.drawable.ic_reminder)
                    .setContentTitle(event.title).setContentText("$verb${if (event.location.isNotBlank()) " · ${event.location}" else ""}")
                    .setStyle(NotificationCompat.BigTextStyle().bigText("$verb\n${event.action}"))
                    .setContentIntent(open).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
                NotificationManagerCompat.from(context).notify(id, 1, notification)
                ReminderScheduler(context).markFired(id, event.updatedAt)
            } catch (_: Exception) { /* A revoked permission must not crash a receiver. */ }
            finally { result.finish() }
        }
    }
}

class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.MY_PACKAGE_REPLACED",
            "android.intent.action.TIME_SET", "android.intent.action.TIMEZONE_CHANGED",
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) return
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { (context.applicationContext as YijiApplication).repository.reschedule() }
            finally { result.finish() }
        }
    }
}
