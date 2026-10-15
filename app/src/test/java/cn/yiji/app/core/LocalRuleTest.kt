package cn.yiji.app.core

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class LocalRuleTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 10, 14)
    private val reference = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private fun time(day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun parse(text: String, title: String = "班级群") = RuleParser.parse(title, text, reference, zone)

    @Test fun campusActionsWithTimesAreRecognizedWithoutAi() {
        listOf("明天14点到301集合，带学生证", "明早八点半签到", "今晚九点前交表", "周五下午两点领取教材",
            "请于明天17点前填报信息", "请明天到教务处上交申请表", "下周一八点报到", "明天上传电子版",
            "今晚十点健康打卡", "明天九点归还图书", "请尽快登记返校信息", "请领取准考证")
            .forEach { assertTrue(it, RuleParser.isImportant(it)) }
    }

    @Test fun noiseCasualChatAndCompletedWorkAreNotTasks() {
        listOf("晚饭吃什么", "今天真热", "今晚一起吃饭吗", "这个活动真好玩", "今天的考试终于结束了",
            "我作业写完了", "领取红包今晚八点开始", "优惠券明天截止领取", "你的验证码是123456")
            .forEach { assertFalse(it, RuleParser.isImportant(it)) }
    }

    @Test fun groupNamesCannotTurnUnrelatedChatIntoAnEvent() {
        listOf("数学作业群", "班级会议群", "考试报名群").forEach { title ->
            assertFalse(title, RuleParser.isImportant("晚饭吃什么", title))
            assertFalse(title, RuleParser.isImportant("今天真热", title))
        }
        assertTrue(RuleParser.isImportant("明天14点开始，请准时参加", "班级会议"))
    }

    @Test fun incompleteNoticesStillReachConfirmation() {
        assertTrue(RuleParser.isImportant("会议时间另行通知"))
        assertTrue(RuleParser.isImportant("请领取教材"))
        val draft = parse("请领取教材")
        assertNull(draft.startAt)
        assertNull(draft.endAt)
        assertNotNull(draft.validationError())
    }

    @Test fun changesAndUnscheduledGeneralNoticesAreNotDropped() {
        listOf("原定明天的活动取消", "课程改期，时间另行通知", "本次活动时间另行通知", "材料领取地点调整")
            .forEach { assertTrue(it, RuleParser.isImportant(it)) }
        assertFalse(RuleParser.isImportant("订单已取消"))
    }

    @Test fun bodyDeterminesCategoryAndKindBeforeGroupName() {
        val draft = parse("明天14点到15点开会", "交表作业群")
        assertEquals("会议", draft.category)
        assertEquals("SCHEDULE", draft.kind)
        assertEquals(time(15, 14), draft.startAt)
        assertEquals(time(15, 15), draft.endAt)
    }

    @Test fun gatheringExtractsRoomAndKeepsMissingEndTime() {
        val draft = parse("明天14点到301集合，带学生证")
        assertEquals(time(15, 14), draft.startAt)
        assertEquals("301", draft.location)
        assertEquals("活动", draft.category)
        assertNull(draft.endAt)
        assertNotNull(draft.validationError())
        assertEquals("明天14点到301集合，带学生证", draft.note)
    }

    @Test fun chineseNumeralsAndQuarterHours() {
        assertEquals(listOf(LocalTime.of(14, 30), LocalTime.of(15, 15)), RuleParser.parseTimes("下午两点半至三点一刻"))
        assertEquals(listOf(LocalTime.of(8, 45)), RuleParser.parseTimes("上午八点三刻"))
        assertEquals(listOf(LocalTime.of(10, 15)), RuleParser.parseTimes("上午十点十五分"))
        assertEquals(time(15, 8, 30), parse("明早八点半签到").startAt)
    }

    @Test fun fullWidthAndShortClockRanges() {
        val draft = parse("明天下午２－３点在图书馆３０１集合")
        assertEquals(time(15, 14), draft.startAt)
        assertEquals(time(15, 15), draft.endAt)
        assertEquals("图书馆301", draft.location)
        assertNull(draft.validationError())
        assertEquals(listOf(LocalTime.of(14, 30), LocalTime.of(15, 30)), RuleParser.parseTimes("１４：３０～１５：３０"))
    }

    @Test fun periodInheritanceIsLimitedToRanges() {
        assertEquals(listOf(LocalTime.of(14, 0), LocalTime.of(3, 0)), RuleParser.parseTimes("下午2点集合，3点另行通知"))
        assertEquals(listOf(LocalTime.of(23, 0), LocalTime.of(1, 0)), RuleParser.parseTimes("晚上11点至次日1点"))
    }

    @Test fun tonightAndTomorrowEvening() {
        assertEquals(time(14, 20, 30), parse("今晚八点半集合").startAt)
        assertEquals(time(15, 19), parse("明晚七点签到").startAt)
        assertEquals(time(15, 8), parse("明早八点报到").startAt)
    }

    @Test fun eveningMidnightBelongsToTheFollowingDate() {
        assertEquals(time(15, 0), parse("今晚十二点前交表").dueAt)
        val draft = parse("明天晚上十一点到十二点值班")
        assertEquals(time(15, 23), draft.startAt)
        assertEquals(time(16, 0), draft.endAt)
        assertNull(draft.validationError())
    }

    @Test fun formsUploadsAndApplicationsAreDeadlines() {
        listOf("请明天17点前交表", "请明天17点前填写信息", "请明天17点上传附件", "请明天17点上报名单")
            .forEach { text ->
                val draft = parse(text)
                assertEquals(text, "DEADLINE", draft.kind)
                assertEquals(text, time(15, 17), draft.dueAt)
                assertNull(draft.startAt)
                assertNull(draft.endAt)
            }
    }

    @Test fun collectionAndSignInStaySchedules() {
        listOf("明天14点领取教材", "明天14点签到", "明天14点到301集合").forEach {
            assertEquals(it, "SCHEDULE", parse(it).kind)
        }
    }

    @Test fun dateOnlyDeadlineNeverInventsEndOfDay() {
        val draft = parse("请在十月二十五日前交表")
        assertEquals("DEADLINE", draft.kind)
        assertNull(draft.dueAt)
        assertTrue(draft.issues.any { "2026-10-25" in it })
        assertNotNull(draft.validationError())
    }

    @Test fun chineseFullWidthAndSlashDates() {
        listOf("十月二十五日", "１０月２５日", "10/25", "10.25", "10-25").forEach {
            assertEquals(it, LocalDate.of(2026, 10, 25), RuleParser.parseDate(it, date))
        }
        assertEquals(LocalDate.of(2026, 10, 25), RuleParser.parseDate("2026.10.25", date))
        assertEquals(LocalDate.of(2026, 10, 16), RuleParser.parseDate("本周5", date))
    }

    @Test fun bareDayAndNextMonthIncludeAnAssumptionWarning() {
        val issues = mutableListOf<String>()
        assertEquals(LocalDate.of(2026, 10, 20), RuleParser.parseDate("20号", date, issues))
        assertTrue(issues.any { "本" in it || "10 月" in it })
        assertEquals(LocalDate.of(2027, 1, 2), RuleParser.parseDate("下个月2号", LocalDate.of(2026, 12, 31)))
        assertNull(RuleParser.parseDate("本月31号", LocalDate.of(2026, 2, 1)))
    }

    @Test fun roomNumbersAreNotDatesOrClocks() {
        assertNull(RuleParser.parseDate("3号楼301集合", date))
        assertNull(RuleParser.parseDate("120号", date))
        assertTrue(RuleParser.parseTimes("图书馆301，学号20261014").isEmpty())
        assertEquals("3号楼301", parse("明天14点到3号楼301集合").location)
        assertEquals("三号楼301", parse("明天14点到三号楼301集合").location)
    }

    @Test fun titleCanSupplyMissingDateAndClock() {
        val draft = parse("请在图书馆301集合", "10月20日14:00至15:00集合通知")
        assertEquals(time(20, 14), draft.startAt)
        assertEquals(time(20, 15), draft.endAt)
    }

    @Test fun locationOnlyChangesKeepTheOriginalTime() {
        val draft = parse("明天14点到15点召开会议，地点改为图书馆302", "班级会议")
        assertEquals(time(15, 14), draft.startAt)
        assertEquals(time(15, 15), draft.endAt)
        assertEquals("图书馆302", draft.location)
    }

    @Test fun reschedulingUsesChineseNewTime() {
        val draft = parse("原定今天下午两点到三点的会议改到明天下午四点至五点", "班级会议")
        assertEquals(time(15, 16), draft.startAt)
        assertEquals(time(15, 17), draft.endAt)
    }

    @Test fun invalidBodyDatesAndClocksAreNotReplacedByTitleValues() {
        assertNull(parse("2026年2月30日14点会议", "10月20日会议").startAt)
        assertNull(parse("明天25:70开会", "14:00会议").startAt)
        assertTrue(RuleParser.parseTimes("123:30，25:70，14:005").isEmpty())
    }
}
