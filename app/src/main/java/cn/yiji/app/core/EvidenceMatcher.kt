package cn.yiji.app.core

import java.text.Normalizer

data class EvidenceSpan(val text: String, val start: Int, val end: Int)

/** Formatting equivalence only. Never removes punctuation, words or numbers. */
object EvidenceMatcher {
    private data class Mapped(val text: String, val starts: List<Int>, val ends: List<Int>)
    private fun normalized(raw: String): Mapped {
        val text = StringBuilder(); val starts = mutableListOf<Int>(); val ends = mutableListOf<Int>()
        var offset = 0
        while (offset < raw.length) {
            val cp = raw.codePointAt(offset)
            val end = offset + Character.charCount(cp)
            val piece = when {
                Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> " "
                cp in 0xFF01..0xFF5E || cp == 0x3000 -> Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFKC)
                else -> String(Character.toChars(cp))
            }
            piece.forEach { c ->
                if (c == ' ' && text.lastOrNull() == ' ') ends[ends.lastIndex] = end
                else { text.append(c); starts += offset; ends += end }
            }
            offset = end
        }
        return Mapped(text.toString(), starts, ends)
    }
    fun match(source: String, quoted: String, within: EvidenceSpan? = null): EvidenceSpan? {
        if (quoted.isBlank() || quoted.length > 2000) return null
        val base = within?.start ?: 0
        val haystack = within?.let { source.substring(it.start, it.end) } ?: source
        val exact = haystack.indexOf(quoted)
        if (exact >= 0 && haystack.indexOf(quoted, exact + 1) < 0)
            return EvidenceSpan(source.substring(base + exact, base + exact + quoted.length), base + exact, base + exact + quoted.length)
        val a = normalized(haystack); val b = normalized(quoted).text.trim()
        if (b.isBlank()) return null
        val index = a.text.indexOf(b)
        if (index < 0 || a.text.indexOf(b, index + 1) >= 0) return null
        val start = base + a.starts[index]; val end = base + a.ends[index + b.length - 1]
        return EvidenceSpan(source.substring(start, end), start, end)
    }
}
