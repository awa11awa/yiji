package org.ntqqrev.acidify.event

import org.ntqqrev.acidify.message.BotIncomingMessage
import kotlin.js.JsExport

/**
 * 消息接收事件
 * @property message 接收到的消息
 */
@JsExport
data class MessageReceiveEvent internal constructor(
    val message: BotIncomingMessage
) : AcidifyEvent