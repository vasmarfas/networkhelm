package com.vasmarfas.networkhelm.openwrt

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.transport.BufferedStream
import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.transport.HttpResponse
import com.vasmarfas.networkhelm.transport.httpExchange
import com.vasmarfas.networkhelm.transport.httpLines
import com.vasmarfas.networkhelm.transport.keepsAlive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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

interface UbusWeb {
    suspend fun post(json: String): HttpResponse

    fun lines(target: String, headers: Map<String, String>): Flow<String>
}

// uhttpd serves three requests at once (max_requests) and keeps an idle connection for twenty seconds
class SocketUbusWeb(
    private val host: String,
    private val timeSource: TimeSource,
    private val open: suspend () -> ByteStream,
) : UbusWeb {
    constructor(host: String, open: suspend () -> ByteStream) : this(host, TimeSource.Monotonic, open)

    private val slots = Semaphore(MAX_REQUESTS)
    private val lock = Mutex()
    private val idle = ArrayDeque<Pair<BufferedStream, TimeMark>>()

    override suspend fun post(json: String): HttpResponse = slots.withPermit {
        val body = json.encodeToByteArray()
        val headers = mapOf("Content-Type" to "application/json")
        val reading = reads(json)
        // a change goes over a new connection: a pooled one that proves dead would leave it unknown whether the change was made
        val kept = if (reading) {
            lock.withLock {
                while (idle.isNotEmpty()) {
                    val (connection, since) = idle.removeLast()
                    if (since.elapsedNow() < MAX_IDLE) return@withLock connection
                    connection.close()
                }
                null
            }
        } else {
            null
        }
        if (kept != null) exchange(kept, headers, body, reading)?.let { return@withPermit it }
        exchange(BufferedStream(open()), headers, body, reading)
            ?: throw if (reading) DeviceException(ErrorCategory.ConnectionLost, "the server closed the connection without an answer") else RequestOutcomeUnknownException()
    }

    override fun lines(target: String, headers: Map<String, String>) = httpLines(open, host, target, headers)

    private suspend fun exchange(connection: BufferedStream, headers: Map<String, String>, body: ByteArray, reading: Boolean): HttpResponse? {
        val answer = try {
            inTime(connection) { httpExchange(connection, host, "POST", "/ubus", headers, body) }
        } catch (e: Throwable) {
            connection.close()
            if (!reading && e !is CancellationException && e is Exception && (e !is DeviceException || e.category == ErrorCategory.ConnectionLost)) throw RequestOutcomeUnknownException(e)
            throw e
        }
        when {
            answer == null -> connection.close()
            answer.keepsAlive() -> try {
                lock.withLock { idle.addLast(connection to timeSource.markNow()) }
            } catch (e: CancellationException) {
                connection.close()
                throw e
            }
            else -> connection.close()
        }
        return answer
    }

    // a blocking read ends only when its connection is closed, which the time and a cancelled caller do;
    // an answered connection stays open for the pool
    private suspend fun inTime(connection: BufferedStream, request: suspend () -> HttpResponse?): HttpResponse? = coroutineScope {
        var answered = false
        val watchdog = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                delay(REQUEST_TIME)
            } finally {
                if (!answered) connection.close()
            }
        }
        try {
            request().also { answered = true }
        } catch (e: Exception) {
            ensureActive()
            throw e
        } finally {
            watchdog.cancel()
        }
    }

    private companion object {
        const val MAX_REQUESTS = 3
        val MAX_IDLE = 15.seconds

        // uhttpd gives a call of ubus 60 seconds (script_timeout), the rest is for the answer to come over a slow link
        val REQUEST_TIME = 2.minutes
    }
}

class RequestOutcomeUnknownException(cause: Throwable? = null) : DeviceException(
    ErrorCategory.ConnectionLost,
    MESSAGE,
    cause,
) {
    companion object {
        const val MESSAGE = "the connection was lost after sending the request; its outcome is unknown"
    }
}

private fun reads(json: String): Boolean {
    val request = try {
        Json.parseToJsonElement(json) as? JsonObject
    } catch (e: IllegalArgumentException) {
        null
    } ?: return false
    fun text(value: JsonElement?) = (value as? JsonPrimitive)?.contentOrNull
    if (text(request["method"]) == "list") return true
    if (text(request["method"]) != "call") return false
    val params = request["params"] as? JsonArray ?: return false
    return text(params.getOrNull(2)) in readCalls[text(params.getOrNull(1))].orEmpty()
}

// what is not listed counts as a change: a repeat of it could do the work twice
private val readCalls = mapOf(
    "file" to setOf("read", "list", "stat"),
    "uci" to setOf("get", "state", "configs", "changes"),
    "session" to setOf("list", "access", "login"),
    "system" to setOf("board", "info"),
    "network" to setOf("get_proto_handlers"),
    "network.interface" to setOf("dump"),
    "network.device" to setOf("status"),
    "network.wireless" to setOf("status", "get_validate"),
    "service" to setOf("list"),
    "rc" to setOf("list"),
    "log" to setOf("read"),
    "dhcp" to setOf("ipv6leases"),
    "iwinfo" to setOf("info", "assoclist", "freqlist", "txpowerlist", "countrylist"),
    "luci" to setOf("getFeatures", "getVersion", "getHostHints", "getNetworkDevices", "getProcessList", "getConntrackList", "getUnixtime", "getLocaltime"),
    "luci-rpc" to setOf("getDHCPLeases", "getHostHints", "getNetworkDevices"),
    "luci.ddns" to setOf("get_services_status"),
    "luci.upnp" to setOf("get_status"),
    "luci.wireguard" to setOf("getWgInstances"),
    "rpc-sys" to setOf("packagelist"),
)
