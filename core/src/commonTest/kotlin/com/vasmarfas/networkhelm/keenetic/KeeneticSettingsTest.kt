package com.vasmarfas.networkhelm.keenetic

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KeeneticSettingsTest {

    // answers of a router with an IPoE connection and its PPPoE, a Wireless ISP station, a modem and a WireGuard tunnel; names,
    // keys and addresses made up
    private val shows = mapOf(
        "show/interface" to """{"FastEthernet0/Vlan2":{"id":"FastEthernet0/Vlan2","type":"Vlan","description":"Broadband connection","link":"up","state":"up","security-level":"public","global":true},""" +
            """"PPPoE0":{"id":"PPPoE0","type":"PPPoE","description":"rd-test provider","state":"up","role":["inet"],"global":true,"defaultgw":true,"security-level":"public"},""" +
            """"WifiMaster0":{"id":"WifiMaster0","type":"WifiMaster","state":"up","channel":6},""" +
            """"WifiMaster0/WifiStation0":{"id":"WifiMaster0/WifiStation0","type":"WifiStation","state":"down","security-level":"public","global":false},""" +
            """"UsbQmi0":{"id":"UsbQmi0","type":"UsbQmi","description":"rd-test modem","state":"up","plugged":"yes","security-level":"public","global":false},""" +
            """"Wireguard0":{"id":"Wireguard0","type":"Wireguard","description":"rd-test wg","state":"up","security-level":"public","wireguard":{"public-key":"rd-pub","peer":[{"public-key":"rd-peer-a","online":true,"rxbytes":10,"txbytes":20}]}},""" +
            """"Gre0":{"id":"Gre0","type":"Gre","description":"rd-test gre","state":"up","security-level":"public","tunnel-local-source":"203.0.113.33","tunnel-remote-destination":"198.51.100.1"}}""",
        "show/rc/interface" to """{"FastEthernet0/Vlan2":{"ip":{"address":{"dhcp":true},"name-server":[{"name-server":"192.0.2.53","port":""}],"name-servers":false,"dhcp":{"client":{"hostname":"rd-test-host"}},"mtu":"1500","global":{"order":1}},""" +
            """"mac":{"address":{"mac":"02:00:5e:10:00:aa"}},"ping-check":{"profile":"_WEBADMIN_FastEthernet0/Vlan2"},"authentication":{"identity":"rd-dot1x","password":"rddot","eap-ttls":true,"mschap-v2":true}},""" +
            """"PPPoE0":{"description":"rd-test provider","connect":{"via":"FastEthernet0/Vlan2"},"role":["inet"],"authentication":{"identity":"rd-user","password":"rdpass","chap":true},""" +
            """"pppoe":{"service":"rd-test-service"},"ip":{"global":{"order":0}},"up":true},""" +
            """"WifiMaster0":{"channel":{"channel":"6"}},""" +
            """"WifiMaster0/WifiStation0":{"ssid":"rd-test-hotspot","encryption":{"enable":true,"wpa2":true},"authentication":{"wpa-psk":{"psk":"rdkey"}},""" +
            """"standby":{"enable":true,"timeout":{"seconds":600}},"ip":{"address":{"dhcp":true},"adjust-ttl":{"send":"65"}}},""" +
            """"UsbQmi0":{"mobile":{"apn":{"apn":"internet","comment":"ru.rd-test"},"acq":"lte","roaming":true},"authentication":{"identity":"rd-sim","password":"rdsim","pap":true}},""" +
            """"Wireguard0":{"description":"rd-test wg","ip":{"address":{"address":"10.8.0.2","mask":"255.255.255.255"},"global":{"order":2},"tcp":{"adjust-mss":{"pmtu":true}}},""" +
            """"wireguard":{"listen-port":{"port":51820},"peer":[{"key":"rd-peer-a","comment":"rd-test peer","endpoint":{"address":"vpn.example.net:51820"},""" +
            """"allow-ips":[{"address":"0.0.0.0","mask":"0.0.0.0"},{"address":"::","mask":"0"}],"keepalive-interval":{"interval":25}}]},"up":true},""" +
            """"Gre0":{"description":"rd-test gre","ip":{"address":{"address":"172.16.0.1","mask":"255.255.255.252"}},"tunnel":{"destination":"198.51.100.1","source":{"interface":"PPPoE0"}}}}""",
        "show/rc/ping-check" to """{"profile":{"_WEBADMIN_FastEthernet0/Vlan2":{"mode":"icmp","host":["8.8.8.8"],"update-interval":{"seconds":10},"max-fails":{"count":5}}}}""",
        "show/rc/schedule" to """{}""",
        "show/rc/ip/route" to """[]""",
        "show/interface/channels/WifiMaster0" to """{"channel":[{"number":1},{"number":6},{"number":11}]}""",
        "show/interface/Dsl0" to """{"id":"Dsl0","type":"Dsl","line":"showtime","opmode":"VDSL2","rate-down":"61000 kbit/s"}""",
        "show/media" to """{"Media0":{"manufacturer":"rd","product":"flash","serial":"rd-0","size":"8000","state":"ready","ejectable":true,"usb":{"port":1,"usb-version":"2.0"},""" +
            """"partition":{"0":{"id":"0","label":"RD","fstype":"exfat","free":"1000","total":"2000"}}}}""",
        "show/rc/opkg" to """{"disk":"RD:/","initrc":"/opt/etc/init.d/rc.unslung"}""",
        "show/rc/ntce/qos" to """{"enable":true}""",
        "show/rc/ntce/upstream/rate-limit" to """{"PPPoE0":{"rate":"50000"}}""",
    )

    private val dsl = """{"Dsl0":{"description":"rd-test dsl","operating-mode":{"mode":"auto"},"dsl":{"ginp":true},"up":true}}"""

    // a change is answered with nothing but the import of a WireGuard file, which names the connection the router made of it
    private inner class Fake(extra: Map<String, String> = emptyMap(), private val rests: Map<String, String> = emptyMap()) : KeeneticCall {
        private val answers = shows + extra
        val posted = mutableListOf<String>()
        val rested = mutableListOf<String>()

        override suspend fun show(path: String): JsonElement = Json.parseToJsonElement(answers[path] ?: throw DeviceException(ErrorCategory.MissingItem, "this router has no /rci/$path"))

        override suspend fun post(commands: List<JsonObject>, raising: Boolean): List<JsonElement> = commands.map { command ->
            if ("show" in command) answerOf(command) else {
                posted += command.toString()
                if (inside(command, "interface", "wireguard", "import") == null) JsonObject(emptyMap())
                else Json.parseToJsonElement("""{"interface":{"wireguard":{"import":{"created":"Wireguard1","intersects":""}}}}""")
            }
        }

        override suspend fun realm() = "Keenetic Giga"

        override suspend fun rest(method: String, path: String, body: JsonElement?): JsonElement {
            rested += listOfNotNull("$method $path", body?.toString()).joinToString(" ")
            return Json.parseToJsonElement(rests["$method $path"] ?: "{}")
        }

        // a show with a name is the show of that element: show interface Dsl0 with its details
        private fun answerOf(command: JsonObject): JsonElement {
            var at = command["show"] as? JsonObject ?: return JsonObject(emptyMap())
            val path = mutableListOf<String>()
            while (at.size == 1) {
                val (key, inner) = at.entries.single()
                if (inner !is JsonObject) break
                path += key
                at = inner
            }
            val asked = (listOf("show") + path + listOfNotNull((at["name"] as? JsonPrimitive)?.content)).joinToString("/")
            val result = when {
                path == listOf("interface") && "stat" in at -> JsonObject(mapOf("stat" to JsonArray(emptyList())))
                else -> answers[asked]?.let(Json::parseToJsonElement) ?: Json.parseToJsonElement("""{"status":[{"status":"error","message":"no such command"}]}""")
            }
            return JsonObject(mapOf("show" to path.foldRight(result) { key, inner -> JsonObject(mapOf(key to inner)) }))
        }
    }

    @Test
    fun aConnectionReadsAsThePageOfItsTypeShowsIt() = runTest {
        val connections = Connections(Fake(), components = setOf("dot1x", "pingcheck"))
        val rows = connections.read().associateBy { it.id }
        assertEquals(listOf("FastEthernet0/Vlan2", "PPPoE0", "WifiMaster0/WifiStation0", "UsbQmi0", "Wireguard0", "Gre0"), rows.keys.toList())
        val ipoe = rows.getValue("FastEthernet0/Vlan2")
        assertEquals(
            listOf("ipoe", "auto", "192.0.2.53", "true", "rd-test-host", "1500", "manual", "02:00:5e:10:00:aa", "1", "PPPoE", "rd-user", "chap", "rd-test-service", "mschap-v2", "rd-dot1x", "icmp", "8.8.8.8", "10", "5", "disabled"),
            listOf(
                ".kind", "ipv4", "dns", "ignore-dns", "hostname", "ip-mtu", "mac-mode", "mac-address", "order", "tunnel", "user", "authentication", "service", "dot1x", "dot1x-user",
                "ping-check", "ping-check-host", "ping-check-interval", "ping-check-fails", "ipv6",
            ).map { ipoe[it] },
        )
        assertEquals(listOf("ppp", "rd-user", "rdpass", "chap", "0", ""), listOf(".kind", "user", "password", "authentication", "order", "concentrator").map { rows.getValue("PPPoE0")[it] })
        assertEquals(
            listOf("wisp", "rd-test-hotspot", "wpa2", "rdkey", "6", "on-demand", "600", "65", "auto", "-1"),
            listOf(".kind", "ssid", "security", "psk", "channel", "connection-type", "idle-timeout", "ttl-out", "ipv4", "order").map { rows.getValue("WifiMaster0/WifiStation0")[it] },
        )
        assertEquals(listOf("mobile", "internet", "lte", "true", "pap", "rd-sim"), listOf(".kind", "apn", "network-type", "roaming", "authentication", "user").map { rows.getValue("UsbQmi0")[it] })
        assertEquals(listOf("vpn", null), listOf(".kind", "ping-check").map { rows.getValue("Wireguard0")[it] })

        val bare = Connections(Fake(), components = emptySet())
        assertTrue(bare.read().none { "dot1x" in it.values || "ping-check" in it.values })
        assertTrue(bare.arguments().none { it.name == "dot1x" || it.name.startsWith("ping-check") })
        val arguments = connections.arguments().associateBy { it.name }
        assertEquals(listOf("-1", "0", "1", "2", "3"), arguments.getValue("order").choices)
        assertEquals("Backup 3", arguments.getValue("order").labels["3"])
        assertEquals(listOf("0", "1", "6", "11"), arguments.getValue("channel").choices)
        assertEquals(listOf(mapOf(".kind" to "ipoe", "ipv4" to "manual"), mapOf(".kind" to "wisp", "ipv4" to "manual")), arguments.getValue("ip-address").depends)
    }

    @Test
    fun aConnectionChangesWhatTheFieldsOfItsPageChange() = runTest {
        val router = Fake()
        val connections = Connections(router, "internet/wired", components = setOf("dot1x", "pingcheck")) { it !in MODEMS && it != WISP && it !in TUNNELS }
        val ipoe = "FastEthernet0/Vlan2"
        connections.set(ipoe, mapOf("ipv4" to "manual", "ip-address" to "192.0.2.10", "ip-mask" to "255.255.255.0", "ip-gateway" to "192.0.2.1"))
        assertEquals(
            listOf(
                """{"interface":{"$ipoe":{"ip":{"address":{"no":true,"dhcp":false}}}}}""",
                """{"interface":{"$ipoe":{"ip":{"address":{"dhcp":false,"address":"192.0.2.10","mask":"255.255.255.0"}}}}}""",
                """{"interface":{"$ipoe":{"ip":{"gateway":"192.0.2.1"}}}}""",
            ),
            router.posted,
        )

        router.posted.clear()
        connections.set(ipoe, mapOf("order" to "-1", "dns" to "1.1.1.1, 9.9.9.9:5353", "ignore-dns" to "false", "dot1x-password" to "rdnew"))
        assertEquals(
            listOf(
                """{"interface":{"$ipoe":{"ip":{"global":{"no":true}}}}}""",
                """{"interface":{"$ipoe":{"ip":{"name-server":[{"no":true},{"name-server":"1.1.1.1","port":""},{"name-server":"9.9.9.9","port":"5353"}]}}}}""",
                """{"interface":{"$ipoe":{"ip":{"name-servers":true}}}}""",
                """{"interface":{"$ipoe":{"authentication":{"identity":"rd-dot1x","password":"rdnew","eap-ttls":true,"mschap-v2":true}}}}""",
            ),
            router.posted,
        )

        router.posted.clear()
        connections.set(ipoe, mapOf("ping-check" to "connect", "ping-check-port" to "443"))
        connections.set(ipoe, mapOf("ping-check" to "none"))
        assertEquals(
            listOf(
                """{"ping-check":{"profile":{"name":"_WEBADMIN_$ipoe","host":["8.8.8.8"],"update-interval":{"seconds":"10"},"mode":"connect","max-fails":{"count":"5"},"port":"443"}}}""",
                """{"interface":{"$ipoe":{"ping-check":{"profile":"_WEBADMIN_$ipoe"}}}}""",
                """{"interface":{"$ipoe":{"ping-check":{"profile":{"no":true}}}}}""",
                """{"ping-check":{"profile":{"name":"_WEBADMIN_$ipoe","no":true}}}""",
            ),
            router.posted,
        )

        // the password the router gives back goes with a new user, and the account of the tunnel is changed from its connection too
        router.posted.clear()
        connections.set("PPPoE0", mapOf("user" to "rd-other"))
        connections.set(ipoe, mapOf("password" to "rdnew", "service" to ""))
        assertEquals(
            listOf(
                """{"interface":{"PPPoE0":{"authentication":{"identity":"rd-other","password":"rdpass","pap":false,"chap":true,"mschap":false,"mschap-v2":false}}}}""",
                """{"interface":{"PPPoE0":{"authentication":{"identity":"rd-user","password":"rdnew","pap":false,"chap":true,"mschap":false,"mschap-v2":false}}}}""",
                """{"interface":{"PPPoE0":{"pppoe":{"service":{"no":true}}}}}""",
            ),
            router.posted,
        )

        router.posted.clear()
        connections.set(ipoe, mapOf("tunnel" to "L2TP", "server" to "vpn.example.net", "user" to "rd-l2tp", "password" to "rdl2tp"))
        assertEquals(
            listOf(
                """{"parse":"no interface PPPoE0"}""",
                """{"interface":{"L2TP0":{"description":"Broadband connection","connect":{"via":"$ipoe"},"role":["inet"],"ipcp":{"address":true},"up":true}}}""",
                """{"interface":{"L2TP0":{"authentication":{"identity":"rd-l2tp","password":"rdl2tp","pap":false,"chap":false,"mschap":false,"mschap-v2":false}}}}""",
                """{"interface":{"L2TP0":{"peer":"vpn.example.net"}}}""",
                """{"interface":{"L2TP0":{"ip":{"global":{"enabled":true,"order":0}}}}}""",
                """{"interface":{"$ipoe":{"ip":{"global":{"no":true}}}}}""",
            ),
            router.posted,
        )

        router.posted.clear()
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { connections.set(ipoe, mapOf("ipv4" to "manual", "ip-mask" to "")) }.category)
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { connections.set(ipoe, mapOf("tunnel" to "PPTP")) }.category)
        assertEquals(ErrorCategory.MissingItem, assertFailsWith<DeviceException> { connections.set("WifiMaster0/WifiStation0", mapOf("ssid" to "x")) }.category)
        assertEquals(emptyList(), router.posted)
    }

    @Test
    fun aStationAndAModemChangeAsWirelessIspAndMobileChangeThem() = runTest {
        val router = Fake()
        val connections = Connections(router, components = setOf("pingcheck"))
        val station = "WifiMaster0/WifiStation0"
        connections.set(station, mapOf("security" to "wpa2+wpa3", "channel" to "0", "connection-type" to "always-on", "ttl-out" to ""))
        connections.set("UsbQmi0", mapOf("apn" to "rd.apn", "network-type" to "auto", "roaming" to "false"))
        assertEquals(
            listOf(
                """{"interface":{"$station":{"encryption":{"enable":{"no":false},"wpa":{"no":true},"wpa2":{"no":false},"owe":{"no":true},"wpa3":{"no":false}},"authentication":{"wpa-psk":{"psk":"rdkey"}}}}}""",
                """{"interface":{"WifiMaster0":{"channel":{"channel":false}}}}""",
                """{"interface":{"$station":{"standby":{"enable":false,"timeout":{"seconds":"600"}}}}}""",
                """{"interface":{"$station":{"ip":{"adjust-ttl":{"send":{"no":true}}}}}}""",
                """{"interface":{"UsbQmi0":{"mobile":{"apn":{"apn":"rd.apn","comment":"ru.rd-test"}}}}}""",
                """{"interface":{"UsbQmi0":{"mobile":{"acq":{"no":true}}}}}""",
                """{"interface":{"UsbQmi0":{"mobile":{"roaming":false}}}}""",
            ),
            router.posted,
        )
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { connections.set(station, mapOf("security" to "wep")) }.category)
    }

    @Test
    fun aWireguardConnectionComesFromItsFieldsOrFromAConfFileAndItsPeersAreItsOwnList() = runTest {
        val router = Fake()
        val wireguard = WireguardConnections(router)
        val read = wireguard.read().single()
        assertEquals(listOf("rd-pub", "10.8.0.2/32", "51820", "true", "true", "1"), listOf("public-key", "address", "listen-port", "global", "adjust-mss", "peers").map { read[it] })

        assertEquals("Wireguard1", wireguard.add(mapOf("description" to "rd-wg", "address" to "10.8.0.3/32", "listen-port" to "51821", "dns" to "10.8.0.1", "global" to "yes")))
        assertEquals(
            listOf(
                """{"interface":{"Wireguard1":{"description":"rd-wg"}}}""",
                """{"interface":{"Wireguard1":{"ip":{"global":{"auto":true}}}}}""",
                """{"interface":{"Wireguard1":{"ip":{"address":[{"no":true},{"address":"10.8.0.3","mask":"255.255.255.255"}]}}}}""",
                """{"interface":{"Wireguard1":{"wireguard":{"listen-port":{"port":"51821"}}}}}""",
                """{"interface":{"Wireguard1":{"ip":{"name-server":[{"no":true},{"name-server":"10.8.0.1","port":""}]}}}}""",
                """{"interface":{"Wireguard1":{"wireguard":{"private-key":""}}}}""",
                """{"interface":{"Wireguard1":{"up":true}}}""",
            ),
            router.posted,
        )

        // a change leaves the private key the router does not give back, and only the command makes a new pair
        router.posted.clear()
        wireguard.set("Wireguard0", mapOf("listen-port" to "51822", "private-key" to ""))
        wireguard.run("generate-keys", mapOf("numbers" to "Wireguard0"))
        assertEquals(
            listOf("""{"interface":{"Wireguard0":{"wireguard":{"listen-port":{"port":"51822"}}}}}""", """{"interface":{"Wireguard0":{"wireguard":{"private-key":""}}}}"""),
            router.posted,
        )

        router.posted.clear()
        val conf = "[Interface]\nAddress = 10.8.0.4/32\n"
        assertEquals("Wireguard1", wireguard.add(mapOf("config" to conf, "description" to "rd-wg")))
        assertEquals(
            listOf(
                """{"interface":{"wireguard":{"import":{"import":"W0ludGVyZmFjZV0KQWRkcmVzcyA9IDEwLjguMC40LzMyCg==","name":"","filename":"rd-wg.conf"}}}}""",
                """{"interface":{"Wireguard1":{"description":"rd-wg","up":true}}}""",
            ),
            router.posted,
        )

        val peers = WireguardPeers(router)
        val peer = peers.read().single()
        assertEquals("Wireguard0/rd-peer-a", peer.id)
        assertEquals(
            listOf("rd-test peer", "vpn.example.net:51820", "0.0.0.0/0, ::/0", "25", "any", "true", "10"),
            listOf("comment", "endpoint", "allowed-ips", "keepalive", "via", "online", "rxbytes").map { peer[it] },
        )
        router.posted.clear()
        peers.add(mapOf("interface" to "Wireguard0", "public-key" to "rd-peer-b", "allowed-ips" to "10.8.0.0/24, fd00::/64", "keepalive" to "25", "via" to "PPPoE0"))
        peers.set("Wireguard0/rd-peer-a", mapOf("public-key" to "rd-peer-c"))
        peers.remove(listOf("Wireguard0/rd-peer-a"))
        assertEquals(
            listOf(
                """{"interface":{"Wireguard0":{"wireguard":{"peer":{"key":"rd-peer-b","allow-ips":[{"no":true},{"address":"10.8.0.0","mask":"255.255.255.0"},{"address":"fd00::","mask":"64"}],""" +
                    """"keepalive-interval":{"interval":25},"connect":{"via":"PPPoE0"}}}}}}""",
                """{"interface":{"Wireguard0":{"wireguard":{"peer":{"key":"rd-peer-c","allow-ips":[{"no":true},{"address":"0.0.0.0","mask":"0.0.0.0"},{"address":"::","mask":"0"}],""" +
                    """"comment":"rd-test peer","keepalive-interval":{"interval":25},"endpoint":{"address":"vpn.example.net:51820"},"connect":{}}}}}}""",
                """{"interface":{"Wireguard0":{"wireguard":{"peer":{"key":"rd-peer-a","no":true}}}}}""",
                """{"interface":{"Wireguard0":{"wireguard":{"peer":{"key":"rd-peer-a","no":true}}}}}""",
            ),
            router.posted,
        )
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { peers.add(mapOf("interface" to "Gre0", "public-key" to "rd-peer-d")) }.category)
    }

    @Test
    fun openVpnIpTunnelsAndTunnelsToABrokerAreMadeAsThePageMakesThem() = runTest {
        val router = Fake()
        assertEquals("OpenVPN0", OpenVpnConnections(router).add(mapOf("description" to "rd-ovpn", "config" to "client\nremote vpn.example.net 1194\n", "accept-routes" to "yes")))
        assertEquals("Gre1", IpTunnels(router, setOf("gre")).add(mapOf("type" to "Gre", "destination" to "198.51.100.2", "address" to "172.16.1.1/30", "source" to "auto", "global" to "yes")))
        assertEquals("TunnelSixInFour0", SixInFourTunnels(router).add(mapOf("server" to "203.0.113.1", "ipv6-address" to "2001:db8::2", "ipv6-prefix" to "2001:db8:1::/48")))
        assertEquals(
            listOf(
                """{"interface":{"OpenVPN0":{"description":"rd-ovpn"}}}""",
                """{"interface":{"OpenVPN0":{"role":["misc"]}}}""",
                """{"interface":{"OpenVPN0":{"openvpn":{"config":{"config":"client\nremote vpn.example.net 1194\n"}}}}}""",
                """{"interface":{"OpenVPN0":{"openvpn":{"accept-routes":true}}}}""",
                """{"interface":{"OpenVPN0":{"up":true}}}""",
                """{"interface":{"Gre1":{"description":"Gre1"}}}""",
                """{"interface":{"Gre1":{"ip":{"global":{"auto":true}}}}}""",
                """{"ip":{"route":{"default":true,"interface":"Gre1","no":false}}}""",
                """{"interface":{"Gre1":{"ip":{"address":[{"no":true},{"address":"172.16.1.1","mask":"255.255.255.252"}]}}}}""",
                """{"interface":{"Gre1":{"tunnel":{"destination":"198.51.100.2"}}}}""",
                """{"interface":{"Gre1":{"tunnel":{"source":{"auto":"auto"}}}}}""",
                """{"interface":{"Gre1":{"up":true}}}""",
                """{"interface":{"TunnelSixInFour0":{"description":"TunnelSixInFour0"}}}""",
                """{"interface":{"TunnelSixInFour0":{"tunnel":{"destination":"203.0.113.1"}}}}""",
                """{"interface":{"TunnelSixInFour0":{"ipv6":{"address":[{"no":true},{"address":"2001:db8::2"}]}}}}""",
                """{"interface":{"TunnelSixInFour0":{"ipv6":{"prefix":[{"no":true},{"prefix":"2001:db8:1::/48"}]}}}}""",
                """{"interface":{"TunnelSixInFour0":{"up":true}}}""",
            ),
            router.posted,
        )
        val gre = IpTunnels(router, null).read().single()
        assertEquals(listOf("172.16.0.1/30", "198.51.100.1", "PPPoE0", "203.0.113.33"), listOf("address", "destination", "source", "local").map { gre[it] })
        assertEquals(listOf("IPIP"), IpTunnels(router, setOf("ipip")).arguments().single { it.name == "type" }.choices)
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { OpenVpnConnections(router).add(mapOf("description" to "rd-ovpn")) }.category)
    }

    @Test
    fun theComponentsTheTesterLacksAreReadAndTheirCommandsGoToTheirOwnPaths() = runTest {
        val line = DslLine(Fake(mapOf("show/rc/interface" to dsl)), setOf("dsl")).read().single()
        assertEquals(listOf("Dsl0", "showtime", "61000 kbit/s", "auto", "true", null), listOf("name", "line", "rate-down", "operating-mode.mode", "dsl.ginp", "up").map { line[it] })

        val rests = mapOf(
            "POST sms/list" to """{"messages":{"nv-1":{"from":"+70000000000","text":"rd-test","timestamp":"2026-11-17T10:00:00","read":false}},"messages-count":1}""",
            "POST ussd/send" to """{"continued":true}""",
            "GET ussd/send" to """{"response":"rd-test balance"}""",
        )
        val router = Fake(rests = rests)
        val messages = Messages(router)
        val message = messages.read().single()
        assertEquals(listOf("UsbQmi0/nv-1", "+70000000000", "false", "nv"), listOf(message.id, message["from"], message["read"], message["storage"]))
        messages.add(mapOf("to" to "+70000000001", "message" to "rd-test"))
        messages.set("UsbQmi0/nv-1", mapOf("read" to "true"))
        messages.remove(listOf("UsbQmi0/nv-1"))
        val ussd = Ussd(router)
        ussd.set(null, mapOf("request" to "*100#"))
        assertEquals("rd-test balance", ussd.read().single()["response"])
        val storages = Storages(router)
        val drive = storages.read().single()
        assertEquals(listOf("Media0", "rd flash", "RD exfat", "1000", "1 2.0"), listOf(drive.id, drive["name"], drive["partitions"], drive["free"], drive["usb"]))
        storages.run("eject", mapOf("numbers" to "Media0"))
        assertEquals(
            listOf(
                """POST sms/list {"interface":"UsbQmi0"}""",
                """POST sms/send {"interface":"UsbQmi0","to":"+70000000001","message":"rd-test"}""",
                """POST sms/list {"interface":"UsbQmi0"}""",
                """POST sms/read {"interface":"UsbQmi0","id":"nv-1"}""",
                """POST sms/list {"interface":"UsbQmi0"}""",
                """POST sms/delete {"interface":"UsbQmi0","id":"nv-1"}""",
                """POST ussd/send {"interface":"UsbQmi0","request":"*100#"}""",
                "GET ussd/send",
                """POST system/eject {"name":"Media0"}""",
            ),
            router.rested,
        )
        assertEquals(emptyList(), router.posted)

        assertEquals(listOf("RD:/", "opkg, opkg-kmod-fs"), Opkg(router, setOf("opkg-kmod-fs", "opkg", "storage")).read().single().let { listOf(it["disk"], it["components"]) })
        assertEquals(listOf("true", "50000"), IntelliQos(router).read().single().let { listOf(it["enable"], it["rate-limit.PPPoE0.rate"]) })
    }
}
