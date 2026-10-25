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
import cn.yiji.app.core.reminderPoints
import kotlinx.coroutines.*

class ReminderScheduler(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val fired = context.getSharedPreferences("yiji-reminder-revisions", Context.MODE_PRIVATE)
    fun createChannel() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("yiji-reminders", "事项提醒", NotificationManager.IMPORTANCE_HIGH).apply { description = "日程和截止事项的提前提醒" })
    }
    fun exactAllowed(): Boolean = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
    private fun pending(id: String, revision: Long = 0L, role: String = "start"): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderReceiver::class.java).setData(Uri.parse(if (role == "legacy") "yiji://reminder/$id" else "yiji://reminder/$id/$role"))
            .putExtra("eventId", id).putExtra("revision", revision).putExtra("role", role), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun roles(id: String): Set<String> = fired.getStringSet("roles:$id", emptySet())?.toSet().orEmpty()
    private fun removeAlarms(id: String) {
        alarms.cancel(pending(id, role = "legacy"))
        roles(id).forEach { role -> alarms.cancel(pending(id, role = role)) }
    }
    fun cancel(id: String) {
        removeAlarms(id)
        NotificationManagerCompat.from(context).cancel(id, 1)
        val edit = fired.edit().remove(id).remove("roles:$id")
        roles(id).forEach { role ->
            NotificationManagerCompat.from(context).cancel("$id/$role", 1)
            edit.remove("$id/$role").remove("at:$id/$role")
        }
        edit.apply()
    }
    fun markFired(id: String, revision: Long, role: String = "start", at: Long? = null) {
        fired.edit().putLong("$id/$role", revision).apply()
        if (at != null) fired.edit().putLong("at:$id/$role", at).apply()
    }
    fun schedule(event: Event) {
        if (event.status != "ACTIVE" || event.reminderMinutes < 0) { cancel(event.id); return }
        val points = event.draft().reminderPoints()
        val previousRoles = roles(event.id)
        removeAlarms(event.id)
        val removed = previousRoles - points.map { it.role }.toSet()
        removed.forEach { NotificationManagerCompat.from(context).cancel("${event.id}/$it", 1) }
        fired.edit().putStringSet("roles:${event.id}", (previousRoles + points.map { it.role }).toSet()).apply()
        points.forEach { point ->
            if (point.at <= System.currentTimeMillis()) return@forEach
            if (fired.getLong("${event.id}/${point.role}", -1) == event.updatedAt ||
                fired.getLong("at:${event.id}/${point.role}", -1) == point.at || fired.getLong(event.id, -1) == event.updatedAt) return@forEach
            val whenAt = maxOf(System.currentTimeMillis() + 1000L, point.at - event.reminderMinutes * 60_000L)
            val operation = pending(event.id, event.updatedAt, point.role)
            try {
                if (exactAllowed()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenAt, operation)
                else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenAt, operation)
            } catch (_: SecurityException) { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenAt, operation) }
        }
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
                val role = intent.getStringExtra("role") ?: return@launch
                val point = event.draft().reminderPoints().firstOrNull { it.role == role } ?: return@launch
                val verb = point.message
                val notification = NotificationCompat.Builder(context, "yiji-reminders").setSmallIcon(R.drawable.ic_reminder)
                    .setContentTitle(event.title).setContentText("$verb${if (event.location.isNotBlank()) " · ${event.location}" else ""}")
                    .setStyle(NotificationCompat.BigTextStyle().bigText("$verb\n${event.action}"))
                    .setContentIntent(open).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
                NotificationManagerCompat.from(context).notify("$id/$role", 1, notification)
                ReminderScheduler(context).markFired(id, event.updatedAt, role, point.at)
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
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            cn.yiji.app.qq.QqCaptureService.resumeIfEnabled(context)
            (context.applicationContext as YijiApplication).collectionSupervisor.refresh(allowForegroundStart = true, recover = true)
        }
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { (context.applicationContext as YijiApplication).repository.reschedule() }
            finally { result.finish() }
        }
    }
}
