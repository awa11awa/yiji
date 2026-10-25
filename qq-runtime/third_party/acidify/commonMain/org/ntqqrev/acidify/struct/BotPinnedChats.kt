package org.ntqqrev.acidify.struct

import kotlin.js.JsExport

/**
 * 当前置顶的好友与群聊
 * @property friendUins 置顶好友 QQ 号列表
 * @property groupUins 置顶群聊 QQ 号列表
 */
@JsExport
data class BotPinnedChats internal constructor(
    val friendUins: List<Long>,
    val groupUins: List<Long>
)