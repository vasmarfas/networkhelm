package com.vasmarfas.networkhelm.ui.log

import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogEntryTest {

    private fun entry(id: String, time: String, topics: String, message: String, today: String? = "2026-10-27") =
        Item(id, mapOf("time" to time, "topics" to topics, "message" to message)).toEntry(today)

    private val lines = listOf(
        entry("*1", "2026-10-27 09:00:00", "system,info,account", "user admin logged in from 192.0.2.5 via winbox"),
        entry("*2", "2026-10-27 09:00:05", "firewall,info", "input: in:ether1 out:(unknown 0), proto TCP"),
        entry("*3", "2026-10-27 09:01:10", "system,error,critical", "login failure for user root via ssh"),
        entry("*4", "2026-10-27 09:01:11", "system,error,critical", "login failure for user root via ssh"),
        entry("*5", "2026-10-27 09:01:12", "wireless,debug", "ap: 00:00:5E:00:53:01 connected"),
        entry("*6", "2026-10-27 09:30:00", "dhcp,warning", "lease for 192.0.2.77 not renewed"),
    )

    @Test
    fun theHighestWordOfSeverityAmongTheTopicsDecides() {
        assertEquals(Severity.Info, lines[0].severity)
        assertEquals(Severity.Critical, lines[2].severity)
        assertEquals(Severity.Debug, lines[4].severity)
        assertEquals(Severity.Warning, lines[5].severity)
        assertEquals(Severity.Error, entry("a", "", "daemon.err", "").severity)
        assertEquals(Severity.Warning, entry("a", "", "kern.warn", "").severity)
        assertEquals(Severity.Info, entry("a", "", "", "no topics").severity)
        assertEquals(listOf("system", "error", "critical"), lines[2].words)
    }

    @Test
    fun theListTagsTheWordsThatSayMoreThanInfo() {
        assertEquals(listOf("system", "account"), lines[0].tags)
        assertEquals(listOf("firewall"), lines[1].tags)
        assertEquals(listOf("system", "error", "critical"), lines[2].tags)
        assertEquals(listOf("wireless", "debug"), lines[4].tags)
        assertEquals(listOf("daemon"), entry("a", "", "daemon.notice", "").tags)
    }

    @Test
    fun timesOfTheFormsRoutersWriteThemGetStamps() {
        val iso = assertNotNull(stampOf("2026-10-22 07:08:26", null))
        assertEquals("2026-10-22", iso.date)
        assertEquals("07:08:26", iso.clock)
        assertEquals(60L, assertNotNull(stampOf("2026-10-22 07:09:26", null)).seconds - iso.seconds)
        assertEquals("2026-10-22", assertNotNull(stampOf("oct/22/2026 07:08:26", null)).date)
        assertEquals("2026-10-22", assertNotNull(stampOf("oct/22 07:08:26", "2026-10-27")).date)
        assertEquals("2026-10-22", assertNotNull(stampOf("Oct 22 07:08:26", "2026-10-27")).date)
        assertEquals("2026-01-01", assertNotNull(stampOf("Jan  1 00:00:04", "2026-10-27")).date)
        assertEquals("2026-10-27", assertNotNull(stampOf("12:01:23", "2026-10-27")).date)
        assertEquals("2026-10-23", assertNotNull(stampOf("Fri Oct 23 17:41:46 2026", null)).date)
        assertEquals("2026-10-22", assertNotNull(stampOf("2026-10-22T07:08:26+03:00", null)).date)
    }

    @Test
    fun aTimeWithNoDayNeedsTheDateOfTheRouterAndATimeWithNoMomentHasNoStamp() {
        assertNull(stampOf("12:01:23", null))
        assertNull(stampOf("oct/22 07:08:26", null))
        assertNull(stampOf("12.345678", "2026-10-27"))
        assertNull(stampOf("", "2026-10-27"))
        assertNull(stampOf("feb/31/2026 25:00:00", null))
    }

    @Test
    fun aLineOfDecemberReadInJanuaryBelongsToTheYearBefore() {
        assertEquals("2026-12-30", assertNotNull(stampOf("dec/30 23:59:59", "2027-01-02")).date)
        assertEquals("2027-01-02", assertNotNull(stampOf("jan/02 00:00:01", "2027-01-02")).date)
    }

    @Test
    fun theDaysCountedFromTheEpochAreRight() {
        assertEquals(0L, assertNotNull(stampOf("1970-01-01 00:00:00", null)).seconds)
        assertEquals(11_017L * 86_400, assertNotNull(stampOf("2000-03-01 00:00:00", null)).seconds)
        assertEquals(86_400L, assertNotNull(stampOf("2024-03-01 00:00:00", null)).seconds - assertNotNull(stampOf("2024-02-29 00:00:00", null)).seconds)
    }

    @Test
    fun aFilterKeepsTheLinesThatPassEveryRuleItHas() {
        assertEquals(listOf("*3", "*4"), lines.filtered(LogFilter(severities = setOf(Severity.Critical))).map { it.id })
        assertEquals(listOf("*3", "*4", "*6"), lines.filtered(LogFilter(severities = setOf(Severity.Critical, Severity.Warning))).map { it.id })
        assertEquals(listOf("*1", "*3", "*4"), lines.filtered(LogFilter(topics = setOf("SYSTEM"))).map { it.id })
        assertEquals(listOf("*2", "*5", "*6"), lines.filtered(LogFilter(hidden = setOf("system"))).map { it.id })
        assertEquals(listOf("*3", "*4"), lines.filtered(LogFilter(query = "login ssh")).map { it.id })
        assertEquals(listOf("*1"), lines.filtered(LogFilter(query = "WINBOX")).map { it.id })
        assertEquals(listOf("*6"), lines.filtered(LogFilter(query = "dhcp 192.0.2")).map { it.id })
        assertEquals(listOf("*3", "*4"), lines.filtered(LogFilter(message = "login failure for user root via ssh")).map { it.id })
        assertEquals(lines.size, lines.filtered(LogFilter()).size)
    }

    @Test
    fun aSpanCountsBackFromTheMomentOfTheRouter() {
        val now = assertNotNull(stampOf("2026-10-27 09:32:00", null)).seconds
        assertEquals(listOf("*6"), lines.filtered(LogFilter(span = Span.Minutes5, now = now)).map { it.id })
        assertEquals(listOf("*6"), lines.filtered(LogFilter(span = Span.Minutes15, now = now)).map { it.id })
        assertEquals(lines.size, lines.filtered(LogFilter(span = Span.Hour, now = now)).size)
        val tomorrow = assertNotNull(stampOf("2026-10-28 00:10:00", null)).seconds
        assertEquals(emptyList(), lines.filtered(LogFilter(span = Span.Today, now = tomorrow)))
        assertEquals(lines.size, lines.filtered(LogFilter(span = Span.Today, now = now)).size)
        assertEquals(lines.size, lines.filtered(LogFilter(span = Span.Minutes5)).size)
    }

    @Test
    fun aLineWithNoStampIsNotCutOffByASpan() {
        val kernel = entry("k", "12.345678", "kern.info", "usb 1-1: new device")
        assertNull(kernel.stamp)
        val now = assertNotNull(stampOf("2026-10-27 09:32:00", null)).seconds
        assertEquals(listOf("k"), listOf(kernel).filtered(LogFilter(span = Span.Minutes5, now = now)).map { it.id })
    }

    @Test
    fun linesThatFollowEachOtherAndSayTheSameBecomeOneRow() {
        val rows = lines.collapsed()
        assertEquals(listOf(1, 1, 2, 1, 1), rows.map { it.count })
        assertEquals("*3", rows[2].key)
        assertEquals("*4", rows[2].last.id)
        val apart = listOf(lines[2], lines[0], lines[3]).collapsed()
        assertEquals(listOf(1, 1, 1), apart.map { it.count })
        assertTrue(emptyList<LogEntry>().collapsed().isEmpty())
    }

    @Test
    fun linesCopyAsTheConsolePrintsThem() {
        assertEquals(
            "2026-10-27 09:00:00 system,info,account user admin logged in from 192.0.2.5 via winbox\n" +
                "2026-10-27 09:00:05 firewall,info input: in:ether1 out:(unknown 0), proto TCP",
            lines.take(2).asLines(),
        )
        assertEquals("login failure for user root via ssh\nap: 00:00:5E:00:53:01 connected", listOf(lines[2], lines[4]).asMessages())
        assertEquals("12.3 kernel text", entry("k", "12.3", "", "kernel text").line)
    }

    @Test
    fun csvQuotesTheCellsThatNeedIt() {
        val csv = listOf(lines[1], entry("q", "2026-10-27 09:40:00", "system,info", "said \"hi\"\nthen left")).asCsv()
        assertEquals(
            "time,topics,message\r\n" +
                "2026-10-27 09:00:05,\"firewall,info\",\"input: in:ether1 out:(unknown 0), proto TCP\"\r\n" +
                "2026-10-27 09:40:00,\"system,info\",\"said \"\"hi\"\"\nthen left\"",
            csv,
        )
    }

    @Test
    fun jsonHasTheTopicsAsAList() {
        val json = listOf(lines[0]).asJson()
        assertTrue("\"time\": \"2026-10-27 09:00:00\"" in json, json)
        assertTrue("\"system\"" in json && "\"account\"" in json, json)
        assertTrue("\"message\": \"user admin logged in from 192.0.2.5 via winbox\"" in json, json)
    }

    @Test
    fun topicsAreCountedWithoutTheWordsOfSeverity() {
        assertEquals(
            listOf("system" to 3, "account" to 1, "dhcp" to 1, "firewall" to 1, "wireless" to 1),
            lines.topicCounts(),
        )
        assertEquals(mapOf(Severity.Info to 2, Severity.Critical to 2, Severity.Debug to 1, Severity.Warning to 1), lines.severityCounts())
    }

    @Test
    fun howLongAgoIsToldInTheLargestUnit() {
        assertEquals(AgoUnit.Now, ago(-30).unit)
        assertEquals(AgoUnit.Now, ago(4).unit)
        assertEquals(AgoUnit.Seconds to 42L, ago(42).let { it.unit to it.count })
        assertEquals(AgoUnit.Minutes to 5L, ago(5 * 60 + 59).let { it.unit to it.count })
        assertEquals(AgoUnit.Hours to 3L, ago(3 * 3_600 + 10).let { it.unit to it.count })
        assertEquals(AgoUnit.Days to 2L, ago(2 * 86_400 + 5).let { it.unit to it.count })
    }

    @Test
    fun theFieldsOfALineCopyOnePerLineAfterItsId() {
        assertEquals(".id: *3\ntime: 2026-10-27 09:01:10\ntopics: system,error,critical\nmessage: login failure for user root via ssh", lines[2].fields)
        assertEquals(".id: *9", LogEntry("*9", "", "", "", null).fields)
    }

    @Test
    fun theLinesAroundOneAreFiveToEachSideAndNoneForALineThatIsNotAmongThem() {
        val long = List(20) { entry("*$it", "2026-10-27 09:00:${it.toString().padStart(2, '0')}", "system,info", "line $it") }
        assertEquals((5..15).map { "*$it" }, long.around(long[10]).map { it.id })
        assertEquals((0..7).map { "*$it" }, long.around(long[2]).map { it.id })
        assertEquals((14..19).map { "*$it" }, long.around(long[19]).map { it.id })
        assertEquals(listOf("*9", "*10", "*11"), long.around(long[10], reach = 1).map { it.id })
        assertTrue(long.around(entry("*gone", "2026-10-27 09:00:00", "system,info", "dropped")).isEmpty())
    }

    @Test
    fun aWindowIsTitledByTheTimeAndTheFirstWordsOfTheMessage() {
        val clock = RouterClock("2026-10-27", 0)
        fun titled(line: LogEntry) = LogEntryWindow(MenuPath.parse("log"), listOf(LogRow(listOf(line))), listOf(line), 0, clock).title
        val window = LogEntryWindow(MenuPath.parse("log"), lines.collapsed(), lines, 2, clock)
        assertEquals("09:01:11 login failure for user root via ssh", window.title)
        window.index = 0
        assertEquals("09:00:00 user admin logged in from 192.0.2.5 via winbox", window.title)
        assertEquals("09:02:00 The quick brown fox jumps over the lazy dog and…", titled(entry("l", "2026-10-27 09:02:00", "system,info", "The quick brown fox jumps over the lazy dog and keeps running far away")))
        assertEquals("09:02:00 " + "x".repeat(48) + "…", titled(entry("x", "2026-10-27 09:02:00", "system,info", "x".repeat(60))))
        assertEquals("12.345678 usb 1-1: new device", titled(entry("k", "12.345678", "kern.info", "usb 1-1: new device")))
        assertEquals("09:03:00", titled(entry("s", "2026-10-27 09:03:00", "system,info", "")))
    }
}
