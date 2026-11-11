package com.vasmarfas.networkhelm.keenetic

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.routeros.api.md5
import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.transport.HttpResponse
import com.vasmarfas.networkhelm.transport.closingAfter
import com.vasmarfas.networkhelm.transport.httpRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Duration.Companion.minutes

fun interface KeeneticCall {
    suspend fun show(path: String): JsonElement
}

// the router only shows its state here, which it does at once; the time is for the answer to come over a slow link
private val REQUEST_TIME = 1.minutes

// the session cookie lasts 300 s without a request; with no user the secret is a KeeneticOS 5.2 access token
class HttpKeenetic(
    private val host: String,
    private val user: String,
    private val secret: String,
    private val open: suspend () -> ByteStream,
) : KeeneticCall {
    private val logins = Mutex()
    private var cookie: String? = null

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
            val answer = hex(sha256((nonce + hex(md5("$user:$realm:$secret".encodeToByteArray()))).encodeToByteArray()))
            val reply = request("POST", "/auth", JsonObject(mapOf("login" to JsonPrimitive(user), "password" to JsonPrimitive(answer))).toString())
            cookieOf(reply)?.let { cookie = it }
            if (reply.status != 200) throw DeviceException(ErrorCategory.PermissionDenied, "the router refused the user and password")
        }
    }

    override suspend fun show(path: String): JsonElement {
        var reply = request("GET", "/rci/$path", null)
        if (reply.status == 401 && user.isNotEmpty()) {
            login()
            reply = request("GET", "/rci/$path", null)
        }
        return when (reply.status) {
            200 -> answer(path, reply)
            401, 403 -> throw DeviceException(ErrorCategory.PermissionDenied, if (user.isEmpty()) "the router refused the access token" else "the router refused /rci/$path")
            404 -> throw DeviceException(ErrorCategory.MissingItem, "this router has no /rci/$path")
            else -> throw DeviceException(ErrorCategory.General, "/rci/$path answered HTTP ${reply.status}")
        }
    }

    private suspend fun request(method: String, target: String, body: String?): HttpResponse {
        val headers = buildMap {
            put("Accept", "application/json")
            if (user.isEmpty()) put("X-Ndma-Tkn", secret)
            cookie?.let { put("Cookie", it) }
            if (body != null) put("Content-Type", "application/json")
        }
        return open().closingAfter(REQUEST_TIME) { httpRequest(it, host, method, target, headers, body?.encodeToByteArray() ?: ByteArray(0)) }
    }

    // the router names the cookie of the session anew, so whatever it sets is kept
    private fun cookieOf(response: HttpResponse) = response.headers["set-cookie"]?.substringBefore(';')?.takeIf { '=' in it }
}

// RCI reports a failed command in the status list of a 200 answer
private fun answer(path: String, reply: HttpResponse): JsonElement {
    val json = try {
        Json.parseToJsonElement(reply.body.decodeToString())
    } catch (e: SerializationException) {
        throw DeviceException(ErrorCategory.Api, "/rci/$path answered with no JSON", e)
    }
    val statuses = ((json as? JsonObject)?.get("status") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val failed = statuses.firstOrNull { (it["status"] as? JsonPrimitive)?.contentOrNull == "error" }
    if (failed != null) throw DeviceException(ErrorCategory.Api, (failed["message"] as? JsonPrimitive)?.contentOrNull ?: "/rci/$path failed")
    return json
}

private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
