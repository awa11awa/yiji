package dev.yiji.qqlocalprobe

import kotlinx.serialization.json.*
import org.ntqqrev.acidify.AbstractBot
import org.ntqqrev.acidify.getForwardedMessages
import org.ntqqrev.acidify.message.*

/** Lossless strings and typed fields; display summaries are never the archive. */
object MessageCodec {
    fun encode(message: BotIncomingMessage, source: String): JsonObject = buildJsonObject {
        put("schemaVersion", 2); put("source", source); put("capturedAt", System.currentTimeMillis())
        put("scene", message.scene.name); put("peerUin", message.peerUin.toString()); put("peerUid", message.peerUid)
        put("senderUin", message.senderUin.toString()); put("senderUid", message.senderUid)
        put("sequence", message.sequence.toString()); put("clientSequence", message.clientSequence.toString())
        put("messageUid", message.messageUid.toString()); put("random", message.random)
        put("timestamp", message.timestamp); put("packageCount", message.packageCount)
        put("packageIndex", message.packageIndex); put("divisionSequence", message.divisionSequence)
        put("text", text(message.segments)); put("segments", segments(message.segments))
        put("rawMessage", Json.parseToJsonElement(message.rawMessageJson()))
        put("longTextResourceId", message.longTextResId)
        put("contentState", if (needsExpansion(message)) "pending" else
            if (message.segments.any { it is BotIncomingSegment.Unsupported }) "partial" else "parsed")
        message.extraInfo?.let { info -> put("senderInfo", buildJsonObject {
            put("nickname", info.nick); put("groupCard", info.groupCard)
            put("specialTitle", info.specialTitle); put("level", info.level)
        }) }
    }

    fun needsExpansion(message: BotIncomingMessage): Boolean = message.longTextResId.isNotEmpty() ||
        message.segments.any { it is BotIncomingSegment.Forward }

    fun segments(items: List<BotIncomingSegment>): JsonArray = buildJsonArray {
        items.forEach { segment -> add(buildJsonObject {
            put("type", segment.javaClass.simpleName); put("text", text(listOf(segment)))
            when (segment) {
                is BotIncomingSegment.Text -> put("content", segment.text)
                is BotIncomingSegment.Mention -> {
                    put("uin", segment.uin?.toString()?.let(::JsonPrimitive) ?: JsonNull)
                    put("uid", segment.uid?.let(::JsonPrimitive) ?: JsonNull); put("name", segment.name)
                }
                is BotIncomingSegment.Face -> {
                    put("faceId", segment.faceId); put("summary", segment.summary); put("isLarge", segment.isLarge)
                }
                is BotIncomingSegment.Reply -> {
                    put("sequence", segment.sequence.toString()); put("senderUin", segment.senderUin.toString())
                    put("senderName", segment.senderName?.let(::JsonPrimitive) ?: JsonNull)
                    put("timestamp", segment.timestamp); put("segments", segments(segment.segments))
                }
                is BotIncomingSegment.Image -> {
                    put("fileId", segment.fileId); put("width", segment.width); put("height", segment.height)
                    put("subType", segment.subType.name); put("summary", segment.summary)
                }
                is BotIncomingSegment.Record -> { put("fileId", segment.fileId); put("duration", segment.duration) }
                is BotIncomingSegment.Video -> {
                    put("fileId", segment.fileId); put("duration", segment.duration)
                    put("width", segment.width); put("height", segment.height)
                }
                is BotIncomingSegment.File -> {
                    put("fileId", segment.fileId); put("fileName", segment.fileName); put("fileSize", segment.fileSize.toString())
                    put("fileHash", segment.fileHash?.let(::JsonPrimitive) ?: JsonNull)
                }
                is BotIncomingSegment.Forward -> {
                    put("resourceId", segment.resId); put("title", segment.title); put("summary", segment.summary)
                    put("preview", JsonArray(segment.preview.map(::JsonPrimitive)))
                }
                is BotIncomingSegment.MarketFace -> {
                    put("url", segment.url); put("summary", segment.summary); put("emojiId", segment.emojiId)
                    put("emojiPackageId", segment.emojiPackageId); put("key", segment.key)
                }
                is BotIncomingSegment.LightApp -> { put("appName", segment.appName); put("jsonPayload", segment.jsonPayload) }
                is BotIncomingSegment.Markdown -> put("content", segment.content)
                is BotIncomingSegment.Unsupported -> { put("raw", Json.parseToJsonElement(segment.rawJson)); put("reason", segment.reason) }
            }
        }) }
    }

    fun text(items: List<BotIncomingSegment>): String = items.joinToString("") { segment ->
        when (segment) {
            is BotIncomingSegment.Reply -> "[引用 ${segment.senderName ?: segment.senderUin}] ${text(segment.segments)}\n"
            is BotIncomingSegment.Forward -> "[${segment.title}]\n${segment.preview.joinToString("\n")}\n${segment.summary}\n"
            is BotIncomingSegment.LightApp -> "[卡片 ${segment.appName}]\n${segment.jsonPayload}\n"
            else -> segment.toString()
        }
    }

    fun forwarded(items: List<BotForwardedMessage>): JsonArray = buildJsonArray {
        items.forEach { message -> add(buildJsonObject {
            put("sequence", message.sequence.toString()); put("senderName", message.senderName)
            put("avatarUrl", message.avatarUrl); put("timestamp", message.timestamp)
            put("text", text(message.segments)); put("segments", segments(message.segments))
        }) }
    }

    suspend fun expand(bot: AbstractBot, message: BotIncomingMessage, encoded: JsonObject): JsonObject {
        return expandEncoded(bot, encoded)
    }

    suspend fun expandEncoded(bot: AbstractBot, encoded: JsonObject): JsonObject {
        val longId = encoded["longTextResourceId"]?.jsonPrimitive?.content.orEmpty()
        val resources = (listOf(longId) + encoded["segments"]!!.jsonArray.mapNotNull {
            it.jsonObject["resourceId"]?.jsonPrimitive?.content
        }).filter(String::isNotEmpty).distinct()
        val expanded = resources.associateWith { id ->
            bot.getForwardedMessages(id, encoded["scene"]!!.jsonPrimitive.content == "GROUP").also {
                check(it.isNotEmpty()) { "展开资源没有返回可解析消息" }
            }
        }
        return JsonObject(encoded + buildJsonObject {
            put("expandedResources", JsonObject(expanded.mapValues { forwarded(it.value) }))
            val fullText = expanded[longId]?.joinToString("\n") { text(it.segments) }
            put("text", fullText ?: encoded["text"]!!.jsonPrimitive.content + expanded.values.flatten().joinToString("\n", prefix = "\n") {
                "${it.senderName}: ${text(it.segments)}"
            })
            put("contentState", "expanded")
        })
    }
}
