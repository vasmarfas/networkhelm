package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.ssh.ExecResult
import com.vasmarfas.networkhelm.ssh.RemoteFile
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.ByteStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinuxHostTest {

    private class Host(answers: List<Pair<String, String>>) : SshSession {
        private val answers = answers.toMutableList()

        operator fun set(start: String, answer: String) {
            answers.add(0, start to answer)
        }

        override suspend fun exec(command: String): ExecResult {
            val answer = answers.firstOrNull { command.startsWith(it.first) } ?: return ExecResult("", "not found", 127)
            return ExecResult(answer.second, "", 0)
        }

        override suspend fun process(command: String): ByteStream = error("no process")
        override suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel = error("no shell")
        override suspend fun sftp(): SftpClient = error("no sftp")
        override suspend fun forward(host: String, port: Int): ByteStream = error("no forward")
        override fun close() = Unit
    }

    private val device = DeviceInfo(Platform.Ssh, "rd-test", "Linux 6.12.87", "x86_64", "", emptySet(), emptySet())

    private val links = """[{"ifindex":1,"ifname":"lo","flags":["LOOPBACK","UP","LOWER_UP"],"mtu":65536,"operstate":"UNKNOWN","link_type":"loopback","address":"00:00:00:00:00:00",""" +
        """"stats64":{"rx":{"bytes":2880246,"packets":38635,"errors":0,"dropped":0},"tx":{"bytes":2880246,"packets":38635,"errors":0,"dropped":0}}},""" +
        """{"ifindex":2,"ifname":"eth0","flags":["BROADCAST","MULTICAST","UP","LOWER_UP"],"mtu":1500,"operstate":"UP","link_type":"ether","address":"00:00:5e:00:53:02","ifalias":"rd-test lan",""" +
        """"stats64":{"rx":{"bytes":220,"packets":2,"errors":0,"dropped":0},"tx":{"bytes":30530,"packets":225,"errors":0,"dropped":0}}},""" +
        """{"ifindex":5,"ifname":"wg0","flags":["POINTOPOINT","NOARP"],"mtu":1420,"operstate":"DOWN","link_type":"none","linkinfo":{"info_kind":"wireguard"}}]"""

    private val routes = """[{"type":"local","dst":"default","dev":"lo","table":"1298229097","scope":"host","flags":[]},""" +
        """{"dst":"default","gateway":"192.0.2.1","dev":"eth1","protocol":"static","prefsrc":"192.0.2.72","flags":[]},""" +
        """{"dst":"192.0.2.0/24","dev":"eth1","protocol":"kernel","scope":"link","prefsrc":"192.0.2.72","flags":[]},""" +
        """{"type":"local","dst":"127.0.0.1","dev":"lo","table":"local","protocol":"kernel","scope":"host","prefsrc":"127.0.0.1","flags":[]},""" +
        """{"type":"unreachable","dst":"203.0.113.0/24","dev":"lo","protocol":"boot","flags":[]}]"""

    private fun resource(stat: String) = "93784.11 180000.02\n--rd-section--\n0.12 0.08 0.03 1/89 2657\n--rd-section--\n" +
        "MemTotal:        1009760 kB\nMemFree:          800000 kB\nMemAvailable:     850000 kB\n--rd-section--\n$stat\n--rd-section--\n" +
        "NAME=\"OpenWrt\"\nPRETTY_NAME=\"OpenWrt 25.12.6\"\n--rd-section--\nx86_64\n--rd-section--\n2\n--rd-section--\nStandard PC (Q35 + ICH9, 2009)\n"

    private fun dev(bytes: Long, packets: Long) = "Inter-|   Receive\n face |bytes\n  eth0: $bytes $packets 0 0 0 0 0 0 30530 225 0 0 0 0 0 0\n"

    private fun openWrt() = Host(
        listOf(
            "ip -j link show lo" to """[{"ifindex":1,"ifname":"lo"}]""",
            "ip -j -d -s link show" to links,
            "ip -j -4 addr show" to """[{"ifindex":3,"ifname":"eth1","addr_info":[{"family":"inet","local":"192.0.2.72","prefixlen":24,"scope":"global","dynamic":true,"label":"eth1"}]}]""",
            "ip -j -6 addr show" to """[{"ifindex":3,"ifname":"eth1","addr_info":[{"family":"inet6","local":"fe80::a4:13ff:fe7c:e899","prefixlen":64,"scope":"link"}]}]""",
            "ip -j route show table all" to routes,
            "ip -j -4 neigh show" to """[{"dst":"192.0.2.1","dev":"eth1","lladdr":"00:00:5e:00:53:01","state":["REACHABLE"]},{"dst":"192.0.2.9","dev":"eth1","state":["FAILED"]}]""",
            "ip -j -6 neigh show" to "[]",
            "cat /proc/net/dev" to dev(220, 2),
            "cat /proc/mounts" to "/dev/root / squashfs ro,relatime 0 0\ntmpfs /tmp tmpfs rw,nosuid,nodev,noatime 0 0\n--nh-section--\n" +
                "Filesystem           1024-blocks    Used Available Capacity Mounted on\n/dev/root              2049192    125856   1906952   6% /\ntmpfs 504880 2736 502144 1% /tmp\n",
            "ps w" to "  PID USER       VSZ STAT COMMAND\n    1 root      1656 S    /sbin/procd\n 2603 root      1088 S    /usr/sbin/dropbear -F -P /var/run/dropbear.1.pid\n",
            "logread -l 500" to "Fri Oct 23 17:41:46 2026 authpriv.info dropbear[2657]: Child connection from 192.0.2.2:1184\n" +
                "Fri Oct 23 17:41:46 2026 authpriv.info dropbear[2657]: Child connection from 192.0.2.2:1184\n",
            "date '+%Y-%m-%d" to "2026-10-23 17:41:47 +0000\n",
            "cat /proc/uptime" to resource("cpu  100 0 100 800 0 0 0 0 0 0"),
        ),
    )

    private suspend fun SshHostSession.rows(path: String) = (watch(MenuPath.parse(path)).first() as ListEvent.Snapshot).items

    @Test
    fun theInterfacesAddressesRoutesAndNeighboursComeInTheNamesOfRouterOs() = runTest {
        val session = SshHostSession(openWrt(), device, linux = true)
        val interfaces = session.rows("interface")
        assertEquals(listOf("lo", "eth0", "wg0"), interfaces.map { it["name"] })
        assertEquals(listOf("loopback", "ether", "wg"), interfaces.map { it["type"] })
        val eth0 = interfaces[1]
        assertEquals(listOf("00:00:5E:00:53:02", "true", "false", "rd-test lan", "220", "30530"), listOf("mac-address", "running", "disabled", "comment", "rx-byte", "tx-byte").map { eth0[it] })
        assertEquals(listOf("false", "true"), listOf(interfaces[2]["running"], interfaces[2]["disabled"]))

        val address = session.rows("ip/address").single()
        assertEquals(listOf("192.0.2.72/24", "192.0.2.0", "eth1", "true"), listOf("address", "network", "interface", "dynamic").map { address[it] })
        assertEquals("fe80::a4:13ff:fe7c:e899/64", session.rows("ipv6/address").single()["address"])

        val byDestination = session.rows("ip/route").associateBy { it["dst-address"] }
        assertEquals(setOf("0.0.0.0/0", "192.0.2.0/24", "203.0.113.0/24"), byDestination.keys)
        assertEquals(listOf("192.0.2.1", "eth1", "true", "false", "main"), listOf("gateway", "interface", "static", "connect", "routing-table").map { byDestination.getValue("0.0.0.0/0")[it] })
        assertEquals(listOf("true", "true"), listOf("connect", "dynamic").map { byDestination.getValue("192.0.2.0/24")[it] })
        assertEquals(listOf("unreachable", "true"), listOf("type", "static").map { byDestination.getValue("203.0.113.0/24")[it] })

        val arp = session.rows("ip/arp")
        assertEquals(listOf("00:00:5E:00:53:01", "reachable", "true", "true"), listOf("mac-address", "status", "dynamic", "complete").map { arp[0][it] })
        assertEquals(listOf("", "failed", "false"), listOf("mac-address", "status", "complete").map { arp[1][it] })
    }

    @Test
    fun resourcesStorageProcessesAndTheLogOfOpenWrt() = runTest {
        val host = openWrt()
        val session = SshHostSession(host, device, linux = true)
        val resource = session.get(MenuPath.parse("system/resource"), null)
        assertEquals(
            listOf("1d2h3m4s", "0.12 0.08 0.03", "2", "1033994240", "870400000", "OpenWrt 25.12.6", "x86_64", "Standard PC (Q35 + ICH9, 2009)"),
            listOf("uptime", "load", "cpu-count", "total-memory", "free-memory", "version", "architecture-name", "board-name").map { resource?.get(it) },
        )
        assertEquals(null, resource?.get("cpu-load"))
        host["cat /proc/uptime"] = resource("cpu  120 0 120 960 0 0 0 0 0 0")
        assertEquals("20", session.get(MenuPath.parse("system/resource"), null)?.get("cpu-load"))
        assertEquals(listOf("2026-10-23", "17:41:47"), listOf("date", "time").map { session.get(MenuPath.parse("system/clock"), null)?.get(it) })

        val root = session.rows("system/storage").first()
        assertEquals(listOf("/", "/dev/root", "2098372608", "128876544", "1952718848"), listOf("mount-point", "device", "size", "used", "free").map { root[it] })

        val processes = session.rows("system/process")
        assertEquals(listOf("1", "2603"), processes.map { it.id })
        assertEquals("/usr/sbin/dropbear -F -P /var/run/dropbear.1.pid", processes[1]["command"])

        val log = session.rows("log")
        assertEquals(2, log.map { it.id }.toSet().size)
        assertEquals(listOf("Fri Oct 23 17:41:46 2026", "authpriv.info", "dropbear[2657]: Child connection from 192.0.2.2:1184"), listOf("time", "topics", "message").map { log[0][it] })

        assertFailsWith<DeviceException> { session.describe(MenuPath.parse("system/service")) }
        assertTrue("log" in session.describe(MenuPath.Root).children)
    }

    @Test
    fun theJournalAndTheServicesOfSystemd() = runTest {
        val host = Host(
            listOf(
                "for t in" to "systemctl\njournalctl\n",
                "date +%z" to "+0300\n--rd-section--\n" +
                    """{"__CURSOR":"s=1;i=2","__REALTIME_TIMESTAMP":"1792766506000000","PRIORITY":"6","SYSLOG_FACILITY":"3","SYSLOG_IDENTIFIER":"sshd","MESSAGE":"Accepted publickey for rd-test"}""" + "\n" +
                    """{"__CURSOR":"s=1;i=3","__REALTIME_TIMESTAMP":"1792766507000000","PRIORITY":"3","MESSAGE":[208,159,209,128,208,184]}""" + "\n",
                "systemctl list-units" to "ssh.service loaded active running OpenBSD Secure Shell server\nrd-test.service masked inactive dead rd-test\n",
            ),
        )
        val session = SshHostSession(host, device, linux = true)
        val log = session.rows("log")
        assertEquals(listOf("s=1;i=2", "s=1;i=3"), log.map { it.id })
        assertEquals(listOf("2026-10-23 17:41:46", "daemon.info", "sshd: Accepted publickey for rd-test"), listOf("time", "topics", "message").map { log[0][it] })
        assertEquals(listOf("err", "При"), listOf("topics", "message").map { log[1][it] })
        val services = session.rows("system/service")
        assertEquals(listOf("ssh", "rd-test"), services.map { it["name"] })
        assertEquals(listOf("active (running)", "true", "false"), listOf("status", "running", "disabled").map { services[0][it] })
        assertEquals("true", services[1]["disabled"])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun monitorTrafficCountsWhatProcNetDevCounts() = runTest {
        val host = openWrt()
        val session = SshHostSession(host, device, linux = true, time = testScheduler.timeSource)
        val rounds = mutableListOf<Item>()
        backgroundScope.launch { session.run(MenuPath.parse("interface"), "monitor-traffic", mapOf("interface" to "eth0")).collect { rounds += it } }
        advanceTimeBy(500)
        host["cat /proc/net/dev"] = dev(125_220, 102)
        advanceTimeBy(1_000)
        assertEquals(listOf("eth0", "1000000", "100", "0"), listOf("name", "rx-bits-per-second", "rx-packets-per-second", "tx-packets-per-second").map { rounds.single()[it] })
    }

    @Test
    fun busyboxIpIsReadByItsText() = runTest {
        val host = Host(
            listOf(
                "ip -j link show lo" to "BusyBox v1.36.1 (2026-07-24 07:21:50 UTC) multi-call binary.\n",
                "for d in /sys/class/net" to "eth0|2|1|0x1003|1500|up|00:00:5e:00:53:03|220|30530|2|225|0|0|0|0|br-lan||\n" +
                    "lo|1|772|0x9|65536|unknown|00:00:00:00:00:00|10|10|1|1|0|0|0|0|||\nwg0|5|65534|0x90|1420|down||0|0|0|0|0|0|0|0||wireguard|rd-test vpn\n",
                "ip -o -4 addr show" to "1: lo    inet 127.0.0.1/8 scope host lo\\       valid_lft forever preferred_lft forever\n" +
                    "3: eth1    inet 192.0.2.74/24 brd 192.0.2.255 scope global eth1\\       valid_lft forever preferred_lft forever\n",
                "ip route show table all" to "default via 192.0.2.1 dev eth1  src 192.0.2.74 \n192.0.2.0/24 dev eth1 scope link  src 192.0.2.74 \n",
                "ip -6 route show table all" to "unreachable fdb4:785f:cce2::/48 dev lo  metric 2147483647 \nanycast fe80:: dev eth0  metric 0 \n",
                "ip -4 neigh show" to "192.0.2.1 dev eth1 lladdr 00:00:5e:00:53:01 ref 1 used 0/0/0 probes 1 REACHABLE\n192.0.2.9 dev eth1  FAILED\n",
            ),
        )
        val session = SshHostSession(host, device, linux = true)
        val interfaces = session.rows("interface").associateBy { it["name"] }
        assertEquals(listOf("ether", "00:00:5E:00:53:03", "true", "false", "br-lan", "220"), listOf("type", "mac-address", "running", "disabled", "master", "rx-byte").map { interfaces.getValue("eth0")[it] })
        assertEquals(listOf("loopback", "true"), listOf("type", "running").map { interfaces.getValue("lo")[it] })
        assertEquals(listOf("wg", "false", "true", "rd-test vpn", ""), listOf("type", "running", "disabled", "comment", "mac-address").map { interfaces.getValue("wg0")[it] })

        val addresses = session.rows("ip/address").associateBy { it["address"] }
        assertEquals(listOf("192.0.2.0", "eth1", "global"), listOf("network", "interface", "scope").map { addresses.getValue("192.0.2.74/24")[it] })
        assertEquals("lo", addresses.getValue("127.0.0.1/8")["interface"])

        val routes = session.rows("ip/route").associateBy { it["dst-address"] }
        assertEquals(listOf("192.0.2.1", "eth1", "192.0.2.74", "true"), listOf("gateway", "interface", "pref-src", "static").map { routes.getValue("0.0.0.0/0")[it] })
        assertEquals(listOf("true", "true"), listOf("connect", "dynamic").map { routes.getValue("192.0.2.0/24")[it] })
        assertEquals(listOf("fdb4:785f:cce2::/48"), session.rows("ipv6/route").map { it["dst-address"] })

        val arp = session.rows("ip/arp")
        assertEquals(listOf("00:00:5E:00:53:01", "reachable"), listOf("mac-address", "status").map { arp[0][it] })
        assertEquals(listOf("", "failed", "false"), listOf("mac-address", "status", "complete").map { arp[1][it] })
    }

    @Test
    fun aHostThatIsNotLinuxHasOnlyItsFiles() = runTest {
        val session = SshHostSession(openWrt(), DeviceInfo(Platform.Ssh, "rd-test", "Darwin 25.0.0", "arm64", "", emptySet(), emptySet()))
        assertFailsWith<DeviceException> { session.describe(MenuPath.parse("interface")) }
        assertEquals(listOf("file"), session.describe(MenuPath.Root).children)
    }

    @Test
    fun theFilesOfTheHomeAndOfTmpComeOverSftp() = runTest {
        val removed = mutableListOf<String>()
        val sftp = object : SftpClient {
            override suspend fun list(path: String) = when (path) {
                "/home/rd-test" -> listOf(RemoteFile("notes.txt", 12, false, 1_800_000_000), RemoteFile("backups", 4096, true, 1_800_000_000))
                "/tmp" -> listOf(RemoteFile("rd-test.bin", 3, false, 1_800_000_000))
                else -> error("no $path")
            }
            override suspend fun read(path: String) = ByteArray(0)
            override suspend fun write(path: String, bytes: ByteArray) = Unit
            override suspend fun remove(path: String) {
                removed += path
            }
            override fun close() = Unit
        }
        val host = object : SshSession by Host(listOf("pwd" to "/home/rd-test\n+0300\n")) {
            override suspend fun sftp() = sftp
        }
        val session = SshHostSession(host, device, linux = true)
        assertEquals(listOf("print", "remove"), session.describe(MenuPath.parse("file")).commands)
        val files = session.rows("file")
        assertEquals(listOf("home/rd-test", "home/rd-test/notes.txt", "home/rd-test/backups", "tmp", "tmp/rd-test.bin"), files.map { it.id })
        assertEquals(listOf("file", "12", "2027-01-15 11:00:00"), listOf("type", "size", "last-modified").map { files[1][it] })
        assertEquals(listOf("directory", "directory"), listOf(files[0]["type"], files[2]["type"]))

        session.remove(MenuPath.parse("file"), listOf("tmp/rd-test.bin"))
        assertEquals(listOf("/tmp/rd-test.bin"), removed)
        assertFailsWith<DeviceException> { session.remove(MenuPath.parse("system/process"), listOf("1")) }
    }

    @Test
    fun aChannelThatRunsNoCommandListsTheFilesFromTheRoot() = runTest {
        val removed = mutableListOf<String>()
        val sftp = object : SftpClient {
            override suspend fun list(path: String) = when (path) {
                "/" -> listOf(RemoteFile("backup.rsc", 12, false, 1_800_000_000), RemoteFile("pub", 0, true, 1_800_000_000))
                "/pub" -> listOf(RemoteFile("skin.json", 3, false, 1_800_000_060))
                else -> error("no $path")
            }
            override suspend fun read(path: String) = ByteArray(0)
            override suspend fun write(path: String, bytes: ByteArray) = Unit
            override suspend fun remove(path: String) {
                removed += path
            }
            override fun close() = Unit
        }
        val host = object : SshSession by Host(emptyList()) {
            override suspend fun exec(command: String): ExecResult = throw DeviceException(ErrorCategory.Unsupported, "no commands")
            override suspend fun sftp() = sftp
        }
        val session = SshHostSession(host, device)
        val files = session.rows("file")
        assertEquals(listOf("backup.rsc", "pub", "pub/skin.json"), files.map { it.id })
        assertEquals(listOf("file", "12", "2027-01-15 08:00:00"), listOf("type", "size", "last-modified").map { files[0][it] })
        assertEquals("directory", files[1]["type"])
        assertEquals(listOf("pub/skin.json", "file", "2027-01-15 08:01:00"), listOf("name", "type", "last-modified").map { files[2][it] })

        session.remove(MenuPath.parse("file"), listOf("pub/skin.json"))
        assertEquals(listOf("/pub/skin.json"), removed)
    }

    @Test
    fun aFailedCommandIsNotAChannelWithoutCommands() = runTest {
        val host = object : SshSession by Host(emptyList()) {
            override suspend fun exec(command: String): ExecResult = throw DeviceException(ErrorCategory.ConnectionLost, "reset")
        }
        val session = SshHostSession(host, device)
        assertEquals(ErrorCategory.ConnectionLost, assertFailsWith<DeviceException> { session.rows("file") }.category)
    }
}
