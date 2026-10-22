package cn.yiji.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Provider options belong to a documented model, not every model on the same host. */
internal object ModelRequestPolicy {
    private fun officialGlm(profile: ApiProfile) = URI(profile.baseUrl.trim()).host.equals("open.bigmodel.cn", true)
    private fun requiredThinking(profile: ApiProfile) = officialGlm(profile) &&
        (profile.model == "glm-5.3" || profile.model.startsWith("glm-5.3-"))

    fun payload(profile: ApiProfile, messages: JSONArray, test: Boolean): JSONObject {
        val reasoning = requiredThinking(profile)
        val budget = if (test) { if (reasoning) 2048 else 512 } else { if (reasoning) 8192 else 6144 }
        return JSONObject().put("model", profile.model).put("messages", messages)
            .put("stream", true).put("max_tokens", budget).apply {
                if (reasoning) {
                    // GLM-5.3 (including Flash/FlashX) rejects thinking.type=disabled.
                    put("thinking", JSONObject().put("type", "enabled"))
                    put("reasoning_effort", "low")
                    put("response_format", JSONObject().put("type", "json_object"))
                } else if (officialGlm(profile) && profile.model == ApiProfile.GLM_MODEL) {
                    put("thinking", JSONObject().put("type", "disabled"))
                    put("response_format", JSONObject().put("type", "json_object"))
                    put("temperature", 0.1)
                }
                // Unknown models use provider defaults; never force another model's options.
            }
    }
}

internal object ApiErrorDetails {
    fun hint(response: ApiResponse, key: String): String {
        if (response.status != 400) return ""
        val root = runCatching { JSONObject(response.body) }.getOrNull() ?: return ""
        val error = root.optJSONObject("error") ?: root
        val code = error.optString("code").takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,64}")) }.orEmpty()
        var message = error.opt("message") as? String ?: ""
        if (key.isNotBlank()) message = message.replace(key, "[密钥已隐藏]")
        message = message.replace(Regex("(?i)Bearer\\s+[^\\s,;\"']+"), "Bearer [已隐藏]")
            .replace(Regex("(?i)((?:api[_-]?key|access[_-]?token|authorization)\\s*[:=]\\s*)[^\\s,;\"']+"), "$1[已隐藏]")
            .replace(Regex("[\\r\\n\\t]+"), " ").take(300)
        val guidance = when (code) {
            "1211" -> "模型代码不存在，请从当前服务商的模型列表选择完整名称"
            "1212" -> "该模型不支持 Chat Completions 调用，请选择兼容的文本模型"
            "1210", "1213", "1214", "1215" -> "模型拒绝了请求参数，请根据下面的具体原因核对模型与接口"
            "1261" -> "通知和上下文超过模型长度限制，请缩短后重试"
            else -> "请求参数或模型配置不兼容"
        }
        return buildList {
            add(guidance)
            if (code.isNotEmpty()) add("服务错误码：$code")
            if (message.isNotBlank()) add(message)
        }.joinToString("\n")
    }
}
