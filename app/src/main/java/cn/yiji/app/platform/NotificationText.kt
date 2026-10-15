package cn.yiji.app.platform

import android.app.Notification
import androidx.core.app.NotificationCompat

data class NotificationText(val title: String, val body: String, val receivedAt: Long)

object NotificationTextExtractor {
    fun extract(notification: Notification, postTime: Long): List<NotificationText> {
        val extras = notification.extras
        val title = sequenceOf(Notification.EXTRA_CONVERSATION_TITLE, Notification.EXTRA_TITLE, Notification.EXTRA_TITLE_BIG)
            .map { extras.getCharSequence(it)?.toString().orEmpty().trim() }.firstOrNull { it.isNotBlank() }.orEmpty().take(200)
        // Conversation updates include earlier unread messages. Filter each message separately.
        val messages = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)?.messages.orEmpty()
        }.getOrDefault(emptyList()).mapNotNull { message ->
            val body = message.text?.toString().orEmpty().trim().take(16000)
            if (body.isBlank()) null else NotificationText(title.ifBlank { message.person?.name?.toString().orEmpty().take(200) }, body,
                message.timestamp.takeIf { it > 0 && it <= postTime } ?: postTime)
        }
        if (messages.isNotEmpty()) return messages
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty()
            .map { it.toString().trim().take(16000) }.filter { it.isNotBlank() }.distinct()
        if (lines.isNotEmpty()) return lines.map { NotificationText(title, it, postTime) }
        val body = sequenceOf(extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            extras.getCharSequence(Notification.EXTRA_TEXT), notification.tickerText)
            .map { it?.toString().orEmpty().trim() }.firstOrNull { it.isNotBlank() }.orEmpty().take(16000)
        // Preserve title-only notifications in the inbox instead of silently dropping them.
        return if (title.isBlank() && body.isBlank()) emptyList() else listOf(NotificationText(title, body, postTime))
    }
}
