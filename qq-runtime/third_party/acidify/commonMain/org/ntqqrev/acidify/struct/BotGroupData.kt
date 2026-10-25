package org.ntqqrev.acidify.struct

import kotlin.js.JsExport

/**
 * Bot 群数据
 * @property uin 群的 uin（群号）
 * @property name 群名称
 * @property memberCount 群成员数量
 * @property capacity 群容量
 * @property remark 群备注
 * @property createdAt 群创建时间
 * @property description 群介绍
 * @property question 入群问题
 * @property announcementPreview 群公告预览
 */
@JsExport
data class BotGroupData internal constructor(
    val uin: Long,
    val name: String,
    val memberCount: Int,
    val capacity: Int,
    val remark: String,
    val createdAt: Long,
    val description: String,
    val question: String,
    val announcementPreview: String,
)