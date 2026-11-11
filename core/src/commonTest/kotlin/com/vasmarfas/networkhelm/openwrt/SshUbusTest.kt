package com.vasmarfas.networkhelm.openwrt

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.ssh.ExecResult
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.transport.CannedStream
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SshUbusTest {

    private class Shell(vararg answers: String) : ByteStream {
        val commands = mutableListOf<String>()
        var closed = false
        private val answers = ArrayDeque(answers.toList())
        private var pending = ByteArray(0)

        override suspend fun write(bytes: ByteArray) {
            commands += bytes.decodeToString()
            pending += answers.removeFirstOrNull().orEmpty().encodeToByteArray()
        }

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pending.isEmpty()) return -1
            val count = minOf(length, pending.size)
            pending.copyInto(buffer, offset, 0, count)
            pending = pending.copyOfRange(count, pending.size)
            return count
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun oversizedLinesAndManySmallLinesCloseAndReplaceTheShell() = runTest {
        for (answer in listOf("x".repeat(100), "x\n".repeat(40))) {
            val tooLarge = Shell(answer)
            val normal = Shell("{}\nrd-status 0\n")
            val shells = ArrayDeque(listOf(tooLarge, normal))
            val ubus = SshUbus(Ssh { shells.removeFirst() }, maxResponseBytes = 32)
            assertFailsWith<DeviceException> { ubus.call("system", "board") }
            assertTrue(tooLarge.closed)
            assertEquals(JsonObject(emptyMap()), ubus.call("system", "board"))
        }
    }

    @Test
    fun aSubscriptionAlsoRejectsAnOversizedEventLine() = runTest {
        val stream = CannedStream("x".repeat(33))
        assertFailsWith<DeviceException> { SshUbus(Ssh { stream }, maxResponseBytes = 32).listen("event").toList() }
        assertTrue(stream.closed)
    }

    private class Ssh(private val processes: (String) -> ByteStream) : SshSession {
        val commands = mutableListOf<String>()
        override suspend fun exec(command: String): ExecResult = error("no exec")
        override suspend fun process(command: String): ByteStream = processes(command).also { commands += command }
        override suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel = error("no shell")
        override suspend fun sftp(): SftpClient = error("no sftp")
        override suspend fun forward(host: String, port: Int): ByteStream = error("no forward")
        override fun close() = Unit
    }

    @Test
    fun callsGoThroughOneShellQuotedAndEndWithTheStatus() = runTest {
        val shell = Shell("{\"release\":{\"version\":\"25.12.6\"}}\n\nrd-status 0\n", "\nrd-status 4\n", "\nrd-status 0\n")
        val ssh = Ssh { shell }
        val ubus = SshUbus(ssh)
        assertEquals("25.12.6", ubus.call("system", "board")["release"]?.jsonObject?.get("version")?.jsonPrimitive?.content)
        assertEquals(4, assertFailsWith<UbusException> { ubus.call("rd-test", "nothing") }.status)
        assertEquals(JsonObject(emptyMap()), ubus.call("uci", "set", JsonObject(mapOf("comment" to JsonPrimitive("it's")))))
        assertEquals(listOf("sh"), ssh.commands)
        assertEquals("ubus -S call 'system' 'board' '{}' 2>/dev/null; s=$?; echo; echo \"rd-status \$s\"\n", shell.commands[0])
        assertEquals("ubus -S call 'uci' 'set' '{\"comment\":\"it'\\''s\"}' 2>/dev/null; s=$?; echo; echo \"rd-status \$s\"\n", shell.commands[2])
    }

    @Test
    fun aCommandOfTheSameShellGivesItsOutputAndStatus() = runTest {
        val shell = Shell("IP address  Device\n192.0.2.1  eth1\n\nrd-status 0\n", "cat: can't open '/proc/net/nf_conntrack'\n\nrd-status 1\n")
        val ubus = SshUbus(Ssh { shell })
        assertEquals("IP address  Device\n192.0.2.1  eth1\n", ubus.sh("cat /proc/net/arp").output)
        val failed = ubus.sh("cat /proc/net/nf_conntrack")
        assertEquals(1, failed.exitStatus)
        assertEquals("cat: can't open '/proc/net/nf_conntrack'\n", failed.output)
        assertEquals("{ cat /proc/net/arp; } 2>&1; s=$?; echo; echo \"rd-status \$s\"\n", shell.commands[0])
    }

    @Test
    fun aShellThatEndsIsReplacedOnTheNextCall() = runTest {
        val shells = ArrayDeque(listOf(Shell("{\"a\""), Shell("{}\nrd-status 0\n")))
        val ssh = Ssh { shells.removeFirst() }
        val ubus = SshUbus(ssh)
        assertEquals(UbusStatus.CONNECTION_FAILED, assertFailsWith<UbusException> { ubus.call("system", "board") }.status)
        assertEquals(JsonObject(emptyMap()), ubus.call("system", "board"))
        assertEquals(listOf("sh", "sh"), ssh.commands)
    }

    @Test
    fun eventsComeLineByLineOverAChannelOfTheirOwn() = runTest {
        val ssh = Ssh { CannedStream("{\"config.change\":{\"config\":\"network\"}}\n\n{\"config.change\":{\"config\":\"dhcp\"}}\n") }
        val events = SshUbus(ssh).listen("config.change").toList()
        assertEquals(listOf("network", "dhcp"), events.map { it["config.change"]?.jsonObject?.get("config")?.jsonPrimitive?.content })
        // the subscription goes with its channel: dropbear would leave it running on the router
        assertEquals(listOf("exec 3<&0; ubus -S listen 'config.change' & p=\$!; { read _ <&3; kill \$p; } >/dev/null 2>&1 & wait \$p"), ssh.commands)
    }

    @Test
    fun initScriptsWithoutRpcdComeFromTheirFiles() = runTest {
        val shell = Shell(
            "boot 10 \ncron 50 \ndropbear 19 50\n\nK50dropbear\nS10boot\nS19dropbear\n\nrd-status 0\n",
            "{\"cron\":{\"instances\":{\"instance1\":{\"running\":false}}},\"dropbear\":{\"instances\":{\"instance1\":{\"running\":true,\"pid\":1180}}}}\n\nrd-status 0\n",
            "\nrd-status 0\n",
        )
        val ssh = Ssh { shell }
        val empty = JsonObject(emptyMap())
        val lists = LiveLists(SshUbus(ssh), ShellUci(ssh), OpenWrtFacts(empty, empty, empty, setOf("service", "system"), emptySet(), emptySet(), true))
        val services = MenuPath.parse("status/services")
        assertEquals(
            listOf(listOf("boot", "10", null, "true", "false"), listOf("cron", "50", null, "false", "false"), listOf("dropbear", "19", "50", "true", "true")),
            lists.read(services).map { listOf(it.id, it["start"], it["stop"], it["enabled"], it["running"]) },
        )
        lists.run(services, "restart", mapOf("numbers" to "dropbear"))
        assertEquals("{ /etc/init.d/'dropbear' 'restart'; } 2>&1; s=$?; echo; echo \"rd-status \$s\"\n", shell.commands[2])
        // the shell is shared with every other command, so the listing changes the directory for itself alone
        assertTrue(shell.commands[0].startsWith("{ (cd /etc/init.d && for n in *; do ") && "; done); echo; ls /etc/rc.d; } 2>&1; " in shell.commands[0], shell.commands[0])
    }

    @Test
    fun theKeysOfRootAreWrittenWithAMaskThatDoesNotStayInTheShell() = runTest {
        val shell = Shell("\nrd-status 0\n", "\nrd-status 0\n")
        val ssh = Ssh { shell }
        val empty = JsonObject(emptyMap())
        val lists = LiveLists(SshUbus(ssh), ShellUci(ssh), OpenWrtFacts(empty, empty, empty, setOf("system"), emptySet(), emptySet(), true))
        val key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhscHR4f rd-test"
        lists.add(MenuPath.parse("status/ssh-keys"), mapOf("key" to key))
        assertEquals("{ (umask 077 && printf '%s' '$key\n' > /etc/dropbear/authorized_keys); } 2>&1; s=$?; echo; echo \"rd-status \$s\"\n", shell.commands[1])
    }

    @Test
    fun anImageIsWrittenOverSshByACommandLeftInTheBackground() = runTest {
        val shell = Shell("{\"valid\":true}\n\nrd-status 0\n", "\nrd-status 0\n")
        val ssh = Ssh { shell }
        val empty = JsonObject(emptyMap())
        val lists = LiveLists(SshUbus(ssh), ShellUci(ssh), OpenWrtFacts(empty, empty, empty, setOf("system"), emptySet(), emptySet(), true))
        lists.run(MenuPath.parse("status/files"), "upgrade", mapOf("numbers" to "tmp/openwrt-sysupgrade.bin"))
        assertEquals("{ (sleep 1; sysupgrade '/tmp/openwrt-sysupgrade.bin') >/dev/null 2>&1 & true; } 2>&1; s=$?; echo; echo \"rd-status \$s\"\n", shell.commands[1])
    }

    @Test
    fun theModemOfRutosComesFromGsmctl() = runTest {
        val printed = listOf(
            "o" to "Tele2", "t" to "LTE", "g" to "Registered (home)", "z" to "inserted", "q" to "RSSI: -60\nRSRP: -103\nSINR: 2\nRSRQ: -16", "C" to "2D2A0B",
            "F" to "+QNWINFO: \"FDD LTE\",\"24603\",\"LTE BAND 3\",1300", "i" to "860000000000001", "J" to "8937200000000000001", "x" to "246030000000001",
            "m" to "EG06", "y" to "EG06ELAR04A04M4G", "c" to "-45",
        ).joinToString("") { (option, text) -> "--rd-$option--\n$text\n" }
        val shell = Shell("$printed\nrd-status 0\n")
        val empty = JsonObject(emptyMap())
        val ssh = Ssh { shell }
        val lists = LiveLists(SshUbus(ssh), ShellUci(ssh), OpenWrtFacts(empty, empty, empty, setOf("system"), emptySet(), emptySet(), true, tools = setOf("gsmctl")))
        val modem = lists.read(MenuPath.parse("status/mobile")).single()
        assertEquals(
            listOf("Tele2", "LTE", "Registered (home)", "inserted", "-60", "-103", "-16", "2", "2D2A0B", "LTE BAND 3", "860000000000001", "EG06", "-4.5"),
            listOf("current-operator", "data-class", "registration-status", "sim-status", "rssi", "rsrp", "rsrq", "sinr", "current-cellid", "primary-band", "imei", "model", "temperature")
                .map { modem[it] },
        )
        assertTrue("gsmctl -\$o" in shell.commands.single())
        assertFalse(LiveLists(SshUbus(ssh), ShellUci(ssh), OpenWrtFacts(empty, empty, empty, setOf("system"), emptySet(), emptySet(), true)).has(MenuPath.parse("status/mobile")))
    }
}
