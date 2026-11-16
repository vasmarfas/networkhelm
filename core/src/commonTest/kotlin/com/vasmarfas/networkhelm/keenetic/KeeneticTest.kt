package com.vasmarfas.networkhelm.keenetic

import com.vasmarfas.networkhelm.journal.EditKind
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.routeros.api.md5
import com.vasmarfas.networkhelm.transport.AnsweringServer
import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.transport.TrustPolicy
import com.vasmarfas.networkhelm.transport.httpReply
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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

class KeeneticTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private val answer = hex(sha256(("RD-TEST-CHALLENGE" + hex(md5("admin:Keenetic Giga:rd-test-secret".encodeToByteArray()))).encodeToByteArray()))

    private val trust = TrustPolicy(MemoryTrustStore()) { false }

    // answers of a Keenetic Launcher with KeeneticOS 5.1.6, names and addresses replaced
    private val shows = mapOf(
        "show/system" to """{"hostname":"rd-test-keenetic","domainname":"WORKGROUP","cpuload":7,"memory":"38428/131072","memtotal":131072,"memfree":48564,"uptime":"93784"}""",
        "show/version" to """{"release":"5.01.C.6.0-1","sandbox":"stable","title":"5.1.6","arch":"mips","ndw4":{"version":"5.1.C.6.0"},"model":"Launcher (KN-1221)","hw_id":"KN-1221","device":"Launcher","description":"Keenetic Launcher (KN-1221)"}""",
        "show/user" to """{"users":[{"user":"admin","readonly":false,"manager":false,"password":"******"}]}""",
        "show/interface" to """{"FastEthernet0":{"id":"FastEthernet0","index":0,"interface-name":"FastEthernet0","type":"FastEthernet","description":"","link":"up","state":"up","mtu":1500,""" +
            """"port":{"0":{"id":"FastEthernet0/0","label":"0","type":"Port","link":"up"}}},""" +
            """"0":{"id":"FastEthernet0/0","index":0,"interface-name":"0","label":"0","type":"Port","link":"up","speed":"100","duplex":"full"},""" +
            """"FastEthernet0/Vlan2":{"id":"FastEthernet0/Vlan2","interface-name":"ISP","type":"Vlan","description":"Broadband connection","link":"up","connected":"yes","state":"up","mtu":1500,"security-level":"public","mac":"02:00:5e:10:00:a1"},""" +
            """"WifiMaster0":{"id":"WifiMaster0","interface-name":"WifiMaster0","type":"WifiMaster","link":"up","state":"up","channel":9,"bandwidth":"40","temperature":56,"bitrate":300000000,"busy-channels":[7,8,9]},""" +
            """"WifiMaster0/AccessPoint0":{"id":"WifiMaster0/AccessPoint0","interface-name":"AccessPoint","type":"AccessPoint","description":"Wi-Fi access point","link":"up","state":"up","group":"Bridge0","mac":"02:00:5e:10:00:a2","ssid":"rd-test-wifi","encryption":"wpa2,wpa3"},""" +
            """"WifiMaster0/AccessPoint1":{"id":"WifiMaster0/AccessPoint1","index":1,"interface-name":"WifiMaster0/AccessPoint1","type":"AccessPoint","description":"","link":"down","state":"down","mtu":1500,"encryption":""},""" +
            """"Bridge0":{"id":"Bridge0","index":0,"interface-name":"Home","type":"Bridge","description":"Home network","link":"up","state":"up","mtu":1500,"address":"192.168.1.1","mask":"255.255.255.0","uptime":3725,"security-level":"private","summary":{"layer":{"ipv4":"running"}}},""" +
            """"PPPoE0":{"id":"PPPoE0","interface-name":"PPPoE0","type":"PPPoE","description":"rd-test provider","link":"up","connected":"yes","state":"up","role":["inet"],"mtu":1492,"address":"203.0.113.33","mask":"255.255.255.255","uptime":3725,"global":true,"defaultgw":true,"priority":32767,"security-level":"public"}}""",
        "show/rc/interface" to """{"Bridge0":{"rename":"Home","description":"Home network","include":[{"interface":"FastEthernet0/Vlan1"},{"interface":"WifiMaster0/AccessPoint0"}],"up":true},""" +
            """"WifiMaster0":{"compatibility":[{"annex":"BGN"}],"channel":{"width":"40-below"},"up":true},""" +
            """"WifiMaster0/AccessPoint0":{"ssid":"rd-test-wifi","authentication":{"wpa-psk":{"psk":"rd-test-key"}},"encryption":{"enable":true,"wpa2":true,"wpa3":{"enabled":true}},"up":true},""" +
            """"WifiMaster0/AccessPoint1":{"ssid":"rd-test-guest"},""" +
            """"PPPoE0":{"description":"rd-test provider","ip":{"access-group":[{"acl":"_WEBADMIN_PPPoE0","direction":"in"}]},"up":true}}""",
        "show/ip/hotspot" to """{"host":[{"mac":"02:00:5e:10:00:01","via":"02:00:5e:10:00:01","ip":"192.168.1.33","hostname":"rd-test-phone","name":"rd-test","interface":{"id":"Bridge0","name":"Home"},"registered":true,""" +
            """"access":"permit","priority":6,"active":true,"rxbytes":123456,"txbytes":654321,"ap":"WifiMaster0/AccessPoint0","ssid":"rd-test-wifi","uptime":3725},""" +
            """{"mac":"02:00:5e:10:00:02","via":"02:00:5e:10:00:02","ip":"0.0.0.0","hostname":"","name":"","interface":{"id":"Bridge0","name":"Home"},"registered":false,"access":"permit","active":false,"rxbytes":0,"txbytes":0,"link":"down","uptime":0}]}""",
        "show/rc/known" to """{"host":{"rd-test":{"mac":"02:00:5e:10:00:01"}}}""",
        "show/rc/ip/dhcp/host" to """[{"mac":"02:00:5e:10:00:01","ip":"192.168.1.33"}]""",
        "show/ip/dhcp/bindings" to """{"lease":[{"ip":"192.168.1.87","mac":"02:00:5e:10:00:02","hostname":"rd-test-pc","name":"rd-test-pc","expires":9334}]}""",
        "show/rc/ip/dhcp/pool" to """{"_WEBADMIN":{"range":{"begin":"192.168.1.33","end":"192.168.1.152","size":120},"lease":25200,"bind":{"interface":"Bridge0"},"enable":true}}""",
        "show/ip/dhcp/pool" to """{"_WEBADMIN":{"network":"192.168.1.1/24","state":"running","size":120,"used":1}}""",
        "show/rc/ip/nat" to """[{"interface":"Bridge0"}]""",
        "show/rc/access-list" to """[{"acl":"_WEBADMIN_PPPoE0","index":"r0","action":"permit","protocol":"tcp","source":"198.51.100.0","source-mask":"255.255.255.0","destination":"0.0.0.0","destination-mask":"0.0.0.0","dst-port-operator":"eq","destination-port":"22","description":"rd-test office"},""" +
            """{"acl":"_WEBADMIN_PPPoE0","index":"r1","action":"deny","protocol":"udp","source":"0.0.0.0","source-mask":"0.0.0.0","destination":"192.0.2.7","destination-mask":"255.255.255.255","src-port-operator":"range","source-port":"1000","source-end-port":"2000","disable":true},""" +
            """{"acl":"_WEBADMIN_PPPoE0","auto-delete":true}]""",
        "show/internet/status" to """{"internet":true,"gateway":{"interface":"PPPoE0","failures":0}}""",
        "show/ndns" to """{"name":"rd-test","domain":"netcraze.link","access":"cloud"}""",
        "show/ip/name-server" to """{"server":[{"address":"192.0.2.53","domain":"","interface":"PPPoE0"},{"address":"9.9.9.9","domain":"","interface":"PPPoE0"}]}""",
        "show/clock/date" to """{"weekday":0,"day":15,"month":11,"year":2026,"hour":11,"min":37,"sec":57,"dst":"inactive","tz":[{"locality":"Europe/Moscow"}]}""",
        "show/identification" to """{"servicetag":"000-000-000-000-000","serial":"S0000000000000","hwid":"KN-1221"}""",
        "show/rc/components/auto-update" to """{"disable":false}""",
        "show/rc/wireguard-server" to """{"config":{"nat":true,"interface":"Bridge0","subnet":"172.16.6.1/24","enable":false}}""",
        "show/ip/hotspot/summary" to """{"t":0,"host":[{"active":true,"name":"rd-test","mac":"02:00:5e:10:00:01","sumbytes":3000,"rxbytes":1000,"txbytes":2000},{"type":"others","sumbytes":0,"rxbytes":0,"txbytes":0}]}""",
        "show/interface/channel-utilization/chart" to """{"ch":9,"ech":13,"bw":"40","since":39,"data":[{"t":40473,"v":{"load":51,"flags":"v"}},{"t":40476,"v":{"load":53,"flags":"v"}}]}""",
        "show/ip/route" to """[{"destination":"0.0.0.0/0","gateway":"0.0.0.0","interface":"PPPoE0","metric":1000,"flags":"U","proto":"boot"},{"destination":"192.168.1.0/24","gateway":"0.0.0.0","interface":"Bridge0","metric":1000,"flags":"U","proto":"kernel"}]""",
        "show/ip/arp" to """{"status":[{"status":"error","code":"7405600","ident":"Command::Base","message":"no such command: arp."}]}""",
        "show/rc/ip/route" to """[{"network":"192.0.2.0","mask":"255.255.255.0","interface":"Bridge0","index":"4acd6dd9b63e1c21b88214f44c2841aa","comment":"rd-test"}]""",
        "show/tags" to """{"tag":["cli","http","readonly","printers"]}""",
        "show/rc/user" to """{"admin":{"password":{"md5":{"hash":"0123456789abcdef0123456789abcdef"}},"tag":["cli","http"]}}""",
        "show/running-config" to """{"message":["! ${'$'}${'$'}${'$'} Model: Keenetic Launcher","system","    hostname rd-test-keenetic","!"]}""",
        "show/log" to """{"log":{"951":{"message":{"level":"Warning","label":"W","message":"Core::Syslog: last message repeated 6 times."},"timestamp":"Nov 15 11:38:54","ident":"ndm","id":951},""" +
            """"950":{"message":{"level":"Error","label":"E","message":"Core::Scgi::ThreadPool: not found: \"kernel\"."},"timestamp":"Nov 15 11:38:54","ident":"ndm","id":950}},"continued":false}""",
    )

    private val counters = mapOf(
        "PPPoE0" to """{"rxbytes":257515328,"txbytes":46846154,"rxpackets":224172,"txpackets":115775,"rxspeed":40522,"txspeed":180300}""",
        "Bridge0" to """{"rxbytes":7594211,"txbytes":9050909,"rxspeed":0,"txspeed":0}""",
    )

    // a command of POST /rci/ answered as the router does: a show from the answers above, a change with nothing
    private fun answerOf(command: JsonObject): JsonElement {
        var at = command["show"] as? JsonObject ?: return JsonObject(emptyMap())
        val path = mutableListOf<String>()
        while (at.size == 1) {
            val (key, inner) = at.entries.single()
            if (inner !is JsonObject) break
            path += key
            at = inner
        }
        val result = when {
            path == listOf("interface") && "stat" in at -> JsonObject(
                mapOf(
                    "stat" to JsonArray(
                        (at["stat"] as JsonArray).map { asked ->
                            val name = ((asked as JsonObject)["name"] as JsonPrimitive).content
                            counters[name]?.let(Json::parseToJsonElement) ?: Json.parseToJsonElement("""{"status":[{"status":"error","message":"unable to find $name"}]}""")
                        },
                    ),
                ),
            )
            else -> shows["show/" + path.joinToString("/")]?.let(Json::parseToJsonElement) ?: Json.parseToJsonElement("""{"status":[{"status":"error","message":"no such command"}]}""")
        }
        return JsonObject(mapOf("show" to path.foldRight(result) { key, inner -> JsonObject(mapOf(key to inner)) }))
    }

    private fun target(request: String) = request.substringAfter(' ').substringBefore(' ')

    private fun rci(request: String) = when (val path = target(request).removePrefix("/rci/")) {
        "" -> httpReply(200, JsonArray((Json.parseToJsonElement(request.substringAfter("\r\n\r\n")) as JsonArray).map { answerOf(it as JsonObject) }).toString())
        else -> shows[path]?.let { httpReply(200, it) } ?: httpReply(404)
    }

    private fun withLogin(expired: () -> Boolean = { false }) = AnsweringServer { request ->
        val target = target(request)
        when {
            target == "/auth" && request.startsWith("GET") -> httpReply(401, head = "X-NDM-Realm: Keenetic Giga\r\nX-NDM-Challenge: RD-TEST-CHALLENGE\r\nSet-Cookie: rd-test-7f3a=one; Path=/; HttpOnly\r\n")
            target == "/auth" -> if (answer in request && "Cookie: rd-test-7f3a=one" in request) httpReply(200) else httpReply(401)
            "Cookie: rd-test-7f3a=one" !in request || expired() -> httpReply(401)
            else -> rci(request)
        }
    }

    private suspend fun connected() = connectKeenetic(KeeneticLogin("192.0.2.1", "admin", "rd-test-secret"), trust, withLogin()::open)

    @Test
    fun theLoginAnswersTheChallengeWithSha256OfItAndTheMd5OfThePassword() = runTest {
        val router = withLogin()
        val session = connectKeenetic(KeeneticLogin("192.0.2.1", "admin", "rd-test-secret"), trust, router::open)
        assertEquals(listOf("rd-test-keenetic", "5.1.6", "mips", "Keenetic Launcher (KN-1221)"), with(session.device) { listOf(identity, version, architecture, board) })
        assertEquals(listOf("GET /auth", "POST /auth", "POST /rci/", "GET /rci/show/user"), router.requests.map { it.substringBefore(" HTTP/1.1") })
        assertEquals("""{"login":"admin","password":"$answer"}""", router.requests[1].substringAfter("\r\n\r\n"))
        assertEquals("""[{"show":{"system":{}}},{"show":{"version":{}}},{"show":{"system":{"mode":{}}}}]""", router.requests[2].substringAfter("\r\n\r\n"))
        assertTrue(!session.capabilities.encrypted && session.device.canWrite && session.ssh != null)
    }

    @Test
    fun aUserTheRouterCallsReadOnlyGetsNoWrite() = runTest {
        val router = AnsweringServer { request ->
            when (target(request)) {
                "/auth" -> httpReply(200)
                "/rci/show/user" -> httpReply(200, """{"users":[{"user":"rd_viewer","readonly":true}]}""")
                else -> rci(request)
            }
        }
        assertTrue(!connectKeenetic(KeeneticLogin("192.0.2.1", "rd_viewer", ""), trust, router::open).device.canWrite)
    }

    @Test
    fun aSessionTheRouterForgotLogsInAgain() = runTest {
        var forgotten = false
        val router = withLogin { forgotten.also { forgotten = false } }
        val session = connectKeenetic(KeeneticLogin("192.0.2.1", "admin", "rd-test-secret"), trust, router::open)
        forgotten = true
        assertEquals(8, session.count(MenuPath.parse("interface"), null))
        assertEquals(2, router.requests.count { it.startsWith("POST /auth") })
    }

    @Test
    fun anAccessTokenOfKeeneticOs52GoesInItsHeaderWithNoLogin() = runTest {
        val router = AnsweringServer { request -> if ("X-Ndma-Tkn: rd-test-token" in request) rci(request) else httpReply(401) }
        connectKeenetic(KeeneticLogin("192.0.2.1", "", "rd-test-token"), trust, router::open)
        assertTrue(router.requests.none { target(it) == "/auth" || target(it) == "/rci/show/user" })
        val refused = assertFailsWith<DeviceException> { connectKeenetic(KeeneticLogin("192.0.2.1", "", "rd-test-wrong"), trust, router::open) }
        assertEquals(ErrorCategory.PermissionDenied, refused.category)
    }

    @Test
    fun theInterfacesCarryTheirCountersAndASwitchPortStandsByItsId() = runTest {
        val session = connected()
        val interfaces = MenuPath.parse("interface")
        val bridge = session.get(interfaces, "Bridge0")
        assertEquals(
            listOf("Bridge0", "Home", "192.168.1.1", "running", "true", "false", "1h2m5s", "7594211", "0"),
            listOf("name", "interface-name", "address", "summary.layer.ipv4", "running", "disabled", "uptime", "rxbytes", "rxspeed").map { bridge?.get(it) },
        )
        assertEquals("100", session.get(interfaces, "FastEthernet0/0")?.get("speed"))
        assertTrue(session.get(interfaces, "FastEthernet0")?.values.orEmpty().keys.none { it.startsWith("port.") })
        assertEquals(listOf("false", "true", "rd-test-guest"), listOf("running", "disabled", "ssid").map { session.get(interfaces, "WifiMaster0/AccessPoint1")?.get(it) })
        assertEquals(listOf("print", "set", "monitor-traffic"), session.describe(interfaces).commands)

        val rates = session.run(interfaces, "monitor-traffic", mapOf("interface" to "PPPoE0,Bridge0")).take(2).toList()
        assertEquals(listOf("PPPoE0" to "40522", "Bridge0" to "0"), rates.map { it["name"] to it["rx-bits-per-second"] })
        assertEquals("180300", rates.first()["tx-bits-per-second"])
    }

    @Test
    fun theDashboardSumsUpTheRouterItsWayOutAndItsClients() = runTest {
        val status = connected().get(MenuPath.parse("status"), null)
        assertEquals(
            listOf(
                "Keenetic Launcher (KN-1221)", "5.1.6", "stable", "1d2h3m4s", "2026-11-15 11:37:57", "39350272", "134217728",
                "true", "rd-test provider", "PPPoE", "203.0.113.33", "1h2m5s", "40522", "46846154", "192.0.2.53, 9.9.9.9", "rd-test.netcraze.link",
                "rd-test-wifi (2.4 GHz)", "1", "1",
            ),
            listOf(
                "model", "firmware", "channel", "uptime", "time", "used-memory", "total-memory",
                "internet", "connection", "connection-type", "address", "connected", "rxspeed", "txbytes", "dns-servers", "keendns",
                "wifi", "clients-online", "clients-registered",
            ).map { status?.get(it) },
        )
        assertEquals(
            listOf("Home network", "192.168.1.1", "rd-test-wifi", "Bridge0", "wpa2,wpa3", "true"),
            listOf("network.Bridge0.name", "network.Bridge0.address", "ap.WifiMaster0/AccessPoint0.ssid", "ap.WifiMaster0/AccessPoint0.network", "ap.WifiMaster0/AccessPoint0.security", "ap.WifiMaster0/AccessPoint0.up").map { status?.get(it) },
        )
    }

    @Test
    fun theDashboardTakesWhatTheModelHasAndLeavesOutWhatTheRouterRefuses() = runTest {
        val session = connected()
        val first = session.get(MenuPath.parse("status"), null)
        val second = session.get(MenuPath.parse("status"), null)
        assertEquals(
            listOf("000-000-000-000-000", "S0000000000000", "true", "WireGuard VPN", "false", "rd-test", "1000", "2000", "", "2.4 GHz", "9", "40", "1"),
            listOf(
                "service-tag", "serial", "auto-update", "app.wireguard-server.name", "app.wireguard-server.enabled", "top.0.name", "top.0.rx", "top.0.tx", "top.1.name",
                "radio.WifiMaster0.band", "radio.WifiMaster0.channel", "radio.WifiMaster0.width", "radio.WifiMaster0.clients",
            ).map { first?.get(it) },
        )
        assertEquals(listOf(null, null, null, null), listOf("app.oc-server.name", "content-filter", "remote-access", "extenders").map { first?.get(it) })
        assertEquals("53" to "53", first?.get("radio.WifiMaster0.load") to second?.get("radio.WifiMaster0.load"))
    }

    @Test
    fun clientsJoinTheHostsTheKnownNamesAndTheFixedAddresses() = runTest {
        val clients = (connected().watch(MenuPath.parse("clients")).first() as ListEvent.Snapshot).items.associateBy { it.id }
        assertEquals(setOf("02:00:5e:10:00:01", "02:00:5e:10:00:02"), clients.keys)
        val phone = clients.getValue("02:00:5e:10:00:01")
        assertEquals(
            listOf("rd-test", "192.168.1.33", "Home", "rd-test-wifi (2.4 GHz)", "true", "true", "192.168.1.33", "permit", "123456"),
            listOf("name", "ip", "segment", "connection", "online", "registered", "static-ip", "access", "rxbytes").map { phone[it] },
        )
        val stranger = clients.getValue("02:00:5e:10:00:02")
        assertEquals(listOf("rd-test-pc", "192.168.1.87", "false", "false", "", "2h35m34s"), listOf("name", "ip", "online", "registered", "connection", "expires").map { stranger[it] })
    }

    @Test
    fun wifiSegmentsAndConnectionsAreReadFromTheInterfaces() = runTest {
        val session = connected()
        val ap = session.get(MenuPath.parse("wifi"), "WifiMaster0/AccessPoint0")
        assertEquals(
            listOf("rd-test-wifi", "2.4 GHz", "9", "wpa2,wpa3", "rd-test-key", "Home network", "1"),
            listOf("ssid", "band", "channel", "security", "psk", "segment", "clients").map { ap?.get(it) },
        )
        assertEquals("none", session.get(MenuPath.parse("wifi"), "WifiMaster0/AccessPoint1")?.get("security"))
        val radio = session.get(MenuPath.parse("wifi/radio"), "WifiMaster0")
        assertEquals(listOf("2.4 GHz", "40", "BGN", "40-below", "56", "1"), listOf("band", "width", "standard", "channel-width", "temperature", "access-points").map { radio?.get(it) })

        val home = session.get(MenuPath.parse("segments"), "Bridge0")
        assertEquals(
            listOf("Home", "192.168.1.33-192.168.1.152", "true", "rd-test-wifi", "FastEthernet0/Vlan1, Wi-Fi access point", "1"),
            listOf("interface-name", "dhcp", "nat", "wifi", "members", "clients").map { home?.get(it) },
        )

        val connections = (session.watch(MenuPath.parse("internet")).first() as ListEvent.Snapshot).items
        assertEquals(listOf("FastEthernet0/Vlan2", "PPPoE0"), connections.map { it.id })
        assertEquals(listOf("rd-test provider", "true", "257515328"), listOf("title", "default", "rxbytes").map { connections.last()[it] })
    }

    @Test
    fun theFirewallShowsItsRulesByInterfaceWithNetworksAndPortsAsPeopleWriteThem() = runTest {
        val rules = (connected().watch(MenuPath.parse("firewall")).first() as ListEvent.Snapshot).items
        assertEquals(listOf("r0", "r1"), rules.map { it.id })
        assertEquals(
            listOf("PPPoE0", "in", "0", "permit", "tcp", "198.51.100.0/24", "", "", "22", "rd-test office", "false"),
            listOf("interface", "direction", "position", "action", "protocol", "source", "source-port", "destination", "destination-port", "description", "disabled").map { rules[0][it] },
        )
        assertEquals(listOf("1", "", "1000-2000", "192.0.2.7", "true"), listOf("position", "source", "source-port", "destination", "disabled").map { rules[1][it] })
    }

    @Test
    fun networksAndPortsGoBothWays() {
        assertEquals("192.0.2.0/24", network("192.0.2.0", "255.255.255.0"))
        assertEquals("192.0.2.7", network("192.0.2.7", "255.255.255.255"))
        assertEquals("", network("0.0.0.0", "0.0.0.0"))
        assertEquals("192.0.2.0/255.0.255.0", network("192.0.2.0", "255.0.255.0"))
        assertEquals("192.0.2.0" to "255.255.254.0", address("192.0.2.0/23"))
        assertEquals("192.0.2.9" to "255.255.255.255", address("192.0.2.9"))
        assertEquals("0.0.0.0" to "0.0.0.0", address(""))
        assertEquals(listOf("22", "1000-2000", "<1024", ">1023", ""), listOf(port("eq", "22", null), port("range", "1000", "2000"), port("lt", "1024", null), port("gt", "1023", null), port(null, null, null)))
        assertEquals(listOf(Triple("eq", "22", null), Triple("range", "1000", "2000"), Triple("lt", "1024", null), null), listOf(ports("22"), ports("1000-2000"), ports("<1024"), ports("")))
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { ports("70000") }.category)
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { address("192.0.2.0/33") }.category)
    }

    // RCI tells of a failed command with 200 and a status list, not with the status of HTTP
    @Test
    fun anErrorInTheStatusListOfAnAnswerIsAnError() = runTest {
        val session = connected()
        val failed = assertFailsWith<DeviceException> { session.count(MenuPath.parse("show/ip/arp"), null) }
        assertEquals("no such command: arp.", failed.message)
        assertEquals(ErrorCategory.MissingItem, assertFailsWith<DeviceException> { session.count(MenuPath.parse("show/ip/nat"), null) }.category)
        assertEquals("no input.", failure(Json.parseToJsonElement("""[{"ip":{"static":{"status":[{"status":"message","message":"ok"},{"status":"error","code":"7471107","message":"no input."}]}}}]""")))
    }

    @Test
    fun theLogComesByPostWithTheLevelAmongTheTopics() = runTest {
        val router = withLogin()
        val session = connectKeenetic(KeeneticLogin("192.0.2.1", "admin", "rd-test-secret"), trust, router::open)
        val lines = (session.watch(MenuPath.parse("show/log")).first() as ListEvent.Snapshot).items
        assertEquals(listOf("950", "951"), lines.map { it.id })
        assertEquals(listOf("Nov 15 11:38:54", "ndm,error", "Core::Scgi::ThreadPool: not found: \"kernel\"."), listOf("time", "topics", "message").map { lines.first()[it] })
        assertEquals("POST /rci/show/log", router.requests.last().substringBefore(" HTTP/1.1"))
        assertEquals("""{"max-lines":500}""", router.requests.last().substringAfter("\r\n\r\n"))
        assertEquals("! \$\$\$ Model: Keenetic Launcher\nsystem\n    hostname rd-test-keenetic\n!", session.export(MenuPath.Root))
        val ip = session.describe(MenuPath.parse("show/ip"))
        assertEquals(NodeKind.Directory, ip.kind)
        assertEquals(listOf("route", "arp", "dhcp", "nat", "name-server", "service"), ip.children)
    }

    // the saves that follow the changes are counted apart from them
    private inner class Fake : KeeneticCall {
        val posted = mutableListOf<String>()
        var saves = 0

        override suspend fun show(path: String): JsonElement = Json.parseToJsonElement(shows[path] ?: throw DeviceException(ErrorCategory.MissingItem, "this router has no /rci/$path"))

        override suspend fun post(commands: List<JsonObject>, raising: Boolean): List<JsonElement> = commands.map { command ->
            when {
                command == SAVE -> saves++
                "show" !in command -> posted += command.toString()
            }
            answerOf(command)
        }

        override suspend fun realm() = "Keenetic Giga"

        override suspend fun rest(method: String, path: String, body: JsonElement?): JsonElement = throw DeviceException(ErrorCategory.MissingItem, path)
    }

    private val device = DeviceInfo(Platform.Keenetic, "rd-test", "5.1.6", "mips", "Keenetic Launcher (KN-1221)", emptySet(), setOf("read", "write"))

    @Test
    fun theMenuHasThePagesTheWebInterfaceShowsTheRouterAndEthernetCableTakesTheWiredConnections() = runTest {
        val session = KeeneticSession(Fake(), device, components = setOf("corewireless", "ndns", "ocserver", "tsmb"))
        suspend fun hidden(path: String) = try {
            session.outline(MenuPath.parse(path))
            false
        } catch (e: DeviceException) {
            e.category == ErrorCategory.MissingItem
        }
        val paths = listOf("wifi/acl", "mws/member", "rc/mws", "internet/mobile", "internet/wired", "internet/wisp", "rc/oc-server", "rc/wireguard-server", "show/ndns", "firewall")
        assertEquals(listOf("mws/member", "rc/mws", "internet/mobile", "rc/wireguard-server"), paths.filter { hidden(it) })
        val extender = KeeneticSession(Fake(), device, components = setOf("corewireless"), mode = "extender")
        suspend fun hiddenThere(path: String) = try {
            extender.outline(MenuPath.parse(path))
            false
        } catch (e: DeviceException) {
            e.category == ErrorCategory.MissingItem
        }
        assertEquals(listOf("firewall", "internet/wired"), listOf("firewall", "internet/wired", "wifi/acl", "show/ip/route").filter { hiddenThere(it) })

        suspend fun rows(path: String) = (session.watch(MenuPath.parse(path)).first() as ListEvent.Snapshot).items
        assertEquals(listOf("FastEthernet0/Vlan2", "PPPoE0"), rows("internet/wired").map { it.id })
        assertTrue(rows("internet/wisp").isEmpty())
        val apps = rows("apps").associateBy { it.id }
        assertEquals(listOf("Installed", "Not installed", "Not installed"), listOf("cifs", "vpn-sstp", "torrent").map { apps.getValue(it)["status"] })
    }

    @Test
    fun aRouteThatKeepsItsKeyChangesInPlaceAndOneWithANewKeyIsAddedBeforeTheOldGoes() = runTest {
        val router = Fake()
        val session = KeeneticSession(router, device)
        val routes = MenuPath.parse("ip/route")
        val id = "4acd6dd9b63e1c21b88214f44c2841aa"

        session.set(routes, id, mapOf("comment" to "rd-test 2", "auto" to "yes"))
        assertEquals(listOf("""{"ip":{"route":{"network":"192.0.2.0","mask":"255.255.255.0","interface":"Bridge0","auto":true,"comment":"rd-test 2"}}}"""), router.posted)
        assertTrue(router.saves > 0, "a change goes with a save, as the web interface sends it")

        router.posted.clear()
        session.set(routes, id, mapOf("gateway" to "192.168.1.254", "interface" to null))
        assertEquals(
            listOf(
                """{"ip":{"route":{"network":"192.0.2.0","mask":"255.255.255.0","gateway":"192.168.1.254","comment":"rd-test"}}}""",
                """{"ip":{"route":{"no":true,"index":"$id"}}}""",
            ),
            router.posted,
        )

        router.posted.clear()
        session.remove(routes, listOf(id))
        assertEquals(listOf("""{"ip":{"route":{"no":true,"index":"$id"}}}"""), router.posted)
        assertEquals(id, session.add(routes, mapOf("network" to "192.0.2.0", "mask" to "255.255.255.0", "interface" to "Bridge0", "auto" to "no")))
        assertEquals("""{"ip":{"route":{"network":"192.0.2.0","mask":"255.255.255.0","interface":"Bridge0"}}}""", router.posted.last())
        val routeInterface = session.describe(routes).argument("interface")
        assertEquals("Home network (Bridge0)", routeInterface?.labels?.get("Bridge0"))
        assertTrue("FastEthernet0/0" !in routeInterface?.choices.orEmpty())
    }

    // RCI drops an argument it does not know, and "no known host" with no MAC removes every known host
    @Test
    fun clientsAreNamedPermittedGivenAFixedAddressAndForgottenByTheirMac() = runTest {
        val router = Fake()
        val session = KeeneticSession(router, device)
        val clients = MenuPath.parse("clients")
        session.set(clients, "02:00:5e:10:00:02", mapOf("name" to "rd-test tv", "access" to "deny", "static-ip" to "192.168.1.40"))
        assertEquals(
            listOf(
                """{"known":{"host":{"name":"rd-test tv","mac":"02:00:5e:10:00:02"}}}""",
                """{"ip":{"hotspot":{"host":{"mac":"02:00:5e:10:00:02","deny":true}}}}""",
                """{"ip":{"dhcp":{"host":{"mac":"02:00:5e:10:00:02","ip":"192.168.1.40"}}}}""",
            ),
            router.posted,
        )
        router.posted.clear()
        session.set(clients, "02:00:5e:10:00:01", mapOf("static-ip" to ""))
        assertEquals(listOf("""{"ip":{"dhcp":{"host":{"no":true,"mac":"02:00:5e:10:00:01"}}}}"""), router.posted)
        router.posted.clear()
        session.remove(clients, listOf("02:00:5e:10:00:01"))
        assertEquals(listOf("""{"known":{"host":{"no":true,"mac":"02:00:5e:10:00:01"}}}""", """{"ip":{"dhcp":{"host":{"no":true,"mac":"02:00:5e:10:00:01"}}}}"""), router.posted)
        router.posted.clear()
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { session.remove(clients, listOf("02:00:5e:10:00:02")) }.category)
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { session.add(clients, mapOf("name" to "rd-test", "mac" to "rd-test")) }.category)
        assertEquals(ErrorCategory.MissingItem, assertFailsWith<DeviceException> { session.remove(MenuPath.parse("ip/nat"), listOf("Bridge9")) }.category)
        assertEquals(emptyList(), router.posted)
        session.remove(MenuPath.parse("ip/nat"), listOf("Bridge0"))
        assertEquals("""{"ip":{"nat":{"no":true,"interface":"Bridge0"}}}""", router.posted.single())
    }

    @Test
    fun theNtHashIsMd4OfThePasswordInUtf16() {
        assertEquals("31d6cfe0d16ae931b73c59d7e0c089c0", hex(md4(ByteArray(0))))
        assertEquals("a448017aaf21d8525fc10ae87aa6729d", hex(md4("abc".encodeToByteArray())))
        assertEquals("d9130a8164549fe818874806e1c7014b", hex(md4("message digest".encodeToByteArray())))
        assertEquals("e33b4ddc9c38f2199c3e7b164fcc0536", hex(md4("1234567890".repeat(8).encodeToByteArray())))
        assertEquals("8846f7eaee8fb117ad06bdd830b7586c", ntHash("password"))
    }

    @Test
    fun aPasswordGoesAsTheHashTheRouterKeepsAndNamedThingsGoByTheirNames() = runTest {
        val router = Fake()
        val session = KeeneticSession(router, device)
        val users = MenuPath.parse("user")
        assertEquals(listOf("cli", "http", "readonly", "printers"), session.describe(users).argument("tag")?.choices)
        session.add(users, mapOf("name" to "rd_test", "password" to "rd-test-pw", "tag" to "cli, http"))
        val hash = hex(md5("rd_test:Keenetic Giga:rd-test-pw".encodeToByteArray()))
        val nt = ntHash("rd-test-pw")
        assertEquals("""{"user":{"rd_test":{"tag":["cli","http"],"password":{"nt":{"hash":"$nt"},"md5":{"hash":"$hash"}}}}}""", router.posted.last())
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { session.add(users, mapOf("name" to "rd-test")) }.category)
        session.remove(users, listOf("rd_test"))
        assertEquals("""{"parse":"no user \"rd_test\""}""", router.posted.last())

        val pools = MenuPath.parse("ip/dhcp/pool")
        assertEquals(listOf("192.168.1.33", "25200", "false", "running"), listOf("begin", "lease", "disabled", "state").map { session.get(pools, "_WEBADMIN")?.get(it) })
        session.set(pools, "_WEBADMIN", mapOf("end" to "192.168.1.200", "disabled" to "yes"))
        assertEquals("""{"ip":{"dhcp":{"pool":{"_WEBADMIN":{"range":{"begin":"192.168.1.33","end":"192.168.1.200"},"enable":{"no":true}}}}}}""", router.posted.last())
        session.remove(pools, listOf("_WEBADMIN"))
        assertEquals("""{"parse":"no ip dhcp pool \"_WEBADMIN\""}""", router.posted.last())
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { session.remove(pools, listOf("")) }.category)
    }

    @Test
    fun wifiInterfacesAndSegmentsChangeWhatTheWebInterfaceChanges() = runTest {
        val router = Fake()
        val session = KeeneticSession(router, device)
        session.set(MenuPath.parse("interface"), "WifiMaster0/AccessPoint1", mapOf("disabled" to "yes", "description" to "", "ssid" to "rd-test", "psk" to "rd-test-key-2"))
        assertEquals(
            """{"interface":{"WifiMaster0/AccessPoint1":{"description":{"no":true},"up":{"no":true},"ssid":"rd-test","authentication":{"wpa-psk":{"psk":"rd-test-key-2"}}}}}""",
            router.posted.last(),
        )
        session.set(MenuPath.parse("wifi"), "WifiMaster0/AccessPoint1", mapOf("ssid" to "rd-test", "security" to "wpa2"))
        assertEquals(
            """{"interface":{"WifiMaster0/AccessPoint1":{"ssid":"rd-test","encryption":{"enable":true,"wpa2":true,"wpa3":{"no":true}}}}}""",
            router.posted.last(),
        )
        session.set(MenuPath.parse("wifi"), "WifiMaster0/AccessPoint1", mapOf("security" to "none"))
        assertEquals("""{"interface":{"WifiMaster0/AccessPoint1":{"encryption":{"wpa2":{"no":true},"wpa3":{"no":true},"enable":{"no":true}}}}}""", router.posted.last())
        session.set(MenuPath.parse("segments"), "Bridge0", mapOf("interface-name" to "rd-test home", "address" to "192.0.2.1"))
        assertEquals("""{"interface":{"Bridge0":{"rename":"rd-test home"}}}""", router.posted.last())
        session.set(MenuPath.parse("internet"), "PPPoE0", mapOf("description" to "rd-test isp"))
        assertEquals("""{"interface":{"PPPoE0":{"description":"rd-test isp"}}}""", router.posted.last())
        assertEquals(ErrorCategory.MissingItem, assertFailsWith<DeviceException> { session.set(MenuPath.parse("internet"), "Bridge0", mapOf("description" to "x")) }.category)
    }

    @Test
    fun aRuleOfTheFirewallIsAddedBoundChangedMovedAndRemovedByItsMatch() = runTest {
        val router = Fake()
        val session = KeeneticSession(router, device)
        val firewall = MenuPath.parse("firewall")
        session.add(firewall, mapOf("interface" to "WifiMaster0/AccessPoint1", "action" to "deny", "protocol" to "tcp", "source" to "192.0.2.0/24", "destination-port" to "22", "description" to "rd-test"))
        assertEquals(
            listOf(
                """{"access-list":{"acl":"_WEBADMIN_WifiMaster0/AccessPoint1","action":"deny","protocol":"tcp","source":"192.0.2.0","source-mask":"255.255.255.0","destination":"0.0.0.0","destination-mask":"0.0.0.0","dst-port-operator":"eq","destination-port":"22"}}""",
                """{"interface":{"WifiMaster0/AccessPoint1":{"ip":{"access-group":{"acl":"_WEBADMIN_WifiMaster0/AccessPoint1","direction":"in"}}}}}""",
                """{"access-list":{"acl":"_WEBADMIN_WifiMaster0/AccessPoint1","rule":{"index":0,"description":"rd-test"}}}""",
            ),
            router.posted,
        )

        router.posted.clear()
        session.add(firewall, mapOf("interface" to "PPPoE0", "action" to "permit", "protocol" to "tcp/443", "destination-port" to "8443"))
        session.add(firewall, mapOf("interface" to "PPPoE0", "action" to "permit", "protocol" to "ipip"))
        assertEquals(
            listOf(
                """{"access-list":{"acl":"_WEBADMIN_PPPoE0","action":"permit","protocol":"tcp","source":"0.0.0.0","source-mask":"0.0.0.0","destination":"0.0.0.0","destination-mask":"0.0.0.0","dst-port-operator":"eq","destination-port":"443"}}""",
                """{"access-list":{"acl":"_WEBADMIN_PPPoE0","action":"permit","protocol":"ipip","source":"0.0.0.0","source-mask":"0.0.0.0","destination":"0.0.0.0","destination-mask":"0.0.0.0"}}""",
            ),
            router.posted,
        )
        assertEquals("TCP/22 - Secure Shell (SSH)", session.describe(firewall).arguments.single { it.name == "protocol" }.labels["tcp/22"])

        router.posted.clear()
        session.set(firewall, "r1", mapOf("description" to "rd-test udp", "disabled" to "no"))
        assertEquals(
            listOf(
                """{"access-list":{"acl":"_WEBADMIN_PPPoE0","rule":{"index":1,"description":"rd-test udp"}}}""",
                """{"access-list":{"acl":"_WEBADMIN_PPPoE0","rule":{"index":1,"disable":{"no":true}}}}""",
            ),
            router.posted,
        )

        router.posted.clear()
        session.set(firewall, "r0", mapOf("source" to "198.51.100.7"))
        assertEquals(
            listOf(
                """{"access-list":{"acl":"_WEBADMIN_PPPoE0","action":"permit","protocol":"tcp","source":"198.51.100.7","source-mask":"255.255.255.255","destination":"0.0.0.0","destination-mask":"0.0.0.0","dst-port-operator":"eq","destination-port":"22"}}""",
                """{"access-list":{"acl":"_WEBADMIN_PPPoE0","rule":{"index":2,"new-index":0}}}""",
                """{"access-list":{"acl":"_WEBADMIN_PPPoE0","no":true,"action":"permit","protocol":"tcp","source":"198.51.100.0","source-mask":"255.255.255.0","destination":"0.0.0.0","destination-mask":"0.0.0.0","dst-port-operator":"eq","destination-port":"22"}}""",
                """{"access-list":{"acl":"_WEBADMIN_PPPoE0","rule":{"index":0,"description":"rd-test office"}}}""",
            ),
            router.posted,
        )

        router.posted.clear()
        session.move(firewall, listOf("r1"), "r0")
        session.remove(firewall, listOf("r1"))
        assertEquals(
            listOf(
                """{"access-list":{"acl":"_WEBADMIN_PPPoE0","rule":{"index":1,"new-index":0}}}""",
                """{"access-list":{"acl":"_WEBADMIN_PPPoE0","no":true,"action":"deny","protocol":"udp","source":"0.0.0.0","source-mask":"0.0.0.0","destination":"192.0.2.7","destination-mask":"255.255.255.255","src-port-operator":"range","source-port":"1000","source-end-port":"2000"}}""",
            ),
            router.posted,
        )
        assertEquals(ErrorCategory.Unsupported, assertFailsWith<DeviceException> { session.set(firewall, "r0", mapOf("interface" to "Bridge0")) }.category)

        router.posted.clear()
        session.remove(firewall, listOf("r0", "r1"))
        assertEquals(
            listOf(
                """{"interface":{"PPPoE0":{"ip":{"access-group":{"no":true,"acl":"_WEBADMIN_PPPoE0","direction":"in"}}}}}""",
                """{"parse":"no access-list \"_WEBADMIN_PPPoE0\""}""",
            ),
            router.posted.drop(2),
        )
    }

    @Test
    fun theJournalWritesAnEditAsRciTakesIt() {
        val routes = MenuPath.parse("ip/route")
        assertEquals("""{"ip":{"route":{"network":"192.0.2.0","comment":"rd-test"}}}""", keeneticCall(routes, EditKind.Add, emptyList(), mapOf("network" to "192.0.2.0", "comment" to "rd-test")))
        assertEquals("""{"ip":{"route":{"no":true,"index":"4acd"}}}""", keeneticCall(routes, EditKind.Remove, listOf("4acd"), emptyMap()))
        assertEquals("""{"user":{"rd_test":{"tag":"cli"}}}""", keeneticCall(MenuPath.parse("user"), EditKind.Add, emptyList(), mapOf("name" to "rd_test", "tag" to "cli")))
        assertEquals("""{"interface":{"Bridge0":{"description":{"no":true}}}}""", keeneticCall(MenuPath.parse("interface"), EditKind.Set, listOf("Bridge0"), mapOf("description" to null)))
        assertEquals("# firewall: r1 before r0", keeneticCall(MenuPath.parse("firewall"), EditKind.Move, listOf("r1"), emptyMap(), "r0"))
    }

    @Test
    fun theConsolePrintsAnAnswerAsTheCliDoes() {
        assertEquals(
            "Command::Base error[7405600]: no such command: rd.\r\n",
            cliText(Json.parseToJsonElement("""{"prompt":"(config)","status":[{"status":"error","code":"7405600","ident":"Command::Base","message":"no such command: rd."}]}""") as JsonObject),
        )
        assertEquals("system\r\n    hostname rd-test\r\n", cliText(Json.parseToJsonElement("""{"message":["system","    hostname rd-test"]}""") as JsonObject))
        assertEquals(
            "\r\n   release: 5.1.6\r\n       ndm: \r\n               exact: 0-996b1e0\r\n       tag: cli, http\r\n\r\nNetwork::RoutingTable: added static route.\r\n",
            cliText(Json.parseToJsonElement("""{"release":"5.1.6","ndm":{"exact":"0-996b1e0"},"tag":["cli","http"],"status":[{"status":"message","ident":"Network::RoutingTable","message":"added static route."}]}""") as JsonObject),
        )
    }

    @Test
    fun theTunnelGoesToTheAddressOfTheHomeNetworkTheCliNames() {
        val bridge = "\u001b[K\n                id: Bridge0\n             index: 0\n    interface-name: Home\n              type: Bridge\n" +
            "           address: 192.168.1.1\n              mask: 255.255.255.0\n    security-level: private\n\n              ipv6: \n             addresses: \n" +
            "                   address: fdf3:a5b5:ed51:0:52ff:20ff:fec8:bfd5\n"
        assertEquals("192.168.1.1", homeAddress(bridge))
        val all = "Interface, name = \"PPPoE0\": \n                id: PPPoE0\n           address: 198.51.100.7\n    security-level: public\n\n" +
            "Interface, name = \"Bridge1\": \n                id: Bridge1\n    security-level: protected\n           address: 10.1.30.1\n\n" +
            "Interface, name = \"Bridge2\": \n                id: Bridge2\n    security-level: private\n           address: 10.2.0.1\n"
        assertEquals("10.2.0.1", homeAddress(all))
        assertEquals(null, homeAddress("Command::Base error[7405600]: no such interface: Bridge0."))
    }

    @Test
    fun theShowsAskedInOneMomentGoInOneRequest() = runTest {
        val posted = mutableListOf<List<String>>()
        val router = object : KeeneticCall {
            override suspend fun show(path: String): JsonElement = throw DeviceException(ErrorCategory.MissingItem, path)

            override suspend fun post(commands: List<JsonObject>, raising: Boolean): List<JsonElement> {
                posted += commands.map { it.toString() }
                return commands.map { JsonObject(emptyMap()) }
            }

            override suspend fun realm() = "Keenetic Giga"

            override suspend fun rest(method: String, path: String, body: JsonElement?): JsonElement = throw DeviceException(ErrorCategory.MissingItem, path)
        }
        val cache = KeeneticCache(router)
        fun show(vararg path: String) = JsonObject(mapOf("show" to path.reversed().fold(JsonObject(emptyMap())) { inner, key -> JsonObject(mapOf(key to inner)) }))
        val stat = JsonObject(mapOf("show" to JsonObject(mapOf("interface" to JsonObject(mapOf("stat" to JsonArray(emptyList())))))))
        listOf(
            async { cache.post(listOf(show("system"), show("version"))) },
            async { cache.post(listOf(show("interface"), show("system"))) },
            async { cache.post(listOf(stat)) },
        ).awaitAll()
        assertEquals(1, posted.size, "$posted")
        assertEquals(4, posted.single().size, "$posted")
        cache.post(listOf(show("system")))
        cache.post(listOf(stat))
        assertEquals(listOf(stat.toString()), posted.last(), "a live show is asked again, a kept one is not")
        cache.close()
    }

    @Test
    fun theLogIsReadForWhatCameSinceAndAgainWhenTheRouterEndedIt() = runTest {
        val asked = mutableListOf<String>()
        val router = object : KeeneticCall {
            override suspend fun show(path: String): JsonElement = throw DeviceException(ErrorCategory.MissingItem, path)

            override suspend fun post(commands: List<JsonObject>, raising: Boolean): List<JsonElement> = commands.map { JsonObject(emptyMap()) }

            override suspend fun realm() = "Keenetic Giga"

            override suspend fun rest(method: String, path: String, body: JsonElement?): JsonElement {
                asked += "$method $path"
                fun line(id: Int) = """"$id":{"message":{"level":"Info","label":"I","message":"rd-test $id"},"timestamp":"Nov 17 12:00:0$id","ident":"ndm","id":$id}"""
                return Json.parseToJsonElement(
                    when (asked.size) {
                        1 -> """{"log":{${line(1)},${line(2)}},"continued":true}"""
                        2 -> """{"log":{${line(3)}},"continued":true}"""
                        3 -> """{"continued":false}"""
                        else -> """{"log":{${line(3)},${line(4)}},"continued":true}"""
                    },
                )
            }
        }
        val session = KeeneticSession(router, DeviceInfo(Platform.Keenetic, "rd-test", "5.1.8", "mips", "", emptySet(), setOf("read")))
        suspend fun ids() = (session.watch(MenuPath.parse("show/log")).first() as ListEvent.Snapshot).items.map { it.id }
        assertEquals(listOf("1", "2"), ids())
        assertEquals(listOf("1", "2", "3"), ids())
        assertEquals(listOf("3", "4"), ids())
        assertEquals(listOf("POST show/log", "GET show/log", "GET show/log", "POST show/log"), asked)
        session.close()
    }

    @Test
    fun theConsoleSendsALineAtATimeAndKeepsThePromptOfTheRouter() = runTest {
        val sent = mutableListOf<String>()
        val router = object : KeeneticCall {
            override suspend fun show(path: String): JsonElement = throw DeviceException(ErrorCategory.MissingItem, path)

            override suspend fun post(commands: List<JsonObject>, raising: Boolean): List<JsonElement> {
                val line = (commands.single()["parse"] as JsonPrimitive).content
                sent += line
                val prompt = if (line.startsWith("interface")) "(config-if)" else "(config)"
                return listOf(Json.parseToJsonElement("""{"parse":{"prompt":"$prompt","status":[{"status":"message","ident":"Core::Configurator","message":"done."}]}}"""))
            }

            override suspend fun realm() = "Keenetic Giga"

            override suspend fun rest(method: String, path: String, body: JsonElement?): JsonElement = throw DeviceException(ErrorCategory.MissingItem, path)
        }
        val shell = KeeneticConsole { router }.shell("xterm", 80, 24)
        val seen = StringBuilder()
        suspend fun until(text: String) {
            val buffer = ByteArray(64)
            while (text !in seen) {
                val count = shell.read(buffer, 0, buffer.size)
                if (count < 0) break
                seen.append(buffer.decodeToString(0, count))
            }
        }
        shell.write("interface Bridge0\r\n".encodeToByteArray())
        until("(config-if)> ")
        shell.write("exti\u007f\u007fit\r".encodeToByteArray())
        until("done.\r\n(config)> ")
        shell.write("\u001b[A".encodeToByteArray())
        until("\r(config)> exit\u001b[K")
        shell.close()
        assertEquals(listOf("interface Bridge0", "exit"), sent)
    }

    @Test
    fun aCommandThatGoesOnIsReadAtItsOwnPathUntilItEnds() = runTest {
        val read = mutableListOf<String>()
        val router = object : KeeneticCall {
            override suspend fun show(path: String): JsonElement = throw DeviceException(ErrorCategory.MissingItem, path)

            override suspend fun post(commands: List<JsonObject>, raising: Boolean): List<JsonElement> =
                listOf(Json.parseToJsonElement("""{"parse":{"message":["PING rd-test (192.0.2.7) 56 (84) bytes of data."],"continued":true}}"""))

            override suspend fun realm() = "Keenetic Giga"

            override suspend fun rest(method: String, path: String, body: JsonElement?): JsonElement {
                read += "$method $path"
                return Json.parseToJsonElement(
                    when {
                        path != "tools/ping" -> """{"status":[{"status":"error","code":"7471107","ident":"Command::Root","message":"no input [http/rci 192.0.2.1]."}]}"""
                        read.count { it == "GET tools/ping" } == 1 -> """{"message":["84 bytes from 192.0.2.7: icmp_req=1, ttl=64, time=0.40 ms."],"continued":true}"""
                        else -> """{"message":["","--- rd-test ping statistics ---"]}"""
                    },
                )
            }
        }
        val shell = KeeneticConsole { router }.shell("xterm", 80, 24)
        val seen = StringBuilder()
        val buffer = ByteArray(256)
        shell.write("tools ping rd-test count 2\r".encodeToByteArray())
        while ("statistics ---\r\n(config)> " !in seen) {
            val count = shell.read(buffer, 0, buffer.size)
            if (count < 0) break
            seen.append(buffer.decodeToString(0, count))
        }
        shell.close()
        assertEquals(listOf("GET tools/ping/rd-test/count", "GET tools/ping/rd-test", "GET tools/ping", "GET tools/ping"), read)
        assertTrue("icmp_req=1" in seen.toString())
    }

    @Test
    fun theWordsOfTheWindowsComeFromTheDictionaryOfTheWebInterface() {
        val dictionary = Json.parseToJsonElement("""{"menu":{"dashboard":"Системный монитор","segments":"Сегменты"},"dashboard":{"card_internet":{"mac":"MAC-адрес"}}}""")
        val russian = wordsOf(dictionary, "ru")
        assertEquals("Системный монитор", russian["Dashboard"])
        assertEquals("MAC-адрес", russian["MAC Address"])
        assertEquals("Интерфейсы", russian["Interfaces"])
        assertEquals(null, russian["Firewall"])
        assertEquals(null, wordsOf(dictionary, "en")["Interfaces"])
        assertEquals("Интерфейсы", wordsOf(null, "ru")["Interfaces"])
    }

    private class Silent : ByteStream {
        private val closed = CompletableDeferred<Unit>()

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            closed.await()
            return -1
        }

        override suspend fun write(bytes: ByteArray) = Unit

        override fun close() {
            closed.complete(Unit)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aRouterThatGoesSilentEndsTheRequestInsteadOfHoldingItForever() = runTest {
        val silent = HttpKeenetic("192.0.2.1", "", "rd-test-token") { Silent() }
        assertEquals(ErrorCategory.ConnectionLost, assertFailsWith<DeviceException> { silent.show("show/system") }.category)
        assertEquals(60_000, testScheduler.currentTime)
    }

    @Test
    fun interfacesThatShareATitleAreNamedByTheirInterfaceToo() {
        val rows = listOf(
            Item("FastEthernet0/Vlan2", mapOf("description" to "Broadband connection")),
            Item("PPPoE0", mapOf("description" to "Broadband connection")),
            Item("Bridge0", mapOf("description" to "Home network")),
        )
        assertEquals(
            mapOf("FastEthernet0/Vlan2" to "Broadband connection (FastEthernet0/Vlan2)", "PPPoE0" to "Broadband connection (PPPoE0)", "Bridge0" to "Home network"),
            titles(rows),
        )
    }
}
