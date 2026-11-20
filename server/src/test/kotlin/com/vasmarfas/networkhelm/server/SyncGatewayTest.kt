package com.vasmarfas.networkhelm.server

import com.vasmarfas.networkhelm.sync.GatewayService
import com.vasmarfas.networkhelm.sync.Listed
import com.vasmarfas.networkhelm.sync.SyncHttp
import com.vasmarfas.networkhelm.sync.VAULT
import com.vasmarfas.networkhelm.sync.gatewayToken
import com.vasmarfas.networkhelm.sync.statePath
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.transport.TrustPolicy
import com.vasmarfas.networkhelm.transport.TrustRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncGatewayTest {
    private val device = "0f8c4a1b2c3d4e5f60718293a4b5c6d7"
    private val home = createTempDirectory("rd-test-gateway-sync").toFile()
    private val clock = 1_800_000_000_000L

    private fun ApplicationTestBuilder.start(gateway: Gateway): HttpClient {
        application { routing { synchronisation(gateway) } }
        return client
    }

    private fun open(code: Boolean = false) = Gateway.open(home, public = false, code = code, password = "rd-test-password", announce = {}, now = { clock })

    private suspend fun HttpClient.token(password: String = "rd-test-password", code: String = "") =
        post("/sync/login") { setBody("{\"user\":\"admin\",\"password\":\"$password\",\"code\":\"$code\"}") }

    private fun HttpRequestBuilder.signed(token: String) = header(HttpHeaders.Authorization, "Bearer $token")

    private suspend fun HttpClient.write(path: String, token: String, body: String, condition: Pair<String, String>? = null) = put(path) {
        signed(token)
        condition?.let { (name, value) -> header(name, value) }
        setBody(body)
    }.status

    // the page on GitHub Pages synchronises from an address of its own: the gateway answers its preflight and names it with each answer,
    // the ETag shown; a page of another address is named nothing
    @Test
    fun thePageOnGitHubPagesIsLetInAndAPageOfAnotherAddressIsNot() = testApplication {
        val client = start(open())
        val token = client.token().bodyAsText()
        val page = "https://vasmarfas.github.io"
        val asked = client.options("/sync/files/$VAULT") {
            header(HttpHeaders.Origin, page)
            header(HttpHeaders.AccessControlRequestMethod, "PUT")
            header(HttpHeaders.AccessControlRequestHeaders, "authorization, if-match")
        }
        assertEquals(HttpStatusCode.NoContent, asked.status)
        assertEquals(page, asked.headers[HttpHeaders.AccessControlAllowOrigin])
        assertEquals("GET, PUT, POST, DELETE", asked.headers[HttpHeaders.AccessControlAllowMethods])
        assertEquals("Authorization, Content-Type, If-Match, If-None-Match", asked.headers[HttpHeaders.AccessControlAllowHeaders])
        val listed = client.get("/sync/files") {
            signed(token)
            header(HttpHeaders.Origin, page)
        }
        assertEquals(HttpStatusCode.OK, listed.status)
        assertEquals(page, listed.headers[HttpHeaders.AccessControlAllowOrigin])
        assertEquals(HttpHeaders.ETag, listed.headers[HttpHeaders.AccessControlExposeHeaders])
        assertEquals(HttpHeaders.Origin, listed.headers[HttpHeaders.Vary])
        val refused = client.get("/sync/files") { header(HttpHeaders.Origin, page) }
        assertEquals(HttpStatusCode.Unauthorized, refused.status)
        assertEquals(page, refused.headers[HttpHeaders.AccessControlAllowOrigin])

        val other = "https://example.org"
        assertNull(client.options("/sync/login") { header(HttpHeaders.Origin, other) }.headers[HttpHeaders.AccessControlAllowOrigin])
        assertNull(client.get("/sync/files") {
            signed(token)
            header(HttpHeaders.Origin, other)
        }.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun aDeviceGetsATokenForThePasswordAndTheCodeAndKeepsItUntilThePasswordChanges() = testApplication {
        val client = start(open(code = true))
        val totp = Totp(fromBase32(assertNotNull(Gateway.account(home)?.totp)))
        val code = totp.code(clock / 30_000)
        assertEquals(HttpStatusCode.Unauthorized, client.token(password = "wrong", code = code).status)
        assertEquals(HttpStatusCode.Forbidden, client.token().status)
        val answer = client.token(code = code)
        assertEquals(HttpStatusCode.OK, answer.status)
        assertEquals("no-store", answer.headers[HttpHeaders.CacheControl])
        val token = answer.bodyAsText()
        assertTrue(token.length >= 40)
        assertTrue(token !in File(home, "sync-tokens.json").readText())
        assertEquals(HttpStatusCode.Unauthorized, client.get("/sync/files").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/sync/files") { signed((if (token[0] == 'A') "B" else "A") + token.drop(1)) }.status)
        assertEquals("[]", client.get("/sync/files") { signed(token) }.bodyAsText())

        Gateway.changePassword(home, "rd-test-password", "rd-test-other", announce = {})
        assertEquals(HttpStatusCode.Unauthorized, client.get("/sync/files") { signed(token) }.status)
    }

    @Test
    fun aFileIsWrittenOnItsConditionAndADeviceWaitingHearsOfTheChange() = testApplication {
        val client = start(open())
        val token = client.token().bodyAsText()
        val vault = "/sync/files/$VAULT"
        val state = "/sync/files/${statePath(device)}"
        assertEquals(HttpStatusCode.NoContent, client.write(vault, token, "one", HttpHeaders.IfNoneMatch to "*"))
        assertEquals(HttpStatusCode.PreconditionFailed, client.write(vault, token, "two", HttpHeaders.IfNoneMatch to "*"))
        val read = client.get(vault) { signed(token) }
        assertEquals("one", read.bodyAsText())
        val tag = assertNotNull(read.headers[HttpHeaders.ETag])
        assertEquals(HttpStatusCode.PreconditionFailed, client.write(vault, token, "two", HttpHeaders.IfMatch to "\"0f\""))
        assertEquals(HttpStatusCode.NoContent, client.write(vault, token, "two", HttpHeaders.IfMatch to tag))
        assertEquals(HttpStatusCode.PreconditionFailed, client.write(vault, token, "three", HttpHeaders.IfMatch to tag))

        val version = client.get("/sync/changes?after=0") { signed(token) }.bodyAsText().toLong()
        coroutineScope {
            val waiting = async { client.get("/sync/changes?after=$version") { signed(token) }.bodyAsText().toLong() }
            assertEquals(HttpStatusCode.NoContent, client.write(state, token, "state"))
            assertEquals(version + 1, waiting.await())
        }
        val listed = Json.decodeFromString(ListSerializer(Listed.serializer()), client.get("/sync/files") { signed(token) }.bodyAsText())
        assertEquals(setOf(VAULT, statePath(device)), listed.map { it.path }.toSet())
        assertEquals(HttpStatusCode.NoContent, client.delete(state) { signed(token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get(state) { signed(token) }.status)
    }

    // only the paths of a vault are served: no other file of the home of the gateway can be named, and none is made
    @Test
    fun onlyTheFilesOfAVaultAreServed() = testApplication {
        val client = start(open())
        val token = client.token().bodyAsText()
        for (path in listOf("gateway.json", "sync-tokens.json", "../gateway.json", "%2E%2E/gateway.json", "state/0f8c", "state/$device/x", "secrets.json")) {
            assertEquals(HttpStatusCode.NotFound, client.get("/sync/files/$path") { signed(token) }.status, path)
            assertNotEquals(HttpStatusCode.NoContent, client.write("/sync/files/$path", token, "x"), path)
        }
        assertEquals(emptyList<File>(), File(home, "sync").walk().filter { it.isFile }.toList())
    }

    // a read with a limit ends quietly where a body was cut off on its way: the gateway asks the channel and keeps nothing
    @Test
    fun aBodyCutOffOnItsWayIsNotKept() = testApplication {
        val client = start(open())
        val token = client.token().bodyAsText()
        val cut = object : OutgoingContent.WriteChannelContent() {
            override val contentLength = 4096L

            override suspend fun writeTo(channel: ByteWriteChannel) {
                channel.writeFully(ByteArray(1000))
                channel.cancel(IllegalStateException("the connection was cut"))
            }
        }
        val outcome = runCatching {
            client.put("/sync/files/${statePath(device)}") {
                signed(token)
                setBody(cut)
            }.status
        }
        assertNotEquals(HttpStatusCode.NoContent, outcome.getOrNull())
        assertEquals(HttpStatusCode.NotFound, client.get("/sync/files/${statePath(device)}") { signed(token) }.status)
    }

    // the app on the other side: its HTTP asks about the certificate of the gateway once, pins it and synchronises through the gateway
    @Test
    fun theAppSynchronisesThroughAGatewayItPinnedTheCertificateOf() = runBlocking {
        val gateway = open()
        val keyStore = gateway.keyStore()
        val server = embeddedServer(Netty, applicationEnvironment {}, {
            sslConnector(keyStore, Gateway.KEY_ALIAS, gateway::keyStorePassword, gateway::keyStorePassword) {
                host = "127.0.0.1"
                port = 0
            }
        }) { routing { synchronisation(gateway) } }.start()
        val asked = mutableListOf<TrustRequest>()
        val http = SyncHttp(TrustPolicy(MemoryTrustStore()) {
            asked += it
            true
        })
        try {
            val address = "https://127.0.0.1:${server.engine.resolvedConnectors().single().port}"
            val token = gatewayToken(http, address, "admin", "rd-test-password", "")
            val service = GatewayService(http, address, token)
            assertTrue(service.writeIf(VAULT, "one".encodeToByteArray(), null))
            service.write(statePath(device), "state".encodeToByteArray())
            assertEquals(setOf(VAULT, statePath(device)), service.list().map { it.path }.toSet())
            val vault = assertNotNull(service.read(VAULT))
            assertContentEquals("one".encodeToByteArray(), vault.bytes)
            assertTrue(service.writeIf(VAULT, "two".encodeToByteArray(), vault))
            assertFalse(service.writeIf(VAULT, "three".encodeToByteArray(), vault))
            val version = service.changes(0)
            service.delete(statePath(device))
            assertEquals(version + 1, service.changes(version))
            val certificate = keyStore.getCertificate(Gateway.KEY_ALIAS).encoded
            assertEquals(MessageDigest.getInstance("SHA-256").digest(certificate).joinToString("") { "%02x".format(it) }, asked.single().fingerprint)
        } finally {
            http.close()
            server.stop(0, 0)
        }
    }
}
