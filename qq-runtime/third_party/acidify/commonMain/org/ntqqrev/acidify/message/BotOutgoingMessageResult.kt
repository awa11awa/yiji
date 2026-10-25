package org.ntqqrev.acidify.message

import kotlin.js.JsExport

/**
 * 发送消息的结果
 * @param sequence 消息序列号
 * @param sendTime 消息的 Unix 时间戳（秒）
 */
@JsExport
data class BotOutgoingMessageResult internal constructor(
    val sequence: Long,
    val sendTime: Long,
)