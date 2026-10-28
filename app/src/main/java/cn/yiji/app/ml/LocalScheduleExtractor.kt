package cn.yiji.app.ml

import android.content.Context
import cn.yiji.app.core.Draft
import cn.yiji.app.core.NoticeSafety
import cn.yiji.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest

data class Extraction(val drafts: List<Draft>, val operation: String = "NEW", val ignored: Boolean = false,
    val info: String = "", val operations: List<String> = emptyList(), val targetIds: List<String?> = emptyList(),
    val engine: String = "云端模型", val needsReview: Boolean = false)
fun interface ScheduleExtractor { suspend fun extract(title: String, body: String, receivedAt: Long): Extraction }
interface ContextualScheduleExtractor : ScheduleExtractor {
    suspend fun extract(input: ExtractionInput): Extraction
    override suspend fun extract(title: String, body: String, receivedAt: Long): Extraction =
        extract(ExtractionInput(RawMessage(title = title, body = body, receivedAt = receivedAt, source = "手动导入")))
}

class TinyBertMessageGate(context: Context) : MessageGate {
    private val application = context.applicationContext
    override suspend fun classify(text: String): GatePrediction = withContext(Dispatchers.Default) { model(application).predict(text) }
    companion object {
        @Volatile private var instance: TinyBertModel? = null
        private fun model(context: Context): TinyBertModel = instance ?: synchronized(this) {
            instance ?: run {
                val metadata = JSONObject(context.assets.open("tinybert/metadata.json").bufferedReader().use { it.readText() })
                val bytes = context.assets.open("tinybert/${metadata.optString("model_file", "model.int8.onnx")}").use { it.readBytes() }
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                require(digest == metadata.getString("model_sha256")) { "本地筛选模型校验失败" }
                val vocabulary = context.assets.open("tinybert/vocab.txt").bufferedReader().use { it.readLines() }
                TinyBertModel(bytes, vocabulary, metadata).also { instance = it }
            }
        }
    }
}

class HybridScheduleExtractor(
    context: Context,
    private val profiles: ApiProfileStore = ApiProfileStore(context),
    private val client: CloudExtractionClient = CloudExtractionClient(),
    private val gate: MessageGate = TinyBertMessageGate(context),
    private val onCandidate: suspend (String) -> Unit = {},
) : ContextualScheduleExtractor {
    override suspend fun extract(input: ExtractionInput): Extraction {
        if (!input.forceCloud) {
            if (NoticeSafety.isNoise(input.current.body.ifBlank { input.current.title }, input.context.isNotEmpty() || input.candidates.isNotEmpty()))
                return Extraction(emptyList(), ignored = true, info = "无明确安排含义的消息，原文已保留在已忽略列表", engine = "本地消息分流")
            val result = gate.classify(input.current.body.ifBlank { input.current.title })
            if (!result.candidate && !NoticeSafety.protects(input.current.title, input.current.body)) {
                if (NoticeSafety.mayBeIncomplete(input.current.body)) return Extraction(emptyList(), needsReview = true,
                    info = "本地未检出事项，但通知正文可能不完整，已保留待核对；可补全正文或主动送云端复核", engine = "TinyBERT 聊天／日程筛选")
                return Extraction(emptyList(), ignored = true,
                    info = "TinyBERT 二分类：聊天或非日程消息，原文已保留；如有误判可送云端复核", engine = "TinyBERT 聊天／日程筛选")
            }
        }
        onCandidate(input.current.id)
        val state = profiles.current
        val profile = state.selected
        if (!state.enabled || profile == null) throw ExtractionFailure("已筛选为日程候选；请到设置中的 API 与模型填写密钥并开启云端识别", waitingForApi = true)
        val key = try { profiles.key(profile.id) } catch (_: Exception) {
            throw ExtractionFailure("API 密钥无法读取，请编辑当前配置并重新填写", waitingForApi = true)
        }
        if (key.isBlank()) throw ExtractionFailure("当前 API 配置缺少密钥，请填写后继续提取", waitingForApi = true)
        return client.extract(profile, key, if (state.includeContext) input else input.copy(context = emptyList(), candidates = emptyList()))
    }
}
