package org.ntqqrev.acidify.common

import kotlin.js.JsExport

/**
 * SSO 响应结构体
 * @property retCode 返回码
 * @property command 命令字符串
 * @property response 响应数据
 * @property sequence 序列号
 * @property extra 额外信息（如果返回码非 0，则包含错误信息）
 */
@JsExport
class SsoResponse internal constructor(
    val retCode: Int,
    val command: String,
    val response: ByteArray,
    val sequence: Int,
    val extra: String? = null
)