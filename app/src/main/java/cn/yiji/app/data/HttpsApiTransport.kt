package cn.yiji.app.data

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HttpsApiTransport(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val requestTimeoutMillis: Long = 180_000,
) : ApiTransport {
    override suspend fun request(url: String, key: String, payload: String?): ApiResponse {
        require(URL(url).protocol == "https") { "API 地址必须使用 HTTPS" }
        // Cancellation and the total deadline close the socket, including during a blocking read.
        return withTimeoutOrNull(requestTimeoutMillis) {
            coroutineScope {
                val connection = withContext(Dispatchers.IO) { openConnection(URL(url)) }
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { connection.disconnect() }
                    launch(Dispatchers.IO) {
                        try {
                            connection.connectTimeout = 15_000
                            connection.readTimeout = 90_000
                            connection.instanceFollowRedirects = false
                            connection.setRequestProperty("Authorization", "Bearer $key")
                            connection.setRequestProperty("Accept", "text/event-stream, application/json")
                            connection.requestMethod = if (payload == null) "GET" else "POST"
                            if (payload != null) {
                                connection.doOutput = true
                                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                            }
                            val status = connection.responseCode
                            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)?.use {
                                if (status in 200..299 && connection.contentType.orEmpty().contains("text/event-stream", true))
                                    CompletionStream.read(it)
                                else it.readLimited(1024 * 1024).toString(Charsets.UTF_8)
                            }.orEmpty()
                            val result = ApiResponse(status, body, retryAfter(connection.getHeaderField("Retry-After")))
                            if (continuation.isActive) continuation.resume(result)
                        } catch (e: Exception) {
                            val failure = when (e) {
                                is ExtractionFailure, is CancellationException -> e
                                is SocketTimeoutException -> ExtractionFailure("模型响应超时，原文已保留；稍后重试或切换配置", retryable = true)
                                else -> ExtractionFailure("无法连接模型服务，原文已保存；请检查网络后重试", retryable = true)
                            }
                            if (continuation.isActive) continuation.resumeWithException(failure)
                        } finally { connection.disconnect() }
                    }
                }
            }
        } ?: throw ExtractionFailure("模型处理超过3分钟，已停止本次请求；原文保留待重试", retryable = true)
    }

    companion object {
        internal fun retryAfter(value: String?, now: Long = System.currentTimeMillis()): Long {
            val millis = value?.trim()?.toLongOrNull()?.coerceIn(0, 900)?.times(1000)
                ?: runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now }.getOrDefault(0)
            return millis.coerceIn(0, 900_000)
        }
    }
}

/** Collect only answer deltas; a cut-off stream must never become a successful response. */
internal object CompletionStream {
    fun read(input: InputStream): String {
        var bytes = 0
        val limited = object : FilterInputStream(input) {
            private fun count(size: Int) {
                if (size > 0) bytes += size
                if (bytes > 2 * 1024 * 1024) throw ExtractionFailure("模型响应过大，已停止接收；请分段导入通知")
            }
            override fun read(): Int = super.read().also { if (it >= 0) count(1) }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                `in`.read(buffer, offset, length).also { count(it) }
        }
        val content = StringBuilder()
        var finish: String? = null
        var done = false
        val event = mutableListOf<String>()
        fun acceptEvent() {
            if (event.isEmpty()) return
            val data = event.joinToString("\n"); event.clear()
            if (data == "[DONE]") { done = true; return }
            val packet = runCatching { JSONObject(data) }.getOrElse {
                throw ExtractionFailure("模型流式响应格式不完整，原文已保留", retryable = true)
            }
            if (packet.has("error")) throw ExtractionFailure("模型服务中断响应，原文已保留", retryable = true)
            val choices = packet.optJSONArray("choices") ?: return
            for (i in 0 until choices.length()) {
                val choice = choices.getJSONObject(i)
                if (choice.optInt("index", 0) != 0) continue
                val delta = choice.optJSONObject("delta")
                val text = delta?.opt("content") as? String
                if (text != null) content.append(text)
                if (!choice.isNull("finish_reason")) finish = choice.optString("finish_reason").takeIf { it.isNotBlank() }
            }
        }
        limited.bufferedReader(Charsets.UTF_8).use { reader ->
            while (!done) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) acceptEvent()
                else if (line.startsWith("data:")) event += line.substring(5).trimStart()
            }
            if (!done) acceptEvent()
        }
        if (finish == null || (!done && finish !in setOf("stop", "length", "content_filter")))
            throw ExtractionFailure("模型连接中断，未收到完整结果；原文已保留", retryable = true)
        return JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", finish)
            .put("message", JSONObject().put("content", content.toString())))).toString()
    }
}
