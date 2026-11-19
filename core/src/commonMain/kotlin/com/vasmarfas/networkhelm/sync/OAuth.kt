package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.keenetic.sha256
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.transport.TcpListener
import com.vasmarfas.networkhelm.transport.closingAfter
import com.vasmarfas.networkhelm.transport.listenTcp
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.formUrlEncode
import io.ktor.http.parseQueryString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// the loopback port the consoles of the clouds list for the desktop: the browser that signed in comes back to it with the code
const val SIGN_IN_PORT = 47213

internal val SIGN_IN_TIME = 5.minutes
private val REQUEST_TIME = 3.seconds
private const val REQUEST_LIMIT = 8192
private const val RENEW_BEFORE = 60_000L

// an app registered with a cloud for OAuth 2.0 with PKCE and no secret of its own. Redirect is the address the console of the cloud lists:
// a loopback address with port 0 for any, a scheme of the app or the page; Google asks its clients for a desktop for a secret all the
// same, which in an installed app is no secret. Relay is the scheme of the app the page hands the answer over to, which the state
// carries for the page to tell
data class OAuthClient(
    val id: String,
    val authorize: String,
    val token: String,
    val scope: String,
    val redirect: String,
    val secret: String = "",
    val extra: Map<String, String> = emptyMap(),
    val revocation: String = "",
    val relay: String = "",
) {
    // a client in a message or a log shows its id alone, without the secret
    override fun toString() = "OAuthClient($id)"
}

internal val OAuthClient.loopback get() = redirect.startsWith("http://127.0.0.1:") || redirect.startsWith("http://localhost:")

// the tokens of the sign-in of this device, kept as text in the protected store; a plain class, so that no log prints them. Expires is
// when the access token runs out, in milliseconds since 1970, 0 where the cloud did not say
@Serializable
class OAuthTokens(val access: String, val refresh: String = "", val expires: Long = 0)

// a sign-in begun: the address the browser opens, and the redirect, verifier and state its answer is exchanged with
internal class SignIn(val url: String, val redirect: String, val verifier: String, val state: String)

@Serializable
private class OAuthGrant(
    @SerialName("access_token") val access: String = "",
    @SerialName("refresh_token") val refresh: String = "",
    @SerialName("expires_in") val lifetime: Long = 0,
    val error: String = "",
    @SerialName("error_description") val description: String = "",
)

// the challenge of RFC 7636: base64url of the SHA-256 of the verifier, without padding
internal fun challenge(verifier: String) = unpadded(sha256(verifier.encodeToByteArray()))

internal suspend fun OAuthClient.begin(redirect: String): SignIn {
    val verifier = unpadded(Sodium.random(32))
    val state = listOfNotNull(relay.takeIf { it.isNotEmpty() }, unpadded(Sodium.random(16))).joinToString(".")
    val query = listOf(
        "response_type" to "code",
        "client_id" to id,
        "redirect_uri" to redirect,
        "state" to state,
        "code_challenge" to challenge(verifier),
        "code_challenge_method" to "S256",
    ) + listOfNotNull(scope.takeIf { it.isNotEmpty() }?.let { "scope" to it }) + extra.toList()
    return SignIn("$authorize?${query.formUrlEncode()}", redirect, verifier, state)
}

// the tokens for the answer the browser came back with; an answer to another sign-in is refused before anything goes out
internal suspend fun OAuthClient.tokens(http: SyncHttp, signIn: SignIn, answer: Parameters): OAuthTokens {
    if (answer["state"] != signIn.state) throw DeviceException(ErrorCategory.PermissionDenied, "the answer of the sign-in came for another request")
    answer["error"]?.let { throw refused(Url(authorize).host, listOfNotNull(it, answer["error_description"]).joinToString(": ")) }
    val code = answer["code"] ?: throw DeviceException(ErrorCategory.Api, "the browser came back without the answer of the sign-in")
    return granted(http, listOf("grant_type" to "authorization_code", "code" to code, "redirect_uri" to signIn.redirect, "code_verifier" to signIn.verifier), "")
}

internal suspend fun OAuthClient.renewed(http: SyncHttp, tokens: OAuthTokens): OAuthTokens =
    granted(http, listOf("grant_type" to "refresh_token", "refresh_token" to tokens.refresh), tokens.refresh)

// RFC 7009: the refresh token stops working, and the access tokens made from it with it
internal suspend fun OAuthClient.revoke(http: SyncHttp, tokens: OAuthTokens) {
    val form = listOf("token" to tokens.refresh.ifEmpty { tokens.access }).formUrlEncode().encodeToByteArray()
    val reply = http.send("POST", revocation, body = outgoing(form, "application/x-www-form-urlencoded"))
    if (reply.status != 200) throw answered(Url(revocation).host, "${reply.status}")
}

// a sign-in in the browser of this computer, which comes back to the loopback address of the client with the code; page is what the
// browser shows then
internal suspend fun OAuthClient.signIn(http: SyncHttp, open: (String) -> Unit, page: String): OAuthTokens {
    val listener = listen(Url(redirect).specifiedPort)
    try {
        val begun = begin(URLBuilder(redirect).apply { port = listener.port }.buildString())
        open(begun.url)
        return tokens(http, begun, loopbackAnswer(listener, page))
    } finally {
        listener.close()
    }
}

// a sign-in the system hands back to the app, as a phone does by the scheme of the app and a page by its own address: open shows the
// address of the sign-in and gives the address the browser was sent back to
internal suspend fun OAuthClient.signIn(http: SyncHttp, open: suspend (String) -> String): OAuthTokens {
    val begun = begin(redirect)
    val back = withTimeoutOrNull(SIGN_IN_TIME) { open(begun.url) }
        ?: throw DeviceException(ErrorCategory.Interrupted, "the sign-in was not finished in the browser in time")
    return tokens(http, begun, parseQueryString(back.substringAfter('?', "").substringBefore('#')))
}

internal fun tokensOf(text: String): OAuthTokens? = try {
    fileJson.decodeFromString(OAuthTokens.serializer(), text).takeIf { it.access.isNotEmpty() }
} catch (e: IllegalArgumentException) {
    null
}

internal fun textOf(tokens: OAuthTokens) = fileJson.encodeToString(OAuthTokens.serializer(), tokens)

// the sign-in of this device to a cloud as its requests use it: the access token is renewed with the refresh token before it runs out
// and when the cloud refuses it, and the new tokens go to keep. Tokens is the text the protected store keeps them as
class OAuthLogin(
    private val http: SyncHttp,
    private val client: OAuthClient,
    private val tokens: String,
    private val keep: suspend (String) -> Unit,
    private val scheme: String = "Bearer",
) {
    private val lock = Mutex()
    private var held: OAuthTokens? = null

    // a request with the access token, sent once more with a renewed one where the cloud refuses it
    suspend fun send(method: String, url: String, headers: Map<String, String> = emptyMap(), body: Outgoing? = null): Reply {
        val host = Url(url).host
        val access = current(host, null)
        val reply = http.send(method, url, headers + ("Authorization" to "$scheme $access"), body)
        if (reply.status != 401) return reply
        val again = http.send(method, url, headers + ("Authorization" to "$scheme ${current(host, access)}"), body)
        if (again.status == 401) throw loginRefused(host)
        return again
    }

    // renewed when it runs out within a minute or was refused, unless another request renewed it meanwhile
    private suspend fun current(host: String, refused: String?): String = lock.withLock {
        val now = held ?: (tokensOf(tokens) ?: throw loginRefused(host)).also { held = it }
        val ending = now.expires != 0L && now.expires - RENEW_BEFORE <= Clock.System.now().toEpochMilliseconds()
        if (now.access != refused && !ending) {
            now.access
        } else {
            if (now.refresh.isEmpty()) throw loginRefused(host)
            val renewed = client.renewed(http, now)
            held = renewed
            keep(textOf(renewed))
            renewed.access
        }
    }
}

// the port is taken by a program that came first, perhaps another copy of the app in the middle of its own sign-in
internal suspend fun listen(port: Int): TcpListener = try {
    listenTcp("127.0.0.1", port)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    throw DeviceException(ErrorCategory.General, "the port $port of this computer is taken by another program", e)
}

// the query the browser comes back to the loopback address with; a browser that does not come back in five minutes leaves the sign-in
// unfinished, and the listener closes with the wait
internal suspend fun loopbackAnswer(listener: TcpListener, page: String): Parameters = coroutineScope {
    var late = false
    val watchdog = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            delay(SIGN_IN_TIME)
            late = true
        } finally {
            listener.close()
        }
    }
    try {
        listener.answer(page) { late }
    } finally {
        watchdog.cancel()
    }
}

private suspend fun TcpListener.answer(page: String, late: () -> Boolean): Parameters {
    while (true) {
        val stream = try {
            accept()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            if (late()) throw DeviceException(ErrorCategory.Interrupted, "the sign-in was not finished in the browser in time")
            throw e
        }
        // a connection the browser opened ahead and left idle, or one that broke, is not the answer
        val answer = try {
            stream.closingAfter(REQUEST_TIME) { requested(it, page) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (answer != null) return answer
    }
}

// the query of a request, answered with the page; a request without one, as for the icon of the page, gets 404 and null
private suspend fun requested(stream: ByteStream, page: String): Parameters? {
    val buffer = ByteArray(REQUEST_LIMIT)
    var length = 0
    while ("\r\n\r\n" !in buffer.decodeToString(0, length)) {
        if (length == buffer.size) return null
        val read = stream.read(buffer, length, buffer.size - length)
        if (read <= 0) return null
        length += read
    }
    val target = buffer.decodeToString(0, length).substringBefore("\r\n").split(' ').getOrElse(1) { "" }
    val query = parseQueryString(target.substringAfter('?', ""))
    if (query.isEmpty()) {
        stream.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".encodeToByteArray())
        return null
    }
    val body = page.encodeToByteArray()
    stream.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".encodeToByteArray() + body)
    return query
}

// a refresh token the answer does not replace stays: Google and Dropbox keep theirs, Microsoft and Yandex give a new one each time
private suspend fun OAuthClient.granted(http: SyncHttp, grant: List<Pair<String, String>>, refresh: String): OAuthTokens {
    val host = Url(token).host
    val form = grant + ("client_id" to id) + listOfNotNull(secret.takeIf { it.isNotEmpty() }?.let { "client_secret" to it })
    val reply = http.send("POST", token, mapOf("Accept" to "application/json"), outgoing(form.formUrlEncode().encodeToByteArray(), "application/x-www-form-urlencoded"))
    val answer = try {
        fileJson.decodeFromString(OAuthGrant.serializer(), reply.body.decodeToString())
    } catch (e: IllegalArgumentException) {
        throw DeviceException(ErrorCategory.Api, "$host did not answer as the API of OAuth 2.0")
    }
    if (answer.error.isNotEmpty()) throw refused(host, listOf(answer.error, answer.description).filter { it.isNotEmpty() }.joinToString(": "))
    if (reply.status != 200 || answer.access.isEmpty()) throw answered(host, "${reply.status}")
    val expires = if (answer.lifetime > 0) Clock.System.now().toEpochMilliseconds() + answer.lifetime * 1000 else 0
    return OAuthTokens(answer.access, answer.refresh.ifEmpty { refresh }, expires)
}

private fun refused(host: String, reason: String) = DeviceException(ErrorCategory.PermissionDenied, "$host refused the sign-in: $reason")

private fun unpadded(bytes: ByteArray) = Base64.UrlSafe.encode(bytes).trimEnd('=')
