package org.ntqqrev.acidify.exception

import org.ntqqrev.acidify.internal.service.Service
import kotlin.js.JsExport

/**
 * 服务内部异常，表示没有反映在 SSO 包和 Oidb 包错误码中的异常
 * @property serviceName 服务名称
 * @property retCode 错误码
 * @property retMsg 错误信息
 */
@JsExport
class ServiceInternalException internal constructor(
    service: Service<*, *>,
    val retCode: Int,
    val retMsg: String,
) : Exception("${service::class.simpleName} failed with $retCode: $retMsg") {
    val serviceName = service::class.simpleName
}