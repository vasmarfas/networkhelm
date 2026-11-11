package com.vasmarfas.networkhelm

import com.vasmarfas.networkhelm.gateway.FromGateway
import com.vasmarfas.networkhelm.gateway.GatewayClient
import com.vasmarfas.networkhelm.gateway.Opened
import com.vasmarfas.networkhelm.gateway.Request
import com.vasmarfas.networkhelm.gateway.ToGateway
import com.vasmarfas.networkhelm.miknative.macTelnetInterfaces
import com.vasmarfas.networkhelm.model.Capabilities
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.SafeModeKind
import com.vasmarfas.networkhelm.routeros.RouterOsLogin
import com.vasmarfas.networkhelm.server.Gateway
import com.vasmarfas.networkhelm.server.KtorWire
import com.vasmarfas.networkhelm.server.Totp
import com.vasmarfas.networkhelm.server.fromBase32
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.SafeMode
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.transport.TrustPolicy
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApplicationTest {

    private val home = createTempDirectory("rd-test-gateway").toFile()
    private var clock = 1_800_000_000_000L
    private val gateway = Gateway.open(home, public = false, code = true, password = "rd-test-password", announce = {}, now = { clock })
    private val totp = Totp(fromBase32(assertNotNull(Gateway.account(home)?.totp)))

    private fun code() = totp.code(clock / 30_000)

    private val web = File(home, "web").apply {
        mkdirs()
        File(this, "index.html").writeText("<html>NetworkHelm</html>")
        File(this, "webApp.wasm").writeBytes(byteArrayOf(0, 0x61, 0x73, 0x6d) + ByteArray(4096))
    }

    private fun ApplicationTestBuilder.start(gateway: Gateway = this@ApplicationTest.gateway, router: LabRouter = LabRouter()): HttpClient {
        application { gateway(gateway, connectRouterOs = { _, _, _ -> router }, web = web) }
        return createClient { install(WebSockets) }
    }

    private suspend fun HttpClient.login(password: String = "rd-test-password", code: String = code()) = post("/login") {
        setBody("{\"user\":\"admin\",\"password\":\"$password\",\"code\":\"$code\"}")
    }

    private fun cookie(setCookie: String?) = assertNotNull(setCookie).substringBefore(';')

    @Test
    fun aLoginNeedsThePasswordAndTheCodeAndIsWrittenDown() = testApplication {
        val client = start()
        assertEquals(HttpStatusCode.Unauthorized, client.login(password = "wrong").status)
        assertEquals(HttpStatusCode.Unauthorized, client.login(code = "000000".takeIf { it != code() } ?: "111111").status)
        val response = client.login()
        assertEquals(HttpStatusCode.NoContent, response.status)
        val setCookie = assertNotNull(response.headers[HttpHeaders.SetCookie])
        assertTrue(setCookie.startsWith("networkhelm=") && "HttpOnly" in setCookie && "Secure" in setCookie && "SameSite=Strict" in setCookie, setCookie)
        assertEquals(HttpStatusCode.Unauthorized, client.login().status)
        assertEquals(listOf("password", "code", "ok", "code"), File(home, "logins.log").readLines().map { it.substringAfterLast(' ') })
    }

    @Test
    fun theSecretIsShownAfterThePasswordUntilACodeOfItIsTaken() = testApplication {
        val client = start()
        assertEquals("{\"code\":true,\"setUp\":true}", client.get("/login").bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, client.login(password = "wrong", code = "").status)
        val setUp = client.login(code = "")
        assertEquals(HttpStatusCode.OK, setUp.status)
        assertEquals("no-store", setUp.headers[HttpHeaders.CacheControl])
        val secret = assertNotNull(Gateway.account(home)?.totp)
        assertTrue("secret=$secret" in setUp.bodyAsText())
        assertEquals(HttpStatusCode.NoContent, client.login().status)
        assertEquals(true, Gateway.account(home)?.confirmed)
        assertEquals("{\"code\":true,\"setUp\":false}", client.get("/login").bodyAsText())
        clock += 30_000
        assertEquals(HttpStatusCode.Unauthorized, client.login(code = "").status)
        assertEquals(listOf("password", "set-up", "ok", "code"), File(home, "logins.log").readLines().map { it.substringAfterLast(' ') })
    }

    @Test
    fun withoutTheCodeThePasswordAloneLogsIn() = testApplication {
        val own = createTempDirectory("rd-test-gateway").toFile()
        val client = start(Gateway.open(own, public = false, code = false, password = "rd-test-password", announce = {}))
        assertEquals("{\"code\":false,\"setUp\":false}", client.get("/login").bodyAsText())
        assertEquals(HttpStatusCode.NoContent, client.login(code = "").status)
        assertNull(Gateway.account(own)?.totp)
    }

    @Test
    fun aGatewayOpenToAnyAddressAsksForTheCodeAndShowsItsSecretUntilTaken() {
        val own = createTempDirectory("rd-test-gateway").toFile()
        val shown = mutableListOf<String>()
        val gateway = Gateway.open(own, public = true, code = false, password = null, announce = { shown += it })
        assertTrue(gateway.asksCode)
        val account = assertNotNull(Gateway.account(own))
        assertEquals(2, shown.size)
        assertTrue(shown[0].startsWith("The password of admin"))
        assertTrue("secret=${account.totp}" in shown[1])
        Gateway.setPassword(own, "rd-test-other")
        assertEquals(account.totp, Gateway.account(own)?.totp)
        assertEquals(account.keyStorePassword, Gateway.account(own)?.keyStorePassword)
        assertTrue(Gateway.newCode(own).totp != account.totp)
    }

    @Test
    fun fiveFailuresStopTheLoginsOfAnAddressForAWhile() = testApplication {
        val client = start()
        repeat(5) { assertEquals(HttpStatusCode.Unauthorized, client.login(password = "wrong").status) }
        assertEquals(HttpStatusCode.Unauthorized, client.login().status)
        assertEquals("locked", File(home, "logins.log").readLines().last().substringAfterLast(' '))
        clock += 11 * 60_000L
        assertEquals(HttpStatusCode.NoContent, client.login().status)
    }

    @Test
    fun aLongLoginFormIsNotReadWhetherItsLengthIsToldOrNot() = testApplication {
        val client = start()
        fun form(password: String) = "{\"user\":\"admin\",\"password\":\"$password\",\"code\":\"${code()}\"}"
        assertEquals(HttpStatusCode.PayloadTooLarge, client.login(password = "x".repeat(8 * 1024)).status)
        assertEquals(HttpStatusCode.PayloadTooLarge, client.post("/login") { setBody(ByteReadChannel(form("x".repeat(8 * 1024)))) }.status)
        assertFalse(File(home, "logins.log").exists())
        assertEquals(HttpStatusCode.NoContent, client.post("/login") { setBody(ByteReadChannel(form("rd-test-password"))) }.status)
    }

    @Test
    fun theWebAppComesFromTheGatewayAndAPageLoadedAgainFindsItsSession() = testApplication {
        val client = start()
        val page = client.get("/")
        assertEquals(HttpStatusCode.OK, page.status)
        assertEquals("<html>NetworkHelm</html>", page.bodyAsText())
        assertEquals("DENY", page.headers["X-Frame-Options"])
        assertEquals(ContentType.parse("application/wasm"), client.get("/webApp.wasm").contentType()?.withoutParameters())
        assertEquals("gzip", client.get("/webApp.wasm") { header(HttpHeaders.AcceptEncoding, "gzip") }.headers[HttpHeaders.ContentEncoding])
        assertEquals(HttpStatusCode.Unauthorized, client.get("/login").status)
        val cookie = cookie(client.login().headers[HttpHeaders.SetCookie])
        assertEquals(HttpStatusCode.NoContent, client.get("/login") { header(HttpHeaders.Cookie, cookie) }.status)
        client.post("/logout") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/login") { header(HttpHeaders.Cookie, cookie) }.status)
    }

    @Test
    fun withoutAFolderTheWebAppComesFromTheJar() = testApplication {
        application { gateway(gateway) }
        assertEquals("<html>NetworkHelm in the jar</html>", client.get("/").bodyAsText().trim())
    }

    @Test
    fun theSessionWantsTheCookieAndAPageOfTheGatewayItself() = testApplication {
        val client = start()
        val cookie = cookie(client.login().headers[HttpHeaders.SetCookie])
        assertFailsWith<IllegalStateException> { client.webSocket("/session") {} }
        assertFailsWith<IllegalStateException> {
            client.webSocket("/session", request = {
                header(HttpHeaders.Cookie, cookie)
                header(HttpHeaders.Origin, "https://rd-test.example")
            }) {}
        }
        client.webSocket("/session", request = {
            header(HttpHeaders.Cookie, cookie)
            header(HttpHeaders.Host, "localhost:8443")
            header(HttpHeaders.Origin, "https://localhost:8443")
        }) {
            assertTrue(Json.decodeFromString(FromGateway.serializer(), assertNotNull(KtorWire(this).receive())) is FromGateway.Hello)
        }
    }

    @Test
    fun aRouterAnswersAndTheDocumentsAndSecretsStayOnTheGateway() = testApplication {
        val client = start()
        val cookie = cookie(client.login().headers[HttpHeaders.SetCookie])
        client.webSocket("/session", request = { header(HttpHeaders.Cookie, cookie) }) {
            val remote = GatewayClient(KtorWire(this), this)
            val session = remote.connect(RouterOsLogin("192.0.2.1", "rd-test", "secret"), TrustPolicy(MemoryTrustStore()) { false })
            assertEquals("lab", session.device.identity)
            assertEquals(NodeKind.List, session.describe(MenuPath.parse("ip/address")).kind)

            remote.writeDocument("routers.json", "[]")
            assertEquals("[]", remote.readDocument("routers.json"))
            remote.writeSecret("password/192.0.2.1/rd-test", "rd-test-secret")
            assertEquals("rd-test-secret", remote.readSecret("password/192.0.2.1/rd-test"))
            session.close()
        }
        assertEquals("[]", File(home, "documents/routers.json").readText())
        assertFalse("rd-test-secret" in File(home, "secrets.json").readText(), "secrets are sealed on disk")
    }

    @Test
    fun theGatewayOffersWhatItsOwnComputerHas() = testApplication {
        val client = start()
        val cookie = cookie(client.login().headers[HttpHeaders.SetCookie])
        client.webSocket("/session", request = { header(HttpHeaders.Cookie, cookie) }) {
            val remote = GatewayClient(KtorWire(this), this)
            remote.hello()
            assertTrue(remote.offers.native && remote.offers.neighbors && remote.offers.scan)
            assertEquals(macTelnetInterfaces(), remote.offers.interfaces)
            assertTrue(remote.scan(listOf("127.0.0.1")).toList().all { it.address == "127.0.0.1" })
        }
    }

    @Test
    fun aDocumentOutsideItsFolderIsRefused() = testApplication {
        val client = start()
        val cookie = cookie(client.login().headers[HttpHeaders.SetCookie])
        client.webSocket("/session", request = { header(HttpHeaders.Cookie, cookie) }) {
            val remote = GatewayClient(KtorWire(this), this)
            assertFailsWith<DeviceException> { remote.readDocument("../gateway.json") }
            assertNull(remote.readDocument("routers.json"))
        }
    }

    @Test
    fun loggingOutClosesTheSocketAndCancelsItsRouterWork() = revokedSessionStopsRouterWork { client, cookie ->
        client.post("/logout") { header(HttpHeaders.Cookie, cookie) }
    }

    @Test
    fun changingThePasswordClosesTheSocketAndCancelsItsRouterWork() = revokedSessionStopsRouterWork { _, _ ->
        Gateway.setPassword(home, "rd-test-new-password")
    }

    @Test
    fun anExpiredSessionClosesTheSocketAndCancelsItsRouterWork() = revokedSessionStopsRouterWork { _, _ ->
        clock += 12 * 60 * 60_000L
    }

    private fun revokedSessionStopsRouterWork(revoke: suspend (HttpClient, String) -> Unit) = testApplication {
        val router = LabRouter()
        val client = start(router = router)
        val cookie = cookie(client.login().headers[HttpHeaders.SetCookie])
        client.webSocket("/session", request = { header(HttpHeaders.Cookie, cookie) }) {
            val wire = KtorWire(this)
            assertTrue(Json.decodeFromString(FromGateway.serializer(), assertNotNull(wire.receive())) is FromGateway.Hello)
            wire.send(Json.encodeToString(ToGateway.serializer(), ToGateway.Ask(1, Request.ConnectRouterOs(RouterOsLogin("192.0.2.1", "rd-test", "secret")))))
            val opened = Json.decodeFromString(FromGateway.serializer(), assertNotNull(wire.receive())) as FromGateway.Value
            val session = Json.decodeFromJsonElement(Opened.serializer(), opened.value).session
            val path = MenuPath.parse("system")
            wire.send(Json.encodeToString(ToGateway.serializer(), ToGateway.Ask(2, Request.Run(session, path, "wait", emptyMap()))))
            withTimeout(5_000) { router.running.await() }
            revoke(client, cookie)
            val sent = runCatching { wire.send(Json.encodeToString(ToGateway.serializer(), ToGateway.Ask(3, Request.Change(session, path, null, mapOf("name" to "changed"))))) }
            withTimeout(5_000) {
                router.closed.await()
                router.stopped.await()
                closeReason.await()
            }
            sent.exceptionOrNull()?.let { assertTrue(it is CancellationException || it is ClosedSendChannelException) }
        }
        assertEquals(0, router.changes)
    }

    private class LabRouter : DeviceSession {
        val running = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        var changes = 0
        override val device = DeviceInfo(Platform.RouterOs, "lab", "7.26", "x86_64", "CHR", setOf("routeros"), setOf("read", "write"))
        override val capabilities = Capabilities(true, true, true, SafeModeKind.None, terminal = false, files = false, export = false)

        override suspend fun describe(path: MenuPath) = MenuSchema(path, NodeKind.List, emptyList(), listOf("print"), emptyList())

        override fun watch(path: MenuPath, filter: Filter?, properties: List<String>?): Flow<ListEvent> = emptyFlow()

        override suspend fun get(path: MenuPath, id: String?, properties: List<String>?): Item? = null

        override suspend fun count(path: MenuPath, filter: Filter?) = 0

        override suspend fun add(path: MenuPath, values: Map<String, String>) = "*1"

        override suspend fun set(path: MenuPath, id: String?, changes: Map<String, String?>) {
            this.changes++
        }

        override suspend fun remove(path: MenuPath, ids: List<String>) = Unit

        override suspend fun move(path: MenuPath, ids: List<String>, before: String?) = Unit

        override fun run(path: MenuPath, command: String, arguments: Map<String, String>): Flow<Item> = if (command == "wait") flow {
            running.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        } else emptyFlow()

        override suspend fun export(path: MenuPath) = ""

        override val safeMode: SafeMode? = null

        override val ssh: SshSession? = null

        override fun close() {
            closed.complete(Unit)
        }
    }
}
