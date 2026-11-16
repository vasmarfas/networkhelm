package com.vasmarfas.networkhelm.keenetic

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.routeros.api.md5
import com.vasmarfas.networkhelm.transport.BufferedStream
import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.transport.HttpResponse
import com.vasmarfas.networkhelm.transport.closingUnanswered
import com.vasmarfas.networkhelm.transport.httpExchange
import com.vasmarfas.networkhelm.transport.keepsAlive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

interface KeeneticCall {
    suspend fun show(path: String): JsonElement

    // commands as RCI takes them, {"ip": {"route": {...}}} or {"parse": "<CLI line>"}, one answer each;
    // raising, an error in the status list of an answer is thrown, else it stays in the answer
    suspend fun post(commands: List<JsonObject>, raising: Boolean = true): List<JsonElement>

    // the hash of a password is MD5 of user:realm:password, and the realm is the name the router gives at /auth
    suspend fun realm(): String

    // a command at a path of its own, as the REST tab of the web CLI sends it: POST runs it, GET reads on a command that
    // answered continued, DELETE stops it; the answer comes as it is, a failure in its status list
    suspend fun rest(method: String, path: String, body: JsonElement? = null): JsonElement
}

// the router answers at once; the time is for the answer to come over a slow link
private val REQUEST_TIME = 1.minutes

// a Launcher kept an idle connection for 25 s and closed it by 50 s; through KeenDNS a new one costs a TLS handshake
// of 1.2-2.6 s, which the router computes itself
private val MAX_IDLE = 20.seconds
private const val MAX_KEPT = 4

// the session cookie lasts 300 s without a request; with no user the secret is a KeeneticOS 5.2 access token
class HttpKeenetic(
    private val host: String,
    private val user: String,
    private val secret: String,
    private val open: suspend () -> ByteStream,
) : KeeneticCall {
    private val logins = Mutex()
    private var cookie: String? = null
    private var realm: String? = null
    private val pool = Mutex()
    private val idle = ArrayDeque<Pair<BufferedStream, TimeMark>>()
    // more requests at once than connections kept would each cost the router a handshake: they wait for a kept one instead
    private val connections = Semaphore(MAX_KEPT)

    suspend fun login() {
        if (user.isEmpty()) return
        logins.withLock {
            val challenge = request("GET", "/auth", null)
            cookieOf(challenge)?.let { cookie = it }
            // a user without a password is let in at once
            if (challenge.status == 200) return
            val realm = challenge.headers["x-ndm-realm"]
            val nonce = challenge.headers["x-ndm-challenge"]
            if (challenge.status != 401 || realm == null || nonce == null) {
                throw DeviceException(ErrorCategory.Api, "$host gave no challenge of KeeneticOS at /auth (HTTP ${challenge.status})")
            }
            this.realm = realm
            val reply = request("POST", "/auth", JsonObject(mapOf("login" to JsonPrimitive(user), "password" to JsonPrimitive(passwordHash(nonce, user, realm, secret)))).toString())
            cookieOf(reply)?.let { cookie = it }
            if (reply.status != 200) throw DeviceException(ErrorCategory.PermissionDenied, "the router refused the user and password")
        }
    }

    override suspend fun show(path: String): JsonElement {
        val reply = authorized("GET", "/rci/$path", null)
        return when (reply.status) {
            200 -> answer(path, reply, ErrorCategory.Api)
            401, 403 -> throw DeviceException(ErrorCategory.PermissionDenied, if (user.isEmpty()) "the router refused the access token" else "the router refused /rci/$path")
            404 -> throw DeviceException(ErrorCategory.MissingItem, "this router has no /rci/$path")
            else -> throw DeviceException(ErrorCategory.General, "/rci/$path answered HTTP ${reply.status}")
        }
    }

    override suspend fun post(commands: List<JsonObject>, raising: Boolean): List<JsonElement> {
        val reads = commands.all { it.keys == setOf("show") }
        val reply = authorized("POST", "/rci/", JsonArray(commands).toString(), reads)
        if (reply.status == 401 || reply.status == 403) throw DeviceException(ErrorCategory.PermissionDenied, "the router refused the command")
        if (reply.status != 200) throw DeviceException(ErrorCategory.General, "/rci/ answered HTTP ${reply.status}")
        return answer("", reply, if (reads) ErrorCategory.Api else ErrorCategory.ArgumentValue, raising) as? JsonArray
            ?: throw DeviceException(ErrorCategory.Api, "/rci/ answered with no list of answers")
    }

    override suspend fun realm(): String = realm ?: request("GET", "/auth", null, anonymous = true).headers["x-ndm-realm"]?.also { realm = it }
        ?: throw DeviceException(ErrorCategory.Api, "$host named no realm at /auth")

    // a file of the web interface, such as its dictionary; the router gives these without a login, and a missing one is null
    suspend fun page(target: String): JsonElement? {
        val reply = request("GET", target, null, anonymous = true)
        if (reply.status != 200) return null
        return try {
            Json.parseToJsonElement(reply.body.decodeToString())
        } catch (e: SerializationException) {
            throw DeviceException(ErrorCategory.Api, "$target of the router is no JSON", e)
        }
    }

    override suspend fun rest(method: String, path: String, body: JsonElement?): JsonElement {
        val reply = authorized(method, "/rci/$path", body?.toString(), method == "GET" || path.startsWith("show/"))
        if (reply.status == 401 || reply.status == 403) throw DeviceException(ErrorCategory.PermissionDenied, "the router refused /rci/$path")
        if (reply.status != 200) throw DeviceException(ErrorCategory.General, "/rci/$path answered HTTP ${reply.status}")
        return answer(path, reply, ErrorCategory.Api, raising = false)
    }

    private suspend fun authorized(method: String, target: String, body: String?, reading: Boolean = true): HttpResponse {
        val reply = request(method, target, body, reading)
        if (reply.status != 401 || user.isEmpty()) return reply
        login()
        return request(method, target, body, reading)
    }

    private suspend fun request(method: String, target: String, body: String?, reading: Boolean = true, anonymous: Boolean = false): HttpResponse =
        connections.withPermit { exchanged(method, target, body, reading, anonymous) }

    // a kept connection that proves dead is tried again on a new one only by a read: a change sent on it may have been made
    private suspend fun exchanged(method: String, target: String, body: String?, reading: Boolean, anonymous: Boolean): HttpResponse {
        val headers = buildMap {
            put("Accept", "application/json")
            if (!anonymous) {
                if (user.isEmpty()) put("X-Ndma-Tkn", secret)
                cookie?.let { put("Cookie", it) }
            }
            if (body != null) put("Content-Type", "application/json")
        }
        val bytes = body?.encodeToByteArray() ?: ByteArray(0)
        val kept = pool.withLock {
            while (idle.isNotEmpty()) {
                val (connection, since) = idle.removeLast()
                if (since.elapsedNow() < MAX_IDLE) return@withLock connection
                connection.close()
            }
            null
        }
        if (kept != null) {
            exchange(kept, method, target, headers, bytes)?.let { return it }
            if (!reading) throw DeviceException(ErrorCategory.ConnectionLost, "the router closed the connection without an answer, so the change may or may not have been made")
        }
        return exchange(BufferedStream(open()), method, target, headers, bytes) ?: throw DeviceException(ErrorCategory.ConnectionLost, "the router closed the connection without an answer")
    }

    private suspend fun exchange(connection: BufferedStream, method: String, target: String, headers: Map<String, String>, body: ByteArray): HttpResponse? {
        val answer = try {
            connection.closingUnanswered(REQUEST_TIME) { httpExchange(connection, host, method, target, headers, body) }
        } catch (e: Throwable) {
            connection.close()
            throw e
        }
        if (answer?.keepsAlive() != true) {
            connection.close()
            return answer
        }
        try {
            pool.withLock { if (idle.size < MAX_KEPT) idle.addLast(connection to TimeSource.Monotonic.markNow()) else connection.close() }
        } catch (e: CancellationException) {
            connection.close()
            throw e
        }
        return answer
    }

    // the router names the cookie of the session anew, so whatever it sets is kept
    private fun cookieOf(response: HttpResponse) = response.headers["set-cookie"]?.substringBefore(';')?.takeIf { '=' in it }
}

internal fun passwordHash(nonce: String, user: String, realm: String, password: String) = hex(sha256((nonce + hex(md5("$user:$realm:$password".encodeToByteArray()))).encodeToByteArray()))

internal fun storedHash(user: String, realm: String, password: String) = hex(md5("$user:$realm:$password".encodeToByteArray()))

// RCI reports a failed command in a status list of a 200 answer, at the depth of the command
private fun answer(path: String, reply: HttpResponse, category: ErrorCategory, raising: Boolean = true): JsonElement {
    val json = try {
        Json.parseToJsonElement(reply.body.decodeToString())
    } catch (e: SerializationException) {
        throw DeviceException(ErrorCategory.Api, "/rci/$path answered with no JSON", e)
    }
    if (raising) failure(json)?.let { throw DeviceException(category, it) }
    return json
}

internal fun failure(json: JsonElement): String? = when (json) {
    is JsonArray -> json.firstNotNullOfOrNull(::failure)
    is JsonObject -> (json["status"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        .firstOrNull { (it["status"] as? JsonPrimitive)?.contentOrNull == "error" }
        ?.let { (it["message"] as? JsonPrimitive)?.contentOrNull ?: "the router refused the command" }
        ?: json.values.firstNotNullOfOrNull(::failure)
    else -> null
}

internal fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
