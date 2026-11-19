package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.transport.TrustKind
import com.vasmarfas.networkhelm.transport.TrustPolicy
import com.vasmarfas.networkhelm.transport.TrustRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncHttpTest {
    private val store = MemoryTrustStore()
    private val asked = mutableListOf<TrustRequest>()
    private var made = 0

    private fun policy(accept: Boolean) = TrustPolicy(store) {
        asked += it
        accept
    }

    // a server with a certificate of its own: the handshake fails, as the trust manager of a platform fails it, until the client pins it
    private fun selfSigned(fingerprint: String) = { pinning: Pinning ->
        made++
        HttpClient(MockEngine {
            if (pinning.pin != fingerprint) {
                pinning.rejected = fingerprint
                throw IllegalStateException("the certificate is not trusted")
            }
            respond("ok")
        })
    }

    @Test
    fun aRequestGoesWithItsMethodHeadersAndBodyAndEveryStatusIsAnAnswer() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val bodies = mutableListOf<String>()
        val http = SyncHttp(policy(false)) {
            made++
            HttpClient(MockEngine { request ->
                requests += request
                bodies += request.body.toByteArray().decodeToString()
                respond("<d:multistatus/>", HttpStatusCode.MultiStatus, headersOf("ETag", "W/\"5f-63a\""))
            })
        }
        assertEquals(0, made)
        val reply = http.send("PROPFIND", "https://dav.example.org/dav/networkhelm-sync/", mapOf("Depth" to "1"), outgoing("<d:propfind/>".encodeToByteArray(), "application/xml; charset=utf-8"))
        assertEquals(207, reply.status)
        assertEquals("W/\"5f-63a\"", reply.header("etag"))
        assertEquals("<d:multistatus/>", reply.body.decodeToString())
        assertEquals("PROPFIND", requests.single().method.value)
        assertEquals("1", requests.single().headers["Depth"])
        assertEquals("application/xml; charset=utf-8", requests.single().body.contentType.toString())
        assertEquals("<d:propfind/>", bodies.single())

        http.send("PUT", "https://dav.example.org/dav/networkhelm-sync/state/a1", mapOf("If-Match" to "\"5f-63a\""), Outgoing("application/octet-stream", 3) { it.writeFully(byteArrayOf(1, 2, 3)) })
        assertEquals(1, made)
        assertEquals("\"5f-63a\"", requests.last().headers["If-Match"])
        assertEquals(3L, requests.last().body.contentLength)
        http.send("GET", "https://other.example.org/")
        assertEquals(2, made)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun aCertificateOfItsOwnIsAskedAboutOncePinnedAndAnotherOneAskedAboutAgain() = runTest {
        val http = SyncHttp(policy(true), selfSigned("ab12"))
        assertEquals("ok", http.send("GET", "https://192.0.2.10:8443/dav/").body.decodeToString())
        val question = asked.single()
        assertEquals("192.0.2.10" to 8443, question.host to question.port)
        assertEquals(TrustKind.Tls, question.kind)
        assertEquals("ab12", question.fingerprint)
        assertNull(question.previous)
        assertEquals("ab12", store.get("192.0.2.10", 8443, TrustKind.Tls))
        assertEquals("ok", http.send("GET", "https://192.0.2.10:8443/dav/").body.decodeToString())
        assertEquals(1, asked.size)
        assertEquals(2, made)

        val refusing = SyncHttp(policy(false), selfSigned("cd34"))
        assertEquals("192.0.2.10: cd34 was not accepted", assertFailsWith<DeviceException> { refusing.send("GET", "https://192.0.2.10:8443/dav/") }.message)
        assertEquals("ab12", asked.last().previous)
        assertEquals("ab12", store.get("192.0.2.10", 8443, TrustKind.Tls))
    }

    // a certificate the system does not trust is a question only for a server of the user's own: an address, a local name or another
    // port, as the gateway listens on; a public name on the port of HTTPS is refused at once
    @Test
    fun aPublicServiceIsNotPinned() = runTest {
        val http = SyncHttp(policy(true), selfSigned("ab12"))
        val error = assertFailsWith<DeviceException> { http.send("GET", "https://webdav.yandex.ru/networkhelm-sync/") }
        assertEquals("the system does not trust the certificate of webdav.yandex.ru: a public service needs one it trusts", error.message)
        assertTrue(asked.isEmpty())
        assertEquals("ok", http.send("GET", "https://nas.local/dav/").body.decodeToString())
        assertEquals("ok", http.send("GET", "https://home.example.org:8443/").body.decodeToString())
        assertEquals(listOf("nas.local", "home.example.org"), asked.map { it.host })
    }

    // a connection the server closed while it waited in the pool takes a read once more; a write, as the conditional one of the vault,
    // is never sent twice
    @Test
    fun aReadIsAskedAgainOnceWhereTheConnectionBrokeBeforeTheAnswer() = runTest {
        val methods = mutableListOf<String>()
        val http = SyncHttp(policy(false)) {
            HttpClient(MockEngine { request ->
                methods += request.method.value
                if (methods.size % 2 == 1) throw IllegalStateException("HTTP/1.1 header parser received no bytes")
                respond("<d:multistatus/>", HttpStatusCode.MultiStatus)
            })
        }
        assertEquals(207, http.send("PROPFIND", "https://dav.example.org/dav/").status)
        assertEquals(listOf("PROPFIND", "PROPFIND"), methods)
        methods.clear()
        val error = assertFailsWith<DeviceException> { http.send("PUT", "https://dav.example.org/dav/vault", body = outgoing(byteArrayOf(1), "application/octet-stream")) }
        assertEquals("dav.example.org does not answer: HTTP/1.1 header parser received no bytes", error.message)
        assertEquals(listOf("PUT"), methods)
    }

    @Test
    fun aServerThatDoesNotAnswerIsToldAsSuchWithoutAQuestion() = runTest {
        val http = SyncHttp(policy(true)) { HttpClient(MockEngine { throw IllegalStateException("connection refused") }) }
        assertEquals("dav.example.org does not answer: connection refused", assertFailsWith<DeviceException> { http.send("GET", "https://dav.example.org/") }.message)
        assertTrue(asked.isEmpty())
    }
}
