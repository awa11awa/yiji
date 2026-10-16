package cn.yiji.app.ml

import android.content.Context
import cn.yiji.app.core.Draft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest

data class Extraction(val drafts: List<Draft>, val operation: String = "NEW", val ignored: Boolean = false, val info: String = "")
fun interface ScheduleExtractor {
    suspend fun extract(title: String, body: String, receivedAt: Long): Extraction
}

class LocalScheduleExtractor(context: Context) : ScheduleExtractor {
    private val application = context.applicationContext
    override suspend fun extract(title: String, body: String, receivedAt: Long): Extraction = withContext(Dispatchers.Default) {
        // A chat/group title alone must not promote an unrelated message.
        val text = body.ifBlank { title }
        val prediction = model(application).predict(text)
        if (prediction.category == "IGNORE" && prediction.confidence >= .80)
            Extraction(emptyList(), ignored = true, info = "本地 TinyBERT 判断为非待办消息（${(prediction.confidence * 100).toInt()}%），可查看原文或手动添加事项")
        else Extraction(DraftAssembler.assemble(prediction, text, title, receivedAt), prediction.operation,
            info = "TinyBERT 离线识别 · 请逐项核对")
    }

    companion object {
        @Volatile private var instance: TinyBertModel? = null
        private fun model(context: Context): TinyBertModel = instance ?: synchronized(this) {
            instance ?: run {
                val metadata = JSONObject(context.assets.open("tinybert/metadata.json").bufferedReader().use { it.readText() })
                val bytes = context.assets.open("tinybert/model.int8.onnx").use { it.readBytes() }
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                require(digest == metadata.getString("model_sha256")) { "本地模型校验失败" }
                val vocabulary = context.assets.open("tinybert/vocab.txt").bufferedReader().use { it.readLines() }
                TinyBertModel(bytes, vocabulary, metadata).also { instance = it }
            }
        }
    }
}
