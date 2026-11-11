package com.vasmarfas.networkhelm.vyos

import com.vasmarfas.networkhelm.journal.EditKind
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.transport.AnsweringServer
import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.transport.TrustPolicy
import com.vasmarfas.networkhelm.transport.httpReply
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VyosTest {

    private val config = """
        set interfaces ethernet eth0 address '192.0.2.1/24'
        set interfaces ethernet eth0 address '192.0.2.2/24'
        set interfaces ethernet eth0 description 'WAN'
        set interfaces ethernet eth0 hw-id '00:50:56:00:00:01'
        set interfaces ethernet eth1 address '198.51.100.1/24'
        set interfaces ethernet eth1 disable
        set interfaces ethernet eth1 vif 10 address '203.0.113.1/24'
        set nat source rule 100 outbound-interface name 'eth0'
        set nat source rule 100 source address '198.51.100.0/24'
        set nat source rule 100 translation address 'masquerade'
        set protocols static route 0.0.0.0/0 next-hop 192.0.2.254 distance '10'
        set protocols static route 10.0.0.0/8 next-hop 198.51.100.254
        set protocols static route 10.0.0.0/8 next-hop 198.51.100.253
        set system host-name 'rd-test-vyos'
        set system name-server '192.0.2.53'
        set system login user rd-test authentication encrypted-password 'rd-test-hash'
    """.trimIndent()

    private class Router(config: String, private val fail: (String, JsonObject) -> DeviceException? = { _, _ -> null }) : VyosCall {
        val tree = configTree(config)
        val calls = mutableListOf<Pair<String, JsonObject>>()

        override suspend fun call(endpoint: String, body: JsonObject): JsonElement {
            calls += endpoint to body
            fail(endpoint, body)?.let { throw it }
            val path = MenuPath((body["path"] as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty())
            return when (endpoint to (body["op"] as? JsonPrimitive)?.content) {
                "retrieve" to "showConfig" -> subtree(tree, path)?.let { if (path in lists) JsonObject(mapOf(path.segments.last() to it)) else it }
                    ?: throw DeviceException(ErrorCategory.ArgumentValue, "Configuration under specified path is empty")
                "retrieve" to "exists" -> JsonPrimitive(subtree(tree, path) != null)
                else -> JsonNull
            }
        }

        override suspend fun info(): JsonElement = JsonObject(mapOf("version" to JsonPrimitive("1.5.0"), "hostname" to JsonPrimitive("rd-test-vyos")))
    }

    private fun session(router: VyosCall = Router(config), snapshot: String = SNAPSHOT) =
        VyosSession(router, DeviceInfo(Platform.Vyos, "rd-test-vyos", "1.5.0", "", "VyOS", emptySet(), setOf("read", "write")), snapshot = snapshot)

    private fun commands(router: Router) = router.calls.filter { it.first == "configure" }.map { (_, body) ->
        (body["commands"] as JsonArray).map { command ->
            val fields = command as JsonObject
            "${(fields["op"] as JsonPrimitive).content} " + (fields["path"] as JsonArray).joinToString(" ") { (it as JsonPrimitive).content }
        }
    }

    private fun writes(router: Router) = router.calls.filter { it.first != "retrieve" }.map { (endpoint, body) ->
        listOfNotNull(endpoint, (body["op"] as? JsonPrimitive)?.content, (body["file"] as? JsonPrimitive)?.content, (body["confirm_time"] as? JsonPrimitive)?.content).joinToString(" ")
    }

    @Test
    fun theListsShowTheLeavesOfEachElementUnderTheirNamesInTheCli() = runTest {
        val session = session()
        val ethernet = (session.watch(ETHERNET).first() as ListEvent.Snapshot).items
        assertEquals(listOf("eth0", "eth1"), ethernet.map { it.id })
        assertEquals(listOf("eth0", "192.0.2.1/24,192.0.2.2/24", "WAN", "false"), listOf(".name", "address", "description", "disabled").map { ethernet[0][it] })
        assertEquals(listOf("true", "10", "203.0.113.1/24", null), listOf("disabled", "vif", "vif 10 address", "disable").map { ethernet[1][it] })
        val route = session.get(ROUTES, "0.0.0.0/0")
        assertEquals(listOf("192.0.2.254", "10"), listOf("next-hop", "next-hop 192.0.2.254 distance").map { route?.get(it) })
        assertEquals("198.51.100.254,198.51.100.253", session.get(ROUTES, "10.0.0.0/8")?.get("next-hop"))

        val routes = session.describe(ROUTES)
        assertEquals(listOf(ValueType.Text(), ValueType.YesNo, ValueType.Many(ValueType.Text())), listOf(".name", "disabled", "next-hop").map { routes.argument(it)?.type })
        val system = session.describe(SYSTEM)
        assertEquals(NodeKind.Settings to listOf("login"), system.kind to system.children)
        assertEquals(ValueType.Secret, system.argument("login user rd-test authentication encrypted-password")?.type)
        assertEquals("rd-test-vyos", session.get(SYSTEM, null)?.get("host-name"))
        assertEquals(0, session.count(MenuPath.parse("interfaces/wireguard"), null))
    }

    @Test
    fun anEditSendsWhatChangedInOneCommitAndSavesIt() = runTest {
        val router = Router(config)
        val session = session(router)
        session.set(ETHERNET, "eth0", mapOf("address" to "192.0.2.1/24, 192.0.2.3/24", "description" to null, "disabled" to "yes"))
        session.set(ETHERNET, "eth1", mapOf("address" to "198.51.100.9/24", "description" to ""))
        session.set(ROUTES, "10.0.0.0/8", mapOf("next-hop" to "198.51.100.254"))
        assertEquals("200", session.add(NAT, mapOf(".name" to "200", "source address" to "203.0.113.0/24", "translation address" to "masquerade")))
        session.remove(NAT, listOf("100"))
        session.set(SYSTEM, null, mapOf("host-name" to "rd-test-edge"))
        assertEquals(
            listOf(
                listOf(
                    "delete interfaces ethernet eth0 address 192.0.2.2/24",
                    "set interfaces ethernet eth0 address 192.0.2.3/24",
                    "delete interfaces ethernet eth0 description",
                    "set interfaces ethernet eth0 disable",
                ),
                listOf("delete interfaces ethernet eth1 address 198.51.100.1/24", "set interfaces ethernet eth1 address 198.51.100.9/24"),
                listOf("delete protocols static route 10.0.0.0/8 next-hop 198.51.100.253"),
                listOf("set nat source rule 200 source address 203.0.113.0/24", "set nat source rule 200 translation address masquerade"),
                listOf("delete nat source rule 100"),
                listOf("delete system host-name", "set system host-name rd-test-edge"),
            ),
            commands(router),
        )
        assertEquals(List(6) { listOf("configure", "config-file save") }.flatten(), writes(router))
        val node = assertFailsWith<DeviceException> { session.set(NAT, "100", mapOf("source" to "203.0.113.0/24")) }
        assertEquals(ErrorCategory.ArgumentValue, node.category)
        val renamed = assertFailsWith<DeviceException> { session.set(NAT, "100", mapOf(".name" to "101")) }
        assertEquals(ErrorCategory.Unsupported, renamed.category)
    }

    @Test
    fun safeModeCommitsWithConfirmAndRollsBackToWhatItFound() = runTest {
        val router = Router(config)
        val session = session(router)
        assertTrue(session.safeMode.enter())
        session.set(ETHERNET, "eth0", mapOf("description" to "LAN"))
        assertEquals(true, session.safeMode.active.first())
        session.safeMode.rollback()
        assertEquals(
            listOf(
                "config-file save $SNAPSHOT",
                "configure 1",
                "config-file confirm",
                "config-file save",
                "config-file load $SNAPSHOT",
                "config-file save",
            ),
            writes(router),
        )
        assertEquals(false, session.safeMode.active.first())
    }

    @Test
    fun aChangeTheAppCouldNotConfirmIsConfirmedBeforeTheNextOne() = runTest {
        var lost = true
        val router = Router(config) { endpoint, body ->
            DeviceException(ErrorCategory.ConnectionLost, "rd-test: no route").takeIf { lost && endpoint == "config-file" && (body["op"] as? JsonPrimitive)?.content == "confirm" }
        }
        val session = session(router)
        session.safeMode.enter()
        assertFailsWith<DeviceException> { session.set(ETHERNET, "eth0", mapOf("description" to "LAN")) }
        lost = false
        session.set(ETHERNET, "eth1", mapOf("description" to "LAN 2"))
        assertEquals(
            listOf("config-file save $SNAPSHOT", "configure 1", "config-file confirm", "config-file confirm", "configure 1", "config-file confirm", "config-file save"),
            writes(router),
        )
    }

    @Test
    fun twoSessionsKeepTheirOwnSnapshots() = runTest {
        val reads = Router(config)
        var current = "C0"
        val files = mutableMapOf<String, String>()
        val loaded = mutableListOf<String>()
        val shared = object : VyosCall {
            override suspend fun call(endpoint: String, body: JsonObject): JsonElement {
                val file = (body["file"] as? JsonPrimitive)?.content
                return when (endpoint to (body["op"] as? JsonPrimitive)?.content) {
                    "config-file" to "save" -> {
                        if (file != null) files[file] = current
                        JsonNull
                    }
                    "config-file" to "load" -> {
                        current = files[file] ?: error("no configuration snapshot")
                        loaded += current
                        JsonNull
                    }
                    else -> reads.call(endpoint, body)
                }
            }

            override suspend fun info() = reads.info()
        }
        val first = session(shared, "/config/safe-mode-first.config")
        val second = session(shared, "/config/safe-mode-second.config")
        first.safeMode.enter()
        first.set(ETHERNET, "eth0", mapOf("description" to "first"))
        current = "C1"
        second.safeMode.enter()
        second.set(ETHERNET, "eth0", mapOf("description" to "second"))
        current = "C2"
        first.safeMode.rollback()
        assertEquals("C0", current)
        second.safeMode.rollback()
        assertEquals("C1", current)
        assertEquals(listOf("C0", "C1"), loaded)
    }

    @Test
    fun aSessionThatIsNotInSafeModeRollsNothingBack() = runTest {
        val router = Router(config)
        val session = session(router)
        session.safeMode.enter()
        session.set(ETHERNET, "eth0", mapOf("description" to "LAN"))
        session.safeMode.commit()
        session.safeMode.rollback()
        assertEquals(false, router.calls.any { (it.second["op"] as? JsonPrimitive)?.content == "load" })
    }

    @Test
    fun aSnapshotSurvivesAFailedRollbackUntilItCanBeRetried() = runTest {
        var disconnected = true
        val router = Router(config) { endpoint, body ->
            DeviceException(ErrorCategory.ConnectionLost, "connection lost").takeIf { disconnected && endpoint == "config-file" && (body["op"] as? JsonPrimitive)?.content == "load" }
        }
        val session = session(router)
        session.safeMode.enter()
        session.set(ETHERNET, "eth0", mapOf("description" to "LAN"))
        assertFailsWith<DeviceException> { session.safeMode.rollback() }
        assertEquals(true, session.safeMode.active.first())
        disconnected = false
        session.safeMode.rollback()
        assertEquals(false, session.safeMode.active.first())
        val loads = router.calls.filter { it.first == "config-file" && (it.second["op"] as? JsonPrimitive)?.content == "load" }.map { it.second["file"] }
        assertEquals(listOf(JsonPrimitive(SNAPSHOT), JsonPrimitive(SNAPSHOT)), loads)
    }

    @Test
    fun setCommandsReadAsTheWindowsAndEditsWriteAsThem() {
        val parsed = vyosConfig(config)
        assertEquals(listOf("192.0.2.1/24,192.0.2.2/24", "198.51.100.1/24"), parsed.menus[ETHERNET]?.items?.map { it["address"] })
        assertEquals(NodeKind.Settings, parsed.menus[SYSTEM]?.kind)
        assertEquals(
            "set interfaces ethernet eth0 description 'it is the WAN'\nset interfaces ethernet eth0 disable\ndelete interfaces ethernet eth0 mtu",
            vyosCli(ETHERNET, EditKind.Set, listOf("eth0"), mapOf("description" to "it is the WAN", "disabled" to "true", "mtu" to null)),
        )
        assertEquals("set nat source rule 200 description \"rd-test's\"", vyosCli(NAT, EditKind.Add, emptyList(), mapOf(".name" to "200", "description" to "rd-test's")))
        assertEquals("delete nat source rule 100", vyosCli(NAT, EditKind.Remove, listOf("100"), emptyMap()))
    }

    @Test
    fun theKeyGoesInTheBodyAndAnErrorOfVyosInItsOwnWords() = runTest {
        val server = AnsweringServer { request ->
            val body = request.substringAfter("\r\n\r\n")
            when {
                request.startsWith("GET /info") -> httpReply(404, """{"detail":"Not Found"}""")
                "\"rd-test-key\"" !in body -> httpReply(401, """{"success":false,"data":null,"error":"Valid API key is required"}""")
                request.startsWith("POST /retrieve") && "\"exists\"" in body -> httpReply(200, """{"success":true,"data":true,"error":null}""")
                request.startsWith("POST /retrieve") -> httpReply(200, """{"success":true,"data":"rd-test-vyos","error":null}""")
                request.startsWith("POST /show") -> httpReply(200, """{"success":true,"data":"Version:          VyOS 1.4.3\nRelease train:    sagitta\n","error":null}""")
                else -> httpReply(400, """{"success":false,"data":null,"error":"Commit failed: [ nat ]\nrd-test: translation address is required"}""")
            }
        }
        val trust = TrustPolicy(MemoryTrustStore()) { false }
        val session = connectVyos(VyosLogin("192.0.2.1", "rd-test-key"), trust, server::open)
        assertEquals(listOf("rd-test-vyos", "1.4.3"), listOf(session.device.identity, session.device.version))
        assertTrue(server.requests.first().endsWith("""{"op":"exists","path":["system","host-name"],"key":"rd-test-key"}"""), server.requests.first())
        val failed = assertFailsWith<DeviceException> { session.remove(NAT, listOf("100")) }
        assertEquals(ErrorCategory.ArgumentValue to "Commit failed: [ nat ]\nrd-test: translation address is required", failed.category to failed.message)
        val refused = assertFailsWith<DeviceException> { connectVyos(VyosLogin("192.0.2.1", "rd-test-wrong"), trust, server::open) }
        assertEquals(ErrorCategory.PermissionDenied, refused.category)
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
    fun aRouterThatGoesSilentIsWaitedForAsLongAsAReadOrACommitMayTake() = runTest {
        val silent = HttpsVyos("192.0.2.1", "rd-test-key") { Silent() }
        assertEquals(ErrorCategory.ConnectionLost, assertFailsWith<DeviceException> { silent.call("retrieve", JsonObject(emptyMap())) }.category)
        assertEquals(120_000, testScheduler.currentTime)
        assertEquals(ErrorCategory.ConnectionLost, assertFailsWith<DeviceException> { silent.call("configure", JsonObject(emptyMap())) }.category)
        assertEquals(780_000, testScheduler.currentTime)
    }

    private companion object {
        const val SNAPSHOT = "/config/safe-mode-test.config"
        val ETHERNET = MenuPath.parse("interfaces/ethernet")
        val ROUTES = MenuPath.parse("protocols/static/route")
        val NAT = MenuPath.parse("nat/source/rule")
        val SYSTEM = MenuPath.parse("system")
    }
}
