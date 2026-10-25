package org.ntqqrev.acidify.event

import org.ntqqrev.acidify.struct.QRCodeState
import kotlin.js.JsExport

/**
 * 二维码状态查询事件
 * @property state 二维码状态
 */
@JsExport
data class QRCodeStateQueryEvent internal constructor(
    val state: QRCodeState
) : AcidifyEvent