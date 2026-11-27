package com.vasmarfas.networkhelm.openwrt

import com.vasmarfas.networkhelm.Lab
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.packs.packName
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.rows
import com.vasmarfas.networkhelm.ssh.SshAuth
import com.vasmarfas.networkhelm.ssh.connectSsh
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RouterOsLayerLabTest {

    private fun layer(block: suspend (RouterOsLayer, DeviceSession, Map<String, String>) -> Unit) =
        Lab.forEachOpenWrt("host", "user", "password", "ssh.hostKey", "lan", "version") { lab ->
            runBlocking {
                withTimeout(60.seconds) {
                    val ssh = connectSsh(
                        lab.getValue("host"), 22, lab.getValue("user"), SshAuth.Password(lab.getValue("password")),
                        acceptHostKey = { it.fingerprint == lab.getValue("ssh.hostKey") },
                    )
                    try {
                        val session = connectOpenWrt(ssh) { QuickJsForms(it, ssh) }
                        block(RouterOsLayer(session), session, lab)
                    } finally {
                        ssh.close()
                    }
                }
            }
        }

    private fun Map<String, String>.inLan(host: Int) = getValue("lan").substringBeforeLast('.') + ".$host"

    private suspend fun DeviceSession.rows(path: String) = watch(MenuPath.parse(path)).rows().first { it.isNotEmpty() }

    private suspend fun DeviceSession.has(path: String) = try {
        describe(MenuPath.parse(path))
        true
    } catch (e: DeviceException) {
        false
    }

    @Test
    fun theMenusOfRouterOsShowWhatTheRouterHas() = layer { layer, session, lab ->
        assertTrue(listOf("interface", "ip", "system", "log", "tool").all { it in layer.describe(MenuPath.Root).children })
        assertEquals(
            listOfNotNull("bridge", "l2tp-client", "list", "pppoe-client", "pptp-client", "vlan", "wireguard".takeIf { session.has("status/wireguard") }),
            layer.describe(MenuPath.parse("interface")).children.sorted(),
        )
        assertEquals(ErrorCategory.MissingItem, assertFailsWith<DeviceException> { layer.describe(MenuPath.parse("ip/hotspot")) }.category)
        assertTrue("eth0" in layer.describe(MenuPath.parse("ip/address")).argument("interface")?.choices.orEmpty())
        val addresses = layer.rows("ip/address")
        val lan = addresses.single { it["address"] == lab.getValue("lan") }
        assertEquals(listOf(lab.inLan(0), "eth0", null), listOf(lan["network"], lan["interface"], lan["dynamic"]))
        assertTrue(addresses.any { it["interface"] == "eth1" && it["dynamic"] == "true" }, addresses.map { "${it["interface"]} ${it["dynamic"]}" }.toString())
        val interfaces = layer.rows("interface").associateBy { it.id }
        assertEquals(listOf("ether", "true"), listOf(interfaces["eth0"]?.get("type"), interfaces["eth0"]?.get("running")))
        assertEquals("OpenWrt", layer.rows("system/identity").single()["name"])
        assertEquals(lab.getValue("version"), layer.rows("system/resource").single()["version"])
    }

    @Test
    fun rdTestObjectsGoIntoTheDraftInTermsOfRouterOs() = layer { layer, session, lab ->
        layer.add(MenuPath.parse("interface/bridge"), mapOf("name" to "rd-test-br", "protocol-mode" to "none"))
        assertEquals("false", layer.rows("interface/bridge").single { it["name"] == "rd-test-br" }["vlan-filtering"])
        layer.add(MenuPath.parse("interface/vlan"), mapOf("name" to "rd-test-vlan", "vlan-id" to "99", "interface" to "rd-test-br"))
        assertEquals(listOf("99", "rd-test-br"), layer.rows("interface/vlan").single { it["name"] == "rd-test-vlan" }.let { listOf(it["vlan-id"], it["interface"]) })
        val address = layer.add(MenuPath.parse("ip/address"), mapOf("address" to "10.255.255.1/30", "interface" to "rd-test-br"))
        assertEquals("rd_test_br/10.255.255.1/30", address)
        val owner = session.get(MenuPath.parse("network/interface"), "rd_test_br")
        assertEquals(listOf("static", "rd-test-br", "10.255.255.1/30"), listOf(owner?.get("proto"), owner?.get("device"), owner?.get("ipaddr")))
        assertEquals("10.255.255.0", layer.rows("ip/address").single { it.id == address }["network"])
        val route = layer.add(MenuPath.parse("ip/route"), mapOf("dst-address" to "10.255.254.0/24", "gateway" to "10.255.255.2", "distance" to "5"))
        val written = session.get(MenuPath.parse("network/route"), route)
        assertEquals(listOf("10.255.254.0/24", "10.255.255.2", "rd_test_br", "5"), listOf(written?.get("target"), written?.get("gateway"), written?.get("interface"), written?.get("metric")))
        assertEquals("main", layer.rows("ip/route").single { it.id == route }["routing-table"])
        layer.add(MenuPath.parse("ip/dns/static"), mapOf("name" to "rd-test.lan", "address" to "10.255.255.9"))
        assertTrue(layer.rows("ip/dns/static").any { it["name"] == "rd-test.lan" && it["address"] == "10.255.255.9" })
        val lease = layer.add(MenuPath.parse("ip/dhcp-server/lease"), mapOf("mac-address" to "AA:BB:CC:00:00:99", "address" to lab.inLan(250), "host-name" to "rd-test"))
        assertEquals("waiting", layer.rows("ip/dhcp-server/lease").single { it.id == lease }["status"])
        layer.remove(MenuPath.parse("ip/address"), listOf(address))
        assertEquals(null, session.get(MenuPath.parse("network/interface"), "rd_test_br")?.get("ipaddr"))
        assertTrue(layer.rows("ip/address").none { it["address"] == "10.255.255.1/30" })
        val dynamic = layer.rows("ip/address").first { it["dynamic"] == "true" }
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { layer.remove(MenuPath.parse("ip/address"), listOf(dynamic.id)) }.category)
    }

    @Test
    fun aWireGuardInterfaceAndItsPeerGoIntoTheDraft() = layer { layer, session, _ ->
        if (!session.has("status/wireguard")) return@layer
        val interfaces = MenuPath.parse("interface/wireguard")
        val peers = MenuPath.parse("interface/wireguard/peers")
        val id = layer.add(interfaces, mapOf("name" to "rd_test_wg", "listen-port" to "51899"))
        val added = layer.rows("interface/wireguard").single { it.id == id }
        assertEquals(listOf("rd_test_wg", "51899", "false"), listOf(added["name"], added["listen-port"], added["disabled"]))
        assertEquals(44, added["private-key"]?.length, "a key of 32 bytes in base64")
        assertEquals(null, added["public-key"], "wg reports the key only once the interface is up")
        val other = session.get(MenuPath.parse("status/wireguard-key"), null)?.get("public-key").orEmpty()
        val peer = layer.add(
            peers,
            mapOf("interface" to "rd_test_wg", "public-key" to other, "allowed-address" to "192.0.2.10/32,198.51.100.0/24", "persistent-keepalive" to "25s", "comment" to "rd-test"),
        )
        val section = session.get(MenuPath.parse("network/wireguard_rd_test_wg"), peer)
        assertEquals(listOf(other, "25", "rd-test"), listOf(section?.get("public_key"), section?.get("persistent_keepalive"), section?.get("description")))
        val shown = layer.rows("interface/wireguard/peers").single { it.id == peer }
        assertEquals(listOf("rd_test_wg", "192.0.2.10/32,198.51.100.0/24", "25s"), listOf(shown["interface"], shown["allowed-address"], shown["persistent-keepalive"]))
        layer.set(peers, peer, mapOf("persistent-keepalive" to "1m"))
        assertEquals("60s", layer.rows("interface/wireguard/peers").single { it.id == peer }["persistent-keepalive"])
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { layer.set(peers, peer, mapOf("interface" to "wan")) }.category)
        layer.remove(interfaces, listOf(id))
        assertTrue(layer.watch(peers).rows().drop(1).first().none { it["interface"] == "rd_test_wg" })
    }

    @Test
    fun aPppoeClientGoesIntoTheDraft() = layer { layer, session, _ ->
        layer.add(MenuPath.parse("interface/bridge"), mapOf("name" to "rd-test-pppbr", "protocol-mode" to "none"))
        val clients = MenuPath.parse("interface/pppoe-client")
        val id = layer.add(clients, mapOf("name" to "pppoe-rd_test_ppp", "interface" to "rd-test-pppbr", "user" to "rd-test", "default-route-distance" to "5"))
        val section = session.get(MenuPath.parse("network/interface"), id)
        assertEquals(
            listOf("rd_test_ppp", "pppoe", "rd-test-pppbr", "rd-test", "0", "0", "5"),
            listOf(id, section?.get("proto"), section?.get("device"), section?.get("username"), section?.get("defaultroute"), section?.get("peerdns"), section?.get("metric")),
        )
        val shown = layer.rows("interface/pppoe-client").single { it.id == id }
        assertEquals(listOf("pppoe-rd_test_ppp", "false", "false", "5"), listOf(shown["name"], shown["add-default-route"], shown["use-peer-dns"], shown["default-route-distance"]))
        assertEquals(listOf("pppoe-out", "false"), layer.rows("interface").single { it["name"] == "pppoe-rd_test_ppp" }.let { listOf(it["type"], it["running"]) })
        layer.set(clients, id, mapOf("add-default-route" to "true"))
        assertEquals(null, session.get(MenuPath.parse("network/interface"), id)?.get("defaultroute"))
        assertEquals("true", layer.watch(clients).rows().first { rows -> rows.any { it.id == id && it["add-default-route"] == "true" } }.single { it.id == id }["add-default-route"])
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { layer.set(clients, id, mapOf("name" to "pppoe-other")) }.category)
        layer.remove(clients, listOf(id))
        assertTrue(layer.watch(clients).rows().drop(1).first().none { it.id == id })
    }

    @Test
    fun theAdvertisementsOfOdhcpdAreNdOfRouterOs() = layer { layer, session, _ ->
        val entries = layer.rows("ipv6/nd").associateBy { it.id }
        val lan = entries.getValue("lan")
        assertEquals(
            listOf("eth0", "3m20s-10m", "30m", "medium", "true", "true", "false"),
            listOf(lan["interface"], lan["ra-interval"], lan["ra-lifetime"], lan["ra-preference"], lan["managed-address-configuration"], lan["other-configuration"], lan["disabled"]),
        )
        assertEquals("true", entries.getValue("wan")["disabled"])
        assertEquals(listOf("false", "true"), layer.rows("ipv6/dhcp-server").associateBy { it.id }.let { servers -> listOf("lan", "wan").map { servers.getValue(it)["disabled"] } })
        layer.set(MenuPath.parse("ipv6/nd"), "lan", mapOf("managed-address-configuration" to "false", "ra-interval" to "1m-5m"))
        val written = session.get(MenuPath.parse("dhcp/dhcp"), "lan")
        assertEquals(listOf("other-config", "60", "300"), listOf(written?.get("ra_flags"), written?.get("ra_mininterval"), written?.get("ra_maxinterval")))
    }

    @Test
    fun aScheduleIsATaskOfCron() = layer { layer, session, _ ->
        val schedules = MenuPath.parse("system/scheduler")
        val tasks = MenuPath.parse("status/scheduler")
        try {
            val id = layer.add(schedules, mapOf("start-time" to "03:30:00", "interval" to "1d", "days" to "mon,fri", "on-event" to "logger rd-test-schedule"))
            assertEquals("30 3 * * 1,5 logger rd-test-schedule", id)
            val monthly = session.add(tasks, mapOf("minute" to "0", "hour" to "4", "day" to "1", "command" to "logger rd-test-monthly"))
            val shown = layer.rows("system/scheduler").associateBy { it.id }
            assertEquals(listOf("03:30:00", "1d", "mon,fri", "cron 30 3 * * 1,5"), shown.getValue(id).let { listOf(it["start-time"], it["interval"], it["days"], it["comment"]) })
            assertEquals(listOf(null, null, "cron 0 4 1 * *"), shown.getValue(monthly).let { listOf(it["start-time"], it["interval"], it["comment"]) })
            layer.set(schedules, id, mapOf("start-time" to "00:00:00", "interval" to "15m"))
            val often = layer.rows("system/scheduler").single { it["on-event"] == "logger rd-test-schedule" }
            assertEquals(listOf("00:00:00", "15m", "mon,fri", "cron */15 * * * 1,5"), listOf(often["start-time"], often["interval"], often["days"], often["comment"]))
            val weekly = mapOf("start-time" to "03:30:00", "interval" to "7d", "on-event" to "logger rd-test-week")
            assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { layer.add(schedules, weekly) }.category)
        } finally {
            val ours = session.watch(tasks).rows().drop(1).first().filter { "rd-test-" in it["command"].orEmpty() }.map { it.id }
            if (ours.isNotEmpty()) session.remove(tasks, ours)
        }
    }

    @Test
    fun theLoginsOfRpcdAreUsersAndTheKeysOfRootSshKeys() = layer { layer, _, _ ->
        assertEquals("full", layer.rows("user").single { it["name"] == "root" }["group"])
        val keys = MenuPath.parse("user/ssh-keys")
        val before = layer.watch(keys).rows().drop(1).first().map { it.id }
        val id = layer.add(keys, mapOf("key" to "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhscHR4f rd-test-key"))
        try {
            val added = layer.rows("user/ssh-keys").single { it.id == id }
            assertEquals(listOf("root", "ssh-ed25519", "256", "rd-test-key"), listOf(added["user"], added["key-type"], added["bits"], added["key-owner"]))
        } finally {
            layer.remove(keys, listOf(id))
        }
        assertEquals(before, layer.watch(keys).rows().drop(1).first().map { it.id })
    }

    @Test
    fun anIpv6RouteGoesIntoTheDraft() = layer { layer, session, _ ->
        val routes = MenuPath.parse("ipv6/route")
        val id = layer.add(routes, mapOf("dst-address" to "2001:db8:ffff::/48", "gateway" to "fe80::1%eth0", "distance" to "5"))
        val written = session.get(MenuPath.parse("network/route6"), id)
        assertEquals(listOf("2001:db8:ffff::/48", "fe80::1", "lan", "5"), listOf(written?.get("target"), written?.get("gateway"), written?.get("interface"), written?.get("metric")))
        layer.set(routes, id, mapOf("gateway" to "eth0"))
        val direct = session.get(MenuPath.parse("network/route6"), id)
        assertEquals(listOf(null, "lan"), listOf(direct?.get("gateway"), direct?.get("interface")))
        assertEquals("eth0", layer.rows("ipv6/route").single { it.id == id }["gateway"])
        val dynamic = layer.rows("ipv6/route").first { it["dynamic"] == "true" }
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { layer.remove(routes, listOf(dynamic.id)) }.category)
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { layer.set(routes, id, mapOf("gateway" to "2001:db8:1::1")) }.category)
        layer.remove(routes, listOf(id))
        assertTrue(layer.watch(routes).rows().drop(1).first().none { it.id == id })
    }

    @Test
    fun theShapersOfSqmAreSimpleQueues() = layer { layer, session, _ ->
        if (!session.has("sqm/queue")) return@layer
        val queues = MenuPath.parse("queue/simple")
        val eth1 = layer.rows("queue/simple").single { it["name"] == "eth1" }
        assertEquals(listOf("eth1", "10M/85M", "cake/cake", "true"), listOf(eth1["target"], eth1["max-limit"], eth1["queue"], eth1["disabled"]))
        val id = layer.add(queues, mapOf("name" to "rd_test_q", "target" to "eth0", "max-limit" to "1500k/20M"))
        val section = session.get(MenuPath.parse("sqm/queue"), id)
        assertEquals(listOf("eth0", "1500", "20000", "1"), listOf(section?.get("interface"), section?.get("upload"), section?.get("download"), section?.get("enabled")))
        layer.set(queues, id, mapOf("max-limit" to "2M/1G", "disabled" to "true"))
        assertEquals(listOf("2M/1G", "true"), layer.rows("queue/simple").single { it.id == id }.let { listOf(it["max-limit"], it["disabled"]) })
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { layer.set(queues, id, mapOf("max-limit" to "20M")) }.category)
        layer.remove(queues, listOf(id))
        assertTrue(layer.watch(queues).rows().drop(1).first().none { it["name"] == "rd_test_q" })
    }

    @Test
    fun theFirewallReadsAsInterfaceListsAndRules() = layer { layer, session, lab ->
        val lists = layer.rows("interface/list").mapNotNull { it["name"] }
        assertTrue(listOf("lan", "wan").all { it in lists }, lists.toString())
        assertEquals("eth0", layer.rows("interface/list/member").first { it["list"] == "lan" }["interface"])
        val filter = layer.rows("ip/firewall/filter")
        val renew = filter.first { it["comment"] == "Allow-DHCP-Renew" }
        assertEquals(listOf("input", "wan", "udp", "68", "accept"), listOf(renew["chain"], renew["in-interface-list"], renew["protocol"], renew["dst-port"], renew["action"]))
        val policy = filter.single { it["dynamic"] == "true" && it["chain"] == "input" && it["in-interface-list"] == "wan" }
        assertEquals("reject", policy["action"])
        assertTrue(filter.indexOf(policy) > filter.indexOf(renew))
        assertTrue(layer.rows("ip/firewall/nat").any { it["action"] == "masquerade" && it["out-interface-list"] == "wan" && it["dynamic"] == "true" })

        layer.add(MenuPath.parse("interface/list"), mapOf("name" to "rd_test"))
        val rule = layer.add(
            MenuPath.parse("ip/firewall/filter"),
            mapOf("chain" to "input", "in-interface-list" to "rd_test", "protocol" to "tcp", "dst-port" to "8080", "action" to "accept", "comment" to "rd-test"),
        )
        val written = session.get(MenuPath.parse("firewall/rule"), rule)
        assertEquals(
            listOf("rd_test", null, "tcp", "8080", "ACCEPT", "rd-test"),
            listOf(written?.get("src"), written?.get("dest"), written?.get("proto"), written?.get("dest_port"), written?.get("target"), written?.get("name")),
        )
        val list = layer.rows("interface/list").single { it["name"] == "rd_test" }.id
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { layer.remove(MenuPath.parse("interface/list"), listOf(list)) }.category)
        val forward = layer.add(
            MenuPath.parse("ip/firewall/nat"),
            mapOf("chain" to "dstnat", "action" to "dst-nat", "in-interface-list" to "rd_test", "protocol" to "tcp", "dst-port" to "18080", "to-addresses" to lab.inLan(250), "to-ports" to "80"),
        )
        val redirect = session.get(MenuPath.parse("firewall/redirect"), forward)
        assertEquals(
            listOf("DNAT", "rd_test", "18080", lab.inLan(250), "80"),
            listOf(redirect?.get("target"), redirect?.get("src"), redirect?.get("src_dport"), redirect?.get("dest_ip"), redirect?.get("dest_port")),
        )
        layer.set(MenuPath.parse("ip/firewall/filter"), rule, mapOf("chain" to "forward"))
        assertEquals(listOf("rd_test", "*"), session.get(MenuPath.parse("firewall/rule"), rule).let { listOf(it?.get("src"), it?.get("dest")) })
        val dynamic = layer.rows("ip/firewall/filter").first { it["dynamic"] == "true" }
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { layer.remove(MenuPath.parse("ip/firewall/filter"), listOf(dynamic.id)) }.category)
    }

    @Test
    fun eachFilterHasTheRulesOfItsFamily() = layer { layer, session, _ ->
        val v4 = layer.rows("ip/firewall/filter").mapNotNull { it["comment"] }
        val v6 = layer.rows("ipv6/firewall/filter").mapNotNull { it["comment"] }
        assertTrue("Allow-Ping" in v4 && "Allow-Ping" !in v6, "$v4 $v6")
        assertTrue("Allow-ICMPv6-Input" in v6 && "Allow-ICMPv6-Input" !in v4, "$v4 $v6")
        assertTrue("Allow-IPSec-ESP" in v4 && "Allow-IPSec-ESP" in v6, "$v4 $v6")
        val rule = layer.add(
            MenuPath.parse("ipv6/firewall/filter"),
            mapOf("chain" to "input", "in-interface-list" to "lan", "protocol" to "tcp", "dst-port" to "18086", "action" to "accept", "comment" to "rd-test-v6"),
        )
        assertEquals("ipv6", session.get(MenuPath.parse("firewall/rule"), rule)?.get("family"))
        assertTrue(layer.rows("ip/firewall/filter").none { it.id == rule })
    }

    @Test
    fun moreMenusOfRouterOsShowWhatTheRouterHas() = layer { layer, _, _ ->
        assertTrue(layer.rows("file").any { it.id == "tmp" && it["type"] == "directory" })
        assertTrue(layer.rows("ipv6/route").any { it["dst-address"] == "fe80::/64" && it["dynamic"] == "true" })
        assertTrue(layer.rows("system/clock").single()["date"].orEmpty().matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        assertTrue("sync-time" in layer.describe(MenuPath.parse("system/clock")).commands)
        // the program gives the layer the packs of RouterOS alone, as Keenetic has a Clock window of its own
        val clock = layer.windows(bundledPacks().values.filter { it.platform == Platform.RouterOs.packName }).single { "system/clock" in it.paths }
        assertEquals(listOf("sync-time"), clock.paths.getValue("system/clock").actions.map { it.command })
        layer.run(MenuPath.parse("system/clock"), "sync-time", emptyMap()).collect()
        val ntp = layer.rows("system/ntp/client").single()
        assertEquals(listOf("true", "0.openwrt.pool.ntp.org"), listOf(ntp["enabled"], ntp["servers"]?.substringBefore(',')))
        val ping = layer.rows("ip/firewall/filter").first { it["comment"] == "Allow-Ping" }
        assertTrue(ping["packets"]?.toLongOrNull() != null && ping["bytes"]?.toLongOrNull() != null, ping.toString())
    }

    @Test
    fun aFilterRuleIsDraggedIntoPlace() = layer { layer, session, _ ->
        val filter = MenuPath.parse("ip/firewall/filter")
        fun rule(port: String) = mapOf("chain" to "input", "in-interface-list" to "lan", "protocol" to "tcp", "dst-port" to port, "action" to "accept", "comment" to "rd-test-$port")
        val first = layer.add(filter, rule("18081"))
        val second = layer.add(filter, rule("18082"))
        layer.move(filter, listOf(second), first)
        val order = session.watch(MenuPath.parse("firewall/rule")).rows().first { rows -> rows.any { it.id == second } }.map { it.id }
        assertTrue(order.indexOf(second) < order.indexOf(first), order.toString())
    }

    @Test
    fun theWiFiOfTheRouterReadsAsRouterOs() = runBlocking {
        val lab = Lab.require("openwrtwifi.host", "openwrtwifi.user", "openwrtwifi.password", "openwrtwifi.ssh.hostKey")
        withTimeout(60.seconds) {
            val ssh = connectSsh(
                lab.getValue("openwrtwifi.host"), 22, lab.getValue("openwrtwifi.user"), SshAuth.Password(lab.getValue("openwrtwifi.password")),
                acceptHostKey = { it.fingerprint == lab.getValue("openwrtwifi.ssh.hostKey") },
            )
            try {
                val layer = RouterOsLayer(connectOpenWrt(ssh) { QuickJsForms(it, ssh) })
                val networks = layer.rows("interface/wifi")
                assertTrue(networks.any { it["configuration.mode"] == "ap" && it["channel.band"] == "2ghz" && it["channel.frequency"]?.toIntOrNull() in 2412..2484 }, networks.toString())
                assertTrue(networks.any { it["configuration.mode"] == "ap" && it["channel.band"] == "5ghz" && it["channel.frequency"]?.toIntOrNull() in 5160..5885 })
                assertTrue(networks.any { it["configuration.mode"] == "station" }, "the uplinks of travelmate")
                assertEquals(2, layer.rows("interface/wifi/radio").size)
                val security = MenuPath.parse("interface/wifi/security")
                val profiles = layer.rows("interface/wifi/security")
                assertEquals(networks.map { it["name"] }.toSet(), profiles.map { it["name"] }.toSet())
                val protected = profiles.first { "psk" in it["authentication-types"].orEmpty() }
                layer.set(security, protected.id, mapOf("ft" to "true", "management-protection" to "allowed"))
                val drafted = layer.watch(security).rows().first { rows -> rows.any { it.id == protected.id && it["ft"] == "true" } }.single { it.id == protected.id }
                assertEquals(
                    listOf("allowed", protected["authentication-types"], protected["encryption"]),
                    listOf(drafted["management-protection"], drafted["authentication-types"], drafted["encryption"]),
                    profiles.toString(),
                )
            } finally {
                ssh.close()
            }
        }
    }
}
