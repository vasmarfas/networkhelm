package com.vasmarfas.networkhelm.vyos

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.transport.closingAfter
import com.vasmarfas.networkhelm.transport.httpRequest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Duration.Companion.minutes

interface VyosCall {
    suspend fun call(endpoint: String, body: JsonObject): JsonElement

    suspend fun info(): JsonElement
}

// nginx of VyOS waits 600 seconds for the answer of the API, and a commit may take that long; a read is answered at once
private val COMMIT_TIME = 11.minutes
private val READ_TIME = 2.minutes
private val commits = setOf("/configure", "/config-file")

class HttpsVyos(private val host: String, private val key: String, private val open: suspend () -> ByteStream) : VyosCall {

    override suspend fun call(endpoint: String, body: JsonObject): JsonElement =
        request("POST", "/$endpoint", JsonObject(body + ("key" to JsonPrimitive(key))).toString().encodeToByteArray())

    override suspend fun info(): JsonElement = request("GET", "/info", ByteArray(0))

    private suspend fun request(method: String, target: String, body: ByteArray): JsonElement {
        val headers = buildMap {
            put("Accept", "application/json")
            if (body.isNotEmpty()) put("Content-Type", "application/json")
        }
        val response = open().closingAfter(if (target in commits) COMMIT_TIME else READ_TIME) { httpRequest(it, host, method, target, headers, body) }
        val envelope = try {
            Json.parseToJsonElement(response.body.decodeToString()) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: throw DeviceException(if (response.status == 404) ErrorCategory.MissingItem else ErrorCategory.Api, "$target answered HTTP ${response.status} without the JSON of the API")
        if ((envelope["success"] as? JsonPrimitive)?.booleanOrNull == true) return envelope["data"] ?: JsonNull
        // a request the API does not take comes back from its framework as detail, not as error
        val error = (envelope["error"] as? JsonPrimitive)?.contentOrNull ?: envelope["detail"]?.toString() ?: "$target answered HTTP ${response.status}"
        throw DeviceException(
            when (response.status) {
                401, 403 -> ErrorCategory.PermissionDenied
                404 -> ErrorCategory.MissingItem
                else -> ErrorCategory.ArgumentValue
            },
            error,
        )
    }
}
