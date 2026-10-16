package cn.yiji.app.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.nio.LongBuffer
import kotlin.math.exp

data class ModelEntity(val label: String, val start: Int, val end: Int, val text: String, val confidence: Double)
data class ModelPrediction(val category: String, val kind: String, val state: String, val operation: String,
    val confidence: Double, val entities: List<ModelEntity>)

/** Only learned logits decide relevance, categories, time roles and change intent. */
class TinyBertModel(model: ByteArray, vocabulary: List<String>, metadata: JSONObject) : AutoCloseable {
    private val tokenizer = BertWordPiece(vocabulary)
    private val labels = metadata.getJSONArray("labels").let { a -> List(a.length()) { a.getString(it) } }
    private val heads = listOf("route", "kind", "state", "operation").map { key ->
        metadata.getJSONObject("heads").getJSONArray(key).let { a -> List(a.length()) { a.getString(it) } }
    }
    private val maxLength = metadata.getInt("max_length")
    private val stride = metadata.getInt("stride")
    private val environment = OrtEnvironment.getEnvironment()
    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
        environment.createSession(model, options)
    }
    init { require(maxLength in 16..512 && stride in 1 until maxLength - 2) }

    @Synchronized
    fun predict(text: String): ModelPrediction {
        val pieces = tokenizer.tokenize(text)
        require(pieces.isNotEmpty()) { "通知没有可识别的文字" }
        val votes = Array(pieces.size) { DoubleArray(labels.size) }
        val seen = DoubleArray(pieces.size)
        val documents = heads.map { DoubleArray(it.size) }
        var first = 0
        var windows = 0
        while (first < pieces.size) {
            val last = minOf(first + maxLength - 2, pieces.size)
            val ids = longArrayOf(tokenizer.cls) + pieces.subList(first, last).map { it.id }.toLongArray() + tokenizer.sep
            val shape = longArrayOf(1, ids.size.toLong())
            OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape).use { input ->
                OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(ids.size) { 1 }), shape).use { mask ->
                    OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(ids.size)), shape).use { types ->
                        session.run(mapOf("input_ids" to input, "attention_mask" to mask, "token_type_ids" to types)).use { result ->
                            @Suppress("UNCHECKED_CAST")
                            val tags = (result[0].value as Array<Array<FloatArray>>)[0]
                            for (i in first until last) {
                                // Give complete context more weight than a truncated window edge.
                                val weight = minOf(i - first + 1, last - i, stride).toDouble() / stride
                                for (j in labels.indices) votes[i][j] += tags[i - first + 1][j] * weight
                                seen[i] += weight
                            }
                            for (h in heads.indices) {
                                @Suppress("UNCHECKED_CAST")
                                val logits = (result[h + 1].value as Array<FloatArray>)[0]
                                for (j in logits.indices) documents[h][j] += logits[j]
                            }
                        }
                    }
                }
            }
            windows++
            if (last == pieces.size) break
            first = last - stride
        }
        val docProbabilities = documents.map { softmax(it.map { value -> value / windows }.toDoubleArray()) }
        val decisions = docProbabilities.mapIndexed { i, probabilities -> heads[i][probabilities.indices.maxBy { probabilities[it] }] }
        val entities = mutableListOf<ModelEntity>()
        var active: String? = null
        var start = 0
        var end = 0
        var confidence = 0.0
        var count = 0
        fun flush() {
            active?.let { entities += ModelEntity(it, start, end, text.substring(start, end), confidence / count) }
            active = null; count = 0; confidence = 0.0
        }
        pieces.forEachIndexed { i, piece ->
            val p = softmax(votes[i].map { it / seen[i] }.toDoubleArray())
            val index = p.indices.maxBy { p[it] }
            val label = labels[index]
            val kind = label.substringAfter('-', "")
            if (label == "O" || !label.startsWith("I-") || active != kind) flush()
            if (label != "O") {
                if (active == null) { active = kind; start = piece.start }
                end = piece.end; confidence += p[index]; count++
            }
        }
        flush()
        return ModelPrediction(decisions[0], decisions[1], decisions[2], decisions[3], docProbabilities[0].max(), entities)
    }
    override fun close() = session.close()

    private fun softmax(values: DoubleArray): DoubleArray {
        val max = values.max(); val numbers = values.map { exp(it - max) }; val sum = numbers.sum()
        return numbers.map { it / sum }.toDoubleArray()
    }
}
