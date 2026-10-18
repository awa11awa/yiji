package cn.yiji.app.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.nio.LongBuffer
import kotlin.math.exp

data class GatePrediction(val candidate: Boolean, val scheduleProbability: Double, val uncertain: Boolean)
fun interface MessageGate { suspend fun classify(text: String): GatePrediction }

/** v2.1 graph has one binary routing head, no extraction heads. */
class TinyBertModel(model: ByteArray, vocabulary: List<String>, metadata: JSONObject) : AutoCloseable {
    private val tokenizer = BertWordPiece(vocabulary)
    private val maxLength = metadata.getInt("max_length")
    private val stride = metadata.getInt("stride")
    private val ignoreThreshold = metadata.getDouble("chat_ignore_threshold")
    private val environment = OrtEnvironment.getEnvironment()
    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2)
        options.setInterOpNumThreads(1)
        environment.createSession(model, options)
    }
    init {
        require(metadata.getString("task") == "binary_gate")
        require(metadata.getJSONArray("labels").getString(0) == "CHAT" && metadata.getJSONArray("labels").getString(1) == "SCHEDULE")
        require(maxLength in 16..512 && stride in 1 until maxLength - 2 && ignoreThreshold in .8..1.0)
    }
    @Synchronized fun predict(text: String): GatePrediction {
        val pieces = tokenizer.tokenize(text)
        require(pieces.isNotEmpty()) { "通知没有可识别的文字" }
        var first = 0
        var probability = 0.0
        while (first < pieces.size) {
            val last = minOf(first + maxLength - 2, pieces.size)
            val ids = longArrayOf(tokenizer.cls) + pieces.subList(first, last).map { it.id }.toLongArray() + tokenizer.sep
            val shape = longArrayOf(1, ids.size.toLong())
            OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape).use { input ->
                OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(ids.size) { 1 }), shape).use { mask ->
                    OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(ids.size)), shape).use { types ->
                        session.run(mapOf("input_ids" to input, "attention_mask" to mask, "token_type_ids" to types)).use { result ->
                            @Suppress("UNCHECKED_CAST")
                            val logits = (result[0].value as Array<FloatArray>)[0]
                            require(logits.size == 2)
                            val max = logits.max().toDouble()
                            val chat = exp(logits[0] - max)
                            val schedule = exp(logits[1] - max)
                            probability = maxOf(probability, schedule / (chat + schedule))
                        }
                    }
                }
            }
            if (last == pieces.size) break
            first = last - stride
        }
        return GatePrediction(1.0 - probability < ignoreThreshold, probability, probability in (1.0 - ignoreThreshold)..0.7)
    }
    override fun close() = session.close()
}
