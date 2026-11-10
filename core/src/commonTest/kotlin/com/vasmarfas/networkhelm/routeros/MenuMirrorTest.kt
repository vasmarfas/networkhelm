package com.vasmarfas.networkhelm.routeros

import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.packs.MIRROR_AGE
import com.vasmarfas.networkhelm.packs.MIRROR_SINCE
import com.vasmarfas.networkhelm.packs.MIRROR_STAMP
import com.vasmarfas.networkhelm.packs.MIRROR_TIME
import com.vasmarfas.networkhelm.packs.Pack
import com.vasmarfas.networkhelm.packs.PackNative
import com.vasmarfas.networkhelm.packs.PackNativeField
import com.vasmarfas.networkhelm.packs.PackNativeRef
import com.vasmarfas.networkhelm.packs.PackPath
import com.vasmarfas.networkhelm.packs.PackTab
import com.vasmarfas.networkhelm.packs.PackWindow
import com.vasmarfas.networkhelm.session.NativeObjects
import com.vasmarfas.networkhelm.session.NativeSide
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MenuMirrorTest {
    private val peers = PackNative(
        listOf(20, 110),
        listOf(
            PackNativeField("name", 0x3E9, "string", letter = "s"),
            PackNativeField("interface", 0x3EF, "number", letter = "u", refers = PackNativeRef(listOf(20, 0), listOf(0x10006))),
            PackNativeField("persistent-keepalive", 0x3F2, MIRROR_TIME, letter = "u"),
            PackNativeField("rx", 0x3F3, "number", letter = "q"),
            PackNativeField("endpoint-port", 0x3F6, "number", letter = "u"),
            PackNativeField("responder", 0x3FA, "bool", letter = "b"),
            PackNativeField("mode", 0x3FB, "enm", letter = "u", names = mapOf(1L to "Recommended", 2L to "RSA")),
            PackNativeField("disabled", 0xFE000A, "bool", letter = "b"),
        ),
        whole = true,
    )
    private val mirror = MenuMirror(peers, classic = false)

    private fun row(id: String, vararg values: Pair<String, String>) = mapOf(".id" to id) + values

    @Test
    fun aTimeIsWrittenAsTheConsoleOfItsDialectWritesIt() {
        val timed = PackNative(
            listOf(1),
            listOf(
                PackNativeField("timeout", 1, MIRROR_TIME), PackNativeField("rtt", 2, MIRROR_TIME, scale = 1000), PackNativeField("seen", 3, MIRROR_STAMP),
                PackNativeField("expires-after", 4, MIRROR_AGE), PackNativeField("last-seen", 5, MIRROR_AGE, names = mapOf(4294967295L to "never")),
                PackNativeField("last-link-up-time", 6, MIRROR_SINCE, scale = 100), PackNativeField("to-ports", 7, "numberrange"), PackNativeField("dst-port", 8, "multinumberrange"),
            ),
        )
        val serialized = MenuMirror(timed, classic = false)
        assertEquals(row("*1", "timeout" to "00:00:00", "rtt" to "00:00:01", "seen" to "2026-11-09 16:25:50"), serialized.row(Item("*1", mapOf("timeout" to "0", "rtt" to "1500", "seen" to "1794241550"))))
        assertEquals("23:59:59", serialized.row(Item("*1", mapOf("timeout" to "86399")))["timeout"])
        assertEquals("1d2h", serialized.row(Item("*1", mapOf("timeout" to "93600")))["timeout"])
        assertEquals("", serialized.row(Item("*1", mapOf("seen" to "4294967295")))["seen"])
        // a moment on the clock of the uptime is the time left until it or the time since it, a moment of a date the date it came at
        val time = RouterTime(250_883, 1_794_341_227)
        assertEquals("14:18:43", serialized.row(Item("*1", mapOf("expires-after" to "302406")), time)["expires-after"])
        assertEquals("00:00:33", serialized.row(Item("*1", mapOf("last-seen" to "250850")), time)["last-seen"])
        assertEquals("never", serialized.row(Item("*1", mapOf("last-seen" to "never")), time)["last-seen"])
        assertEquals("", serialized.row(Item("*1", mapOf("expires-after" to "4294967295")), time)["expires-after"])
        assertEquals("2026-11-07 22:25:50", serialized.row(Item("*1", mapOf("last-link-up-time" to "631")), time)["last-link-up-time"])
        assertTrue(serialized.ages && serialized.dates)
        // the console writes a range of numbers with a dash and a list with commas alone
        assertEquals("8000-8080", serialized.row(Item("*1", mapOf("to-ports" to "8000..8080")))["to-ports"])
        assertEquals("80,443,8000-8080", serialized.row(Item("*1", mapOf("dst-port" to "80, 443, 8000..8080")))["dst-port"])
        val classic = MenuMirror(timed, classic = true)
        assertEquals("0s", classic.row(Item("*1", mapOf("timeout" to "0")))["timeout"])
        assertEquals("25s", classic.row(Item("*1", mapOf("timeout" to "25")))["timeout"])
    }

    @Test
    fun aReferenceToNothingIsEmptyOneToAnElementThatIsGoneIsItsIdAndAMaskIsItsBits() {
        assertEquals("*9A", mirror.row(Item("*F", mapOf("interface" to "154")))["interface"])
        assertEquals("", mirror.row(Item("*F", mapOf("interface" to "4294967295")))["interface"])
        assertEquals("wg1", mirror.row(Item("*F", mapOf("interface" to "wg1")))["interface"])
        val other = MenuMirror(PackNative(listOf(1), listOf(PackNativeField("allowed-address", 0x3F0, "multi"), PackNativeField("netmask", 9, "netmask"))), classic = false)
        assertEquals("10.0.0.2/32,10.0.1.0/24", other.row(Item("*1", mapOf("allowed-address" to "10.0.0.2/32, 10.0.1.0/24")))["allowed-address"])
        assertEquals("24", other.row(Item("*1", mapOf("netmask" to "255.255.255.0")))["netmask"])
        assertEquals("255.0.255.0", other.row(Item("*1", mapOf("netmask" to "255.0.255.0")))["netmask"])
    }

    @Test
    fun theConsoleAgreesWhenItPrintsTheValuesBothReadsHoldAndMovingOnesNearThem() {
        val before = listOf(
            row("*1", "name" to "a", "rx" to "10", "responder" to "false", "disabled" to "false", "persistent-keepalive" to "00:01:00"),
            row("*2", "name" to "b", "rx" to "0", "disabled" to "true"),
        )
        val after = listOf(
            row("*1", "name" to "a", "rx" to "15", "responder" to "false", "disabled" to "false", "persistent-keepalive" to "00:01:05"),
            row("*2", "name" to "b", "rx" to "0", "disabled" to "true"),
        )
        // the console leaves out a field it was never given, prints a default the window does not send, and prints fields the window does not have
        val console = listOf(
            row("*1", "name" to "a", "rx" to "12", "private-key" to "x", "persistent-keepalive" to "00:01:02"),
            row("*2", "name" to "b", "rx" to "0", "disabled" to "true", "endpoint-port" to "0"),
        )
        val check = assertIs<Check.Agrees>(mirror.check(before, console, after))
        assertEquals(mapOf("endpoint-port" to "0"), check.agreement.fills)
        assertTrue("disabled=true" in check.agreement.tokens && "rx:number" in check.agreement.tokens && "endpoint-port:number" in check.agreement.tokens)
        assertEquals("0", mirror.shown(row("*2", "name" to "b"), check.agreement)["endpoint-port"])
        val far = console.mapIndexed { at, printed -> if (at == 0) printed + ("persistent-keepalive" to "00:09:00") else printed }
        assertIs<Check.Differs>(mirror.check(before, far, after))
    }

    @Test
    fun aDateWorkedOutFromTheUptimeMayStandASecondOffAndNothingMayBeAWord() {
        val dated = MenuMirror(PackNative(listOf(1), listOf(PackNativeField("last-link-up-time", 6, MIRROR_SINCE, scale = 100), PackNativeField("last-seen", 5, MIRROR_AGE))), classic = false)
        val before = listOf(row("*1", "last-link-up-time" to "2026-11-07 22:25:50", "last-seen" to ""))
        val agreed = assertIs<Check.Agrees>(dated.check(before, listOf(row("*1", "last-link-up-time" to "2026-11-07 22:25:51", "last-seen" to "never")), before))
        assertEquals(mapOf("" to "never"), agreed.agreement.words["last-seen"])
        assertIs<Check.Differs>(dated.check(before, listOf(row("*1", "last-link-up-time" to "2026-11-07 22:26:30")), before))
        // the console spells the time of Last Seen where the window gives a clock
        val seen = listOf(row("*2", "last-seen" to "00:09:46"))
        val spelled = assertIs<Check.Agrees>(dated.check(seen, listOf(row("*2", "last-seen" to "9m46s")), seen)).agreement
        assertEquals(setOf("last-seen"), spelled.spelled)
        assertEquals("9m46s", dated.shown(seen.single(), spelled)["last-seen"])
        assertIs<Check.Differs>(dated.check(seen, listOf(row("*2", "last-seen" to "19m46s")), seen))
        // or gives it as a number of seconds
        val counted = assertIs<Check.Agrees>(dated.check(seen, listOf(row("*2", "last-seen" to "586")), seen)).agreement
        assertEquals(setOf("last-seen"), counted.counted)
        assertEquals("586", dated.shown(seen.single(), counted)["last-seen"])
    }

    @Test
    fun theConsoleLeavesOutADefaultAndNamesAValueItsOwnWay() {
        val before = listOf(row("*1", "name" to "a", "mode" to "Recommended"), row("*2", "name" to "b", "mode" to "RSA"))
        val console = listOf(row("*1", "name" to "a"), row("*2", "name" to "b", "mode" to "rsa"))
        val agreement = assertIs<Check.Agrees>(mirror.check(before, console, before)).agreement
        assertEquals(mapOf("mode" to "Recommended"), agreement.drops)
        assertEquals(mapOf("RSA" to "rsa"), agreement.words["mode"])
        assertEquals(console, before.map { mirror.shown(it, agreement) })
        // the same value of the window is not printed two ways, and a name is not taken for another one
        assertIs<Check.Differs>(mirror.check(before, listOf(row("*1", "name" to "a"), row("*2", "name" to "b", "mode" to "ecdsa")), before, agreement))
        assertIs<Check.Differs>(mirror.check(before, listOf(row("*1", "name" to "A"), row("*2", "name" to "b", "mode" to "rsa")), before))
    }

    @Test
    fun theConsoleDisagreesOnAValueARowItHidesARowTheWindowLacksOrAnotherOrder() {
        val before = listOf(row("*1", "name" to "a"), row("*2", "name" to "b"))
        assertIs<Check.Differs>(mirror.check(before, listOf(row("*1", "name" to "A"), row("*2", "name" to "b")), before))
        assertIs<Check.Differs>(mirror.check(before, listOf(row("*1", "name" to "a")), before))
        assertIs<Check.Differs>(mirror.check(before, before + row("*3", "name" to "c"), before))
        assertIs<Check.Agrees>(mirror.check(before, before.reversed(), before))
        assertIs<Check.Differs>(MenuMirror(peers.copy(move = true), classic = false).check(before, before.reversed(), before))
        assertIs<Check.Differs>(mirror.check(before, listOf(row("*1", "name" to "a", "endpoint-port" to "51820"), row("*2", "name" to "b")), before))
        assertIs<Check.Differs>(mirror.check(before, listOf(row("*1", "name" to "a", "endpoint-address" to "192.0.2.1"), row("*2", "name" to "b")), before, shown = setOf("endpoint-address")))
        // rows that came or went between the reads are not compared, and with none left nothing is learned
        assertIs<Check.Agrees>(mirror.check(before, listOf(row("*2", "name" to "b")), listOf(row("*2", "name" to "b"))))
        assertIs<Check.Unknown>(mirror.check(listOf(row("*1", "name" to "a")), listOf(row("*2", "name" to "b")), listOf(row("*2", "name" to "b"))))
    }

    @Test
    fun theOneRecordOfAWindowOfSettingsIsComparedWithoutAnId() {
        val dns = PackNative(listOf(14, 0), listOf(PackNativeField("allow-remote-requests", 1, "bool", letter = "b"), PackNativeField("cache-size", 2, "number", letter = "u")), single = true, whole = true)
        val settings = MenuMirror(dns, classic = false)
        val native = listOf(settings.row(Item("#0", mapOf("allow-remote-requests" to "true", "cache-size" to "2048"))))
        assertEquals("", native.single()[".id"])
        assertIs<Check.Agrees>(settings.check(native, listOf(mapOf("allow-remote-requests" to "true", "cache-size" to "2048", "servers" to "9.9.9.9")), native))
        assertIs<Check.Differs>(settings.check(native, listOf(mapOf("allow-remote-requests" to "false", "cache-size" to "2048")), native))
    }

    @Test
    fun theSampleTakesAFewRowsForEachValueNotSeenYet() {
        val rows = (1..20).map { row("*$it", "name" to "p$it", "disabled" to if (it > 10) "true" else "false") }
        val known = Agreement(mirror.tokens(rows.first(), Agreement()))
        assertEquals(listOf("*11", "*12", "*13", "*14", "*15"), mirror.sample(rows, known))
    }

    private class Window(var rows: List<Item>) : NativeObjects {
        override suspend fun read(spec: PackNative, limit: Int?): List<Item> = rows

        override fun close() = Unit
    }

    private fun mirrors(window: NativeObjects, kept: MutableMap<String, List<Map<String, String>>>, spec: PackNative = peers): MenuMirrors {
        val windows = NativeWindows(NativeSide { window }, { 8291 }, 1.seconds)
        windows.keep(listOf(Pack(menu = listOf("WireGuard"), window = PackWindow("Peers", listOf(PackTab("Peers", PEERS.toString()))), paths = mapOf(PEERS.toString() to PackPath(mirror = spec)))))
        return MenuMirrors(windows, classic = false, { kept[it] }) { key, rows -> kept[key] = rows }
    }

    @Test
    fun aMenuIsReadFromTheConsoleUntilTheConsoleAgreedAndThenFromItsWindow() = runTest {
        val window = Window(listOf(Item("*1", mapOf("name" to "a", "disabled" to "false", "mode" to "RSA"))))
        val kept = HashMap<String, List<Map<String, String>>>()
        var printed = 0
        val console: suspend () -> List<Map<String, String>> = { printed++; listOf(row("*1", "name" to "a", "mode" to "rsa")) }
        val first = mirrors(window, kept)
        assertEquals(listOf(row("*1", "name" to "a", "mode" to "rsa")), first.rows(PEERS, null, null, console) { null })
        assertEquals(1, printed)
        assertEquals(listOf(row("*1", "name" to "a", "disabled" to "false", "mode" to "rsa")), first.rows(PEERS, null, null, console) { null })
        assertEquals(1, printed)
        // the agreement holds for the next session of the same build
        val second = mirrors(window, kept)
        assertEquals(listOf(row("*1", "name" to "a", "disabled" to "false", "mode" to "rsa")), second.rows(PEERS, Filter.Equals(".id", "*1"), null, null) { null })
        assertEquals(1, printed)
        assertEquals(listOf(row("*1", "name" to "a")), second.rows(PEERS, null, listOf("name"), null) { null })
    }

    @Test
    fun aRowWithAValueNotSeenYetIsPrintedOnceAndADisagreementLeavesTheMenuToTheConsole() = runTest {
        val window = Window(listOf(Item("*1", mapOf("name" to "a", "disabled" to "false"))))
        val kept = HashMap<String, List<Map<String, String>>>()
        val mirrors = mirrors(window, kept)
        mirrors.rows(PEERS, null, null, { listOf(row("*1", "name" to "a")) }) { null }
        window.rows = window.rows + Item("*2", mapOf("name" to "b", "disabled" to "true"))
        val asked = ArrayList<List<String>>()
        assertEquals(2, mirrors.rows(PEERS, null, null, null) { ids -> asked += ids; listOf(row("*2", "name" to "b", "disabled" to "true")) }?.size)
        assertEquals(listOf(listOf("*2")), asked)
        assertEquals(2, mirrors.rows(PEERS, null, null, null) { ids -> asked += ids; null }?.size)
        assertEquals(1, asked.size)
        window.rows = window.rows + Item("*3", mapOf("name" to "c", "responder" to "true"))
        assertNull(mirrors.rows(PEERS, null, null, null) { listOf(row("*3", "name" to "c", "responder" to "false")) })
        assertNull(mirrors.rows(PEERS, null, null, { listOf(row("*1", "name" to "a")) }) { null })
    }

    @Test
    fun aWindowOfSettingsWithoutAValueItsMenuShowsForReadingLeavesTheMenuToTheConsole() = runTest {
        val update = MenuPath.parse("system/package/update")
        val spec = PackNative(listOf(24, 24), listOf(PackNativeField("installed-version", 1, "string")), single = true, whole = true)
        val windows = NativeWindows(NativeSide { Window(listOf(Item("#0", mapOf("installed-version" to "7.26beta1")))) }, { 8291 }, 1.seconds)
        windows.keep(listOf(Pack(menu = listOf("System"), window = PackWindow("Check For Updates", listOf(PackTab("Check For Updates", update.toString()))), paths = mapOf(update.toString() to PackPath(readonly = listOf("installed-version", "status"), mirror = spec)))))
        val mirrors = MenuMirrors(windows, classic = false, { null }) { _, _ -> }
        val console = listOf(mapOf("installed-version" to "7.26beta1", "status" to "New version is available"))
        assertEquals(console, mirrors.rows(update, null, null, { console }) { null })
        assertNull(mirrors.rows(update, null, null, { console }) { null })
    }

    @Test
    fun aMirrorThatLacksFieldsOfTheFormLeavesTheWindowOfARowToTheConsole() = runTest {
        val kept = HashMap<String, List<Map<String, String>>>()
        val mirrors = mirrors(Window(listOf(Item("*1", mapOf("name" to "a")))), kept, peers.copy(partial = true))
        assertEquals(1, mirrors.rows(PEERS, null, null, { listOf(row("*1", "name" to "a")) }) { null }?.size)
        assertNull(mirrors.rows(PEERS, Filter.Equals(".id", "*1"), null, null) { null })
        assertEquals(listOf(row("*1")), mirrors.rows(PEERS, Filter.Equals("name", "a"), emptyList(), null) { null })
    }

    @Test
    fun theUptimeOfTheRouterIsReadOnceForReadsCloseTogether() = runTest {
        var uptimes = 0
        val window = object : NativeObjects {
            override suspend fun read(spec: PackNative, limit: Int?): List<Item> =
                if (spec.handler == UPTIME.handler) listOf(Item("#0", mapOf("uptime" to "1000"))).also { uptimes++ } else listOf(Item("*1", mapOf("name" to "a", "expires-after" to "1600")))

            override fun close() = Unit
        }
        val mirrors = mirrors(window, HashMap(), peers.copy(fields = peers.fields + PackNativeField("expires-after", 0x3FC, MIRROR_AGE, letter = "u")))
        assertEquals("00:10:00", mirrors.rows(PEERS, null, null, { listOf(row("*1", "name" to "a", "expires-after" to "00:10:00")) }) { null }?.single()?.get("expires-after"))
        assertEquals("00:10:00", mirrors.rows(PEERS, null, null, null) { null }?.single()?.get("expires-after"))
        assertEquals(1, uptimes)
    }

    @Test
    fun theAgreementsOfABuildReadBackFromTheirFileAndABrokenFileHoldsNone() {
        val agreements = mapOf("mirror:interface/wireguard/peers" to listOf(mapOf("mark" to "1", "tokens" to "name\ndisabled=false")))
        assertEquals(agreements, decodeAgreements(encodeAgreements(agreements)))
        assertEquals(emptyMap(), decodeAgreements("{\"mirror:"))
        assertEquals(emptyMap(), decodeAgreements(null))
    }

    private companion object {
        val PEERS = MenuPath.parse("interface/wireguard/peers")
    }
}
