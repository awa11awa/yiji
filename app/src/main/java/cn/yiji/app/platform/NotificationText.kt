package cn.yiji.app.platform

import android.app.Notification
import androidx.core.app.NotificationCompat

data class NotificationText(val title: String, val body: String, val receivedAt: Long)

object NotificationTextExtractor {
    fun extract(notification: Notification, postTime: Long): List<NotificationText> {
        val extras = notification.extras
        val title = sequenceOf(Notification.EXTRA_CONVERSATION_TITLE, Notification.EXTRA_TITLE, Notification.EXTRA_TITLE_BIG)
            .map { extras.getCharSequence(it)?.toString().orEmpty().trim() }.firstOrNull { it.isNotBlank() }.orEmpty().take(200)
        val expanded = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty().trim().take(16000)
        val complementary = listOf(Notification.EXTRA_SUB_TEXT, Notification.EXTRA_INFO_TEXT, Notification.EXTRA_SUMMARY_TEXT)
            .mapNotNull { extras.getCharSequence(it)?.toString()?.trim()?.takeIf(String::isNotBlank) }
        // Conversation updates include earlier unread messages. Filter each message separately.
        val messages = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)?.messages.orEmpty()
        }.getOrDefault(emptyList()).mapNotNull { message ->
            val body = message.text?.toString().orEmpty().trim().take(16000)
            if (body.isBlank()) null else NotificationText(title.ifBlank { message.person?.name?.toString().orEmpty().take(200) }, body,
                message.timestamp.takeIf { it > 0 && it <= postTime } ?: postTime)
        }
        fun complete(records: List<NotificationText>): List<NotificationText> {
            if (complementary.isEmpty()) return records
            val extra = complementary.filter { piece -> records.none { it.body.contains(piece) } }
            return when {
                extra.isEmpty() -> records
                records.size == 1 -> listOf(records.single().copy(body = mergePieces(listOf(records.single().body) + extra)))
                else -> records + NotificationText(title, mergePieces(extra), postTime)
            }
        }
        if (messages.isNotEmpty()) return complete(enrich(messages, expanded))
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty()
            .mapNotNull { it?.toString()?.trim()?.take(16000)?.takeIf(String::isNotBlank) }.distinct()
        if (lines.isNotEmpty()) {
            if (lines.size == 1 && expanded.isNotBlank() && Regex("^(你有)?\\d+条(新)?消息$").matches(lines.single()))
                return complete(listOf(NotificationText(title, expanded, postTime)))
            return complete(enrich(lines.map { NotificationText(title, it, postTime) }, expanded))
        }
        val body = sequenceOf(expanded,
            extras.getCharSequence(Notification.EXTRA_TEXT), notification.tickerText)
            .map { it?.toString().orEmpty().trim() }.firstOrNull { it.isNotBlank() }.orEmpty().take(16000)
        // Preserve title-only notifications in the inbox instead of silently dropping them.
        return if (title.isBlank() && body.isBlank() && complementary.isEmpty()) emptyList()
            else complete(listOf(NotificationText(title, body, postTime)))
    }

    internal fun mergePieces(pieces: List<String>): String {
        val unique = pieces.filter(String::isNotBlank).distinct()
        return unique.filter { piece -> unique.none { it.length > piece.length && it.contains(piece) } }
            .joinToString("\n").take(16000)
    }

    private fun enrich(messages: List<NotificationText>, expanded: String): List<NotificationText> {
        if (expanded.isBlank()) return messages
        fun comparable(value: String) = value.replace(Regex("^\\[\\d+条\\][^:：\\n]{0,80}[:：]\\s*"), "").trim()
        val full = comparable(expanded)
        val matches = messages.filter {
            val prefix = comparable(it.body).removeSuffix("...").trimEnd('…', '.', ' ', '\n')
            full.length > comparable(it.body).length && prefix.length >= 4 && full.startsWith(prefix)
        }
        // Never attach a summary of several conversations to an unrelated individual message.
        if (matches.size != 1) return messages
        return messages.map { if (it === matches.single()) it.copy(body = expanded) else it }
    }
}
