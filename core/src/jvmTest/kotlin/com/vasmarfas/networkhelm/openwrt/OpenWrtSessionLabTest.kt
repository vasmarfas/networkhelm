package com.vasmarfas.networkhelm.openwrt

import com.vasmarfas.networkhelm.Lab
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.offline.openConfig
import com.vasmarfas.networkhelm.search.Finding
import com.vasmarfas.networkhelm.search.find
import com.vasmarfas.networkhelm.search.lookup
import com.vasmarfas.networkhelm.session.rows
import com.vasmarfas.networkhelm.ssh.SshAuth
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.ssh.connectSsh
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.transport.TrustPolicy
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class OpenWrtSessionLabTest {

    private val rdTest = MenuPath.parse("rd_test/rd_test")

    private fun openwrt(block: suspend (OpenWrtSession, SshSession, Map<String, String>) -> Unit) =
        Lab.forEachOpenWrt("host", "user", "password", "ssh.hostKey", "lan") { lab ->
            runBlocking {
                withTimeout(60.seconds) {
                    val ssh = connectSsh(
                        lab.getValue("host"), 22, lab.getValue("user"), SshAuth.Password(lab.getValue("password")),
                        acceptHostKey = { it.fingerprint == lab.getValue("ssh.hostKey") },
                    )
                    try {
                        block(connectOpenWrt(ssh), ssh, lab)
                    } finally {
                        ssh.exec("rm -rf /etc/config/rd_test /tmp/rd-uci /tmp/rd-apply")
                        ssh.close()
                    }
                }
            }
        }

    @Test
    fun configsSectionTypesAndSectionsOfTheRouter() = openwrt { session, _, _ ->
        assertEquals("OpenWrt", session.device.identity)
        assertTrue("network" in session.describe(MenuPath.Root).children)
        assertTrue("interface" in session.describe(MenuPath.parse("network")).children)
        val interfaces = MenuPath.parse("network/interface")
        val schema = session.describe(interfaces)
        assertEquals(NodeKind.List, schema.kind)
        assertEquals(ValueType.Flag, assertNotNull(schema.argument("classlessroute"), "a dhcp interface has the options of its protocol").type)
        assertEquals(ValueType.Text(), schema.argument("broadcast")?.type)
        val names = session.watch(interfaces).rows().first { it.isNotEmpty() }.map { it.id }
        assertTrue(names.containsAll(listOf("loopback", "lan", "wan")), names.toString())
        assertEquals("lan", session.get(interfaces, "lan")?.get(".name"))
        assertEquals(listOf("lan"), session.watch(interfaces, Filter.Equals(".id", "lan")).rows().first { it.isNotEmpty() }.map { it.id })
        assertEquals(ErrorCategory.MissingItem, assertFailsWith<DeviceException> { session.describe(MenuPath.parse("network/interface/lan/x")) }.category)
    }

    @Test
    fun sectionsAreAddedChangedMovedAndRemovedInTheDraftThenApplied() = openwrt { session, ssh, _ ->
        ssh.exec("touch /etc/config/rd_test")
        val main = session.add(rdTest, mapOf(".name" to "main", "value" to "1"))
        val anonymous = session.add(rdTest, mapOf("value" to "2"))
        session.set(rdTest, main, mapOf("server" to "192.0.2.1\n192.0.2.2", "value" to null))
        assertEquals(ValueType.Many(ValueType.Text(), LIST), session.describe(rdTest).argument("server")?.type)
        session.move(rdTest, listOf(anonymous), before = main)
        val items = session.watch(rdTest).take(1).toList().filterIsInstance<ListEvent.Snapshot>().single().items
        assertEquals(listOf(anonymous, main), items.map { it.id })
        assertEquals("192.0.2.1\n192.0.2.2", items.last()["server"])
        assertEquals(null, items.last()["value"])
        assertEquals(1, session.count(rdTest, Filter.Has(".name")))
        session.remove(rdTest, listOf(anonymous))
        assertEquals(listOf(main), session.watch(rdTest).rows().first { it.isNotEmpty() }.map { it.id })
        val apply = session.safeMode
        assertTrue(apply.actions.first() > 0)
        assertTrue(apply.enter())
        assertTrue(apply.active.first())
        apply.commit()
        val file = ssh.exec("cat /etc/config/rd_test").output
        assertTrue("list server '192.0.2.2'" in file, file)
        assertEquals(0, apply.actions.first())
    }

    @Test
    fun theSearchFindsTheAddressOfTheLan() = openwrt { session, _, lab ->
        val hits = find(session, assertNotNull(lookup(lab.getValue("lan")))).toList().filterIsInstance<Finding.Hit>()
        val places = hits.map { it.place.path.toString() }.toSet()
        assertTrue(places.containsAll(listOf("status/addresses", "network/interface")), places.toString())
    }

    @Test
    fun aBackupAndUciExportOfTheRouterReadAsItsConfiguration() = openwrt { session, ssh, lab ->
        val backup = "/tmp/rd-test-backup.tar.gz"
        val bytes = try {
            assertEquals(0, ssh.exec("sysupgrade -b $backup").exitStatus)
            val sftp = ssh.sftp()
            try {
                sftp.read(backup)
            } finally {
                sftp.close()
            }
        } finally {
            ssh.exec("rm -f $backup")
        }
        val opened = assertNotNull(openConfig("backup.tar.gz", bytes))
        assertEquals(session.device.identity, opened.device.identity)
        val lan = opened.get(MenuPath.parse("network/interface"), "lan")
        assertTrue(lab.getValue("lan").substringBefore('/') in lan?.get("ipaddr").orEmpty(), lan?.values.toString())
        val exported = assertNotNull(openConfig("uci", session.export().encodeToByteArray()))
        assertEquals("lan", exported.get(MenuPath.parse("network/interface"), "lan")?.get(".name"))
    }

    @Test
    fun theStateOfTheRouterIsReadAsLists() = openwrt { session, ssh, lab ->
        val last = session.watch(MenuPath.parse("status/log")).rows().first { it.isNotEmpty() }.last()
        val printed = ssh.exec("logread -l 50").output.lines()
        assertTrue(printed.any { line -> listOf(last["time"].orEmpty().substringAfter(' '), last["topics"].orEmpty()).all { it in line } && line.endsWith(last["message"].orEmpty()) }, "$last")
        val addresses = session.watch(MenuPath.parse("status/addresses")).rows().first { it.isNotEmpty() }
        assertTrue(addresses.any { it["address"] == lab.getValue("lan") && it["interface"] == "lan" }, addresses.map { it["interface"] }.toString())
        val port = ssh.exec("echo \$SSH_CONNECTION").output.trim().split(' ')[1]
        var connections = emptyList<Item>()
        val ours = withTimeoutOrNull(20.seconds) {
            session.watch(MenuPath.parse("status/connections")).rows().first { rows ->
                connections = rows
                rows.any { it["protocol"] == "tcp" && it["src-port"] == port && it["dst-port"] == "22" }
            }
        }
        assertNotNull(
            ours,
            "${connections.size} connections, ours from $port, to port 22: " + connections.filter { it["dst-port"] == "22" || it["src-port"] == "22" }.map { "${it["protocol"]} ${it["tcp-state"]} ${it["flags"]} ${it["src-port"]}>${it["dst-port"]}" },
        )
        val neighbors = session.watch(MenuPath.parse("status/neighbors")).rows().first { it.isNotEmpty() }
        assertTrue(neighbors.all { it["mac-address"].orEmpty().count { c -> c == ':' } == 5 && it["interface"] != null }, "${neighbors.size} neighbours")
        assertEquals("true", session.get(MenuPath.parse("status/services"), "dropbear")?.get("running"))
        val leases = MenuPath.parse("status/dhcp-leases")
        assertEquals(listOf("print"), session.describe(leases).commands)
        session.count(leases, null)
    }

    @Test
    fun theStatusOfLuciIsReadAsListsAndCommands() = openwrt { session, ssh, lab ->
        val (address, prefix) = lab.getValue("lan").split('/')
        val routes = session.watch(MenuPath.parse("status/routes")).rows().first { it.isNotEmpty() }
        assertTrue(routes.any { it["dst-address"] == "${address.substringBeforeLast('.')}.0/$prefix" && it["interface"] == "eth0" && it["pref-src"] == address }, routes.map { it.id }.toString())
        assertTrue(routes.any { it["type"] == "local" && it["table"] == "local" })
        assertTrue(session.watch(MenuPath.parse("status/routes6")).rows().first { it.isNotEmpty() }.any { it["dst-address"] == "fe80::/64" })
        val processes = MenuPath.parse("status/processes")
        assertTrue(session.watch(processes).rows().first { it.isNotEmpty() }.any { it["command"].orEmpty().startsWith("/usr/sbin/dropbear") })
        val pid = ssh.exec("start-stop-daemon -S -b -m -p /tmp/rd-test-sleep.pid -x /bin/sleep -- 600 && cat /tmp/rd-test-sleep.pid").output.trim()
        try {
            session.run(processes, "terminate", mapOf("numbers" to pid)).toList()
            assertEquals("gone", ssh.exec("sleep 1; kill -0 $pid 2>/dev/null || echo gone").output.trim())
        } finally {
            ssh.exec("kill $pid 2>/dev/null; rm -f /tmp/rd-test-sleep.pid")
        }
        val kernel = session.watch(MenuPath.parse("status/kernel-log")).rows().first { it.isNotEmpty() }
        assertTrue(kernel.any { it["topics"].orEmpty().startsWith("kern.") && it["time"] != null }, kernel.take(3).toString())
        val ruleset = session.watch(MenuPath.parse("status/firewall")).rows().first { it.isNotEmpty() }
        assertTrue(ruleset.any { it["chain"] == "input" && it["rule"] == "policy drop" }, ruleset.map { "${it["chain"]}: ${it["rule"]}" }.toString())
        assertTrue(ruleset.any { it["rule"] == "ct state vmap { established : accept, related : accept }" })
        val samples = session.run(MenuPath.parse("tool"), "traffic", mapOf("interface" to "eth0")).take(2).toList()
        assertEquals(listOf("eth0", "eth0"), samples.map { it["name"] })
        assertTrue(samples.all { it["rx-bits-per-second"]?.toLongOrNull() != null && it["tx-bits-per-second"]?.toLongOrNull() != null })
    }

    @Test
    fun theLogComesAsItIsWrittenAndInterfacesWithTheirState() = openwrt { session, ssh, lab ->
        val lan = assertNotNull(session.get(MenuPath.parse("status/interfaces"), "lan"))
        assertEquals(listOf("true", lab.getValue("lan")), listOf(lan["running"], lan["ipv4-address"]))
        coroutineScope {
            val line = async {
                session.watch(MenuPath.parse("status/log")).filterIsInstance<ListEvent.Upsert>().first { it.item["message"].orEmpty().endsWith("rd-test follows") }
            }
            while (!line.isCompleted) {
                ssh.exec("logger -t rd-test 'rd-test follows'")
                delay(1.seconds)
            }
            assertEquals("user.notice", line.await().item["topics"])
        }
    }

    @Test
    fun packagesResourcesAndTasksOfCron() = openwrt { session, ssh, _ ->
        assertTrue(session.get(MenuPath.parse("status/packages"), "dnsmasq")?.get("version").orEmpty().isNotEmpty())
        val resource = assertNotNull(session.get(MenuPath.parse("status/resource"), null))
        assertEquals(session.device.version, resource["version"])
        assertTrue(resource["uptime"].orEmpty().matches(Regex("(\\d+[wdhms])+")), resource.toString())
        val scheduler = MenuPath.parse("status/scheduler")
        val before = ssh.exec("cat /etc/crontabs/root")
        try {
            val task = session.add(scheduler, mapOf("minute" to "59", "hour" to "23", "day" to "31", "month" to "12", "command" to "logger rd-test"))
            session.set(scheduler, task, mapOf("hour" to "22"))
            assertTrue("59 22 31 12 * logger rd-test" in ssh.exec("cat /etc/crontabs/root").output)
            assertEquals(listOf("59 22 31 12 * logger rd-test"), session.watch(scheduler).rows().first { it.isNotEmpty() }.map { it.id }.filter { "rd-test" in it })
            session.remove(scheduler, listOf("59 22 31 12 * logger rd-test"))
            assertTrue("rd-test" !in ssh.exec("cat /etc/crontabs/root").output)
        } finally {
            if (before.exitStatus != 0) ssh.exec("rm -f /etc/crontabs/root; /etc/init.d/cron restart")
        }
    }

    @Test
    fun theSftpServerComesFromThePackages() = openwrt { session, ssh, _ ->
        if (ssh.exec("[ -x $SFTP_SERVER ]").exitStatus != 0) {
            session.run(MenuPath.parse("status/files"), "sftp", emptyMap()).toList()
            assertEquals(0, ssh.exec("[ -x $SFTP_SERVER ]").exitStatus, "the server is installed")
        }
        assertNotNull(session.ssh).sftp().close()
    }

    @Test
    fun filesOfTmpOverSftpAndOverCatAndABackup() = openwrt { session, ssh, _ ->
        val files = MenuPath.parse("status/files")
        try {
            for ((name, client) in listOf("rd-test-sftp.txt" to assertNotNull(session.ssh).sftp(), "rd-test-cat.txt" to ShellFiles(ssh))) {
                client.write("/tmp/$name", "rd-test $name".encodeToByteArray())
                assertEquals("rd-test $name", client.read("/tmp/$name").decodeToString())
                assertTrue(client.list("/tmp").any { it.name == name && !it.directory })
                client.close()
            }
            // the window starts at the root, the folders below it come over the file channel
            val root = session.watch(files).rows().first { it.isNotEmpty() }
            assertEquals("directory", root.single { it.id == "tmp" }["type"])
            assertEquals("directory", root.single { it.id == "var" }["type"], "the link /var leads to /tmp")
            val tmp = ShellFiles(ssh)
            assertTrue(tmp.list("/tmp").any { it.name == "rd-test-cat.txt" })
            session.run(files, "backup", emptyMap()).toList()
            val backup = tmp.list("/tmp").first { it.name.startsWith("backup-") }
            assertTrue(backup.size > 1000, backup.toString())
            session.remove(files, listOf("tmp/${backup.name}", "tmp/rd-test-cat.txt"))
            assertTrue(tmp.list("/tmp").none { it.name == backup.name || it.name == "rd-test-cat.txt" })
            ShellFiles(ssh).write("/tmp/rd-test-image.bin", "rd-test".encodeToByteArray())
            val refused = assertFailsWith<DeviceException> { session.run(files, "upgrade", mapOf("numbers" to "tmp/rd-test-image.bin")).toList() }
            assertEquals(ErrorCategory.ArgumentValue, refused.category)
        } finally {
            ssh.exec("rm -f /tmp/rd-test-sftp.txt /tmp/rd-test-cat.txt /tmp/rd-test-image.bin")
        }
    }

    @Test
    fun toolsRunOnTheRouterAndStopWhenStopped() = openwrt { session, ssh, lab ->
        val tool = MenuPath.parse("tool")
        val lan = lab.getValue("lan").substringBefore('/')
        assertEquals(NodeKind.Command, session.describe(MenuPath.parse("tool/ping")).kind)
        val replies = session.run(tool, "ping", mapOf("address" to lan, "count" to "2")).toList()
        assertEquals(listOf("0", "1"), replies.map { it["seq"] })
        assertEquals("0%", replies.last()["packet-loss"])
        assertEquals(3, session.run(tool, "ping", mapOf("address" to lan)).take(3).toList().size)
        delay(2.seconds)
        assertEquals("", ssh.exec("pidof ping").output.trim(), "ping without a count ends with its channel")
        val hops = session.run(tool, "traceroute", mapOf("address" to lan, "max-hops" to "3")).toList()
        assertEquals(lan, hops.single()["address"])
        val names = session.run(tool, "nslookup", mapOf("name" to "localhost")).toList()
        assertTrue(names.any { it["address"] == "127.0.0.1" }, names.toString())
    }

    @Test
    fun theWebServerGivesTheSameSession() = openwrt { _, _, lab ->
        for (protocol in listOf(OpenWrtProtocol.Ubus, OpenWrtProtocol.UbusSsl)) {
            val trust = TrustPolicy(MemoryTrustStore()) { protocol.encrypted }
            val session = connectOpenWrt(OpenWrtLogin(lab.getValue("host"), lab.getValue("user"), lab.getValue("password"), protocol), trust)
            assertTrue(session.device.canWrite, session.device.policies.toString())
            val names = session.watch(MenuPath.parse("network/interface")).rows().first { it.isNotEmpty() }.map { it.id }
            assertTrue("lan" in names, "$protocol: $names")
            assertEquals(22, session.sshPort())
            session.close()
            val refused = assertFailsWith<DeviceException>(protocol.toString()) {
                connectOpenWrt(OpenWrtLogin(lab.getValue("host"), "root", "rd-test-wrong", protocol), trust)
            }
            assertEquals(ErrorCategory.PermissionDenied, refused.category, protocol.toString())
        }
    }
}
