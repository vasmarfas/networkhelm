package com.vasmarfas.networkhelm.keenetic

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.routeros.api.md5
import com.vasmarfas.networkhelm.transport.AnsweringServer
import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.transport.TrustPolicy
import com.vasmarfas.networkhelm.transport.httpReply
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KeeneticTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private val answer = hex(sha256(("RD-TEST-CHALLENGE" + hex(md5("admin:Keenetic Giga:rd-test-secret".encodeToByteArray()))).encodeToByteArray()))

    private val trust = TrustPolicy(MemoryTrustStore()) { false }

    private val shows = mapOf(
        "show/system" to """{"hostname":"rd-test-keenetic","cpuload":7,"memory":"131072/262144","memtotal":262144,"memfree":131072,"uptime":"93784"}""",
        "show/version" to """{"release":"4.2.3","title":"4.2.3","arch":"mips","model":"Giga","hw_id":"KN-1011","description":"Keenetic Giga (KN-1011)"}""",
        "show/interface" to """{"GigabitEthernet0":{"id":"GigabitEthernet0","interface-name":"GigabitEthernet0","type":"GigabitEthernet","link":"up","state":"up","mtu":1500,"uptime":3725,"mac":"50:ff:20:00:00:01"},""" +
            """"Bridge0":{"interface-name":"Home","type":"Bridge","description":"Home network","link":"up","state":"up","address":"192.168.1.1","mask":"255.255.255.0","summary":{"layer":{"ipv4":"running"}}},""" +
            """"WifiMaster0/AccessPoint1":{"interface-name":"AccessPoint_2G_guest","type":"AccessPoint","description":"rd-test guest","link":"down","state":"down","traits":["Ethernet","Ip"]}}""",
        "show/ip/hotspot" to """{"host":[{"mac":"aa:bb:cc:00:00:01","ip":"192.168.1.33","hostname":"rd-test-phone","name":"rd-test","interface":{"id":"Bridge0","name":"Home"},"active":true,"rxbytes":123456,"uptime":3725}]}""",
        "show/ip/route" to """[{"destination":"0.0.0.0/0","gateway":"100.64.0.1","interface":"GigabitEthernet0","metric":0},{"destination":"192.168.1.0/24","gateway":"0.0.0.0","interface":"Bridge0","metric":0}]""",
        "show/ip/arp" to """{"status":[{"status":"error","code":"7405600","ident":"Command::Base","message":"no such command: arp."}]}""",
    )

    private fun target(request: String) = request.substringAfter(' ').substringBefore(' ')

    private fun show(request: String) = shows[target(request).removePrefix("/rci/")]?.let { httpReply(200, it) } ?: httpReply(404)

    private fun withLogin(expired: () -> Boolean = { false }) = AnsweringServer { request ->
        val target = target(request)
        when {
            target == "/auth" && request.startsWith("GET") -> httpReply(401, head = "X-NDM-Realm: Keenetic Giga\r\nX-NDM-Challenge: RD-TEST-CHALLENGE\r\nSet-Cookie: rd-test-7f3a=one; Path=/; HttpOnly\r\n")
            target == "/auth" -> if (answer in request && "Cookie: rd-test-7f3a=one" in request) httpReply(200) else httpReply(401)
            "Cookie: rd-test-7f3a=one" !in request || expired() -> httpReply(401)
            else -> show(request)
        }
    }

    @Test
    fun theLoginAnswersTheChallengeWithSha256OfItAndTheMd5OfThePassword() = runTest {
        val router = withLogin()
        val session = connectKeenetic(KeeneticLogin("192.0.2.1", "admin", "rd-test-secret"), trust, router::open)
        assertEquals(listOf("rd-test-keenetic", "4.2.3", "mips", "Keenetic Giga (KN-1011)"), with(session.device) { listOf(identity, version, architecture, board) })
        assertEquals(listOf("GET /auth", "POST /auth", "GET /rci/show/system", "GET /rci/show/version"), router.requests.map { it.substringBefore(" HTTP/1.1") })
        assertEquals("""{"login":"admin","password":"$answer"}""", router.requests[1].substringAfter("\r\n\r\n"))
        assertTrue(!session.capabilities.encrypted && !session.device.canWrite)
    }

    @Test
    fun aSessionTheRouterForgotLogsInAgain() = runTest {
        var forgotten = false
        val router = withLogin { forgotten.also { forgotten = false } }
        val session = connectKeenetic(KeeneticLogin("192.0.2.1", "admin", "rd-test-secret"), trust, router::open)
        forgotten = true
        assertEquals(3, session.count(MenuPath.parse("interface"), null))
        assertEquals(2, router.requests.count { it.startsWith("POST /auth") })
    }

    @Test
    fun anAccessTokenOfKeeneticOs52GoesInItsHeaderWithNoLogin() = runTest {
        val router = AnsweringServer { request -> if ("X-Ndma-Tkn: rd-test-token" in request) show(request) else httpReply(401) }
        connectKeenetic(KeeneticLogin("192.0.2.1", "", "rd-test-token"), trust, router::open)
        assertTrue(router.requests.none { target(it) == "/auth" })
        val refused = assertFailsWith<DeviceException> { connectKeenetic(KeeneticLogin("192.0.2.1", "", "rd-test-wrong"), trust, router::open) }
        assertEquals(ErrorCategory.PermissionDenied, refused.category)
    }

    @Test
    fun theWindowsHoldTheFieldsOfRciUnderTheCommandsOfTheCli() = runTest {
        val session = connectKeenetic(KeeneticLogin("192.0.2.1", "admin", "rd-test-secret"), trust, withLogin()::open)
        val interfaces = MenuPath.parse("interface")
        val bridge = session.get(interfaces, "Bridge0")
        assertEquals(
            listOf("Bridge0", "Home", "192.168.1.1", "running", "true", "false"),
            listOf("id", "interface-name", "address", "summary.layer.ipv4", "running", "disabled").map { bridge?.get(it) },
        )
        val guest = session.get(interfaces, "WifiMaster0/AccessPoint1")
        assertEquals(listOf("false", "true", "Ethernet,Ip"), listOf("running", "disabled", "traits").map { guest?.get(it) })
        assertEquals("1h2m5s", session.get(interfaces, "GigabitEthernet0")?.get("uptime"))

        val hosts = (session.watch(MenuPath.parse("ip/hotspot")).first() as ListEvent.Snapshot).items
        assertEquals(listOf("192.168.1.33", "Home", "true", "1h2m5s"), listOf("ip", "interface.name", "active", "uptime").map { hosts.single()[it] })
        val routes = (session.watch(MenuPath.parse("ip/route")).first() as ListEvent.Snapshot).items
        assertEquals(listOf("0" to "0.0.0.0/0", "1" to "192.168.1.0/24"), routes.map { it.id to it["destination"] })

        val resource = session.get(MenuPath.parse("system/resource"), null)
        assertEquals(
            listOf("7", "268435456", "134217728", "1d2h3m4s", "4.2.3", "Keenetic Giga (KN-1011)"),
            listOf("cpu-load", "total-memory", "free-memory", "uptime", "version", "board-name").map { resource?.get(it) },
        )
        val ip = session.describe(MenuPath.parse("ip"))
        assertEquals(NodeKind.Directory to listOf("route", "arp", "dhcp", "hotspot"), ip.kind to ip.children)
        assertEquals(ErrorCategory.Unsupported, assertFailsWith<DeviceException> { session.set(interfaces, "Bridge0", mapOf("description" to "x")) }.category)
    }

    // RCI tells of a failed command with 200 and a status list, not with the status of HTTP
    @Test
    fun anErrorInTheStatusListOfAnAnswerIsAnError() = runTest {
        val session = connectKeenetic(KeeneticLogin("192.0.2.1", "admin", "rd-test-secret"), trust, withLogin()::open)
        val failed = assertFailsWith<DeviceException> { session.count(MenuPath.parse("ip/arp"), null) }
        assertEquals("no such command: arp.", failed.message)
        assertEquals(ErrorCategory.MissingItem, assertFailsWith<DeviceException> { session.count(MenuPath.parse("log"), null) }.category)
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
}
