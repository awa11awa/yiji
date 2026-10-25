package org.ntqqrev.acidify.event

import kotlin.js.JsExport

/**
 * 群管理员变更事件
 * @property groupUin 群号
 * @property userUin 发生变更的用户 QQ 号
 * @property userUid 发生变更的用户 uid
 * @property operatorUin 操作者 QQ 号
 * @property operatorUid 操作者 uid
 * @property isSet 是否被设置为管理员，`false` 表示被取消管理员
 */
@JsExport
data class GroupAdminChangeEvent internal constructor(
    val groupUin: Long,
    val userUin: Long,
    val userUid: String,
    val operatorUin: Long,
    val operatorUid: String,
    val isSet: Boolean
) : AcidifyEvent