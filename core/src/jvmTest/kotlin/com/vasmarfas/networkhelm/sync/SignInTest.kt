package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.model.DeviceException
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

private const val PAGE = "NetworkHelm: you are signed in, this tab can be closed."

// the browser of the user, played by a thread: it goes to an address and gives back the status and the page
private class Browser {
    val pages = mutableListOf<Pair<Int, String>>()
    private var running: Thread? = null

    fun visit(vararg addresses: String) {
        running = thread {
            for (address in addresses) {
                val connection = URI(address).toURL().openConnection() as HttpURLConnection
                val status = connection.responseCode
                pages += status to (if (status == 200) connection.inputStream.readBytes().decodeToString() else "")
            }
        }
    }

    fun done() {
        running?.join()
    }
}

class SignInTest {
    // the browser opens the sign-in and comes back to the loopback address with the code and the state; a request without them, as for
    // the icon of the page, gets 404 and the wait goes on
    @Test
    fun theBrowserComesBackToTheLoopbackAddressWithTheCode() = runBlocking {
        val authority = FakeAuthority(GOOGLE_DRIVE.copy(redirect = "http://127.0.0.1:0/", secret = "secret of a client for a desktop"))
        val browser = Browser()
        val tokens = authority.client.signIn(authority.http(), { address ->
            val asked = Url(address).parameters
            authority.challenge = asked["code_challenge"].orEmpty()
            val back = asked["redirect_uri"].orEmpty()
            browser.visit("${back}favicon.ico", "$back?state=${asked["state"].orEmpty().encodeURLParameter()}&code=${CLOUD_CODE.encodeURLParameter()}&scope=drive.appdata")
        }, PAGE)
        browser.done()
        assertEquals(authority.access, tokens.access)
        assertEquals(listOf(404, 200), browser.pages.map { it.first })
        assertTrue(PAGE in browser.pages.last().second)
    }

    // a browser that comes back with the state of another sign-in ends this one, and no code goes to the cloud
    @Test
    fun aBrowserThatComesBackForAnotherSignInEndsIt() = runBlocking {
        val authority = FakeAuthority(ONEDRIVE.copy(redirect = "http://127.0.0.1:0/"))
        val browser = Browser()
        val error = assertFailsWith<DeviceException> {
            authority.client.signIn(authority.http(), { address ->
                browser.visit("${Url(address).parameters["redirect_uri"]}?code=${CLOUD_CODE.encodeURLParameter()}&state=another")
            }, PAGE)
        }
        browser.done()
        assertEquals("the answer of the sign-in came for another request", error.message)
        assertTrue(authority.forms.isEmpty())
    }

    // CloudKit takes no loopback address, so a desktop signs in through the page: the page gets the port of the loopback address and the
    // address of the sign-in of Apple, and the browser comes back to that port with the web token
    @Test
    fun cloudKitComesBackToTheLoopbackAddressThroughThePage() = runBlocking {
        val cloudKit = FakeCloudKit()
        val container = CloudKitContainer(cloudKit.container.id, cloudKit.container.environment, CLOUDKIT_API_TOKEN, SIGN_IN_PAGE)
        val browser = Browser()
        var opened = ""
        val token = container.relayed(cloudKit.http, { address ->
            opened = address
            browser.visit("http://127.0.0.1:${Url(address).parameters["relay"]}/?ckWebAuthToken=${WEB_TOKEN.encodeURLParameter()}")
        }, PAGE)
        browser.done()
        assertEquals(WEB_TOKEN, token)
        assertTrue(opened.startsWith(SIGN_IN_PAGE))
        assertEquals("idmsa.apple.com", Url(Url(opened).parameters["to"].orEmpty()).host)
        assertTrue(PAGE in browser.pages.single().second)
    }

    // the port is taken by a program that came first
    @Test
    fun aPortTakenByAnotherProgramIsToldAsSuch() = runBlocking {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { taken ->
            val client = DROPBOX.copy(id = "nh-test-client", redirect = "http://localhost:${taken.localPort}/")
            val error = assertFailsWith<DeviceException> { client.signIn(FakeAuthority(DROPBOX).http(), { fail("the browser opened") }, PAGE) }
            assertEquals("the port ${taken.localPort} of this computer is taken by another program", error.message)
        }
    }
}
