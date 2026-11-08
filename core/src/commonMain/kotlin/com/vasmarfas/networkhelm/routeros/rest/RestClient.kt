package com.vasmarfas.networkhelm.routeros.rest

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.LinkState
import com.vasmarfas.networkhelm.routeros.api.ApiConnectionClosedException
import com.vasmarfas.networkhelm.routeros.api.ApiReply
import com.vasmarfas.networkhelm.routeros.api.ApiResult
import com.vasmarfas.networkhelm.routeros.api.ApiTrapException
import com.vasmarfas.networkhelm.routeros.api.BigTables
import com.vasmarfas.networkhelm.routeros.api.RouterOsClient
import com.vasmarfas.networkhelm.routeros.api.RouterOsLink
import com.vasmarfas.networkhelm.routeros.api.rounds
import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.transport.closingAfter
import com.vasmarfas.networkhelm.transport.httpRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.minutes

// RouterOS gives a REST command 60 seconds, the rest is for the rows to come over a slow link
private val REQUEST_TIME = 2.minutes

class RestClient(
    private val host: String,
    user: String,
    password: String,
    private val open: suspend () -> ByteStream,
) : RouterOsClient {
    private val authorization = "Basic " + Base64.encode("$user:$password".encodeToByteArray())

    private val requests = Semaphore(4)
    private val tables = BigTables()

    override suspend fun call(words: List<String>): ApiResult = read(words) { _, _ -> }

    override suspend fun table(words: List<String>, progress: suspend (Int, Int) -> Unit): List<Map<String, String>> = read(words, progress).rows

    private suspend fun read(words: List<String>, progress: suspend (Int, Int) -> Unit): ApiResult {
        var ids = emptyList<String>()
        val menu = words.first().takeIf { it.endsWith("/print") && "=count-only=" !in words }
        return tables.read(words, menu, progress, ::answer) { part ->
            if (ids.isEmpty()) ids = answer(listOf(words.first(), "=.proplist=.id") + words.filter { it.startsWith("?") }).rows.mapNotNull { it[".id"] }
            val asked = ids.subList(minOf(part.first, ids.size), minOf(part.last + 1, ids.size))
            try {
                if (asked.isEmpty()) emptyList() else answer(words.filterNot { it.startsWith("?") } + "=from=${asked.joinToString(",")}").rows
            } catch (e: ApiTrapException) {
                ids = emptyList()
                if (e.message.orEmpty().contains("from")) null else throw e
            }
        }
    }

    private suspend fun answer(words: List<String>): ApiResult {
        val headers = mapOf("Authorization" to authorization, "Content-Type" to "application/json")
        val response = try {
            requests.withPermit {
                open().closingAfter(REQUEST_TIME) { stream ->
                    httpRequest(stream, host, "POST", "/rest" + words.first(), headers, arguments(words).toString().encodeToByteArray())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DeviceException) {
            throw e
        } catch (e: Exception) {
            throw ApiConnectionClosedException(e.message ?: "REST request failed", e)
        }
        val text = response.body.decodeToString()
        when {
            response.status == 401 -> throw DeviceException(ErrorCategory.PermissionDenied, message(text))
            response.status == 404 -> throw ApiTrapException(0, message(text))
            response.status >= 400 -> throw ApiTrapException(null, message(text))
        }
        return when (val json = if (text.isBlank()) JsonArray(emptyList()) else Json.parseToJsonElement(text)) {
            is JsonArray -> ApiResult(json.map { strings(it.jsonObject) }, emptyMap())
            is JsonObject -> ApiResult(emptyList(), strings(json))
            else -> throw DeviceException(ErrorCategory.Api, "unexpected REST answer: $text")
        }
    }

    override fun command(words: List<String>, sent: CompletableDeferred<Unit>?): Flow<ApiReply> = flow {
        if (words.first().endsWith("/listen")) throw ApiTrapException(null, "listen is not available over REST")
        sent?.complete(Unit)
        val stream = rounds(words) { call(it).rows }
        if (stream == null) call(words).rows.forEach { emit(ApiReply("!re", it, null)) } else emitAll(stream)
    }

    override fun <T> each(block: suspend FlowCollector<T>.(RouterOsLink) -> Unit): Flow<T> = flow { block(this@RestClient) }

    override val status: Flow<LinkState> = flowOf(LinkState.Up)

    override fun close() = Unit

    private fun arguments(words: List<String>) = buildJsonObject {
        val query = words.filter { it.startsWith("?") }.map { it.substring(1) }
        if (query.isNotEmpty()) put(".query", JsonArray(query.map(::JsonPrimitive)))
        for (word in words.drop(1).filter { it.startsWith("=") }) {
            val name = word.substring(1).substringBefore('=')
            val value = word.substring(1).substringAfter('=', "")
            put(name, if (name == ".proplist") JsonArray(value.split(',').map(::JsonPrimitive)) else JsonPrimitive(value))
        }
    }

    private fun strings(json: JsonObject) = json.mapValues { (_, value) -> (value as? JsonPrimitive)?.content ?: value.toString() }

    // the router text is in detail; message is only the HTTP reason (Bad Request)
    private fun message(text: String): String {
        val json = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
        return (json?.get("detail") ?: json?.get("message"))?.jsonPrimitive?.content ?: text
    }
}
