package com.vasmarfas.networkhelm.app

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.server.FileGatewayStore
import com.vasmarfas.networkhelm.server.Gateway
import com.vasmarfas.networkhelm.server.Master
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.ConnectException
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopGatewayTest {

    private val home = createTempDirectory("rd-test-gateway").toFile()
    private val gatewayHome = File(home, "gateway")
    private val web = File(home, "web").apply {
        mkdirs()
        File(this, "index.html").writeText("<html>NetworkHelm</html>")
    }
    private val storage = MemoryStorage()
    private val secrets = MemorySecrets()

    private fun gateway() = DesktopGateway(gatewayHome, web, storage, secrets, MemoryTrustStore(), host = "127.0.0.1")

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun client(): HttpClient {
        val account = assertNotNull(Gateway.account(gatewayHome))
        val certificate = KeyStore.getInstance(File(gatewayHome, "gateway.jks"), account.keyStorePassword.toCharArray()).getCertificate(Gateway.KEY_ALIAS)
        val trusted = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("gateway", certificate)
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(null, TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trusted) }.trustManagers, null)
        }
        return HttpClient.newBuilder().sslContext(context).proxy(HttpClient.Builder.NO_PROXY).version(HttpClient.Version.HTTP_1_1).build()
    }

    @Test
    fun thePasswordOpensTheGatewayAndTheNextStartOfTheAppOpensItAgain() = runBlocking {
        val access = gateway()
        assertFalse(access.ready)
        access.setPassword("rd-test-password")
        val port = freePort()
        access.change(port, public = false, code = false)
        access.open()
        assertTrue(access.open)
        assertNull(access.problem)
        assertEquals(Gateway.fingerprint(KeyStore.getInstance(File(gatewayHome, "gateway.jks"), assertNotNull(Gateway.account(gatewayHome)).keyStorePassword.toCharArray())), access.certificate)
        assertTrue(access.addresses.all { it.startsWith("https://") && it.endsWith(":$port") })

        val client = client()
        val page = client.send(HttpRequest.newBuilder(URI.create("https://127.0.0.1:$port/")).build(), HttpResponse.BodyHandlers.ofString())
        assertEquals("<html>NetworkHelm</html>", page.body())
        val login = HttpRequest.newBuilder(URI.create("https://127.0.0.1:$port/login")).POST(HttpRequest.BodyPublishers.ofString("{\"user\":\"admin\",\"password\":\"rd-test-password\"}")).build()
        assertEquals(204, client.send(login, HttpResponse.BodyHandlers.discarding()).statusCode())

        val next = gateway()
        next.resume()
        assertFalse(next.open)
        assertEquals(port, assertIs<AccessProblem.PortTaken>(next.problem).port)

        access.close()
        assertFalse(access.open)
        assertFailsWith<ConnectException> { client.send(login, HttpResponse.BodyHandlers.discarding()) }
        val closed = gateway()
        closed.resume()
        assertFalse(closed.open)
    }

    @Test
    fun theSecretOfTheAppIsThereToScanBeforeTheGatewayOpens() = runBlocking {
        val access = gateway()
        access.change(freePort(), public = false, code = true)
        assertNull(access.otpauth, "no account yet")
        access.setPassword("rd-test-password")
        val first = assertNotNull(access.otpauth)
        access.setPassword("rd-test-other")
        assertEquals(first, access.otpauth)
        access.newCode()
        assertTrue(access.otpauth != first)
        access.change(access.port, public = false, code = false)
        assertNull(access.otpauth)
        access.change(access.port, public = true, code = false)
        assertNotNull(access.otpauth)
        assertFalse(access.open)
    }

    @Test
    fun filesOfTheGatewayThatCannotBeReadDoNotStopTheApp() = runBlocking {
        gatewayHome.mkdirs()
        File(gatewayHome, "gateway.json").writeText("{\"user\": ")
        File(gatewayHome, "access.json").writeText("[")
        val access = gateway()
        access.resume()
        assertFalse(access.ready)
        assertFalse(access.open)
        assertEquals(8443, access.port)
    }

    @Test
    fun theBrowserKeepsItsSavedRoutersAndPasswordsWithTheApp() = runBlocking {
        val store = DesktopStore(storage, secrets, FileGatewayStore(gatewayHome, Master(ByteArray(32)).sealing))
        store.write("routers.json", "[]")
        store.write("settings.json", "{}")
        store.write("routers.records.json", "{\"records\": []}")
        store.keep("password/192.0.2.1/admin", "rd-test-secret")
        assertEquals("[]", storage.read("routers.json"))
        assertEquals("{\"records\": []}", storage.read("routers.records.json"))
        assertNull(storage.read("settings.json"))
        assertEquals("{}", File(gatewayHome, "documents/settings.json").readText())
        assertEquals("rd-test-secret", secrets.get("password/192.0.2.1/admin"))
        assertFalse(File(gatewayHome, "secrets.json").exists())
    }

    @Test
    fun theBrowserNeitherReadsNorChangesTheKeysOfSynchronisation() = runBlocking {
        val store = DesktopStore(storage, secrets, FileGatewayStore(gatewayHome, Master(ByteArray(32)).sealing))
        secrets.put("sync/key", "rd-test-key")
        assertNull(store.secret("sync/key"))
        assertFailsWith<DeviceException> { store.keep("sync/key", "rd-test-other") }
        assertFailsWith<DeviceException> { store.forget("sync/key") }
        assertEquals("rd-test-key", secrets.get("sync/key"))
    }
}
