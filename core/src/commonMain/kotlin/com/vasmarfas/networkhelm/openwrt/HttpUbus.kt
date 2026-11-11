package com.vasmarfas.networkhelm.openwrt

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.transport.ByteStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

// a session lapses 300 s after its last call
class HttpUbus(private val user: String, private val password: String, private val web: UbusWeb) : Ubus {
    constructor(host: String, user: String, password: String, open: suspend () -> ByteStream) : this(user, password, SocketUbusWeb(host, open))

    private val lock = Mutex()
    private var session: String? = null

    override suspend fun call(obj: String, method: String, args: JsonObject): JsonObject = withSession { rpc(it, obj, method, args) }

    override suspend fun objects(): Set<String> = post("list", listOf(JsonPrimitive("*")))["result"]?.jsonObject?.keys.orEmpty()

    override fun listen(vararg events: String): Flow<JsonObject> = flow {
        throw UbusException(UbusStatus.NOT_SUPPORTED, "broadcast events of ubus are not sent over HTTP")
    }

    override fun subscribe(obj: String): Flow<JsonObject> = flow { withSession { emitAll(events(obj, it)) } }

    // uhttpd answers a session that has lapsed and one whose ACL lacks the call alike, and the UCI draft belongs to the session,
    // so a new login is made only once the old session is gone
    private suspend fun <T> withSession(request: suspend (String) -> T): T {
        val used = session(null)
        return try {
            request(used)
        } catch (denied: Denied) {
            if (lives(used)) throw UbusException(UbusStatus.PERMISSION_DENIED, denied.message.orEmpty())
            try {
                request(session(used))
            } catch (again: Denied) {
                throw UbusException(UbusStatus.PERMISSION_DENIED, again.message.orEmpty())
            }
        }
    }

    // any session that lives may ask session access
    private suspend fun lives(sid: String) = try {
        rpc(sid, "session", "access", JsonObject(mapOf("object" to JsonPrimitive("session"), "function" to JsonPrimitive("access"))))
        true
    } catch (e: Denied) {
        false
    }

    // a subscription the session may not have gets 200 and a JSON-RPC error in place of the events
    private fun events(obj: String, sid: String): Flow<JsonObject> = flow {
        var event = "message"
        val data = StringBuilder()
        web.lines("/ubus/subscribe/$obj", mapOf("Authorization" to "Bearer $sid")).catch { throw lost(it) }.collect { line ->
            when {
                line.isEmpty() -> {
                    if (data.isNotEmpty()) emit(JsonObject(mapOf(event to Json.parseToJsonElement(data.toString()))))
                    event = "message"
                    data.clear()
                }
                line.startsWith("event:") -> event = line.substringAfter(':').trim()
                line.startsWith("data:") -> {
                    data.append(line.substringAfter(':').trim())
                    if (data.length > MAX_EVENT_CHARS) throw DeviceException(ErrorCategory.Api, "an event exceeds $MAX_EVENT_CHARS characters")
                }
                line.startsWith("{") -> Json.parseToJsonElement(line).jsonObject["error"]?.jsonObject?.let { throw error(it) }
            }
        }
    }

    private suspend fun session(lapsed: String?): String = lock.withLock {
        session?.takeIf { it != lapsed } ?: login().also { session = it }
    }

    private suspend fun login(): String {
        val credentials = JsonObject(mapOf("username" to JsonPrimitive(user), "password" to JsonPrimitive(password)))
        val answer = try {
            rpc(ANONYMOUS, "session", "login", credentials)
        } catch (e: Denied) {
            throw UbusException(UbusStatus.PERMISSION_DENIED, e.message.orEmpty())
        }
        return answer["ubus_rpc_session"]?.jsonPrimitive?.contentOrNull
            ?: throw UbusException(UbusStatus.UNKNOWN_ERROR, "session login answered without a session")
    }

    private suspend fun rpc(sid: String, obj: String, method: String, args: JsonObject): JsonObject {
        val result = post("call", listOf(JsonPrimitive(sid), JsonPrimitive(obj), JsonPrimitive(method), args))["result"]?.jsonArray
            ?: throw UbusException(UbusStatus.UNKNOWN_ERROR, "/ubus answered without a result")
        val status = result.firstOrNull()?.jsonPrimitive?.intOrNull ?: throw UbusException(UbusStatus.UNKNOWN_ERROR, "/ubus answered without a status")
        if (status != 0) throw UbusException(status)
        return result.getOrNull(1)?.jsonObject ?: JsonObject(emptyMap())
    }

    private suspend fun post(method: String, params: List<JsonElement>): JsonObject {
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", method)
            putJsonArray("params") { params.forEach { add(it) } }
        }
        val response = try {
            web.post(request.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw lost(e)
        }
        if (response.status != 200) throw UbusException(UbusStatus.NOT_SUPPORTED, "the web server answers /ubus with HTTP ${response.status}")
        val answer = try {
            Json.parseToJsonElement(response.body.decodeToString()) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: throw UbusException(UbusStatus.NOT_SUPPORTED, "the web server answers /ubus with no JSON-RPC")
        answer["error"]?.jsonObject?.let { throw error(it) }
        return answer
    }

    private fun error(error: JsonObject): Exception {
        val message = error["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return when (error["code"]?.jsonPrimitive?.intOrNull) {
            -32001, -32002 -> Denied(message)
            -32000 -> UbusException(UbusStatus.NOT_FOUND, message)
            -32003 -> UbusException(UbusStatus.TIMEOUT, message)
            -32601 -> UbusException(UbusStatus.METHOD_NOT_FOUND, message)
            -32602 -> UbusException(UbusStatus.INVALID_ARGUMENT, message)
            else -> UbusException(UbusStatus.UNKNOWN_ERROR, message)
        }
    }

    private fun lost(e: Throwable) = e as? DeviceException ?: DeviceException(ErrorCategory.ConnectionLost, e.message ?: e::class.simpleName.orEmpty(), e)

    private class Denied(message: String) : Exception(message)

    private companion object {
        const val ANONYMOUS = "00000000000000000000000000000000"
        const val MAX_EVENT_CHARS = 16 * 1024 * 1024
    }
}
