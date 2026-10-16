package cn.yiji.app.core

import java.text.Normalizer
import java.time.*

/** Calendar/clock arithmetic applied only to time spans selected by the model. */
object DateTimeResolver {
    private const val periods = "凌晨|清晨|早上|早晨|上午|中午|下午|傍晚|晚上|晚间|晚"
    private val clock = Regex("(?<![\\d.:])($periods)?\\s*(\\d{1,2})(?:[:：](\\d{2})|[点时](?:(\\d{1,2})分?|([半整])|(一刻|三刻))?)(?![\\d.:])")
    fun resolve(text: String, reference: LocalDate, issues: MutableList<String>, zone: ZoneId, inheritDate: Boolean = false, precedingTime: String? = null): Long? {
        val clean = text.replace(Regex("[【】（）()]"), "")
        val calendar = clean.replace("次日", "").replace("翌日", "").replace("第二天", "")
        val date = parseDate(calendar, reference, issues) ?: if (inheritDate && !hasDate(calendar)) reference else null
        val time = if (precedingTime != null && !hasDate(clean)) parseClocks("$precedingTime 至 $clean").lastOrNull()
            else parseClocks(clean).firstOrNull()
        if (time == null) return null
        return date?.plusDays(if (time.eveningMidnight) 1 else if (clean.contains("次日") || clean.contains("翌日") || clean.contains("第二天")) 1 else 0)
            ?.atTime(time.time)?.atZone(zone)?.toInstant()?.toEpochMilli()
    }
    private fun hasDate(text: String): Boolean = Regex("年|月|日|号|周|星期|礼拜|今天|明天|后天|今晚|今早|明晚|明早|\\d{1,2}[-/]\\d{1,2}").containsMatchIn(text)

    private fun normalize(text: String): String {
        var result = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .replace("今晚", "今天晚上").replace("今早", "今天早上")
            .replace("明晚", "明天晚上").replace("明早", "明天早上")
            .replace(Regex("(?<!\\d)(1[3-9]|2[0-3])\\.(\\d{2})(?!\\d)"), "$1:$2")
        // Only convert numerals next to date/time units; retain venue names and weekday names.
        result = Regex("[零〇一二两三四五六七八九十]{1,3}(?=月|日|号(?!楼|室)|点|时|分)").replace(result) { match ->
            val value = match.value.replace('两', '二')
            val digits = "零一二三四五六七八九"
            fun digit(c: Char) = if (c == '〇') 0 else digits.indexOf(c)
            val number = if ('十' in value) {
                val parts = value.split('十')
                (parts[0].firstOrNull()?.let(::digit) ?: 1) * 10 + (parts[1].firstOrNull()?.let(::digit) ?: 0)
            } else value.fold(0) { n, c -> n * 10 + digit(c) }
            number.toString()
        }
        // Expand a clock range's omitted first unit (2-3点), without treating a room number as time.
        return Regex("(?<![\\d.:])(\\d{1,2})(\\s*(?:到|至|[-~～—–])\\s*)(?=(?:$periods)?\\d{1,2}(?:[点时]|[:：]))")
            .replace(result) { "${it.groupValues[1]}点${it.groupValues[2]}" }
    }
    fun parseDate(text: String, reference: LocalDate, issues: MutableList<String> = mutableListOf()): LocalDate? {
        val normalized = normalize(text)
        Regex("(?<!\\d)(20\\d{2})[-/.年](\\d{1,2})[-/.月](\\d{1,2})(?:日|号)?(?!\\d)").find(normalized)?.let {
            return runCatching { LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }.getOrNull()
        }
        Regex("(?<![\\d./-])(\\d{1,2})(?:月|[./-])(\\d{1,2})(?:日|号)?(?!\\d)").find(normalized)?.let {
            issues += "原文未写年份，已按 ${reference.year} 年解析，请核对"
            return runCatching { LocalDate.of(reference.year, it.groupValues[1].toInt(), it.groupValues[2].toInt()) }.getOrNull()
        }
        when {
            normalized.contains("大后天") -> return reference.plusDays(3)
            normalized.contains("后天") -> return reference.plusDays(2)
            normalized.contains("明天") || normalized.contains("明日") -> return reference.plusDays(1)
            normalized.contains("今天") || normalized.contains("今日") -> return reference
        }
        Regex("(下下|下|本|这)?(?:周|星期|礼拜)([一二三四五六日天1-7])").find(normalized)?.let {
            val day = it.groupValues[2]
            val wanted = day.toIntOrNull() ?: ("一二三四五六日".indexOf(day.replace("天", "日")) + 1)
            val monday = reference.minusDays((reference.dayOfWeek.value - 1).toLong())
            val result = monday.plusDays((wanted - 1).toLong())
            return when (it.groupValues[1]) {
                "下下" -> result.plusWeeks(2)
                "下" -> result.plusWeeks(1)
                "本", "这" -> result
                else -> if (result < reference) result.plusWeeks(1) else result
            }
        }
        Regex("(?<!\\d)(?:(下|本|这)个?月)?(\\d{1,2})[日号](?!楼|室)").find(normalized)?.let {
            val month = if (it.groupValues[1] == "下") reference.plusMonths(1) else reference
            issues += if (it.groupValues[1].isBlank()) "原文只写日号，已按 ${month.year} 年 ${month.monthValue} 月解析，请核对" else "原文未写年份，已按 ${month.year} 年解析，请核对"
            return runCatching { month.withDayOfMonth(it.groupValues[2].toInt()) }.getOrNull()
        }
        return null
    }

    fun parseTimes(text: String): List<LocalTime> = parseClocks(text).map { it.time }

    private data class ParsedTime(val time: LocalTime, val eveningMidnight: Boolean)

    private fun parseClocks(text: String): List<ParsedTime> {
        val normalized = normalize(text)
        var lastPeriod = ""
        var previousEnd = 0
        return clock.findAll(normalized).mapNotNull { m ->
            val connector = normalized.substring(previousEnd, m.range.first)
            val range = Regex("\\s*(?:至|到|[-~～—–])\\s*").matches(connector)
            val period = m.groupValues[1].ifBlank { if (range) lastPeriod else "" }
            lastPeriod = period
            previousEnd = m.range.last + 1
            var hour = m.groupValues[2].toInt()
            val minute = when {
                m.groupValues[3].isNotBlank() -> m.groupValues[3].toInt()
                m.groupValues[4].isNotBlank() -> m.groupValues[4].toInt()
                m.groupValues[5] == "半" -> 30
                m.groupValues[6] == "一刻" -> 15
                m.groupValues[6] == "三刻" -> 45
                else -> 0
            }
            if (period in setOf("下午", "傍晚", "晚上", "晚间", "晚") && hour in 1..11) hour += 12
            if (period == "中午" && hour in 1..5) hour += 12
            val eveningMidnight = period in setOf("晚上", "晚间", "晚") && hour == 12
            if (period in setOf("凌晨", "晚上", "晚间", "晚") && hour == 12) hour = 0
            runCatching { ParsedTime(LocalTime.of(hour, minute), eveningMidnight) }.getOrNull()
        }.toList()
    }
}
