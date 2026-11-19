package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.model.DeviceException
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import io.ktor.http.parametersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class OAuthTest {
    private fun answer(code: String, state: String): Parameters = parametersOf("code" to listOf(code), "state" to listOf(state))

    // RFC 7636, appendix B
    @Test
    fun theChallengeOfAVerifierIsTheOneOfRfc7636() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    // the browser asks for a code to the redirect with the challenge of a verifier of 43 characters and with a state, both new for each
    // sign-in; the verifier itself stays on the device
    @Test
    fun aSignInAsksForACodeWithTheChallengeOfANewVerifier() = runTest {
        val client = DROPBOX.copy(id = "nh-test-client")
        val first = client.begin("http://localhost:47213/")
        val second = client.begin("http://localhost:47213/")
        val asked = Url(first.url)
        assertEquals("www.dropbox.com", asked.host)
        assertEquals("/oauth2/authorize", asked.encodedPath)
        val query = asked.parameters
        assertEquals("code", query["response_type"])
        assertEquals("nh-test-client", query["client_id"])
        assertEquals("http://localhost:47213/", query["redirect_uri"])
        assertEquals(challenge(first.verifier), query["code_challenge"])
        assertEquals("S256", query["code_challenge_method"])
        assertEquals(first.state, query["state"])
        assertEquals("offline", query["token_access_type"])
        assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(first.verifier))
        assertFalse(first.verifier in first.url)
        assertNotEquals(first.verifier, second.verifier)
        assertNotEquals(first.state, second.state)
        assertEquals("cloud_api:disk.app_folder", Url(YANDEX_DISK.copy(id = "nh").begin(YANDEX_DISK.redirect).url).parameters["scope"])
    }

    // the code goes to the token endpoint with the verifier and the redirect it was asked for, Google's secret of a client for a desktop
    // with them; the refresh token then renews the access token and stays where the answer gives no other one
    @Test
    fun theCodeIsExchangedWithTheVerifierAndTheTokensRenewedWithTheRefreshToken() = runTest {
        val authority = FakeAuthority(GOOGLE_DRIVE.copy(secret = "secret of a client for a desktop"))
        val http = authority.http()
        val begun = authority.client.begin("http://127.0.0.1:47213/")
        authority.challenge = Url(begun.url).parameters["code_challenge"].orEmpty()
        val tokens = authority.client.tokens(http, begun, answer(CLOUD_CODE, begun.state))
        assertEquals(authority.access, tokens.access)
        assertEquals(CLOUD_REFRESH, tokens.refresh)
        assertTrue(tokens.expires > Clock.System.now().toEpochMilliseconds() + 3_000_000)
        val form = authority.forms.single()
        assertEquals("authorization_code", form["grant_type"])
        assertEquals(begun.verifier, form["code_verifier"])
        assertEquals("http://127.0.0.1:47213/", form["redirect_uri"])
        assertEquals("secret of a client for a desktop", form["client_secret"])

        val renewed = authority.client.renewed(http, tokens)
        assertEquals(authority.access, renewed.access)
        assertNotEquals(tokens.access, renewed.access)
        assertEquals(CLOUD_REFRESH, renewed.refresh)
        assertEquals("refresh_token", authority.forms.last()["grant_type"])
        assertEquals(CLOUD_REFRESH, authority.forms.last()["refresh_token"])
    }

    // Microsoft and Yandex give a new refresh token with each renewal, and the new one is the one that stays; a client without a secret
    // sends none
    @Test
    fun aRefreshTokenTheAnswerReplacesIsReplaced() = runTest {
        val authority = FakeAuthority(ONEDRIVE, rotates = true)
        val renewed = authority.client.renewed(authority.http(), OAuthTokens(CLOUD_ACCESS, CLOUD_REFRESH))
        assertEquals(authority.refresh, renewed.refresh)
        assertNotEquals(CLOUD_REFRESH, renewed.refresh)
        assertNull(authority.forms.single()["client_secret"])
    }

    // an answer that came for another sign-in is refused before anything goes to the cloud
    @Test
    fun anAnswerForAnotherSignInIsRefused() = runTest {
        val authority = FakeAuthority(ONEDRIVE)
        val begun = authority.client.begin("http://localhost:47213/")
        authority.challenge = Url(begun.url).parameters["code_challenge"].orEmpty()
        val error = assertFailsWith<DeviceException> { authority.client.tokens(authority.http(), begun, answer(CLOUD_CODE, "a state of another sign-in")) }
        assertEquals("the answer of the sign-in came for another request", error.message)
        val none = assertFailsWith<DeviceException> { authority.client.tokens(authority.http(), begun, parametersOf("code", CLOUD_CODE)) }
        assertEquals("the answer of the sign-in came for another request", none.message)
        assertTrue(authority.forms.isEmpty())
    }

    // the cloud says why in its own words, and they are shown as they came
    @Test
    fun aRefusalOfTheCloudIsToldInItsWords() = runTest {
        val authority = FakeAuthority(YANDEX_DISK)
        val begun = authority.client.begin("http://127.0.0.1:47213/")
        val denied = parametersOf("error" to listOf("access_denied"), "error_description" to listOf("the user said no"), "state" to listOf(begun.state))
        val error = assertFailsWith<DeviceException> { authority.client.tokens(authority.http(), begun, denied) }
        assertEquals("oauth.yandex.ru refused the sign-in: access_denied: the user said no", error.message)
        val empty = assertFailsWith<DeviceException> { authority.client.tokens(authority.http(), begun, parametersOf("state", begun.state)) }
        assertEquals("the browser came back without the answer of the sign-in", empty.message)
        authority.challenge = "a challenge of another verifier"
        val code = assertFailsWith<DeviceException> { authority.client.tokens(authority.http(), begun, answer(CLOUD_CODE, begun.state)) }
        assertEquals("oauth.yandex.ru refused the sign-in: invalid_grant: the grant is not known here", code.message)
    }

    // tokens, the code, the verifier and the refresh token go only to the token endpoint and into the Authorization of the API: no message
    // of a failure, which the window of synchronisation shows, no address of a request and no text of what holds them shows them
    @Test
    fun noTokenCodeOrVerifierShowsInAMessageOrAnAddress() = runTest {
        val secrets = mutableListOf(CLOUD_ACCESS, CLOUD_REFRESH, CLOUD_CODE, WEB_TOKEN, WEB_TOKEN.encodeURLParameter(), WEB_TOKEN.encodeURLParameter(spaceToPlus = true))
        val messages = mutableListOf<String>()
        suspend fun failing(action: suspend () -> Unit) {
            val error = assertFailsWith<DeviceException> { action() }
            messages += "$error ${error.cause}"
        }

        val authority = FakeAuthority(YANDEX_DISK, rotates = true)
        val http = authority.http()
        val begun = authority.client.begin("http://127.0.0.1:47213/")
        secrets += begun.verifier
        authority.challenge = Url(begun.url).parameters["code_challenge"].orEmpty()
        failing { authority.client.tokens(http, begun, answer(CLOUD_CODE, "a state of another sign-in")) }
        failing { authority.client.tokens(http, begun, answer("a code that is not known", begun.state)) }
        failing { authority.client.renewed(http, OAuthTokens(CLOUD_ACCESS, "a refresh token that is not known")) }
        val tokens = authority.client.tokens(http, begun, answer(CLOUD_CODE, begun.state))
        secrets += listOf(tokens.access, tokens.refresh)
        assertTrue(secrets.none { it in tokens.toString() || it in begun.toString() })

        val disk = FakeYandexDisk()
        disk.service().write(VAULT, CLOUD_CODE.encodeToByteArray())
        disk.authority.refresh = "a refresh token the disk took back"
        failing { disk.service(cloudTokens("an access token that ran out")).list() }
        disk.breakNext()
        failing { disk.service().read(VAULT) }
        val dropbox = FakeDropbox()
        dropbox.breakNext()
        failing { dropbox.service().write(VAULT, CLOUD_CODE.encodeToByteArray()) }
        val cloudKit = FakeCloudKit()
        cloudKit.stallNext()
        failing { cloudKit.service().list() }
        val stale = cloudKit.service()
        cloudKit.token = "a web token of another sign-in"
        failing { stale.list() }

        assertEquals(8, messages.size)
        for (message in messages) assertTrue(secrets.none { it in message })
        val sent = disk.requests + dropbox.requests
        assertTrue(sent.none { request -> secrets.any { it in request.url.toString() } })
        assertTrue(sent.filter { it.url.host == "cloud-api.yandex.net" }.all { it.headers["Authorization"]?.startsWith("OAuth ") == true })
    }
}
