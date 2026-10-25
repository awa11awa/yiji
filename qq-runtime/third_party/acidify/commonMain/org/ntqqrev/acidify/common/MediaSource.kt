package org.ntqqrev.acidify.common

import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlin.js.JsExport

/**
 * 表示可重复使用、可释放的媒体资源
 */
@JsExport
abstract class MediaSource {
    /**
     * 资源的体积
     */
    abstract val size: Long

    /**
     * 打开一个全新的、用于访问该资源的 [RawSource] 实例
     */
    @JsExport.Ignore
    abstract fun openRawSource(): RawSource

    /**
     * 直接将该 [MediaSource] 的内容读取到一个 [ByteArray] 中。
     */
    open fun readByteArray(): ByteArray {
        return openRawSource().buffered().use { source ->
            source.readByteArray()
        }
    }

    /**
     * 在资源使用完毕后释放该资源。
     * 注意 `acidify-core` 的各个 API 都不会主动调用该方法，因此需要调用方主动释放资源。
     */
    abstract fun dispose()

    companion object {
        @JsExport.Ignore
        fun ByteArray.toMediaSource(): MediaSource = ByteArrayMediaSource(this)
    }
}