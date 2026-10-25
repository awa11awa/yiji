package org.ntqqrev.acidify.event

import org.ntqqrev.acidify.common.android.AndroidSessionStore
import kotlin.js.JsExport

/**
 * Android 会话存储更新事件
 * @property sessionStore 更新后的会话存储
 */
@JsExport
data class AndroidSessionStoreUpdatedEvent(
    val sessionStore: AndroidSessionStore,
) : AcidifyEvent