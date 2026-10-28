package cn.yiji.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * 真实数据回归测试（需求：用真实通知语料完善检测）。
 *
 * 语料来自用户真实收集的校园通知（`src/test/resources/realdata/units.tsv`，48 条、约 1.8 万字），
 * 由 `tools/challenge/merge_units.py` 从原始 Word 按「【…】为通知头」的规则合并而来。
 *
 * 这个测试做两件事：
 * 1. 把规则抽取结果落成报告文件，方便人工复核（不靠"感觉"判断准不准）；
 * 2. 对若干条**人工核对过**的通知做精确断言，并对整体覆盖率设下限。
 */
class RealDataTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val receivedAt = LocalDate.of(2026, 10, 19).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private data class Unit(val id: String, val text: String)

    private fun loadUnits(): List<Unit> {
        val stream = javaClass.getResourceAsStream("/realdata/units.tsv") ?: error("找不到语料文件")
        return stream.bufferedReader(Charsets.UTF_8).readLines().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) null else Unit(line.substring(0, tab), line.substring(tab + 1))
        }
    }

    private fun at(month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDate.of(2026, month, day).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun corpusIsPresentAndMeaningful() {
        val units = loadUnits()
        assertTrue("语料应有足够样本，实际 ${units.size}", units.size >= 40)
        val totalChars = units.sumOf { it.text.length }
        assertTrue("语料总字数偏少：$totalChars", totalChars >= 15_000)
        // 真实校园通知里「时间/地点」字段出现频率很高，这是规律抽取的基础
        assertTrue("含时间字段的通知应占多数", units.count { it.text.contains("时间") || it.text.contains("⏰") } >= 30)
        assertTrue("含地点字段的通知应有不少", units.count { it.text.contains("地点") || it.text.contains("📍") } >= 15)
    }

    @Test
    fun extractsFieldsFromRealNotifications() {
        val units = loadUnits()
        val zone = this.zone
        val lines = mutableListOf<String>()
        var timeFound = 0
        var vague = 0
        var locationFound = 0
        var homework = 0

        units.forEach { unit ->
            val f = RuleExtractor.extract(unit.text, receivedAt, zone)
            if (f.timeAt != null) timeFound++
            if (f.timeVague) vague++
            if (f.location.isNotBlank()) locationFound++
            if (f.isHomework) homework++
            lines += buildString {
                append(unit.id).append('\t')
                append("time=").append(f.timeAt?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime().toString() } ?: "-")
                append("\tvague=").append(f.timeVague)
                append("\tddl=").append(f.deadlineAt?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime().toString() } ?: "-")
                append("\tloc=").append(f.location.ifBlank { "-" })
                append("\thw=").append(if (f.isHomework) f.homeworkName else "-")
                append("\ttitle=").append(f.title)
                if (f.notes.isNotEmpty()) append("\tnotes=").append(f.notes.joinToString("；"))
            }
        }

        val report = File("build/reports/realdata-extraction.txt")
        report.parentFile!!.mkdirs()
        report.writeText(lines.joinToString("\n"), Charsets.UTF_8)

        println("时间解析成功: $timeFound / ${units.size}")
        println("时间不具体: $vague")
        println("地点抽取成功: $locationFound / ${units.size}")
        println("判定为作业: $homework")
        println("报告: ${report.absolutePath}")

        // 真实语料上的覆盖率下限（留出余量，避免规则微调就红）
        assertTrue("时间解析覆盖过低：$timeFound", timeFound >= 15)
        assertTrue("地点抽取覆盖过低：$locationFound", locationFound >= 12)
    }

    /** 人工核对过的四条，要求逐字段精确。 */
    @Test
    fun matchesHandVerifiedNotifications() {
        val units = loadUnits()
        val zone = this.zone

        // 舍长培训大会：⏰会议时间：10月22日（周四）17:40 / 📍会议地点：田家炳409
        units.firstOrNull { it.text.contains("舍长培训大会") }?.let {
            val f = RuleExtractor.extract(it.text, receivedAt, zone)
            assertEquals("舍长培训时间", at(10, 22, 17, 40), f.timeAt)
            assertEquals("舍长培训地点", "田家炳409", f.location)
        } ?: error("语料里缺少舍长培训通知")

        // AI 智能体专题培训会：活动时间 2026年10月19日（下周一）16：00 / 创新楼102教室
        // 用「创新楼102教室」定位：通知标题里含「AI智能体」的段落不止一条
        units.firstOrNull { it.text.contains("创新楼102教室") }?.let {
            val f = RuleExtractor.extract(it.text, receivedAt, zone)
            assertEquals("AI培训时间", at(10, 19, 16, 0), f.timeAt)
            assertEquals("AI培训地点", "创新楼102教室", f.location)
        } ?: error("语料里缺少 AI 培训通知")

        // 迎新晚会（兹定于…晚18:30，没有「时间：」标签，必须靠正文兜底）
        units.firstOrNull { it.text.contains("入场一轮门票登记通知") }?.let {
            val f = RuleExtractor.extract(it.text, receivedAt, zone)
            assertEquals("迎新晚会时间", at(10, 24, 18, 30), f.timeAt)
            assertTrue("迎新晚会地点应命中大礼堂，实际 '${f.location}'", f.location.contains("大礼堂"))
            // 该通知里的 ⏰ 只有「开放填写 12:00」「截止 20:00」，都是报名角色，不能被当成活动时间
            assertEquals("报名截止时间应单独识别", at(10, 21, 20, 0), f.deadlineAt)
        } ?: error("语料里缺少迎新晚会通知")

        // 化妆志愿者：「时间：10月24日中午及下午（时间以现场安排为准）」→ 不能硬编一个时间
        units.firstOrNull { it.text.contains("化妆志愿者招募") }?.let {
            val f = RuleExtractor.extract(it.text, receivedAt, zone)
            assertTrue("该通知时间应标记为不具体", f.timeVague)
            assertEquals("化妆志愿者地点", "躍仁大礼堂", f.location)
            assertNotNull("应保留原始时间文本供用户核对", f.timeText.ifBlank { null })
        } ?: error("语料里缺少化妆志愿者通知")
    }
}
