package dev.yiji.qqlocalprobe

import org.ntqqrev.acidify.message.BotHistoryMessages
import org.ntqqrev.acidify.message.BotIncomingMessage

/** Page back to the last completed sync, rather than stopping after 30 messages. */
object HistoryBackfill {
    suspend fun run(
        sinceTimestamp: Long,
        watermark: Long,
        fetch: suspend (Long?) -> BotHistoryMessages,
        save: suspend (BotIncomingMessage) -> Unit,
    ): Long {
        var cursor: Long? = null
        var newest = watermark
        while (true) {
            val page = fetch(cursor)
            if (page.messages.isEmpty()) return newest
            for (message in page.messages.sortedBy { it.sequence }) {
                if (message.timestamp >= sinceTimestamp && message.sequence > watermark) {
                    save(message)
                    newest = maxOf(newest, message.sequence)
                }
            }
            val next = page.nextStartSequence ?: return newest
            if (page.messages.any { it.timestamp < sinceTimestamp || it.sequence <= watermark }) return newest
            check(cursor == null || next < cursor) { "历史分页游标没有前进" }
            cursor = next
        }
    }
}
