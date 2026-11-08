package com.vasmarfas.networkhelm.miknative

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.routeros.RouterOsSession
import com.vasmarfas.networkhelm.routeros.api.ApiEncoding
import com.vasmarfas.networkhelm.routeros.console.connectRouterOsConsole
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class ConsoleClientLabTest {
    private val list = MenuPath.parse("ip/firewall/address-list")

    private fun session(prefix: String = "chr", encoding: ApiEncoding = ApiEncoding.Utf8, block: suspend CoroutineScope.(RouterOsSession) -> Unit) = runBlocking {
        val lab = Lab.require("$prefix.host", "$prefix.user", "$prefix.password")
        withTimeout(120.seconds) {
            val ssh = connectNative(lab.getValue("$prefix.host"), lab.getValue("$prefix.user"), lab.getValue("$prefix.password"), this)
            val session = connectRouterOsConsole(ssh, lab.getValue("$prefix.user"), this, encoding = encoding) ?: error("the console speaks no dialect of the client")
            try {
                clean(session)
                block(session)
            } finally {
                try {
                    clean(session)
                } finally {
                    session.close()
                }
            }
        }
    }

    private suspend fun clean(session: RouterOsSession) {
        val ids = session.watch(list, Filter.Equals("list", "rd-test-console")).filterIsInstance<ListEvent.Snapshot>().first().items.map { it.id }
        if (ids.isNotEmpty()) session.remove(list, ids)
    }

    private suspend fun ShellChannel.readToPrompt(): String {
        val seen = StringBuilder()
        val buffer = ByteArray(4096)
        while ("] > " !in seen) {
            val size = read(buffer, 0, buffer.size)
            if (size < 0) break
            seen.append(buffer.decodeToString(0, size))
        }
        return seen.toString()
    }

    private suspend fun ShellChannel.readQuiet(): String {
        val seen = StringBuilder()
        val buffer = ByteArray(4096)
        while (true) {
            val size = withTimeoutOrNull(700) { read(buffer, 0, buffer.size) } ?: break
            if (size < 0) break
            seen.append(buffer.decodeToString(0, size))
        }
        return seen.toString()
    }

    @Test
    fun theRouterIsRecognized() = session { session ->
        assertEquals(Platform.RouterOs, session.device.platform)
        assertTrue(session.device.version.startsWith("7."), session.device.version)
        assertTrue(session.device.identity.isNotEmpty())
        assertTrue(session.device.packages.isNotEmpty(), session.device.packages.toString())
        assertTrue(session.capabilities.terminal && session.capabilities.files)
    }

    @Test
    fun aTableOverOnePortionComesWithItsProgressAndGoesInLinesThatFit() = session { session ->
        val script = ":for i from=0 to=2499 do={ /ip firewall address-list add list=rd-test-console address=(\"10.99.\" . (\$i / 256) . \".\" . (\$i % 256)) timeout=10m }"
        session.run(MenuPath.Root, "execute", mapOf("script" to script)).toList()
        val filter = Filter.Equals("list", "rd-test-console")
        assertEquals(2500, session.count(list, filter))
        val events = session.watch(list, filter).transformWhile {
            emit(it)
            it !is ListEvent.Snapshot
        }.toList()
        val rows = assertIs<ListEvent.Snapshot>(events.last()).items
        assertEquals(2500, rows.map { it.id }.toSet().size)
        assertTrue(rows.all { it["list"] == "rd-test-console" && it["address"].orEmpty().startsWith("10.99.") })
        assertEquals(listOf(ListEvent.Progress(2000, 2500), ListEvent.Progress(2500, 2500)), events.dropLast(1))
        val safe = assertNotNull(session.safeMode)
        assertTrue(safe.enter())
        assertEquals(2500, withTimeout(60.seconds) { session.watch(list, filter).filterIsInstance<ListEvent.Snapshot>().first() }.items.size)
        safe.rollback()
        session.remove(list, rows.map { it.id })
        assertEquals(0, session.count(list, filter))
    }

    @Test
    fun menusAreListedCountedAndDescribed() = session { session ->
        val addresses = MenuPath.parse("ip/address")
        val snapshot = session.watch(addresses).first() as ListEvent.Snapshot
        assertTrue(snapshot.items.isNotEmpty() && snapshot.items.all { it.id.startsWith("*") && it["address"] != null })
        assertEquals(snapshot.items.size, session.count(addresses))
        assertEquals(1, session.count(addresses, Filter.Equals("dynamic", "true")).coerceAtMost(1))
        assertNotNull(session.describe(addresses).argument("address"))
        val resource = assertNotNull(session.get(MenuPath.parse("system/resource"), null))
        assertTrue(Regex("(\\d+[wdhms])+|\\d\\d:\\d\\d:\\d\\d").matches(resource["uptime"].orEmpty()), resource["uptime"])
        assertEquals("x86_64", resource["architecture-name"])
    }

    @Test
    fun anEntryIsAddedChangedFilteredAndRemoved() = session { session ->
        val comment = "a;b=c, \"q\" \$x [y] é ? \\ end"
        val id = session.add(list, mapOf("list" to "rd-test-console", "address" to "203.0.113.41", "comment" to comment))
        val other = session.add(list, mapOf("list" to "rd-test-console", "address" to "203.0.113.42", "comment" to "true", "disabled" to "true"))
        val first = assertNotNull(session.get(list, id))
        assertEquals(comment, first["comment"])
        assertEquals("false", first["disabled"])
        assertEquals("true", assertNotNull(session.get(list, other))["disabled"])
        assertEquals("true", assertNotNull(session.get(list, other))["comment"])
        val mine = Filter.Equals("list", "rd-test-console")
        assertEquals(2, session.count(list, mine))
        assertEquals(1, session.count(list, Filter.And(listOf(mine, Filter.Equals("disabled", "false")))))
        assertEquals(1, session.count(list, Filter.And(listOf(mine, Filter.Not(Filter.Equals("address", "203.0.113.41"))))))
        assertEquals(2, session.count(list, Filter.And(listOf(mine, Filter.Or(listOf(Filter.Equals("address", "203.0.113.41"), Filter.Equals("address", "203.0.113.42")))))))
        assertEquals(1, session.count(list, Filter.And(listOf(mine, Filter.Equals("comment", comment)))))
        session.set(list, id, mapOf("disabled" to "true", "comment" to null))
        val changed = assertNotNull(session.get(list, id))
        assertEquals("true", changed["disabled"])
        assertEquals(null, changed["comment"])
        session.set(list, id, mapOf("disabled" to "false"))
        assertEquals("false", assertNotNull(session.get(list, id))["disabled"])
        session.remove(list, listOf(id, other))
        assertEquals(0, session.count(list, mine))
    }

    @Test
    fun aCommentInCp1251IsWrittenAndReadInTheEncodingOfTheRouter() = session(encoding = ApiEncoding.Windows1251) { session ->
        val id = session.add(list, mapOf("list" to "rd-test-console", "address" to "203.0.113.47", "comment" to "Привет, мир"))
        assertEquals("Привет, мир", assertNotNull(session.get(list, id))["comment"])
        session.remove(list, listOf(id))
    }

    @Test
    fun aCommentInCp1251IsReadUnderUtf8WithoutSpoilingTheTable() = session(encoding = ApiEncoding.Windows1251) { cp1251 ->
        val id = cp1251.add(list, mapOf("list" to "rd-test-console", "address" to "203.0.113.48", "comment" to "Привет, мир"))
        val lab = Lab.require("chr.host", "chr.user", "chr.password")
        val utf8 = connectRouterOsConsole(connectNative(lab.getValue("chr.host"), lab.getValue("chr.user"), lab.getValue("chr.password"), this), lab.getValue("chr.user"), this)
        try {
            assertEquals("Привет, мир", assertNotNull(assertNotNull(utf8).get(list, id))["comment"])
        } finally {
            utf8?.close()
            cp1251.remove(list, listOf(id))
        }
    }

    @Test
    fun theRoutersRefusalsKeepTheirCategories() = session { session ->
        val missing = assertFailsWith<DeviceException> { session.set(list, "*FFFF", mapOf("comment" to "x")) }
        assertEquals(ErrorCategory.MissingItem, missing.category)
        val bad = assertFailsWith<DeviceException> { session.add(list, mapOf("list" to "rd-test-console", "address" to "203.0.113.43", "timeout" to "bogus")) }
        assertEquals(ErrorCategory.ArgumentValue, bad.category, bad.message)
        val menu = assertFailsWith<DeviceException> { session.count(MenuPath.parse("no/such/menu")) }
        assertEquals(ErrorCategory.MissingItem, menu.category, menu.message)
        assertEquals(0, session.count(list, Filter.Equals("list", "rd-test-console")))
    }

    @Test
    fun commandsExportsAndConcurrentCalls() = session { session ->
        val pings = session.run(MenuPath.parse("tool"), "ping", mapOf("address" to "127.0.0.1", "count" to "2")).toList()
        assertEquals(2, pings.size)
        val rounds = session.run(MenuPath.parse("interface"), "monitor-traffic", mapOf("interface" to "ether1,lo")).take(6).toList()
        assertEquals(setOf("ether1", "lo"), rounds.map { it["name"] }.toSet(), rounds.toString())
        assertTrue(rounds.all { it["rx-bits-per-second"] != null })
        session.add(list, mapOf("list" to "rd-test-console", "address" to "203.0.113.44", "comment" to "exported"))
        assertTrue("203.0.113.44" in session.export(list))
        val counts = (1..24).map { async { session.count(MenuPath.parse("interface")) } }.awaitAll()
        assertTrue(counts.all { it == counts.first() && it > 0 }, counts.toString())
        val console = checkNotNull(session.ssh).shell("xterm", 80, 24)
        try {
            val seen = console.readToPrompt()
            assertTrue("] > " in seen, seen)
        } finally {
            console.close()
        }
    }

    @Test
    fun theCommandsOfTheClientLeaveNoLinesInTheHistoryOfTheUser() = session { session ->
        val ssh = checkNotNull(session.ssh)
        val mark = "history-${System.nanoTime()}"
        val typing = ssh.shell("xterm", 80, 24)
        try {
            typing.readToPrompt()
            typing.readQuiet()
            typing.write(":put \"$mark\"\r".encodeToByteArray())
            typing.readQuiet()
        } finally {
            typing.close()
        }
        repeat(3) { session.count(MenuPath.parse("interface")) }
        assertNotNull(session.get(MenuPath.parse("system/resource"), null))
        val reading = ssh.shell("xterm", 80, 24)
        try {
            reading.readToPrompt()
            reading.readQuiet()
            val recalled = StringBuilder()
            repeat(3) {
                reading.write("\u001b[A".encodeToByteArray())
                recalled.append(reading.readQuiet())
            }
            assertTrue(mark in recalled, recalled.toString())
            assertTrue(":put \"rd" !in recalled, recalled.toString())
        } finally {
            reading.close()
        }
    }

    @Test
    fun aDroppedSessionIsOpenedAgain() = runBlocking {
        val lab = Lab.require("chr.host", "chr.user", "chr.password")
        withTimeout(120.seconds) {
            val connections = ArrayList<NativeConnection>()
            val ssh = NativeSsh.open(
                { NativeConnection.open(lab.getValue("chr.host"), lab.getValue("chr.user"), lab.getValue("chr.password"), this).also { connections += it } },
                lab.getValue("chr.password"),
            )
            val session = connectRouterOsConsole(ssh, lab.getValue("chr.user"), this) ?: error("the console does not serialize")
            try {
                val interfaces = MenuPath.parse("interface")
                val before = session.count(interfaces)
                connections.single().close()
                var after: Int? = null
                repeat(8) { if (after == null) after = runCatching { session.count(interfaces) }.getOrNull() }
                assertEquals(before, after)
                assertEquals(2, connections.size)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun routerOs6ListsItsMenusAndTellsSettingsFromLists() = session("chr6") { session ->
        assertEquals(Platform.RouterOs, session.device.platform)
        assertTrue(session.device.version.startsWith("6."), session.device.version)
        assertTrue(session.device.packages.isNotEmpty(), session.device.packages.toString())
        val addresses = MenuPath.parse("ip/address")
        val snapshot = session.watch(addresses).first() as ListEvent.Snapshot
        assertTrue(snapshot.items.isNotEmpty() && snapshot.items.all { it.id.startsWith("*") && it["address"] != null })
        assertEquals(snapshot.items.size, session.count(addresses))
        assertEquals(NodeKind.List, session.describe(addresses).kind)
        assertEquals(NodeKind.Settings, session.describe(MenuPath.parse("ip/dns")).kind)
        assertNotNull(assertNotNull(session.get(MenuPath.parse("ip/dns"), null))["cache-size"])
        val resource = assertNotNull(session.get(MenuPath.parse("system/resource"), null))
        assertTrue(Regex("(\\d+[wdhms])+").matches(resource["uptime"].orEmpty()), resource["uptime"])
        assertTrue(resource["version"].orEmpty().startsWith("6."))
        val rates = session.run(MenuPath.parse("interface"), "monitor-traffic", mapOf("interface" to "ether1")).take(3).toList()
        assertTrue(rates.size == 3 && rates.all { it["name"] == "ether1" && it["rx-bits-per-second"] != null }, rates.toString())
        val log = (session.watch(MenuPath.parse("log")).first() as ListEvent.Snapshot).items
        assertTrue(log.isNotEmpty() && log.all { it["message"] != null }, log.size.toString())
    }

    @Test
    fun routerOs6AddsChangesFiltersAndRemovesAnEntry() = session("chr6") { session ->
        val comment = "a;b=c, \"q\" \$x [y] é ? \\ end"
        val id = session.add(list, mapOf("list" to "rd-test-console", "address" to "203.0.113.41", "comment" to comment))
        val other = session.add(list, mapOf("list" to "rd-test-console", "address" to "203.0.113.42", "comment" to "true", "disabled" to "true"))
        val first = assertNotNull(session.get(list, id))
        assertEquals(comment, first["comment"])
        assertEquals("false", first["disabled"])
        assertEquals("true", assertNotNull(session.get(list, other))["disabled"])
        assertEquals("true", assertNotNull(session.get(list, other))["comment"])
        val mine = Filter.Equals("list", "rd-test-console")
        assertEquals(2, session.count(list, mine))
        assertEquals(1, session.count(list, Filter.And(listOf(mine, Filter.Equals("disabled", "false")))))
        assertEquals(1, session.count(list, Filter.And(listOf(mine, Filter.Not(Filter.Equals("address", "203.0.113.41"))))))
        assertEquals(2, session.count(list, Filter.And(listOf(mine, Filter.Or(listOf(Filter.Equals("address", "203.0.113.41"), Filter.Equals("address", "203.0.113.42")))))))
        assertEquals(1, session.count(list, Filter.And(listOf(mine, Filter.Equals("comment", comment)))))
        session.set(list, id, mapOf("disabled" to "true", "comment" to null))
        val changed = assertNotNull(session.get(list, id))
        assertEquals("true", changed["disabled"])
        assertEquals(null, changed["comment"])
        session.set(list, id, mapOf("disabled" to "false"))
        assertEquals("false", assertNotNull(session.get(list, id))["disabled"])
        session.remove(list, listOf(id, other))
        assertEquals(0, session.count(list, mine))
    }

    @Test
    fun routerOs6RefusalsKeepTheirCategoriesAndTheConsolesGoOnAfterThem() = session("chr6") { session ->
        val missing = assertFailsWith<DeviceException> { session.set(list, "*FFFF", mapOf("comment" to "x")) }
        assertEquals(ErrorCategory.MissingItem, missing.category, missing.message)
        val bad = assertFailsWith<DeviceException> { session.add(list, mapOf("list" to "rd-test-console", "address" to "203.0.113.43", "timeout" to "bogus")) }
        assertEquals(ErrorCategory.ArgumentValue, bad.category, bad.message)
        val absent = assertFailsWith<DeviceException> { session.add(MenuPath.parse("ip/address"), mapOf("interface" to "ether1")) }
        assertEquals(ErrorCategory.ArgumentValue, absent.category, absent.message)
        val menu = assertFailsWith<DeviceException> { session.count(MenuPath.parse("zzzz/such/menu")) }
        assertEquals(ErrorCategory.MissingItem, menu.category, menu.message)
        assertEquals(0, session.count(list, Filter.Equals("list", "rd-test-console")))
        val counts = (1..24).map { async { session.count(MenuPath.parse("interface")) } }.awaitAll()
        assertTrue(counts.all { it == counts.first() && it > 0 }, counts.toString())
    }

    @Test
    fun routerOs6KeepsItsTerminal() = session("chr6") { session ->
        val console = checkNotNull(session.ssh).shell("xterm", 80, 24)
        try {
            val seen = console.readToPrompt()
            assertTrue("] > " in seen, seen)
        } finally {
            console.close()
        }
    }

    private class WithoutSftp(private val ssh: SshSession) : SshSession by ssh {
        override suspend fun sftp(): SftpClient = throw DeviceException(ErrorCategory.Unsupported, "no sftp")
    }

    @Test
    fun routerOs6FilesGoThroughTheCommandsOfTheConsole() = runBlocking {
        val lab = Lab.require("chr6.host", "chr6.user", "chr6.password")
        withTimeout(240.seconds) {
            val session = connectRouterOsConsole(WithoutSftp(connectNative(lab.getValue("chr6.host"), lab.getValue("chr6.user"), lab.getValue("chr6.password"), this)), lab.getValue("chr6.user"), this)
                ?: error("the console speaks no dialect of the client")
            val files = checkNotNull(session.ssh).sftp()
            try {
                files.write("/rd-test-mc-v6.txt", "line one\nline two\n".encodeToByteArray())
                assertEquals("line one\nline two\n", files.read("/rd-test-mc-v6.txt").decodeToString())
                files.write("/rd-test-mc-v6.txt", "a\r\nb \$x [y] \"q\"; é".encodeToByteArray())
                assertEquals("a\nb \$x [y] \"q\"; é", files.read("/rd-test-mc-v6.txt").decodeToString())
                val long = "0123456789 abcdef;\"[]\$\n".repeat(130)
                val took = measureTime { files.write("/rd-test-mc-v6.txt", long.encodeToByteArray()) }
                assertEquals(long, files.read("/rd-test-mc-v6.txt").decodeToString())
                println("RESULT RouterOS 6: ${long.length} bytes written in $took")
                assertEquals(listOf("rd-test-mc-v6.txt"), files.list("/").map { it.name }.filter { it.startsWith("rd-test-mc") })
                assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { files.write("/rd-test-mc-v6.txt", ByteArray(4_001)) }.category)
                assertEquals(ErrorCategory.Unsupported, assertFailsWith<DeviceException> { files.write("/rd-test-mc-v6.rsc", ByteArray(1)) }.category)
            } finally {
                files.list("/").filter { it.name.startsWith("rd-test-mc") }.forEach { files.remove("/${it.name}") }
                session.close()
            }
        }
    }

    @Test
    fun safeModeUndoesWhatTheConsolesChanged() = session { session -> safeMode(session) }

    @Test
    fun safeModeUndoesWhatTheConsolesOfRouterOs6Changed() = session("chr6") { session -> safeMode(session) }

    private suspend fun safeMode(session: RouterOsSession) {
        assumeTrue("not asked for", System.getProperty("networkhelm.safemode") != null)
        val mode = assertNotNull(session.safeMode)
        val mine = Filter.Equals("list", "rd-test-console")
        assertTrue(mode.enter())
        session.add(list, mapOf("list" to "rd-test-console", "address" to "203.0.113.45"))
        assertEquals(1, session.count(list, mine))
        assertTrue(mode.active.first() && mode.actions.first() >= 1)
        mode.rollback()
        withTimeout(20.seconds) { while (session.count(list, mine) != 0) delay(250) }
        assertTrue(mode.enter())
        session.add(list, mapOf("list" to "rd-test-console", "address" to "203.0.113.46"))
        mode.commit()
        assertEquals(1, session.count(list, mine))
    }
}
