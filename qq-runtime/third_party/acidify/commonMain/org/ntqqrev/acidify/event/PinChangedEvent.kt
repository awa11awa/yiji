package org.ntqqrev.acidify.event

import org.ntqqrev.acidify.message.MessageScene
import kotlin.js.JsExport

/**
 * 好友或群聊置顶状态变更事件
 * @property scene 消息场景
 * @property peerUin 消息来源的 uin。
 * 对于[好友消息][MessageScene.FRIEND]，为好友的 QQ 号；
 * 对于[群聊消息][MessageScene.GROUP]，为群号。
 * @property isPinned 是否为置顶状态
 */
@JsExport
data class PinChangedEvent internal constructor(
    val scene: MessageScene,
    val peerUin: Long,
    val isPinned: Boolean
) : AcidifyEvent
