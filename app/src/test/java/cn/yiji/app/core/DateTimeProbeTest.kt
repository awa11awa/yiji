package cn.yiji.app.core

import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * 阶段性探针：用一批**真实口语写法**压测时间/日期解析，找出系统性错误。
 *
 * 这不是"通过"用的测试，而是拿失败项当缺陷清单。
 * 确认正确的用例会保留为长期回归测试。
 */
class DateTimeProbeTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val ref: LocalDate = LocalDate.of(2026, 10, 19)   // 周一
    private fun date(text: String) = DateTimeResolver.parseDate(text, ref)
    private fun times(text: String) = DateTimeResolver.parseTimes(text)

    // ---------- 日期 ----------
    @Test fun relativeDates() {
        assertEquals2(ref, date("今天"), "今天")
        assertEquals2(ref.plusDays(1), date("明天"), "明天")
        assertEquals2(ref.plusDays(2), date("后天"), "后天")
        assertEquals2(ref.plusDays(3), date("大后天"), "大后天")
        assertEquals2(ref.plusDays(1), date("明日"), "明日")
    }

    @Test fun weekdayForms() {
        val monday = ref.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        assertEquals2(monday.plusDays(2), date("周三"), "周三（本周三还没过）")
        assertEquals2(monday.plusDays(6), date("周日"), "周日")
        assertEquals2(monday.plusDays(6), date("周天"), "周天")
        assertEquals2(monday.plusWeeks(1), date("下周一"), "下周一")
        assertEquals2(monday, date("本周一"), "本周一")
    }

    @Test fun calendarDates() {
        assertEquals2(LocalDate.of(2026, 3, 5), date("3月5日"), "3月5日")
        assertEquals2(LocalDate.of(2026, 3, 5), date("3月5号"), "3月5号")
        assertEquals2(LocalDate.of(2026, 10, 15), date("10月15日"), "10月15日")
        assertEquals2(LocalDate.of(2026, 3, 5), date("2026年3月5日"), "带年份")
        assertEquals2(LocalDate.of(2026, 11, 5), date("下个月5号"), "下个月5号")
    }

    /** 只写日号、且本月该日已过 —— 该滚到下个月，还是保留在本月（过去的日期）？ */
    @Test fun dayOnlyWhenAlreadyPassed() {
        val got = date("5号")   // ref = 10/19，10月5日已经过去
        assertEquals2(LocalDate.of(2026, 11, 5), got, "已过去的『5号』应滚到下月")
    }

    // ---------- 时间 ----------
    @Test fun clockForms() {
        assertEquals2(listOf(LocalTime.of(14, 0)), times("下午2点"), "下午2点")
        assertEquals2(listOf(LocalTime.of(14, 30)), times("14:30"), "14:30")
        // 「2点半」不带上下午 → 保持不猜（02:30），但 resolve 必须给出歧义提示，见下一条测试
        assertEquals2(listOf(LocalTime.of(2, 30)), times("2点半"), "2点半（无上下午，按字面）")
        assertEquals2(listOf(LocalTime.of(20, 0)), times("晚上8点"), "晚上8点")
        assertEquals2(listOf(LocalTime.of(1, 0)), times("凌晨1点"), "凌晨1点")
        assertEquals2(listOf(LocalTime.of(12, 30)), times("中午12点半"), "中午12点半")
        assertEquals2(listOf(LocalTime.of(13, 0)), times("中午1点"), "中午1点")
        assertEquals2(listOf(LocalTime.of(11, 0)), times("上午11点"), "上午11点")
        assertEquals2(listOf(LocalTime.of(10, 15)), times("10点15分"), "10点15分")
        assertEquals2(listOf(LocalTime.of(3, 15)), times("3点一刻"), "3点一刻（无上下午，按字面）")
        assertEquals2(listOf(LocalTime.of(3, 45)), times("3点三刻"), "3点三刻（无上下午，按字面）")
        assertEquals2(listOf(LocalTime.of(2, 0)), times("两点"), "两点")
        assertEquals2(listOf(LocalTime.of(12, 0)), times("十二点"), "十二点")
    }

    /** 1–6 点没写上下午时，必须让用户看到歧义提示，而不是默默按凌晨算。 */
    @Test fun bareEarlyHourRaisesAmbiguityIssue() {
        val issues = mutableListOf<String>()
        DateTimeResolver.resolve("明天2点半开会", ref, issues, zone)
        if (issues.none { it.contains("上午或下午") }) {
            throw AssertionError("缺少歧义提示，issues=$issues")
        }
    }

    /** 带上下午就不该提示歧义。 */
    @Test fun explicitPeriodHasNoAmbiguityIssue() {
        val issues = mutableListOf<String>()
        DateTimeResolver.resolve("明天下午2点半开会", ref, issues, zone)
        if (issues.any { it.contains("上午或下午") }) {
            throw AssertionError("已写明下午却仍提示歧义，issues=$issues")
        }
    }

    @Test fun clockRanges() {
        assertEquals2(listOf(LocalTime.of(9, 0), LocalTime.of(11, 0)), times("9点到11点"), "9点到11点")
        assertEquals2(listOf(LocalTime.of(14, 0), LocalTime.of(16, 0)), times("下午2点到4点"), "下午2点到4点")
        assertEquals2(listOf(LocalTime.of(9, 0), LocalTime.of(11, 0)), times("2-3点".replace("2-3", "9-11")), "9-11点")
    }

    /** 会议室 / 宿舍号 绝不能被当成时间或日期。 */
    @Test fun venueNumbersAreNotTime() {
        assertEquals2(emptyList<LocalTime>(), times("图书馆301室"), "301室")
        assertEquals2(emptyList<LocalTime>(), times("教三-201"), "教三-201")
        assertEquals2(null, date("301室集合"), "301室不该解析出日期")
    }

    /** 晚上12点 = 次日 00:00；凌晨12点 = 当天 00:00。 */
    @Test fun midnightHandling() {
        val issues = mutableListOf<String>()
        val at = DateTimeResolver.resolve("明天晚上12点", ref, issues, zone)
        val local = java.time.Instant.ofEpochMilli(at!!).atZone(zone)
        assertEquals2(LocalDate.of(2026, 10, 21), local.toLocalDate(), "明天晚上12点应落到次日凌晨")
        assertEquals2(LocalTime.of(0, 0), local.toLocalTime(), "应为 00:00")
    }

    private fun assertEquals2(expected: Any?, actual: Any?, what: String) {
        if (expected != actual) throw AssertionError("$what：期望 <$expected> 实际 <$actual>")
    }
}
