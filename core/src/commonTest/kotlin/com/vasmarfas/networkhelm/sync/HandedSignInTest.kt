package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.model.DeviceException
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import io.ktor.http.parametersOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandedSignInTest {
    // a phone comes back by the scheme of the app: the code is read from the address the system hands over and goes to the token endpoint
    // with the redirect it was asked for
    @Test
    fun theCodeComesFromTheAddressTheSystemHandsBack() = runTest {
        val authority = FakeAuthority(assertNotNull(Cloud.Dropbox.client(SignInWay.Android)))
        var asked = ""
        val tokens = authority.client.signIn(authority.http()) { url ->
            asked = url
            val query = Url(url).parameters
            authority.challenge = query["code_challenge"].orEmpty()
            "${query["redirect_uri"]}?code=${CLOUD_CODE.encodeURLParameter()}&state=${query["state"]}"
        }
        assertEquals(authority.access, tokens.access)
        assertEquals(CLOUD_REFRESH, tokens.refresh)
        assertEquals("db-o62vbooj1z07hkq://2/token", Url(asked).parameters["redirect_uri"])
        assertEquals("db-o62vbooj1z07hkq://2/token", authority.forms.single()["redirect_uri"])
    }

    // the address of an answer to another sign-in is refused before anything goes to the cloud
    @Test
    fun anAnswerHandedBackForAnotherSignInIsRefused() = runTest {
        val authority = FakeAuthority(assertNotNull(Cloud.Dropbox.client(SignInWay.Page)))
        val error = assertFailsWith<DeviceException> {
            authority.client.signIn(authority.http()) { "$SIGN_IN_PAGE?code=${CLOUD_CODE.encodeURLParameter()}&state=another" }
        }
        assertEquals("the answer of the sign-in came for another request", error.message)
        assertTrue(authority.forms.isEmpty())
    }

    // a browser left without an answer ends the sign-in after five minutes, as the loopback address of the desktop does
    @Test
    fun aSignInLeftInTheBrowserEndsInTime() = runTest {
        val authority = FakeAuthority(assertNotNull(Cloud.Dropbox.client(SignInWay.Ios)))
        val error = assertFailsWith<DeviceException> { authority.client.signIn(authority.http()) { awaitCancellation() } }
        assertEquals("the sign-in was not finished in the browser in time", error.message)
    }

    // the secret of the client for a desktop goes to the token endpoint alone: not into the text of the client, the message of a refusal
    // or the address of the sign-in
    @Test
    fun theSecretOfTheClientShowsNowhereElse() = runTest {
        val secret = "secret of a client for a desktop 7Hq"
        val authority = FakeAuthority(GOOGLE_DRIVE.copy(secret = secret))
        val begun = authority.client.begin(GOOGLE_DRIVE.redirect)
        val error = assertFailsWith<DeviceException> {
            authority.client.tokens(authority.http(), begun, parametersOf("code" to listOf("a code that is not known"), "state" to listOf(begun.state)))
        }
        assertEquals(secret, authority.forms.single()["client_secret"])
        assertFalse(secret in "$error ${error.cause}")
        assertFalse(secret in authority.client.toString())
        assertFalse(secret in begun.url)
    }

    // CloudKit answers a request without the web token with the address of its sign-in page, and the page sends the browser back to the
    // address of the API token with the web token in the query
    @Test
    fun cloudKitGivesItsWebTokenInTheAddressTheSystemHandsBack() = runTest {
        val cloudKit = FakeCloudKit()
        val container = CloudKitContainer(cloudKit.container.id, cloudKit.container.environment, CLOUDKIT_API_TOKEN, SIGN_IN_PAGE)
        var opened = ""
        val token = container.signIn(cloudKit.http) { address ->
            opened = address
            "$SIGN_IN_PAGE?ckWebAuthToken=${WEB_TOKEN.encodeURLParameter()}"
        }
        assertEquals(WEB_TOKEN, token)
        assertEquals("idmsa.apple.com", Url(opened).host)
    }

    // each way of signing in has the client its cloud takes for it, and a cloud without one is not offered on that way: Android listens
    // on the loopback address where the cloud gives a phone no scheme of the app, and iOS comes back through the page
    @Test
    fun eachWayHasTheClientItsCloudTakes() {
        assertEquals("http://localhost:47213/", Cloud.Dropbox.client(SignInWay.Loopback)?.redirect)
        assertEquals("db-o62vbooj1z07hkq://2/token", Cloud.Dropbox.client(SignInWay.Ios)?.redirect)
        assertEquals(SIGN_IN_PAGE, Cloud.Dropbox.client(SignInWay.Page)?.redirect)
        assertTrue(SignInWay.entries.all { Cloud.Dropbox.offered(it) })
        for (way in listOf(SignInWay.Loopback, SignInWay.Android)) {
            assertEquals(GOOGLE_DRIVE.secret.isNotEmpty(), Cloud.GoogleDrive.offered(way))
            assertEquals(true, Cloud.YandexDisk.client(way)?.loopback)
        }
        assertTrue(Cloud.GoogleDrive.offered(SignInWay.Ios))
        assertFalse(Cloud.GoogleDrive.offered(SignInWay.Page))
        assertEquals("com.googleusercontent.apps.235939716345-mkq5qr1o3n5a8664sf87jnbmud0csmba:/oauth2redirect", Cloud.GoogleDrive.client(SignInWay.Ios)?.redirect)
        assertEquals("", GOOGLE_DRIVE_IOS.secret)
        assertEquals(false, Cloud.Dropbox.client(SignInWay.Android)?.loopback)
        assertEquals(SIGN_IN_PAGE, Cloud.YandexDisk.client(SignInWay.Ios)?.redirect)
        assertEquals(APP_SCHEME, Cloud.YandexDisk.client(SignInWay.Ios)?.relay)
        assertNull(Cloud.OneDrive.client(SignInWay.Ios))
        assertEquals("cloudkit-icloud.com.vasmarfas.networkhelm://signin", CLOUDKIT_APPLE.redirect)
        assertEquals(CLOUDKIT_APPLE.token.isNotEmpty(), Cloud.CloudKit.offered(SignInWay.Ios))
        for (way in listOf(SignInWay.Loopback, SignInWay.Android, SignInWay.Page)) assertEquals(CLOUDKIT_PAGE.token.isNotEmpty(), Cloud.CloudKit.offered(way))
    }

    // a sign-in of the app on iOS that comes back to the page names the scheme of the app in its state, for the page to hand it over; the
    // state stays new for each sign-in
    @Test
    fun theStateOfASignInThePageHandsOverNamesTheSchemeOfTheApp() = runTest {
        val client = assertNotNull(Cloud.YandexDisk.client(SignInWay.Ios)).copy(id = "nh-test-client")
        val first = client.begin(client.redirect)
        val second = client.begin(client.redirect)
        assertTrue(first.state.startsWith("$APP_SCHEME."))
        assertTrue(first.state != second.state)
        assertEquals(first.state, Url(first.url).parameters["state"])
        assertFalse(assertNotNull(Cloud.YandexDisk.client(SignInWay.Page)).copy(id = "nh-test-client").begin(SIGN_IN_PAGE).state.contains('.'))
    }
}
