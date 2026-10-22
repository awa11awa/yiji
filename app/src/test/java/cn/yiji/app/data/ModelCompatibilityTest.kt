package cn.yiji.app.data

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ModelCompatibilityTest {
    private fun completion(content: String) = JSONObject().put("choices", JSONArray().put(JSONObject()
        .put("finish_reason", "stop").put("message", JSONObject().put("content", content)))).toString()

    @Test fun mandatoryThinkingModelsUseCompatibleOptionsForTestingAndExtraction() = runBlocking {
        listOf("glm-5.3", "glm-5.3-flash", "glm-5.3-flashx").forEach { model ->
            val requests = mutableListOf<JSONObject>()
            val client = CloudExtractionClient { _, _, payload ->
                requests += JSONObject(payload!!)
                ApiResponse(200, completion(if (requests.size == 1) "{\"ok\":true}" else
                    "{\"protocolVersion\":2,\"decision\":\"IGNORE\",\"items\":[]}"))
            }
            val profile = ApiProfile.glm.copy(model = model)
            client.test(profile, "fixture-key")
            client.extract(profile, "fixture-key", ExtractionInput(RawMessage(title = "", body = "收到", source = "测试")))
            assertEquals(2, requests.size)
            requests.forEach {
                assertEquals(model, it.getString("model"))
                assertEquals("enabled", it.getJSONObject("thinking").getString("type"))
                assertEquals("low", it.getString("reasoning_effort"))
                assertFalse(it.has("temperature")); assertTrue(it.getBoolean("stream"))
            }
            assertTrue(requests[0].getInt("max_tokens") >= 2048)
            assertTrue(requests[1].getInt("max_tokens") > requests[0].getInt("max_tokens"))
        }
    }
    @Test fun unknownModelsAndCustomProvidersDoNotReceiveGlmSpecificOptions() {
        listOf(ApiProfile.glm.copy(model = "another-chat-model"),
            ApiProfile.glm.copy(baseUrl = "https://example.com/v1", model = "glm-5.3-flash")).forEach {
            val request = ModelRequestPolicy.payload(it, JSONArray(), test = true)
            assertFalse(request.has("thinking")); assertFalse(request.has("temperature"))
            assertFalse(request.has("response_format")); assertFalse(request.has("reasoning_effort"))
        }
    }
    @Test fun defaultFreeModelRetainsItsPreviouslySupportedOptions() {
        val request = ModelRequestPolicy.payload(ApiProfile.glm, JSONArray(), test = false)
        assertEquals("disabled", request.getJSONObject("thinking").getString("type"))
        assertEquals(0.1, request.getDouble("temperature"), .00001)
    }
    @Test fun invalidParameterErrorShowsUsefulDetailsButNeverEchoesTheApiKey() = runBlocking {
        var calls = 0
        val key = "fixture-private-key"
        val client = CloudExtractionClient { _, _, _ ->
            calls++
            ApiResponse(400, JSONObject().put("error", JSONObject().put("code", "1214")
                .put("message", "thinking.type must be enabled; api_key=$key Bearer $key")).toString())
        }
        val error = runCatching { client.test(ApiProfile.glm, key) }.exceptionOrNull() as ExtractionFailure
        assertEquals(1, calls); assertFalse(error.retryable)
        assertTrue(error.userMessage.contains("1214")); assertTrue(error.userMessage.contains("thinking.type"))
        assertFalse(error.userMessage.contains(key)); assertTrue(error.userMessage.contains("已隐藏"))
    }
    @Test fun unknownModelAndUnsupportedMethodGetDifferentActionableErrors() = runBlocking {
        for ((code, hint) in listOf("1211" to "模型代码不存在", "1212" to "不支持 Chat Completions")) {
            val client = CloudExtractionClient { _, _, _ -> ApiResponse(400, "{\"error\":{\"code\":\"$code\",\"message\":\"fixture\"}}") }
            val error = runCatching { client.test(ApiProfile.glm, "fixture-key") }.exceptionOrNull() as ExtractionFailure
            assertTrue(error.userMessage.contains(hint))
        }
    }
    @Test fun malformedServerErrorIsNotDumpedToTheUser() = runBlocking {
        val client = CloudExtractionClient { _, _, _ -> ApiResponse(400, "private-html-error-body") }
        val error = runCatching { client.test(ApiProfile.glm, "fixture-key") }.exceptionOrNull() as ExtractionFailure
        assertTrue(error.userMessage.contains("HTTP 400")); assertFalse(error.userMessage.contains("private-html"))
    }
}
