package com.vasmarfas.networkhelm.opnsense

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.transport.closingAfter
import com.vasmarfas.networkhelm.transport.httpRequest
import com.vasmarfas.networkhelm.transport.urlEncoded
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.minutes

fun interface OpnsenseCall {
    suspend fun call(method: String, path: String, body: JsonObject?): JsonElement
}

// the backend of OPNsense gives an action 120 seconds, the rest is for the answer to come over a slow link
private val REQUEST_TIME = 3.minutes

class HttpsOpnsense(private val host: String, key: String, secret: String, private val open: suspend () -> ByteStream) : OpnsenseCall {
    private val authorization = "Basic " + Base64.encode("$key:$secret".encodeToByteArray())

    override suspend fun call(method: String, path: String, body: JsonObject?): JsonElement {
        val headers = buildMap {
            put("Authorization", authorization)
            put("Accept", "application/json")
            if (body != null) put("Content-Type", "application/x-www-form-urlencoded")
        }
        val response = open().closingAfter(REQUEST_TIME) { stream ->
            httpRequest(stream, host, method, "/api/$path", headers, body?.let(::form).orEmpty().encodeToByteArray())
        }
        val text = response.body.decodeToString()
        when (response.status) {
            200 -> return try {
                Json.parseToJsonElement(text)
            } catch (e: SerializationException) {
                throw DeviceException(ErrorCategory.Api, "/api/$path answered with no JSON", e)
            }
            401, 403 -> throw DeviceException(ErrorCategory.PermissionDenied, "OPNsense refused the key or its user may not call /api/$path")
            404 -> throw DeviceException(ErrorCategory.MissingItem, "this OPNsense has no /api/$path")
            else -> throw DeviceException(ErrorCategory.General, "/api/$path answered ${response.status}: ${text.take(200)}")
        }
    }
}

internal fun form(body: JsonObject): String = buildList { fields("", body) }.joinToString("&")

private fun MutableList<String>.fields(name: String, value: JsonElement) {
    when (value) {
        is JsonObject -> value.forEach { (key, inner) -> fields(if (name.isEmpty()) urlEncoded(key) else "$name[${urlEncoded(key)}]", inner) }
        is JsonArray -> value.forEach { fields("$name[]", it) }
        is JsonPrimitive -> add("$name=" + urlEncoded(value.contentOrNull.orEmpty()))
    }
}

