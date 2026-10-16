package cn.yiji.app.core

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class DateTimeResolverTest {
    private val reference = LocalDate.of(2026, 10, 16)
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun resolve(text: String) = DateTimeResolver.resolve(text, reference, mutableListOf(), zone)

    @Test fun explicitAndRelativeDates() {
        assertEquals(at(23, 18), resolve("2026年10月23日18:00"))
        assertEquals(at(17, 20), resolve("明晚20:00"))
        assertEquals(at(16, 23, 52), resolve("今晚23.52"))
        assertEquals(at(18, 20), resolve("本周日（【10月18日】）晚20:00"))
    }
    @Test fun missingAndInvalidDatesStayUnknown() {
        assertNull(resolve("14:00")); assertNull(resolve("2026年2月30日14:00"))
        assertNull(resolve("2026年10月23日")); assertNull(resolve("今天25:70"))
        assertNull(resolve("图书馆301，学号20261014"))
    }
    @Test fun endInheritsOnlyAnExplicitStartDateAndPeriod() {
        assertEquals(at(16, 15), DateTimeResolver.resolve("3点", reference, mutableListOf(), zone, true, "今天下午2点"))
        assertEquals(at(17, 1), DateTimeResolver.resolve("次日凌晨1点", reference, mutableListOf(), zone, true, "今天23:00"))
        assertNull(DateTimeResolver.resolve("2月30日15:00", reference, mutableListOf(), zone, true))
    }
    @Test fun chineseFullWidthAndSlashDates() {
        listOf("十月二十五日", "１０月２５日", "10/25", "10.25", "10-25", "2026.10.25").forEach {
            assertEquals(it, LocalDate.of(2026, 10, 25), DateTimeResolver.parseDate(it, reference))
        }
        assertEquals(listOf(LocalTime.of(14, 30), LocalTime.of(15, 15)), DateTimeResolver.parseTimes("下午两点半至三点一刻"))
        assertEquals(listOf(LocalTime.of(8, 45)), DateTimeResolver.parseTimes("上午八点三刻"))
        assertEquals(listOf(LocalTime.of(14, 30), LocalTime.of(15, 30)), DateTimeResolver.parseTimes("１４：３０～１５：３０"))
    }
    @Test fun weekdaysAndYearAssumptions() {
        val issues = mutableListOf<String>()
        assertEquals(LocalDate.of(2026, 10, 23), DateTimeResolver.parseDate("下周五", reference))
        assertEquals(LocalDate.of(2026, 10, 12), DateTimeResolver.parseDate("本周一", reference))
        assertEquals(LocalDate.of(2026, 10, 19), DateTimeResolver.parseDate("周一", reference))
        assertEquals(LocalDate.of(2026, 10, 20), DateTimeResolver.parseDate("20号", reference, issues))
        assertFalse(issues.isEmpty())
        assertEquals(LocalDate.of(2027, 1, 2), DateTimeResolver.parseDate("下个月2号", LocalDate.of(2026, 12, 31)))
    }
    @Test fun roomNumbersAndMalformedClocksAreRejected() {
        assertNull(DateTimeResolver.parseDate("3号楼301", reference))
        assertNull(DateTimeResolver.parseDate("120号", reference))
        assertTrue(DateTimeResolver.parseTimes("123:30，25:70，14:005").isEmpty())
    }
    @Test fun eveningMidnightMeansNextDay() {
        assertEquals(at(17, 0), resolve("今晚十二点"))
        assertEquals(listOf(LocalTime.of(14, 0), LocalTime.of(3, 0)), DateTimeResolver.parseTimes("下午2点集合，3点另行通知"))
    }
}
