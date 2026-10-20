package cn.yiji.app.data

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class HttpsApiTransportTest {
    private fun packet(content: String?, finish: String? = null, reasoning: String? = null): String =
        "data: " + JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0)
            .put("delta", JSONObject().put("content", content ?: JSONObject.NULL).put("reasoning_content", reasoning ?: JSONObject.NULL))
            .put("finish_reason", finish ?: JSONObject.NULL))) + "\n\n"
    private fun stream(value: String) = ByteArrayInputStream(value.toByteArray(Charsets.UTF_8))
    private fun content(value: String) = JSONObject(value).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")

    @Test fun streamedChineseJsonIsReassembledWithoutReasoningOrUsage() {
        val body = ": keepalive\n\n" + packet(null, reasoning = "不要进入结果") + packet("{\"事项\":") +
            packet("\"数智创业大讲坛\"}") + packet(null, "stop") + "data: {\"choices\":[],\"usage\":{}}\n\ndata: [DONE]\n\n"
        assertEquals("{\"事项\":\"数智创业大讲坛\"}", content(CompletionStream.read(stream(body))))
    }

    @Test fun interruptedStreamIsRejectedEvenIfItsPartialTextIsValidJson() {
        val failure = runCatching { CompletionStream.read(stream(packet("{\"ok\":true}"))) }.exceptionOrNull() as ExtractionFailure
        assertTrue(failure.retryable)
    }

    @Test fun streamErrorDoesNotExposeProviderDetails() {
        val failure = runCatching { CompletionStream.read(stream("data: {\"error\":{\"message\":\"secret\"}}\n\n")) }.exceptionOrNull() as ExtractionFailure
        assertTrue(failure.retryable); assertFalse(failure.userMessage.contains("secret"))
    }

    @Test fun oversizedResponseIsBounded() {
        val failure = runCatching { CompletionStream.read(stream(":" + "x".repeat(2 * 1024 * 1024 + 1))) }.exceptionOrNull()
        assertTrue(failure is ExtractionFailure)
    }

    private open class Connection(private val body: InputStream, private val type: String = "application/json", private val code: Int = 200) : HttpURLConnection(URL("https://fixture.invalid")) {
        val written = ByteArrayOutputStream()
        @Volatile var closed = false
        override fun connect() {}
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getContentType() = type
        override fun getInputStream() = body
        override fun getErrorStream() = body
        override fun getOutputStream() = written
        override fun getHeaderField(name: String?) = if (name == "Retry-After") "120" else null
    }

    @Test fun transportAcceptsStreamingAndServersThatReturnRegularJson() = runBlocking {
        val json = "{\"choices\":[]}"
        val regular = Connection(stream(json))
        assertEquals(json, HttpsApiTransport({ regular }).request("https://fixture.invalid", "fixture-key", "{}").body)
        assertEquals("{}", regular.written.toString("UTF-8")); assertTrue(regular.closed)
        val streaming = Connection(stream(packet("{\"ok\":true}") + packet(null, "stop") + "data: [DONE]\n\n"), "text/event-stream; charset=utf-8")
        val result = HttpsApiTransport({ streaming }).request("https://fixture.invalid", "fixture-key", "{}")
        assertEquals("{\"ok\":true}", content(result.body)); assertEquals(120_000L, result.retryAfterMillis)
    }

    private class BlockingConnection : Connection(body = ByteArrayInputStream(byteArrayOf())) {
        val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)
        override fun getInputStream(): InputStream {
            entered.countDown(); release.await(5, TimeUnit.SECONDS)
            throw java.io.IOException("closed")
        }
        override fun disconnect() { super.disconnect(); release.countDown() }
    }

    @Test fun cancellationClosesAnInflightConnectionWithoutConvertingItToRetry() = runBlocking {
        val connection = BlockingConnection()
        val request = launch { HttpsApiTransport({ connection }).request("https://fixture.invalid", "fixture-key", "{}") }
        withContext(Dispatchers.IO) { assertTrue(connection.entered.await(2, TimeUnit.SECONDS)) }
        withTimeout(2000) { request.cancelAndJoin() }
        assertTrue(request.isCancelled); assertTrue(connection.closed)
    }

    @Test fun totalDeadlineStopsAStalledConnection() = runBlocking {
        val connection = BlockingConnection()
        val failure = runCatching { HttpsApiTransport({ connection }, 200).request("https://fixture.invalid", "fixture-key", "{}") }.exceptionOrNull() as ExtractionFailure
        assertTrue(failure.retryable); assertTrue(connection.closed)
    }

    @Test fun retryAfterSupportsDatesAndCapsUnreasonableDelays() {
        assertEquals(120_000L, HttpsApiTransport.retryAfter("120", 0))
        assertEquals(900_000L, HttpsApiTransport.retryAfter("999999", 0))
        assertEquals(60_000L, HttpsApiTransport.retryAfter("Thu, 01 Jan 1970 00:01:00 GMT", 0))
        assertEquals(0L, HttpsApiTransport.retryAfter("invalid", 0))
    }
}
