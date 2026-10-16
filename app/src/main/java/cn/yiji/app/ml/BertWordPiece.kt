package cn.yiji.app.ml

import java.text.Normalizer
import java.util.Locale

/** BERT uncased WordPiece with original UTF-16 source offsets. */
class BertWordPiece(vocabulary: List<String>) {
    data class Piece(val id: Long, val start: Int, val end: Int)
    private val vocab = vocabulary.withIndex().associate { it.value to it.index.toLong() }
    val cls = requireNotNull(vocab["[CLS]"])
    val sep = requireNotNull(vocab["[SEP]"])
    private val unk = requireNotNull(vocab["[UNK]"])
    private val special = listOf("[UNK]", "[SEP]", "[PAD]", "[CLS]", "[MASK]")

    init { require(vocabulary.size == 21128) { "Unexpected TinyBERT vocabulary" } }

    fun tokenize(text: String): List<Piece> {
        val result = mutableListOf<Piece>()
        val word = StringBuilder()
        val starts = mutableListOf<Int>()
        val ends = mutableListOf<Int>()
        fun flush() {
            if (word.isEmpty()) return
            val value = word.toString()
            val pieces = mutableListOf<Piece>()
            var start = 0
            var failed = value.codePointCount(0, value.length) > 100
            while (start < value.length && !failed) {
                var end = value.length
                var found: Piece? = null
                while (end > start) {
                    val key = (if (start > 0) "##" else "") + value.substring(start, end)
                    val id = vocab[key]
                    if (id != null) { found = Piece(id, starts[start], ends[end - 1]); break }
                    end = value.offsetByCodePoints(end, -1)
                }
                if (found == null) failed = true else { pieces += found; start = end }
            }
            if (failed) result += Piece(unk, starts.first(), ends.last()) else result += pieces
            word.clear(); starts.clear(); ends.clear()
        }
        fun append(value: String, start: Int, end: Int) {
            word.append(value)
            repeat(value.length) { starts += start; ends += end }
        }
        var index = 0
        while (index < text.length) {
            val protected = special.firstOrNull { text.startsWith(it, index) }
            if (protected != null) {
                flush()
                result += Piece(requireNotNull(vocab[protected]), index, index + protected.length)
                index += protected.length
                continue
            }
            val cp = text.codePointAt(index)
            val next = index + Character.charCount(cp)
            val type = Character.getType(cp)
            when {
                cp == 0 || cp == 0xFFFD -> Unit
                Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> flush()
                type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() -> Unit
                else -> {
                    val isolated = chinese(cp) || punctuation(cp)
                    if (isolated) flush()
                    val normalized = Normalizer.normalize(String(Character.toChars(cp)).lowercase(Locale.ROOT), Normalizer.Form.NFD)
                        .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
                    if (normalized.isNotEmpty()) append(normalized, index, next)
                    if (isolated) flush()
                }
            }
            index = next
        }
        flush()
        return result
    }

    private fun chinese(cp: Int): Boolean = cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF ||
        cp in 0x20000..0x2A6DF || cp in 0x2A700..0x2B73F || cp in 0x2B740..0x2B81F ||
        cp in 0x2B820..0x2CEAF || cp in 0xF900..0xFAFF || cp in 0x2F800..0x2FA1F

    private fun punctuation(cp: Int): Boolean = cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126 ||
        Character.getType(cp) in setOf(Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(), Character.OTHER_PUNCTUATION.toInt())
}
