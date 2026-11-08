package com.vasmarfas.networkhelm.routeros.api

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.LinkState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed interface ApiLink {
    class Up(val connection: ApiConnection) : ApiLink

    class Down(val cause: Throwable, val attempt: Int, val retryIn: Duration) : ApiLink

    class Closed(val cause: Throwable?) : ApiLink
}

class ApiClient(
    first: ApiConnection,
    scope: CoroutineScope,
    private val keepAlive: Duration = 15.seconds,
    private val open: suspend () -> ApiConnection,
) : RouterOsClient {
    private val link = MutableStateFlow<ApiLink>(ApiLink.Up(first))
    val state: StateFlow<ApiLink> = link.asStateFlow()

    override val status: Flow<LinkState> = state.map {
        when (it) {
            is ApiLink.Up -> LinkState.Up
            is ApiLink.Down -> LinkState.Reconnecting
            is ApiLink.Closed -> LinkState.Closed
        }
    }

    private val supervisor = scope.launch { supervise(first) }

    override suspend fun call(words: List<String>): ApiResult = connection().call(words)

    override fun command(words: List<String>, sent: CompletableDeferred<Unit>?): Flow<ApiReply> =
        flow { emitAll(connection().command(words, sent)) }

    override fun <T> each(block: suspend FlowCollector<T>.(RouterOsLink) -> Unit): Flow<T> = flow {
        var connection = connection()
        while (true) {
            try {
                block(connection)
                return@flow
            } catch (e: ApiConnectionClosedException) {
                connection = connection(after = connection)
            }
        }
    }

    override fun close() {
        val last = link.getAndUpdate { ApiLink.Closed(null) }
        supervisor.cancel()
        (last as? ApiLink.Up)?.connection?.close()
    }

    private suspend fun connection(after: ApiConnection? = null): ApiConnection {
        val current = link.first { it is ApiLink.Closed || it is ApiLink.Up && it.connection !== after }
        if (current is ApiLink.Up) return current.connection
        throw ApiConnectionClosedException("the client is closed", (current as ApiLink.Closed).cause)
    }

    private suspend fun supervise(first: ApiConnection) {
        var connection = first
        while (true) {
            val reason = try {
                publish(ApiLink.Up(connection))
                coroutineScope {
                    val pinger = launch { ping(connection) }
                    connection.awaitClosed().also { pinger.cancel() }
                }
            } finally {
                connection.close()
            }
            connection = reopen(reason) ?: return
        }
    }

    // the answer to a ping waits behind the rows of a big table, so what proves the link is anything heard from the router
    private suspend fun ping(connection: ApiConnection) {
        var heard = connection.heard
        while (true) {
            delay(keepAlive)
            if (connection.heard != heard) {
                heard = connection.heard
                continue
            }
            val answered = try {
                withTimeoutOrNull(keepAlive) { connection.call("/system/identity/print") } != null
            } catch (e: ApiTrapException) {
                true
            } catch (e: ApiConnectionClosedException) {
                return
            }
            if (!answered && connection.heard == heard) {
                connection.close(ApiConnectionClosedException("no answer from the router in $keepAlive"))
                return
            }
            heard = connection.heard
        }
    }

    private suspend fun reopen(reason: Throwable): ApiConnection? {
        var cause = reason
        var attempt = 1
        while (true) {
            val pause = minOf(30.seconds, 1.seconds * (1 shl minOf(attempt - 1, 5)))
            publish(ApiLink.Down(cause, attempt, pause))
            delay(pause)
            try {
                return open()
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                // a refused login or certificate does not change by itself, and every failed login lands in the router log
                if (e is ApiTrapException || e is DeviceException && e.category == ErrorCategory.PermissionDenied) {
                    publish(ApiLink.Closed(e))
                    return null
                }
                cause = e
                attempt++
            }
        }
    }

    private fun publish(state: ApiLink) = link.update { if (it is ApiLink.Closed) it else state }
}
