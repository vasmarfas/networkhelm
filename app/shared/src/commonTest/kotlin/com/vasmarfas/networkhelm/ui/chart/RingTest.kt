package com.vasmarfas.networkhelm.ui.chart

import kotlin.test.Test
import kotlin.test.assertEquals

class RingTest {

    @Test
    fun aFullRingDropsItsOldestValues() {
        val ring = Ring(3)
        for (value in 1..5) ring.add(value.toDouble())
        assertEquals(3, ring.size)
        assertEquals(listOf(3.0, 4.0, 5.0), (0 until ring.size).map(ring::get))
        assertEquals(5.0, ring.last())
        assertEquals(5.0, ring.max())
        assertEquals(4.0, ring.average())
    }

    @Test
    fun anEmptyRingAveragesToZero() {
        assertEquals(0.0, Ring(3).average())
    }

    @Test
    fun ratesReadLikeRouterOsWritesThem() {
        assertEquals("0 bps", bits(0.0))
        assertEquals("999 bps", bits(999.0))
        assertEquals("1.2 kbps", bits(1234.0))
        assertEquals("152 kbps", bits(151880.0))
        assertEquals("2.7 kbps", bits(2688.0))
        assertEquals("2.5 Gbps", bits(2.5e9))
    }

    @Test
    fun ratesOfATableAreCutToOneDecimalAndChangeTheUnitAtTwoThousand() {
        assertEquals(
            listOf("0 bps", "1600 bps", "1999 bps", "2.0 kbps", "3.4 kbps", "22.0 kbps", "175.0 kbps", "176.9 kbps", "588.1 kbps", "1983.8 kbps", "2.1 Mbps", "4.0 Mbps", "2.5 Gbps"),
            listOf(0.0, 1600.0, 1999.0, 2000.0, 3456.0, 22_080.0, 175_088.0, 176_968.0, 588_160.0, 1_983_800.0, 2_152_824.0, 4_000_000.0, 2.5e9).map(::bitRate),
        )
        assertEquals("151.8 kbps / 0 bps", display("tx-bits-per-second", "151880/0"))
    }

    @Test
    fun countersOfPacketsAreGroupedByThousands() {
        assertEquals(
            listOf("0", "947", "1 025", "6 810", "11 306", "1 234 567"),
            listOf("0", "947", "1025", "6810", "11306", "1234567").map { display("tx-packets-per-second", it) },
        )
        assertEquals("3 500", display("packets", "3500"))
        assertEquals("12 345 / 7", display("queued-packets", "12345/7"))
        assertEquals("1500", display("actual-mtu", "1500"))
    }

    @Test
    fun sizesStandWholeUpToFiveKibAndThenHaveOneDecimal() {
        assertEquals(listOf("999 B", "1024 B", "4095 B", "5119 B"), listOf(999.0, 1024.0, 4095.0, 5119.0).map(::byteCount))
        assertEquals(
            listOf("5.0 KiB", "5.1 KiB", "5.4 KiB", "6.8 KiB", "8.0 KiB", "9.8 KiB", "10.0 KiB", "58.6 KiB", "738.9 KiB"),
            listOf(5120.0, 5200.0, 5500.0, 7000.0, 8191.0, 10000.0, 10239.0, 60000.0, 756586.0).map(::byteCount),
        )
        assertEquals(listOf("3906.3 KiB", "5.0 MiB", "512.0 MiB", "3072.0 MiB", "6.0 GiB"), listOf(4_000_000.0, 5_242_880.0, 536_870_912.0, 3_221_225_472.0, 6_442_450_944.0).map(::byteCount))
    }

    @Test
    fun aDateOfTheConsoleIsWrittenTheWayWinBoxWritesIt() {
        assertEquals("2026-07-25", display("start-date", "jul/25/2026"))
        assertEquals("2026-11-03 22:36:19", display("creation-time", "nov/03/2026 22:36:19"))
        assertEquals("2026-11-03 22:36:19", display("creation-time", "2026-11-03 22:36:19"))
        assertEquals("jun/xx/2026", display("name", "jun/xx/2026"))
        assertEquals("abc/25/2026", display("name", "abc/25/2026"))
        assertEquals("jul/25", display("time", "jul/25"))
    }

    @Test
    fun theGigabyteStepComesAtFiveGibLikeInTheBytesOfAFirewallRule() {
        assertEquals(
            listOf("4396.3 MiB", "4901.9 MiB", "5116.4 MiB", "5.0 GiB", "5.0 GiB", "5.1 GiB"),
            listOf(4_609_824_500.0, 5_139_981_500.0, 5_364_974_000.0, 5_371_982_500.0, 5_368_709_120.0, 5_499_969_500.0).map(::byteCount),
        )
        assertEquals(listOf("5120.0 GiB", "5.0 TiB"), listOf(5_497_558_138_879.0, 5_497_558_138_880.0).map(::byteCount))
    }

    @Test
    fun theStatusBarCountsInKibAndChangesTheUnitAtFiveThousandOfThem() {
        assertEquals(
            listOf("0 KiB", "4999 KiB", "4.9 MiB", "291.3 MiB", "512.0 MiB", "4.8 GiB"),
            listOf(0.0, 5_118_976.0, 5_120_000.0, 305_437_696.0, 536_870_912.0, 5_120_000_000.0).map(::kibiCount),
        )
    }

    @Test
    fun theTopOfAScaleIsRound() {
        assertEquals(listOf(1.0, 1.0, 5000.0, 10000.0, 2.5e8, 2000.0), listOf(0.0, 0.7, 4600.0, 5001.0, 2.3e8, 1500.0).map(::roundTop))
    }

    @Test
    fun anAxisOfSmallRatesWritesEveryStepApart() {
        assertEquals(listOf("1 bps", "2 bps", "3 bps", "4 bps", "5 bps"), (1..5).map { bits(axisTop(0.7) * it / 5) })
        assertEquals(listOf("1 bps", "2 bps", "3 bps", "4 bps", "5 bps"), (1..5).map { bits(axisTop(2.0) * it / 5) })
        assertEquals(listOf(5.0, 5000.0, 2.5e8), listOf(0.0, 4600.0, 2.3e8).map(::axisTop))
    }

    @Test
    fun limitsAndPairsReadAsTheyAreTyped() {
        assertEquals(listOf("0", "999", "1500k", "10M", "2G"), listOf(0L, 999L, 1_500_000L, 10_000_000L, 2_000_000_000L).map(::limit))
        assertEquals("10M / 20M", halves("10000000/20000000") { it.toLongOrNull()?.let(::limit) })
        assertEquals("unlimited/10M", halves("unlimited/10M") { it.toLongOrNull()?.let(::limit) })
        assertEquals("1234 B / 0 B", display("queued-bytes", "1234/0"))
        assertEquals("34.6 MiB", display("bytes", "36280000"))
        assertEquals("0-1000", display("connection-bytes", "0-1000"))
    }

    @Test
    fun resourcesReadInTheirUnits() {
        assertEquals("270.2 MiB", display("free-memory", "283328512"))
        assertEquals("958.1 MiB", display("free-hdd-space", "1004609536"))
        assertEquals("3417 MHz", display("cpu-frequency", "3417"))
        assertEquals("1 %", display("cpu-load", "1"))
        assertEquals("12%", display("cpu-load", "12%"))
    }
}
