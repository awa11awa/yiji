package org.ntqqrev.acidify.event

import kotlin.js.JsExport

/**
 * 群消息表情回应事件
 * @property groupUin 群号
 * @property userUin 发送回应者 QQ 号
 * @property userUid 发送回应者 uid
 * @property messageSeq 消息序列号
 * @property faceId 表情 ID
 * @property type 表情的类型，分为 `1`（QQ 表情）和 `2`（系统 Emoji）两种
 * @property isAdd 是否为添加，`false` 表示取消回应
 */
@JsExport
data class GroupMessageReactionEvent internal constructor(
    val groupUin: Long,
    val userUin: Long,
    val userUid: String,
    val messageSeq: Long,
    val faceId: String,
    val type: Int,
    val isAdd: Boolean
) : AcidifyEvent