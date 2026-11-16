package com.vasmarfas.networkhelm.keenetic

import com.vasmarfas.networkhelm.Lab
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.transport.TrustKind
import com.vasmarfas.networkhelm.transport.TrustPolicy
import com.vasmarfas.networkhelm.transport.TrustStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.random.Random
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

// a Keenetic in the lab: keenetic.host (a KeenDNS name answers over HTTPS), keenetic.user, keenetic.password;
// every change is to elements named rd-test, and the configuration has to come back as it was
class KeeneticLabTest {

    // the KeenDNS cloud gave another certificate for the name once, between two connections; pinning is not tested here
    private val trust = TrustPolicy(
        object : TrustStore {
            override suspend fun get(host: String, port: Int, kind: TrustKind): String? = null

            override suspend fun put(host: String, port: Int, kind: TrustKind, fingerprint: String) {}
        },
    ) { true }

    private fun login(user: String? = null, password: String? = null): KeeneticLogin {
        val lab = Lab.require("keenetic.host", "keenetic.user", "keenetic.password")
        return KeeneticLogin(lab.getValue("keenetic.host"), user ?: lab.getValue("keenetic.user"), password ?: lab.getValue("keenetic.password"), tls = true)
    }

    private fun keenetic(block: suspend (KeeneticSession) -> Unit) = runBlocking {
        val session = connectKeenetic(login(), trust)
        withTimeout(10.minutes) { block(session) }
    }

    private suspend fun rows(session: KeeneticSession, path: String): List<Item> = (session.watch(MenuPath.parse(path)).first() as ListEvent.Snapshot).items

    // a page of a component the menu offers only where the router has the component
    private suspend fun offered(session: KeeneticSession, path: String): Boolean = try {
        session.outline(MenuPath.parse(path))
        true
    } catch (e: DeviceException) {
        if (e.category != ErrorCategory.MissingItem) throw e
        false
    }

    private suspend fun config(session: KeeneticSession) = session.export(MenuPath.Root).lines().filter { !it.startsWith("! \$\$\$") }

    private suspend fun keepsConfig(session: KeeneticSession, block: suspend () -> Unit) {
        val before = config(session)
        try {
            block()
        } finally {
            val after = config(session)
            // only the lines that differ: the configuration holds the hashes of the passwords
            assertTrue(before == after, "the configuration did not come back:\n" + (after - before.toSet()).joinToString("\n") + "\n---\n" + (before - after.toSet()).joinToString("\n"))
        }
    }

    // rules of every kind from the home network, off, so nothing goes anywhere: a range for TCP and UDP, ICMP to another
    // address, a service with another port, all protocols; the order, the switch, a change and the removal
    @Test
    fun aPortForwardingRuleOfEveryKindComesAndGoes() = keenetic { session ->
        val path = MenuPath.parse("ip/static")
        suspend fun ours() = rows(session, path.toString()).filter { it["comment"].orEmpty().startsWith("rd-test") }
        keepsConfig(session) {
            try {
                session.add(path, mapOf("disabled" to "yes", "comment" to "rd-test range", "interface" to "Bridge0", "to" to "127.0.0.1", "protocol" to "tcpudp", "mode" to "range", "port" to "65001", "end-port" to "65003"))
                session.add(path, mapOf("disabled" to "yes", "comment" to "rd-test icmp", "interface" to "Bridge0", "to" to "_BY_IP_", "to-address" to "192.168.1.250", "protocol" to "icmp"))
                session.add(path, mapOf("disabled" to "yes", "comment" to "rd-test ssh", "interface" to "Bridge0", "to" to "127.0.0.1", "protocol" to "tcp/22", "to-port" to "2222"))
                session.add(path, mapOf("disabled" to "yes", "comment" to "rd-test all", "interface" to "Bridge0", "to" to "_BY_IP_", "to-address" to "192.168.1.251", "protocol" to "all"))
                val made = ours()
                assertEquals(listOf("TCP/UDP 65001 - 65003", "ICMP", "TCP/22 → 2222", "All"), made.map { it["ports"] })
                assertEquals(listOf("tcpudp", "icmp", "tcp", "all"), made.map { it["protocol"] })
                assertTrue(made.all { it["disabled"] == "true" }, made.toString())
                session.move(path, listOf(made[2].id), made[0].id)
                assertEquals(listOf("rd-test ssh", "rd-test range", "rd-test icmp", "rd-test all"), ours().map { it["comment"] })
                session.move(path, listOf(ours()[0].id), null)
                assertEquals("rd-test ssh", ours().last()["comment"])
                val range = ours().first { it["comment"] == "rd-test range" }
                session.set(path, range.id, mapOf("disabled" to "no"))
                assertEquals("false", ours().first { it["comment"] == "rd-test range" }["disabled"])
                session.set(path, ours().first { it["comment"] == "rd-test range" }.id, mapOf("disabled" to "yes", "comment" to "rd-test range 2", "end-port" to "65004"))
                val changed = ours().first { it["comment"] == "rd-test range 2" }
                assertEquals(listOf("true", "TCP/UDP 65001 - 65004"), listOf(changed["disabled"], changed["ports"]))
                assertTrue(changed.id != range.id, "a changed rule has a new index")
            } finally {
                ours().takeIf { it.isNotEmpty() }?.let { left -> session.remove(path, left.map { it.id }) }
            }
            assertEquals(emptyList(), ours())
        }
    }

    // keenetic.ssh: the address and port of the SSH of the router, from the home network or forwarded to it
    @Test
    fun theWindowsOpenThroughTheSshOfTheRouterAlone() = runBlocking {
        val lab = Lab.require("keenetic.ssh", "keenetic.user", "keenetic.password")
        val (host, port) = lab.getValue("keenetic.ssh").let { it.substringBeforeLast(':') to (it.substringAfterLast(':', "22").toIntOrNull() ?: 22) }
        val session = connectKeenetic(KeeneticLogin(host, lab.getValue("keenetic.user"), lab.getValue("keenetic.password"), port = port, tunnel = true), trust)
        try {
            withTimeout(3.minutes) {
                assertTrue(session.capabilities.encrypted && session.device.version.isNotEmpty(), session.device.toString())
                assertTrue(rows(session, "status").single()["firmware"].orEmpty().isNotEmpty())
                assertTrue(rows(session, "interface").isNotEmpty())
                assertTrue(rows(session, "show/log").isNotEmpty())
                assertTrue(session.ssh?.exec("show version")?.output.orEmpty().contains("release"), "the console goes over the same SSH")
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun everyWindowOpens() = keenetic { session ->
        assertTrue(session.device.version.isNotEmpty() && session.device.canWrite, session.device.toString())
        val paths = listOf(
            "status", "internet", "clients", "wifi", "wifi/radio", "segments", "firewall",
            "interface", "ip/route", "ip/static", "ip/nat", "ip/dhcp/host", "ip/name-server", "ip/dhcp/pool", "user", "system",
            "show/ip/route", "show/ip/arp", "show/ip/dhcp/bindings", "show/ip/nat", "show/ip/name-server", "show/ip/service", "show/upnp/redirect",
            "show/ipv6/addresses", "show/ipv6/prefixes", "show/log", "show/internet/status", "show/ndns", "system/clock", "system/resource",
            "traffic", "vpn", "ip/policy", "wifi/acl", "mws/member", "internet/wired", "internet/wisp", "internet/mobile", "apps",
            "firewall/ipv6", "schedule", "system/led", "system/led/binding", "system/button", "system/update", "vpn/6in4",
        )
        val components = listOf("vpn/wireguard", "vpn/wireguard/peer", "vpn/openvpn", "vpn/tunnel", "internet/dsl", "internet/sms", "internet/ussd", "system/storage", "system/opkg", "qos")
        val failed = (paths + components.filter { offered(session, it) }).mapNotNull { path ->
            try {
                session.describe(MenuPath.parse(path))
                rows(session, path)
                null
            } catch (e: DeviceException) {
                "$path: ${e.message}"
            }
        }
        assertEquals(emptyList(), failed, failed.joinToString("\n"))
        val log = rows(session, "show/log")
        assertTrue(log.size > 10 && log.all { it["topics"].orEmpty().split(',').size == 2 }, log.take(3).toString())
        val clock = assertNotNull(session.get(MenuPath.parse("system/clock"), null))
        assertTrue(clock["date"].orEmpty().matches(Regex("""\d{4}-\d{2}-\d{2}""")) && clock["time"].orEmpty().matches(Regex("""\d{2}:\d{2}:\d{2}""")), clock.toString())
        val resource = assertNotNull(session.get(MenuPath.parse("system/resource"), null))
        assertTrue(resource["total-memory"].orEmpty().toLong() > resource["free-memory"].orEmpty().toLong(), resource.toString())
        assertTrue(rows(session, "interface").any { it.id == "Bridge0" && it["address"].orEmpty().isNotEmpty() })
        assertTrue("hostname" in session.export(MenuPath.Root))

        val status = assertNotNull(session.get(MenuPath.parse("status"), null))
        assertTrue(status["model"].orEmpty().isNotEmpty() && status["connection"].orEmpty().isNotEmpty() && status["used-memory"].orEmpty().toLong() < status["total-memory"].orEmpty().toLong(), status.toString())
        val gateway = rows(session, "internet").single { it["default"] == "true" }
        assertTrue(gateway["rxbytes"].orEmpty().toLong() > 0, gateway.toString())
        val rates = session.run(MenuPath.parse("interface"), "monitor-traffic", mapOf("interface" to gateway.id)).take(2).toList()
        assertTrue(rates.all { it["name"] == gateway.id && it["rx-bits-per-second"].orEmpty().toLongOrNull() != null }, rates.toString())
        assertTrue(rows(session, "wifi").any { it["band"].orEmpty().endsWith("GHz") && it["ssid"].orEmpty().isNotEmpty() })
        assertTrue(rows(session, "segments").any { it["dhcp"].orEmpty().contains('-') })
        assertTrue(rows(session, "clients").all { it.id.matches(Regex("([0-9a-f]{2}:){5}[0-9a-f]{2}")) })
    }

    // a schedule, a rule of IPv6 on the spare access point, and a WireGuard connection that stays off with a peer of a made-up key
    @Test
    fun aScheduleARuleOfIpv6AndAWireguardConnectionComeAndGo() = keenetic { session ->
        val spare = rows(session, "wifi").firstOrNull { it["disabled"] == "true" }
        keepsConfig(session) {
            val schedules = MenuPath.parse("schedule")
            val plan = session.add(schedules, mapOf("description" to "rd-test plan", "actions" to "start 08:00 mon,tue; stop 17:30 mon,tue"))
            try {
                assertEquals(listOf("rd-test plan", "start 08:00 mon,tue; stop 17:30 mon,tue"), listOf("description", "actions").map { session.get(schedules, plan)?.get(it) })
                session.set(schedules, plan, mapOf("description" to "rd-test plan 2", "actions" to "start 09:00 *"))
                assertEquals(listOf("rd-test plan 2", "start 09:00 *"), listOf("description", "actions").map { session.get(schedules, plan)?.get(it) })
            } finally {
                session.remove(schedules, listOf(plan))
            }
            assertTrue(rows(session, "schedule").none { it.id == plan })

            if (spare != null) {
                val firewall = MenuPath.parse("firewall/ipv6")
                suspend fun mine() = rows(session, "firewall/ipv6").filter { it["interface"] == spare.id }
                try {
                    session.add(firewall, mapOf("interface" to spare.id, "action" to "deny", "protocol" to "tcp", "source" to "2001:db8::/32", "destination-port" to "22", "description" to "rd-test six"))
                    assertEquals(listOf("deny", "tcp", "2001:db8::/32", "22", "rd-test six"), listOf("action", "protocol", "source", "destination-port", "description").map { mine().single()[it] })
                    session.set(firewall, mine().single().id, mapOf("disabled" to "yes", "description" to "rd-test six 2"))
                    assertEquals(listOf("true", "rd-test six 2"), listOf("disabled", "description").map { mine().single()[it] })
                } finally {
                    mine().takeIf { it.isNotEmpty() }?.let { left -> session.remove(firewall, left.map { it.id }) }
                }
                assertEquals(emptyList(), mine())
            }

            if (!offered(session, "vpn/wireguard")) return@keepsConfig
            val wireguard = MenuPath.parse("vpn/wireguard")
            val peers = MenuPath.parse("vpn/wireguard/peer")
            val tunnel = session.add(wireguard, mapOf("description" to "rd-test wg", "disabled" to "yes", "global" to "no", "address" to "10.255.255.2/32", "listen-port" to "51999"))
            try {
                assertEquals(listOf("rd-test wg", "true", "false", "10.255.255.2/32", "51999"), listOf("description", "disabled", "global", "address", "listen-port").map { session.get(wireguard, tunnel)?.get(it) })
                val key = Base64.getEncoder().encodeToString(Random.nextBytes(32))
                val peer = session.add(peers, mapOf("interface" to tunnel, "public-key" to key, "comment" to "rd-test peer", "endpoint" to "192.0.2.80:51820", "allowed-ips" to "10.255.255.0/24", "keepalive" to "25"))
                assertEquals(listOf("rd-test peer", "192.0.2.80:51820", "10.255.255.0/24", "25"), listOf("comment", "endpoint", "allowed-ips", "keepalive").map { session.get(peers, peer)?.get(it) })
                session.set(peers, peer, mapOf("comment" to "rd-test peer 2", "allowed-ips" to "10.255.254.0/24, 10.255.255.0/24"))
                assertEquals(listOf("rd-test peer 2", "10.255.254.0/24, 10.255.255.0/24"), listOf("comment", "allowed-ips").map { session.get(peers, peer)?.get(it) })
                session.remove(peers, listOf(peer))
                assertTrue(rows(session, "vpn/wireguard/peer").none { it.id == peer })
                session.set(wireguard, tunnel, mapOf("description" to "rd-test wg 2", "listen-port" to "51998"))
                assertEquals(listOf("rd-test wg 2", "51998"), listOf("description", "listen-port").map { session.get(wireguard, tunnel)?.get(it) })
            } finally {
                session.remove(wireguard, listOf(tunnel))
            }
            assertTrue(rows(session, "vpn/wireguard").none { it.id == tunnel })
        }
    }

    @Test
    fun clientsWifiAndSegmentsChangeAndComeBack() = keenetic { session ->
        val spare = rows(session, "wifi").firstOrNull { it["disabled"] == "true" }
        keepsConfig(session) {
            val clients = MenuPath.parse("clients")
            session.add(clients, mapOf("name" to "rd-test-tv", "mac" to "02:00:5e:10:00:41", "access" to "deny", "static-ip" to "192.0.2.41"))
            try {
                val tv = assertNotNull(session.get(clients, "02:00:5e:10:00:41"))
                assertEquals(listOf("rd-test-tv", "deny", "192.0.2.41", "true"), listOf("name", "access", "static-ip", "registered").map { tv[it] })
                session.set(clients, tv.id, mapOf("name" to "rd-test-tv2", "access" to "permit", "static-ip" to ""))
                assertEquals(listOf("rd-test-tv2", "permit", ""), listOf("name", "access", "static-ip").map { session.get(clients, tv.id)?.get(it) })
            } finally {
                session.remove(clients, listOf("02:00:5e:10:00:41"))
            }
            if (spare != null) {
                val wifi = MenuPath.parse("wifi")
                session.set(wifi, spare.id, mapOf("ssid" to "rd-test-ssid", "security" to "wpa2", "psk" to "rd-test-key-1"))
                assertEquals(listOf("rd-test-ssid", "wpa2", "rd-test-key-1"), listOf("ssid", "security", "psk").map { session.get(wifi, spare.id)?.get(it) })
                session.set(wifi, spare.id, mapOf("security" to "wpa2,wpa3"))
                assertEquals("wpa2,wpa3", session.get(wifi, spare.id)?.get("security"))
                session.set(wifi, spare.id, mapOf("ssid" to spare["ssid"].orEmpty(), "security" to "none", "psk" to ""))
            }
            val segments = MenuPath.parse("segments")
            val home = rows(session, "segments").first()
            session.set(segments, home.id, mapOf("description" to "rd-test segment"))
            assertEquals("rd-test segment", session.get(segments, home.id)?.get("description"))
            session.set(segments, home.id, mapOf("description" to home["description"].orEmpty()))
        }
    }

    @Test
    fun aRuleOfTheFirewallIsAddedChangedMovedAndRemoved() = keenetic { session ->
        val spare = rows(session, "wifi").firstOrNull { it["disabled"] == "true" } ?: return@keenetic
        keepsConfig(session) {
            val firewall = MenuPath.parse("firewall")
            suspend fun mine() = rows(session, "firewall").filter { it["interface"] == spare.id }
            try {
                session.add(firewall, mapOf("interface" to spare.id, "action" to "deny", "protocol" to "tcp", "source" to "192.0.2.0/24", "destination-port" to "22", "description" to "rd-test first"))
                session.add(firewall, mapOf("interface" to spare.id, "action" to "permit", "protocol" to "udp", "destination" to "198.51.100.7", "source-port" to "1000-2000"))
                assertEquals(listOf("192.0.2.0/24" to "22", "" to ""), mine().map { it["source"] to it["destination-port"] })
                assertEquals(listOf("rd-test first", ""), mine().map { it["description"] })
                session.set(firewall, mine()[1].id, mapOf("description" to "rd-test second", "disabled" to "yes"))
                session.set(firewall, mine()[0].id, mapOf("source" to "192.0.2.9"))
                assertEquals(listOf("192.0.2.9" to "rd-test first", "" to "rd-test second"), mine().map { it["source"] to it["description"] })
                assertEquals("true", mine()[1]["disabled"])
                session.move(firewall, listOf(mine()[1].id), mine()[0].id)
                assertEquals(listOf("udp", "tcp"), mine().map { it["protocol"] })
                session.add(firewall, mapOf("interface" to spare.id, "action" to "permit", "protocol" to "ipip"))
                session.add(firewall, mapOf("interface" to spare.id, "action" to "deny", "protocol" to "tcp/443"))
                assertEquals(listOf("ipip" to "", "tcp" to "443"), mine().drop(2).map { it["protocol"] to it["destination-port"] })
            } finally {
                mine().forEach { session.remove(firewall, listOf(it.id)) }
            }
            assertEquals(emptyList(), mine())
        }
    }

    @Test
    fun aStaticRouteAndAPortForwardAreMadeChangedAndRemoved() = keenetic { session ->
        val wan = rows(session, "show/ip/route").first { it["destination"] == "0.0.0.0/0" }["interface"].orEmpty()
        keepsConfig(session) {
            val routes = MenuPath.parse("ip/route")
            val id = session.add(routes, mapOf("network" to "192.0.2.0", "mask" to "255.255.255.0", "interface" to "Bridge0", "comment" to "rd-test"))
            try {
                assertTrue(id.isNotEmpty())
                session.set(routes, id, mapOf("comment" to "rd-test changed", "metric" to "7"))
                val changed = rows(session, "ip/route").single { it["network"] == "192.0.2.0" }
                assertEquals(listOf("rd-test changed", "7", "Bridge0"), listOf("comment", "metric", "interface").map { changed[it] })
                val neighbour = rows(session, "interface").single { it.id == "Bridge0" }["address"].orEmpty().substringBeforeLast('.') + ".250"
                session.set(routes, changed.id, mapOf("interface" to "", "gateway" to neighbour))
                val moved = rows(session, "ip/route").single { it["network"] == "192.0.2.0" }
                assertEquals("", moved["interface"].orEmpty())
                session.remove(routes, listOf(moved.id))
            } finally {
                rows(session, "ip/route").filter { it["comment"].orEmpty().startsWith("rd-test") }.forEach { session.remove(routes, listOf(it.id)) }
            }
            assertTrue(rows(session, "ip/route").none { it["network"] == "192.0.2.0" })

            val forwards = MenuPath.parse("ip/static")
            val forward = session.add(forwards, mapOf("protocol" to "tcp", "interface" to wan, "port" to "61270", "to-address" to "192.0.2.10", "to-port" to "22", "comment" to "rd-test"))
            try {
                session.set(forwards, forward, mapOf("to-address" to "192.0.2.11"))
                val altered = rows(session, "ip/static").single { it["comment"] == "rd-test" }
                assertEquals(listOf("192.0.2.11", "61270"), listOf("to-address", "port").map { altered[it] })
                session.set(forwards, altered.id, mapOf("port" to "61271"))
                assertEquals(listOf("61271"), rows(session, "ip/static").filter { it["comment"] == "rd-test" }.map { it["port"] })
            } finally {
                rows(session, "ip/static").filter { it["comment"] == "rd-test" }.forEach { session.remove(forwards, listOf(it.id)) }
            }
        }
    }

    @Test
    fun aHostAUserAPoolAndTheirNeighboursComeAndGo() = keenetic { session ->
        val spare = rows(session, "interface").firstOrNull { it["type"] == "AccessPoint" && it["state"] == "down" }
        keepsConfig(session) {
            val users = MenuPath.parse("user")
            session.add(users, mapOf("name" to "rd_test", "password" to "rd-test-Pw-1", "tag" to "cli,http"))
            try {
                assertEquals("cli,http", session.get(users, "rd_test")?.get("tag"))
                // the router lets the new user in with the password whose hash the app sent
                assertTrue(connectKeenetic(login("rd_test", "rd-test-Pw-1"), trust).device.identity.isNotEmpty())
                session.set(users, "rd_test", mapOf("tag" to "http"))
                assertEquals("http", session.get(users, "rd_test")?.get("tag"))
            } finally {
                session.remove(users, listOf("rd_test"))
            }

            val pools = MenuPath.parse("ip/dhcp/pool")
            session.add(pools, mapOf("name" to "rd-test", "begin" to "192.0.2.10", "end" to "192.0.2.20", "lease" to "3600", "disabled" to "yes"))
            try {
                session.set(pools, "rd-test", mapOf("end" to "192.0.2.30", "lease" to "7200"))
                assertEquals(listOf("192.0.2.30", "7200", "true"), listOf("end", "lease", "disabled").map { session.get(pools, "rd-test")?.get(it) })
            } finally {
                session.remove(pools, listOf("rd-test"))
            }

            val leases = MenuPath.parse("ip/dhcp/host")
            session.add(leases, mapOf("mac" to "02:00:5e:10:00:32", "ip" to "192.0.2.40"))
            session.set(leases, "02:00:5e:10:00:32", mapOf("ip" to "192.0.2.41"))
            assertEquals("192.0.2.41", session.get(leases, "02:00:5e:10:00:32")?.get("ip"))
            session.remove(leases, listOf("02:00:5e:10:00:32"))

            val servers = MenuPath.parse("ip/name-server")
            val server = session.add(servers, mapOf("address" to "192.0.2.53", "domain" to "rd-test.example"))
            assertEquals("192.0.2.53 rd-test.example", server)
            session.remove(servers, listOf(server))

            if (spare != null) {
                val nat = MenuPath.parse("ip/nat")
                session.add(nat, mapOf("interface" to spare.id))
                session.remove(nat, listOf(spare.id))
                val interfaces = MenuPath.parse("interface")
                session.set(interfaces, spare.id, mapOf("description" to "rd-test", "ssid" to "rd-test-ssid"))
                assertEquals(listOf("rd-test", "rd-test-ssid"), listOf("description", "ssid").map { session.get(interfaces, spare.id)?.get(it) })
                session.set(interfaces, spare.id, mapOf("description" to spare["description"].orEmpty(), "ssid" to spare["ssid"].orEmpty()))
            }
        }
    }

    @Test
    fun aTunnelAPolicyAndTheAccessListOfAPointComeAndGo() = keenetic { session ->
        // the guest point and a black list of addresses no device has: a white list would keep the guests out
        val point = rows(session, "wifi/acl").firstOrNull { it.id.endsWith("AccessPoint1") }
        keepsConfig(session) {
            val vpn = MenuPath.parse("vpn")
            val name = session.add(vpn, mapOf("type" to "PPTP", "description" to "rd-test vpn", "server" to "192.0.2.77", "user" to "rd", "password" to "rd-test-Pw-2", "disabled" to "yes"))
            try {
                val made = assertNotNull(session.get(vpn, name))
                assertEquals(listOf("PPTP", "rd-test vpn", "192.0.2.77", "rd", "true"), listOf("type", "description", "server", "user", "disabled").map { made[it] })
                session.set(vpn, name, mapOf("description" to "rd-test vpn 2", "server" to "192.0.2.78"))
                assertEquals(listOf("rd-test vpn 2", "192.0.2.78", "rd"), listOf("description", "server", "user").map { session.get(vpn, name)?.get(it) })
            } finally {
                session.remove(vpn, listOf(name))
            }
            assertTrue(rows(session, "vpn").none { it.id == name })

            val policies = MenuPath.parse("ip/policy")
            session.add(policies, mapOf("name" to "rdtest", "description" to "rd-test policy", "multipath" to "yes"))
            try {
                assertEquals(listOf("rd-test policy", "true"), listOf("description", "multipath").map { session.get(policies, "rdtest")?.get(it) })
                session.set(policies, "rdtest", mapOf("description" to "rd-test policy 2", "multipath" to "no"))
                assertEquals(listOf("rd-test policy 2", "false"), listOf("description", "multipath").map { session.get(policies, "rdtest")?.get(it) })
            } finally {
                session.remove(policies, listOf("rdtest"))
            }
            assertTrue(rows(session, "ip/policy").none { it.id == "rdtest" })

            if (point != null && point["type"] != "permit") {
                val acl = MenuPath.parse("wifi/acl")
                // the router takes only the MACs of registered clients, and goes on with the type after one it does not know
                val unknown = assertFailsWith<DeviceException> { session.set(acl, point.id, mapOf("type" to "deny", "addresses" to "02:00:5e:10:00:51")) }
                assertTrue("02:00:5e:10:00:51" in unknown.message.orEmpty(), unknown.message)
                assertEquals(point["type"], session.get(acl, point.id)?.get("type"))
                val clients = MenuPath.parse("clients")
                session.add(clients, mapOf("name" to "rd-test-acl1", "mac" to "02:00:5e:10:00:51", "access" to "permit"))
                session.add(clients, mapOf("name" to "rd-test-acl2", "mac" to "02:00:5e:10:00:52", "access" to "permit"))
                try {
                    session.set(acl, point.id, mapOf("type" to "deny", "addresses" to "02:00:5e:10:00:51, 02:00:5e:10:00:52"))
                    assertEquals(listOf("deny", "02:00:5e:10:00:51, 02:00:5e:10:00:52"), listOf("type", "addresses").map { session.get(acl, point.id)?.get(it) })
                    session.set(acl, point.id, mapOf("addresses" to "02:00:5e:10:00:52"))
                    assertEquals("02:00:5e:10:00:52", session.get(acl, point.id)?.get("addresses"))
                } finally {
                    session.set(acl, point.id, mapOf("type" to point["type"], "addresses" to point["addresses"]))
                    session.remove(clients, listOf("02:00:5e:10:00:51", "02:00:5e:10:00:52"))
                }
                assertEquals(listOf(point["type"], point["addresses"]), listOf("type", "addresses").map { session.get(acl, point.id)?.get(it) })
            }
        }
    }

    @Test
    fun aContextOfTheCliIsAWindowOfItsSettings() = keenetic { session ->
        val telnet = MenuPath.parse("rc/ip/telnet")
        val names = session.describe(telnet).arguments.map { it.name }
        assertTrue("security-level" in names && "lockout-policy.threshold" in names, "$names")
        keepsConfig(session) {
            val before = assertNotNull(session.get(telnet, null))
            assertEquals("private", before["security-level"])
            session.set(telnet, null, mapOf("lockout-policy.threshold" to "6"))
            assertEquals("6", session.get(telnet, null)?.get("lockout-policy.threshold"))
            session.set(telnet, null, mapOf("lockout-policy.threshold" to before["lockout-policy.threshold"]))
        }
        val packs = session.packs()
        assertTrue(packs.any { pack -> pack.window.selector && pack.window.tabs.any { it.path == "rc/ip/telnet" } })
        assertTrue(packs.any { pack -> pack.window.tabs.any { it.path == "rc/interface/Bridge0" && it.title.startsWith("Bridge0") } })
        assertTrue(packs.none { pack -> pack.window.tabs.any { it.path.startsWith("rc/eula") || it.path.startsWith("rc/cloud") } })
    }

    @Test
    fun aToolOfTheRouterPrintsItsLinesAsItGoesOn() = keenetic { session ->
        assertEquals("host", session.describe(MenuPath.parse("tools/ping")).arguments.first().name)
        val lines = session.run(MenuPath.parse("tools"), "ping", mapOf("host" to "8.8.8.8", "count" to "2")).toList().map { it["line"].orEmpty() }
        assertTrue(lines.any { "bytes from 8.8.8.8" in it } && lines.any { "statistics" in it }, "$lines")
    }

    @Test
    fun theConsoleKeepsTheContextOfTheCli() = keenetic { session ->
        val shell = assertNotNull(session.ssh).shell("xterm", 80, 24)
        val seen = StringBuilder()
        suspend fun until(text: String) {
            val buffer = ByteArray(4096)
            withTimeoutOrNull(2.minutes) {
                while (text !in seen) {
                    val count = shell.read(buffer, 0, buffer.size)
                    if (count < 0) break
                    seen.append(buffer.decodeToString(0, count))
                }
            }
            assertTrue(text in seen, "no $text in:\n$seen")
        }
        until("(config)> ")
        shell.write("show version\r".encodeToByteArray())
        until("release: ")
        shell.write("interface Bridge0\r".encodeToByteArray())
        until("(config-if)> ")
        shell.write("exit\r".encodeToByteArray())
        // leaving a context the router says done., leaving the CLI bye.
        until("done.\r\n(config)> ")
        shell.close()
    }

    // keenetic.ssh: the address and port of SSH of the lab router, forwarded to it from the internet
    @Test
    fun theCommandLineGoesOverSshWhereTheLoginNamesIt() = runBlocking<Unit> {
        val (host, port) = Lab.require("keenetic.ssh").getValue("keenetic.ssh").split(':').let { it[0] to (it.getOrNull(1)?.toInt() ?: 22) }
        val plain = login()
        val session = connectKeenetic(KeeneticLogin(plain.host, plain.user, plain.secret, tls = true, sshHost = host, sshPort = port), trust)
        try {
            assertTrue(session.capabilities.terminal)
            val shell = assertNotNull(session.ssh).shell("xterm", 120, 40)
            val seen = StringBuilder()
            val buffer = ByteArray(8192)
            // what is typed before the first prompt the CLI wipes
            suspend fun until(text: String) = withTimeoutOrNull(1.minutes) {
                while (text !in seen) {
                    val count = shell.read(buffer, 0, buffer.size)
                    if (count < 0) break
                    seen.append(buffer.decodeToString(0, count))
                }
            }
            until("(config)> ")
            shell.write("show version\r".encodeToByteArray())
            until("release: ")
            assertTrue("release: " in seen, "the CLI over SSH said:\n$seen")
            shell.close()
        } finally {
            session.close()
        }
    }
}
