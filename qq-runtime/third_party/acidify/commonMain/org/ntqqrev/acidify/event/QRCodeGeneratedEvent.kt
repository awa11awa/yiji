package org.ntqqrev.acidify.event

import kotlin.js.JsExport

/**
 * 二维码生成事件
 * @property url 二维码链接
 * @property png 二维码 PNG 图片数据
 */
@JsExport
data class QRCodeGeneratedEvent internal constructor(
    val url: String,
    val png: ByteArray
) : AcidifyEvent {
    override fun toString(): String = "${this::class.simpleName}(url=$url)"
}