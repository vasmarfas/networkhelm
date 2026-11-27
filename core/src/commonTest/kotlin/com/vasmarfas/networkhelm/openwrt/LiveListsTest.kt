package com.vasmarfas.networkhelm.openwrt

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.NodeKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class LiveListsTest {

    private class Router : Ubus {
        val calls = mutableListOf<String>()
        val files = mutableMapOf(
            "/tmp/dhcp.leases" to "1792679000 aa:bb:cc:00:00:01 192.168.99.100 laptop 01:aa:bb:cc:00:00:01\n0 aa:bb:cc:00:00:02 192.168.99.101 * *\n",
            "/proc/net/arp" to "IP address       HW type     Flags       HW address            Mask     Device\n" +
                "192.0.2.1        0x1         0x2         aa:bb:cc:00:00:03     *        eth1\n",
            "/proc/net/nf_conntrack" to
                "ipv4     2 tcp      6 300 ESTABLISHED src=192.0.2.10 dst=192.168.99.1 sport=26760 dport=22 packets=10 bytes=2012 " +
                "src=192.168.99.1 dst=192.0.2.10 sport=22 dport=26760 packets=13 bytes=1534 [ASSURED] mark=0 zone=0 use=2\n" +
                "ipv4     2 icmp     1 11 src=192.0.2.20 dst=192.168.99.1 type=8 code=0 id=55808 packets=2 bytes=112 " +
                "src=192.168.99.1 dst=192.0.2.20 type=0 code=0 id=55808 packets=2 bytes=112 mark=0 zone=0 use=2\n" +
                "ipv4     2 icmp     1 11 src=192.0.2.20 dst=192.168.99.1 type=8 code=0 id=55808 packets=2 bytes=112 " +
                "src=192.168.99.1 dst=192.0.2.20 type=0 code=0 id=55808 packets=2 bytes=112 mark=0 zone=1 use=2\n",
            "/etc/crontabs/root" to "MAILTO=\"\"\n# nightly\n0 3 * * * /sbin/reboot\n",
        )

        var before2512 = false

        override suspend fun call(obj: String, method: String, args: JsonObject): JsonObject {
            calls += "$obj $method $args"
            val answer = when ("$obj $method") {
                "file read" -> {
                    val data = files[args.getValue("path").jsonPrimitive.content] ?: throw UbusException(UbusStatus.NOT_FOUND)
                    return JsonObject(mapOf("data" to JsonPrimitive(data)))
                }
                "file write" -> {
                    files[args.getValue("path").jsonPrimitive.content] = args.getValue("data").jsonPrimitive.content
                    "{}"
                }
                "file exec" -> {
                    val params = args["params"].toString()
                    val stdout = when {
                        "neigh" in params -> "fe80::1 dev br-lan lladdr aa:bb:cc:00:00:05 router STALE\nfe80::2 dev eth1 FAILED\n"
                        "\"-4\",\"route\"" in params -> "default via 192.0.2.1 dev eth1  src 192.0.2.10 \n192.168.99.0/24 dev br-lan scope link  src 192.168.99.1 \n" +
                            "local 127.0.0.1 dev lo table local scope host  src 127.0.0.1 \n"
                        "\"-6\",\"route\"" in params -> "unreachable fd12:3456:789a::/48 dev lo proto static metric 2147483647 pref medium\nfe80::/64 dev br-lan proto kernel metric 256 pref medium\n"
                        "\"top\"" in params -> "Mem: 72764K used, 420840K free, 1316K shrd, 2208K buff, 21600K cached\nCPU:   0% usr   9% sys   0% nic  90% idle   0% io   0% irq   0% sirq\n" +
                            "Load average: 0.00 0.00 0.00 1/73 1983\n  PID  PPID USER     STAT   VSZ %VSZ %CPU COMMAND\n 1180     1 root     S     1144   0%   0% /usr/sbin/dropbear -F -P /var/run/dropbear.1.pid\n"
                        "\"-r\"" in params -> "<6>[    0.000000] Linux version 6.12.40\n<4>[  620.707061] 8021q: adding VLAN 0 to HW filter on device eth1\n"
                        else -> ""
                    }
                    JsonObject(mapOf("code" to JsonPrimitive(0), "stdout" to JsonPrimitive(stdout))).toString()
                }
                "luci getConntrackList" -> """{"result":[{"bytes":2012,"packets":10,"layer3":"ipv4","layer4":"tcp","timeout":300,"src":"192.0.2.10","dst":"192.168.99.1",""" +
                    """"sport":26760,"dport":22,"mark":0,"zone":0,"use":2}]}"""
                "file list" -> if (args.getValue("path").jsonPrimitive.content != "/") """{"entries":[]}""" else """{"entries":[""" +
                    """{"name":"etc","type":"directory","size":4096,"mtime":1792675200},{"name":"var","type":"symlink","size":3,"mtime":1792675200},""" +
                    """{"name":"tmp","type":"directory","size":360,"mtime":1792675200}]}"""
                "system validate_firmware_image" -> """{"valid":${"openwrt" in args.toString()},"forceable":true}"""
                "system info" -> """{"localtime":1792686061,"uptime":93784,"load":[3276,1,0],"memory":{"total":1033998336,"available":932155392},""" +
                    """"root":{"total":100000,"avail":75000}}"""
                "luci getUnixtime" -> if (before2512) throw UbusException(UbusStatus.METHOD_NOT_FOUND) else """{"result":1792675260}"""
                "luci getLocaltime" -> if (before2512) """{"result":1792675260}""" else throw UbusException(UbusStatus.METHOD_NOT_FOUND)
                "uci get" -> """{"values":{"cfg01411c":{".anonymous":true,".type":"dnsmasq",".name":"cfg01411c",".index":0,"leasefile":"/tmp/dhcp.leases"}}}"""
                "log read" -> """{"log":[{"msg":"dropbear[18498]: Child connection from 192.0.2.10:26760","id":1619,"priority":86,"source":1,"time":1792675255767}]}"""
                "network.interface dump" -> """{"interface":[{"interface":"lan","l3_device":"br-lan","ipv4-address":[{"address":"192.168.99.1","mask":24}],""" +
                    """"ipv6-address":[],"ipv6-prefix-assignment":[{"address":"fd12:3456:789a::","mask":60,"local-address":{"address":"fd12:3456:789a::1","mask":60}}]}]}"""
                "rc list" -> """{"dropbear":{"start":19,"stop":50,"enabled":true,"running":true},"boot":{"start":10,"stop":90,"enabled":true}}"""
                "rpc-sys packagelist" -> """{"packages":{"dnsmasq":"2.93-r1","dropbear":"2025.89-r3"}}"""
                "luci.ddns get_services_status" -> """{"myddns_ipv4":{"ip":"192.0.2.7","last_update":"2026-10-22 17:35","next_update":"Verify","next_check":null,"pid":1234}}"""
                "luci.upnp get_status" -> """{"rules":[{"num":"1","proto":"UDP","extport":51413,"intaddr":"192.168.99.100","intport":51413,"descr":"Transmission",""" +
                    """"expires":0},{"num":"2","proto":"TCP","extport":3074,"intaddr":"192.168.99.101","intport":3074,"descr":"Game","expires":3725,"host_hint":"console"}]}"""
                "luci.wireguard getWgInstances" -> """{"wg0":{"name":"wg0","public_key":"own","listen_port":"51820","fwmark":"off","peers":[{"name":"phone",""" +
                    """"public_key":"phonekey","endpoint":"192.0.2.30:41000","allowed_ips":["10.0.0.2/32"],"latest_handshake":"1792675200","transfer_rx":"1000",""" +
                    """"transfer_tx":"2000","persistent_keepalive":"25"},{"public_key":"idlekey","endpoint":"(none)","allowed_ips":[],"latest_handshake":"0",""" +
                    """"transfer_rx":"0","transfer_tx":"0","persistent_keepalive":"off"}]}}"""
                "luci-rpc getDHCPLeases" -> """{"dhcp_leases":[],"dhcp6_leases":[{"expires":3725,"hostname":"laptop","duid":"000100012a3b4c5d","iaid":"1",""" +
                    """"ip6addrs":["fd12:3456:789a::10/128"],"interface":"br-lan"},{"expires":false,"duid":"0004ffff","ip6addr":"fd12:3456:789a::11/128"}]}"""
                "dhcp ipv6leases" -> """{"device":{"br-lan":{"leases":[{"duid":"000100012a3b4c5d","iaid":1,"hostname":"-","valid":7200,"ipv6-addr":[{"address":"fd12:3456:789a::10"}]}]}}}"""
                "hostapd.phy0-ap0 get_clients" -> """{"freq":2412,"clients":{"aa:bb:cc:00:00:09":{"authorized":true,"signal":-48,"bytes":{"rx":1000,"tx":2000}}}}"""
                "iwinfo assoclist" -> """{"results":[{"mac":"AA:BB:CC:00:00:09","signal":-48,"connected_time":125,"inactive":10}]}"""
                else -> "{}"
            }
            return Json.parseToJsonElement(answer).jsonObject
        }

        override suspend fun objects() = emptySet<String>()

        override fun listen(vararg events: String): Flow<JsonObject> = emptyFlow()

        var subscriptions = true

        override fun subscribe(obj: String): Flow<JsonObject> = flow {
            if (!subscriptions) throw UbusException(UbusStatus.PERMISSION_DENIED)
            if (obj == "log") emit(Json.parseToJsonElement("""{"message":{"msg":"rd-test: follows","id":1620,"priority":13,"source":1,"time":1792675256000}}""").jsonObject)
        }
    }

    private val router = Router()
    private val board = Json.parseToJsonElement(
        """{"kernel":"6.12.40","hostname":"OpenWrt","system":"QEMU Virtual CPU version 2.5+","model":"QEMU Standard PC (i440FX + PIIX, 1996)",""" +
            """"release":{"version":"25.12.6","revision":"r32202-0f3c4d1b6e","target":"x86/64"}}""",
    ).jsonObject
    private val empty = JsonObject(emptyMap())
    private val lists = LiveLists(router, RpcdUci(router), OpenWrtFacts(board, empty, empty, setOf("file", "luci", "rpc-sys", "hostapd.phy0-ap0", "iwinfo"), emptySet(), emptySet(), true))

    @Test
    fun theLogAndTheLeasesAreInTheLocalTimeOfTheRouter() = runTest {
        val line = lists.read(MenuPath.parse("status/log")).single()
        assertEquals("1619", line.id)
        assertEquals("2026-10-22 16:20:55", line["time"])
        assertEquals("authpriv.info", line["topics"])
        val (laptop, phone) = lists.read(MenuPath.parse("status/dhcp-leases"))
        assertEquals("2026-10-22 17:23:20", laptop["expires"])
        assertEquals("laptop", laptop["host-name"])
        assertNull(phone["expires"], "a lease of 0 never expires")
        assertEquals("*", phone["host-name"])
    }

    @Test
    fun theTablesOfTheKernelAndNetifdAreRead() = runTest {
        val bare = LiveLists(router, RpcdUci(router), OpenWrtFacts(board, empty, empty, setOf("file"), emptySet(), emptySet(), true))
        val (tcp, ping, again) = bare.read(MenuPath.parse("status/connections"))
        assertEquals("tcp 192.0.2.10 26760 192.168.99.1 22", tcp.id)
        assertEquals(listOf("ESTABLISHED", "ASSURED", "192.168.99.1", "1534", "0"), listOf(tcp["tcp-state"], tcp["flags"], tcp["reply-src-address"], tcp["reply-bytes"], tcp["connection-mark"]))
        assertNull(ping["tcp-state"])
        assertEquals("0", ping["reply-type"])
        assertEquals(ping.id + " #2", again.id)
        assertEquals("aa:bb:cc:00:00:03", lists.read(MenuPath.parse("status/neighbors")).single()["mac-address"])
        assertEquals(listOf("192.168.99.1/24", "fd12:3456:789a::1/60"), lists.read(MenuPath.parse("status/addresses")).map { it["address"] })
        val (gateway, failed) = lists.read(MenuPath.parse("status/neighbors6"))
        assertEquals(listOf("aa:bb:cc:00:00:05", "br-lan", "STALE", "true"), listOf(gateway["mac-address"], gateway["interface"], gateway["status"], gateway["router"]))
        assertEquals(listOf(null, "FAILED"), listOf(failed["mac-address"], failed["status"]))
        val flow = lists.read(MenuPath.parse("status/connections")).single()
        assertEquals(listOf("tcp 192.0.2.10 26760 192.168.99.1 22", "ipv4", "0"), listOf(flow.id, flow["family"], flow["connection-mark"]))
    }

    @Test
    fun theStateOfApplicationsIsListedWhereTheyAreInstalled() = runTest {
        assertFalse(lists.has(MenuPath.parse("status/ddns")))
        val apps = LiveLists(router, RpcdUci(router), OpenWrtFacts(board, empty, empty, setOf("file", "luci", "luci.ddns", "luci.upnp", "luci.wireguard"), emptySet(), emptySet(), true))
        assertEquals(
            mapOf("name" to "myddns_ipv4", "ip" to "192.0.2.7", "last-update" to "2026-10-22 17:35", "next-update" to "Verify", "pid" to "1234"),
            apps.read(MenuPath.parse("status/ddns")).single().values,
        )
        val (torrent, game) = apps.read(MenuPath.parse("status/upnp"))
        assertEquals(listOf("192.168.99.100", "51413", "UDP", null, null), listOf(torrent["address"], torrent["external-port"], torrent["protocol"], torrent["expires"], torrent["client"]))
        assertEquals(listOf("2", "console", "1h2m5s"), listOf(game.id, game["client"], game["expires"]))
        apps.remove(MenuPath.parse("status/upnp"), listOf("1", "2"))
        assertEquals(listOf("luci.upnp delete_rule {\"token\":\"2\"}", "luci.upnp delete_rule {\"token\":\"1\"}"), router.calls.filter { it.startsWith("luci.upnp delete") })
        val (phone, idle) = apps.read(MenuPath.parse("status/wireguard"))
        assertEquals("wg0 phonekey", phone.id)
        assertEquals(
            listOf("phone", "192.0.2.30:41000", "10.0.0.2/32", "2026-10-22 16:20:00", "1000", "25"),
            listOf(phone["name"], phone["endpoint"], phone["allowed-address"], phone["last-handshake"], phone["rx"], phone["persistent-keepalive"]),
        )
        assertEquals(listOf(null, null, "0", null), listOf(idle["endpoint"], idle["allowed-address"], idle["last-handshake"], idle["persistent-keepalive"]))
    }

    @Test
    fun theLeasesOfDhcpv6AndThePrefixesAreRead() = runTest {
        val luci = LiveLists(router, RpcdUci(router), OpenWrtFacts(board, empty, empty, setOf("file", "luci", "luci-rpc"), emptySet(), emptySet(), true))
        val (laptop, other) = luci.read(MenuPath.parse("status/dhcp6-leases"))
        assertEquals(listOf("br-lan 000100012a3b4c5d 1", "fd12:3456:789a::10/128", "laptop", "1h2m5s"), listOf(laptop.id, laptop["address"], laptop["host-name"], laptop["life-time"]))
        assertEquals(listOf("fd12:3456:789a::11/128", null, null), listOf(other["address"], other["host-name"], other["life-time"]))
        val bare = LiveLists(router, RpcdUci(router), OpenWrtFacts(board, empty, empty, setOf("file", "dhcp"), emptySet(), emptySet(), true))
        val odhcpd = bare.read(MenuPath.parse("status/dhcp6-leases")).single()
        assertEquals(listOf("br-lan", "000100012a3b4c5d", "1", "fd12:3456:789a::10", "2h", null), listOf(odhcpd["interface"], odhcpd["duid"], odhcpd["iaid"], odhcpd["address"], odhcpd["life-time"], odhcpd["host-name"]))
        assertFalse(lists.has(MenuPath.parse("status/dhcp6-leases")), "a router without odhcpd and LuCI has none")
        assertEquals(listOf("fd12:3456:789a::/60"), lists.read(MenuPath.parse("status/prefixes")).map { it["prefix"] })
    }

    @Test
    fun servicesAreStartedByName() = runTest {
        val services = MenuPath.parse("status/services")
        assertEquals("true", lists.read(services).first { it.id == "dropbear" }["running"])
        lists.run(services, "restart", mapOf("numbers" to "dropbear,uhttpd"))
        assertEquals(
            listOf("rc init {\"name\":\"dropbear\",\"action\":\"restart\"}", "rc init {\"name\":\"uhttpd\",\"action\":\"restart\"}"),
            router.calls.filter { it.startsWith("rc init") },
        )
        assertEquals(ErrorCategory.Unsupported, assertFailsWith<DeviceException> { lists.add(services, mapOf("name" to "x")) }.category)
    }

    @Test
    fun aTaskOfCronIsItsLineAndTheRestOfTheFileStays() = runTest {
        val scheduler = MenuPath.parse("status/scheduler")
        assertEquals(listOf("0 3 * * * /sbin/reboot"), lists.read(scheduler).map { it.id })
        val added = lists.add(scheduler, mapOf("minute" to "30", "command" to "logger rd-test"))
        assertEquals("30 * * * * logger rd-test", added)
        lists.set(scheduler, added, mapOf("hour" to "4"))
        lists.remove(scheduler, listOf("0 3 * * * /sbin/reboot"))
        assertEquals("MAILTO=\"\"\n# nightly\n30 4 * * * logger rd-test\n", router.files["/etc/crontabs/root"])
        assertTrue(router.calls.last().startsWith("rc init {\"name\":\"cron\""), router.calls.last())
        val split = assertFailsWith<DeviceException> { lists.add(scheduler, mapOf("command" to "logger a\n* * * * * reboot")) }
        assertEquals(ErrorCategory.ArgumentValue, split.category)
    }

    @Test
    fun packagesAreInstalledByNameAndResourcesReadAsRouterOsWritesThem() = runTest {
        val packages = MenuPath.parse("status/packages")
        assertEquals(listOf("print", "add", "remove"), lists.schema(packages)?.commands)
        assertEquals("2.93-r1", lists.read(packages).first { it.id == "dnsmasq" }["version"])
        lists.add(packages, mapOf("name" to "tcpdump"))
        assertEquals(
            listOf("[\"update\"]", "[\"install\",\"tcpdump\"]"),
            router.calls.filter { it.startsWith("file exec") }.map { Json.parseToJsonElement(it.substringAfter("file exec ")).jsonObject["params"].toString() },
        )
        val resource = MenuPath.parse("status/resource")
        assertEquals(NodeKind.Settings, lists.schema(resource)?.kind)
        val values = lists.read(resource).single()
        assertEquals(
            listOf("1d2h3m4s", "25.12.6", "x86/64", "0.04", "932155392", "102400000", "76800000", "2026-10-22", "16:21:01"),
            listOf("uptime", "version", "architecture-name", "load", "free-memory", "total-hdd-space", "free-hdd-space", "date", "time").map { values[it] },
        )
    }

    @Test
    fun theKeysOfRootAreTheLinesOfTheirFile() = runTest {
        fun string(bytes: ByteArray) = byteArrayOf(0, 0, (bytes.size shr 8).toByte(), bytes.size.toByte()) + bytes
        val ed25519 = "ssh-ed25519 " + Base64.encode(string("ssh-ed25519".encodeToByteArray()) + string(ByteArray(32) { it.toByte() }))
        val rsa = "ssh-rsa " + Base64.encode(string("ssh-rsa".encodeToByteArray()) + string(byteArrayOf(1, 0, 1)) + string(byteArrayOf(0, 0x80.toByte()) + ByteArray(255)))
        val keys = MenuPath.parse("status/ssh-keys")
        router.files["/etc/dropbear/authorized_keys"] = "$ed25519 laptop\n# old\n$rsa\n"
        val (laptop, other) = lists.read(keys)
        assertEquals(listOf("root", "ssh-ed25519", "256", "laptop"), listOf(laptop["user"], laptop["key-type"], laptop["bits"], laptop["key-owner"]))
        assertEquals(listOf("ssh-rsa", "2048", null), listOf(other["key-type"], other["bits"], other["key-owner"]))
        lists.add(keys, mapOf("key" to "$ed25519 phone"))
        lists.remove(keys, listOf(laptop.id))
        assertEquals("# old\n$rsa\n$ed25519 phone\n", router.files["/etc/dropbear/authorized_keys"])
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { lists.add(keys, mapOf("user" to "admin", "key" to "$ed25519 admin")) }.category)
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { lists.add(keys, mapOf("key" to "not a key")) }.category)
    }

    @Test
    fun theClockIsSetToThatOfThisDevice() = runTest {
        val resource = MenuPath.parse("status/resource")
        assertTrue("sync-time" in lists.schema(resource)?.commands.orEmpty())
        val before = Clock.System.now().epochSeconds
        lists.run(resource, "sync-time", emptyMap())
        val set = router.calls.single { it.startsWith("luci setLocaltime") }
        val sent = Json.parseToJsonElement(set.substringAfter("luci setLocaltime ")).jsonObject.getValue("localtime").jsonPrimitive.long
        assertTrue(sent in before..Clock.System.now().epochSeconds, set)
    }

    @Test
    fun aRouterOf2410GivesItsTimeAndPackagesTheOldWay() = runTest {
        router.before2512 = true
        router.files["/usr/lib/opkg/status"] = """
            Package: dnsmasq
            Version: 2.93-r1
            Depends: libc
            Status: install ok installed

            Package: kmod-drm
            Version: 6.6.144-r1
            Status: install ok installed
            Auto-Installed: yes

            Package: tcpdump
            Version: 4.99.5-r1
            Status: deinstall ok not-installed
        """.trimIndent()
        val old = LiveLists(router, RpcdUci(router), OpenWrtFacts(board, empty, empty, setOf("file", "luci"), emptySet(), emptySet(), true))
        assertEquals("2026-10-22 16:20:55", old.read(MenuPath.parse("status/log")).single()["time"])
        assertEquals(listOf("dnsmasq" to "2.93-r1", "kmod-drm" to "6.6.144-r1"), old.read(MenuPath.parse("status/packages")).map { it.id to it["version"] })
    }

    @Test
    fun theRoutesProcessesAndLogOfTheKernelAreRead() = runTest {
        val bare = LiveLists(router, RpcdUci(router), OpenWrtFacts(board, empty, empty, setOf("file"), emptySet(), emptySet(), true))
        val routes = bare.read(MenuPath.parse("status/routes")).associateBy { it["dst-address"] }
        assertEquals(listOf("unicast", "192.0.2.1", "eth1", "192.0.2.10", "main"), routes["default"]?.let { listOf(it["type"], it["gateway"], it["interface"], it["pref-src"], it["table"]) })
        assertEquals(listOf("local", "local", "lo"), routes["127.0.0.1"]?.let { listOf(it["type"], it["table"], it["interface"]) })
        val unreachable = bare.read(MenuPath.parse("status/routes6")).first()
        assertEquals(listOf("unreachable", "fd12:3456:789a::/48", "2147483647", "static"), listOf(unreachable["type"], unreachable["dst-address"], unreachable["distance"], unreachable["protocol"]))
        val dropbear = bare.read(MenuPath.parse("status/processes")).single()
        assertEquals(listOf("1180", "1", "root", "S", "0%", "/usr/sbin/dropbear -F -P /var/run/dropbear.1.pid"), listOf(dropbear.id, dropbear["ppid"], dropbear["user"], dropbear["stat"], dropbear["cpu"], dropbear["command"]))
        bare.run(MenuPath.parse("status/processes"), "kill", mapOf("numbers" to "1180"))
        assertTrue("\"/bin/kill\"" in router.calls.last() && "[\"-KILL\",\"1180\"]" in router.calls.last(), router.calls.last())
        val (boot, vlan) = bare.read(MenuPath.parse("status/kernel-log"))
        assertEquals(listOf("kern.info", "0.000000", "Linux version 6.12.40"), listOf(boot["topics"], boot["time"], boot["message"]))
        assertEquals("kern.warn", vlan["topics"])
    }

    @Test
    fun theDatabaseOfApkGivesNamesAndVersions() {
        val database = """
            C:Q1Md/LRJ=
            P:adblock
            V:4.5.9-r4
            A:noarch
            T:blocks domains, see https://example.org
            F:etc
            R:adblock

            C:Q1x=
            P:dnsmasq
            V:2.93-r1
        """.trimIndent()
        assertEquals(mapOf("adblock" to "4.5.9-r4", "dnsmasq" to "2.93-r1"), packageDatabase(database))
    }

    @Test
    fun theLogFollowsWhatLogdTellsAndFallsBackToReadingIt() = runTest {
        val log = MenuPath.parse("status/log")
        val followed = assertNotNull(lists.follow(log) { emptyFlow() }).toList()
        assertEquals(listOf("1619"), (followed.first() as ListEvent.Snapshot).items.map { it.id })
        assertEquals("rd-test: follows", (followed.last() as ListEvent.Upsert).item["message"])
        router.subscriptions = false
        val polled = assertNotNull(lists.follow(log) { flowOf(ListEvent.Snapshot(emptyList())) }).toList()
        assertEquals(listOf(ListEvent.Snapshot(emptyList())), polled.drop(1))
    }

    @Test
    fun theStateOfInterfacesAndTheClientsOfHostapd() = runTest {
        val lan = lists.read(MenuPath.parse("status/interfaces")).single()
        assertEquals(listOf("lan", "192.168.99.1/24", "fd12:3456:789a::1/60"), listOf(lan["name"], lan["ipv4-address"], lan["ipv6-address"]))
        val clients = MenuPath.parse("status/wireless-clients")
        val station = lists.read(clients).single()
        assertEquals(listOf("phy0-ap0 aa:bb:cc:00:00:09", "-48", "2m5s"), listOf(station.id, station["signal"], station["connected-time"]))
        lists.run(clients, "disconnect", mapOf("numbers" to station.id))
        assertEquals(
            "hostapd.phy0-ap0 del_client {\"addr\":\"aa:bb:cc:00:00:09\",\"reason\":5,\"deauth\":true,\"ban_time\":0}",
            router.calls.last(),
        )
        assertTrue(lists.watched(clients) && !lists.watched(MenuPath.parse("status/log")))
    }

    // the folders open from the root, and the link /var opens as the folder it leads to
    @Test
    fun theFilesStartAtTheRootOfTheRouter() = runTest {
        val root = lists.read(MenuPath.parse("status/files"))
        assertEquals(listOf("etc" to "directory", "var" to "directory", "tmp" to "directory"), root.map { it["name"] to it["type"] })
        assertEquals(listOf("etc", "var", "tmp"), root.map { it.id })
        assertEquals("file list {\"path\":\"/\"}", router.calls.first { it.startsWith("file list") })
    }

    @Test
    fun anImageIsWrittenOnlyOnceTheRouterFindsItFits() = runTest {
        val files = MenuPath.parse("status/files")
        lists.run(files, "upgrade", mapOf("numbers" to "tmp/openwrt-sysupgrade.bin"))
        assertEquals("file exec {\"command\":\"/sbin/sysupgrade\",\"params\":[\"/tmp/openwrt-sysupgrade.bin\"]}", router.calls.last())
        val refused = assertFailsWith<DeviceException> { lists.run(files, "upgrade", mapOf("numbers" to "tmp/notes.txt")) }
        assertEquals(ErrorCategory.ArgumentValue, refused.category)
        assertEquals(1, router.calls.count { it.startsWith("file exec") })
        assertFailsWith<DeviceException> { lists.run(files, "upgrade", mapOf("numbers" to "tmp/a.bin,tmp/b.bin")) }
    }
}
