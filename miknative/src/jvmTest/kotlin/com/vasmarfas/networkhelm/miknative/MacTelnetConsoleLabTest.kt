package com.vasmarfas.networkhelm.miknative

import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.routeros.RouterOsSession
import com.vasmarfas.networkhelm.routeros.api.ApiEncoding
import com.vasmarfas.networkhelm.ssh.ShellChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class MacTelnetConsoleLabTest {
    private val list = MenuPath.parse("ip/firewall/address-list")
    private val mine = Filter.Equals("list", "rd-test-mac")

    private fun link(): Triple<Map<String, String>, MacAddress, MacBroadcastInterface> {
        val lab = Lab.require("chr.host", "chr.user", "chr.password")
        val target = System.getProperty("networkhelm.macTarget").orEmpty()
        val from = System.getProperty("networkhelm.macFrom").orEmpty()
        assumeTrue("-PmacTarget and -PmacFrom do not name the link", target.isNotEmpty() && from.isNotEmpty())
        val links = listBroadcastInterfaces()
        val chosen = checkNotNull(links.firstOrNull { it.address == from }) { "no interface has the address $from, there are ${links.map { it.address }}" }
        return Triple(lab, MacAddress.parse(target), chosen)
    }

    private fun session(block: suspend CoroutineScope.(RouterOsSession) -> Unit) = runBlocking {
        val (lab, target, chosen) = link()
        withTimeout(240.seconds) {
            val session = assertIs<RouterOsSession>(connectMacTelnetHost(target, lab.getValue("chr.user"), lab.getValue("chr.password"), chosen, ApiEncoding.Utf8, this))
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
        val ids = (session.watch(list, mine).first() as ListEvent.Snapshot).items.map { it.id }
        if (ids.isNotEmpty()) session.remove(list, ids)
        val files = checkNotNull(session.ssh).sftp()
        files.list("/").filter { it.name.startsWith("rd-test-mac") }.forEach { files.remove("/${it.name}") }
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

    @Test
    fun theRouterIsRecognizedByItsNameAndVersion() = session { session ->
        assertEquals(Platform.RouterOs, session.device.platform)
        assertTrue(session.device.version.isNotEmpty() && session.device.identity.isNotEmpty())
        assertFalse(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}").matches(session.device.identity))
        assertTrue(session.capabilities.terminal && session.capabilities.files)
        println("RESULT identity=${session.device.identity} version=${session.device.version} packages=${session.device.packages}")
    }

    @Test
    fun severalConsolesAnswerAtOnce() = session { session ->
        val interfaces = MenuPath.parse("interface")
        val addresses = MenuPath.parse("ip/address")
        val filter = MenuPath.parse("ip/firewall/filter")
        val counts = (1..24).map { at ->
            async {
                when (at % 3) {
                    0 -> session.count(interfaces)
                    1 -> session.count(addresses)
                    else -> session.count(filter)
                }
            }
        }.awaitAll()
        assertTrue(counts.withIndex().groupBy({ (at, _) -> (at + 1) % 3 }, { it.value }).values.all { it.toSet().size == 1 }, counts.toString())
        val table = (session.watch(addresses).first() as ListEvent.Snapshot).items
        assertTrue(table.isNotEmpty() && table.all { it.id.startsWith("*") && it["address"] != null })
        println("RESULT counts=$counts rows=${table.size}")
    }

    @Test
    fun anEntryIsAddedChangedAndRemoved() = session { session ->
        val comment = "a;b=c, \"q\" \$x [y] é ? \\ end"
        val id = session.add(list, mapOf("list" to "rd-test-mac", "address" to "203.0.113.51", "comment" to comment))
        assertEquals(comment, assertNotNull(session.get(list, id))["comment"])
        session.set(list, id, mapOf("disabled" to "true", "comment" to null))
        assertEquals("true", assertNotNull(session.get(list, id))["disabled"])
        assertEquals(1, session.count(list, mine))
        session.remove(list, listOf(id))
        assertEquals(0, session.count(list, mine))
    }

    @Test
    fun theTerminalIsASessionOfItsOwn() = session { session ->
        val console = checkNotNull(session.ssh).shell("xterm", 80, 24)
        try {
            val seen = console.readToPrompt()
            assertTrue("] > " in seen, seen)
            console.write("/system identity print\r".encodeToByteArray())
            val answer = StringBuilder()
            val buffer = ByteArray(4096)
            while ("name:" !in answer) {
                val size = console.read(buffer, 0, buffer.size)
                if (size < 0) break
                answer.append(buffer.decodeToString(0, size))
            }
            assertTrue("name:" in answer, answer.toString())
            assertTrue(session.count(MenuPath.parse("interface")) > 0)
        } finally {
            console.close()
        }
    }

    @Test
    fun filesGoThroughTheConsole() = session { session ->
        val files = checkNotNull(session.ssh).sftp()
        val text = "line one\r\nline two\nПривет \"q\" \$x [y]\n"
        files.write("/rd-test-mac-text.txt", text.encodeToByteArray())
        assertEquals(text, files.read("/rd-test-mac-text.txt").decodeToString())
        val binary = ByteArray(20_000) { (it * 37).toByte() }
        files.write("/rd-test-mac-binary.bin", binary)
        assertContentEquals(binary, files.read("/rd-test-mac-binary.bin"))
        files.write("/rd-test-mac-text.txt", "again".encodeToByteArray())
        assertEquals("again", files.read("/rd-test-mac-text.txt").decodeToString())
        // the file manager of RouterOS 7.17 shows a file written through the file store up to a second later
        withTimeout(10.seconds) { while (files.list("/").count { it.name.startsWith("rd-test-mac") } < 2) delay(250) }
        val listed = files.list("/").filter { it.name.startsWith("rd-test-mac") }.associateBy { it.name }
        assertEquals(setOf("rd-test-mac-text.txt", "rd-test-mac-binary.bin"), listed.keys)
        assertEquals(20_000L, listed.getValue("rd-test-mac-binary.bin").size)
        files.remove("/rd-test-mac-binary.bin")
        assertEquals(listOf("rd-test-mac-text.txt"), files.list("/").map { it.name }.filter { it.startsWith("rd-test-mac") })
    }

    @Test
    fun safeModeUndoesWhatTheConsolesChanged() = session { session ->
        assumeTrue("not asked for", System.getProperty("networkhelm.safemode") != null)
        val mode = assertNotNull(session.safeMode)
        assertTrue(mode.enter())
        session.add(list, mapOf("list" to "rd-test-mac", "address" to "203.0.113.52"))
        assertEquals(1, session.count(list, mine))
        assertTrue(mode.active.first() && mode.actions.first() >= 1)
        mode.rollback()
        withTimeout(20.seconds) { while (session.count(list, mine) != 0) delay(250) }
        assertTrue(mode.enter())
        session.add(list, mapOf("list" to "rd-test-mac", "address" to "203.0.113.53"))
        mode.commit()
        assertEquals(1, session.count(list, mine))
    }

    @Test
    fun theConsolesAreKeptOrDroppedWhenIdle() = runBlocking {
        val checkpoints = System.getProperty("networkhelm.idle")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
        assumeTrue("not asked for", checkpoints.isNotEmpty())
        val (lab, target, chosen) = link()
        withTimeout((checkpoints.max() + 3).minutes) {
            val session = assertIs<RouterOsSession>(connectMacTelnetHost(target, lab.getValue("chr.user"), lab.getValue("chr.password"), chosen, ApiEncoding.Utf8, this))
            try {
                val interfaces = MenuPath.parse("interface")
                (1..8).map { async { session.count(interfaces) } }.awaitAll()
                var elapsed = 0
                for (checkpoint in checkpoints) {
                    delay((checkpoint - elapsed) * 60_000L)
                    elapsed = checkpoint
                    val outcomes = (1..8).map { runCatching { session.count(interfaces) }.fold({ "answered" }, { it.message.orEmpty().take(60) }) }
                    println("RESULT IDLE after $checkpoint min, eight calls one after another: $outcomes")
                }
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun theSpeedAgainstPort8291() = runBlocking {
        val (lab, target, chosen) = link()
        withTimeout(240.seconds) {
            val resource = MenuPath.parse("system/resource")
            val native = assertIs<RouterOsSession>(connectNativeHost(lab.getValue("chr.host"), 8291, lab.getValue("chr.user"), lab.getValue("chr.password"), this))
            val mac = assertIs<RouterOsSession>(connectMacTelnetHost(target, lab.getValue("chr.user"), lab.getValue("chr.password"), chosen, ApiEncoding.Utf8, this))
            try {
                for (session in listOf(native, mac)) session.get(resource, null)
                val viaNative = measureTime { repeat(30) { native.get(resource, null) } }
                val viaMac = measureTime { repeat(30) { mac.get(resource, null) } }
                val tableNative = measureTime { native.watch(MenuPath.parse("ip/firewall/filter")).first() }
                val tableMac = measureTime { mac.watch(MenuPath.parse("ip/firewall/filter")).first() }
                println("RESULT 30 gets: 8291 $viaNative, MAC-Telnet $viaMac; a table: 8291 $tableNative, MAC-Telnet $tableMac")
            } finally {
                native.close()
                mac.close()
            }
        }
    }
}
