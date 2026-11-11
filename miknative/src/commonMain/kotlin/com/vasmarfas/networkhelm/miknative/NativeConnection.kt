package com.vasmarfas.networkhelm.miknative

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.transport.openTcp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile

internal const val DEFAULT_PORT = 8291
private const val FIRST_SOURCE = 16
private const val LOGIN_ATTEMPTS = 3
private const val LOGIN_TIMEOUT_MILLIS = 10_000L
private const val REPLY_TIMEOUT_MILLIS = 10_000L
private val NOT_IMPLEMENTED = setOf(0xFE0002L, 0xFE0003L)
private const val NOT_PERMITTED = 0xFE0009L
private const val FAILED = 0xFE0006L
private const val TIMEOUT = 0xFE000DL

private const val PROXY = 2L
private const val CONNECT = 2001L
private const val TARGET_ID = 4
private const val CONNECT_FLAG = 6
private const val TARGET_USER = 7
private const val TARGET_PASSWORD = 8

private const val LOGOUT = 0xFE0014L
private const val LINK_KEY = 0xFF0003L

// a login the router ended without an answer, which is not the answer that refuses it
private const val TOO_MANY = "too many concurrent logins"

private class LoginDropped(message: String = "the router closed the connection during login", cause: Throwable? = null) :
    DeviceException(ErrorCategory.PermissionDenied, message, cause)

private class Registry(
    val sources: Map<Int, Channel<M2Message>> = emptyMap(),
    val last: Int = FIRST_SOURCE - 1,
    val failure: Throwable? = null,
) {
    fun reserved() = Registry(sources, last + 1, failure)

    fun with(id: Int, inbox: Channel<M2Message>) = Registry(sources + (id to inbox), last, failure)

    fun without(id: Int) = Registry(sources - id, last, failure)

    fun failed(cause: Throwable) = Registry(sources, last, failure ?: cause)
}

class NativeConnection private constructor(
    private val stream: ByteStream,
    private val frames: FrameReader,
    private val channel: SecureChannel,
    internal val scope: CoroutineScope,
) {
    private val writeLock = Mutex()
    private val registry = MutableStateFlow(Registry())

    @Volatile
    internal var route: List<Long> = emptyList()
        private set

    private val reader = scope.launch { readLoop() }

    internal val alive: Boolean get() = registry.value.failure == null

    internal fun newSource(): NativeSource {
        val source = NativeSource(this, registry.updateAndGet { it.reserved() }.last)
        registry.updateAndGet { if (it.failure == null) it.with(source.id, source.inbox) else it }.failure?.let { source.inbox.close(it) }
        return source
    }

    internal suspend fun send(message: M2Message) {
        val bytes = channel.seal(message.encode())
        writeLock.withLock {
            try {
                stream.write(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DeviceException) {
                throw e
            } catch (e: Exception) {
                throw DeviceException(ErrorCategory.ConnectionLost, "connection closed", e)
            }
        }
    }

    suspend fun relay(target: RomonTarget) {
        val source = newSource()
        try {
            val reply = source.exchange(
                listOf(PROXY),
                M2Value.U32(CONNECT),
                CONNECT_FLAG to M2Value.Bool(true),
                TARGET_PASSWORD to M2Value.Text(target.password),
                TARGET_USER to M2Value.Text(target.user),
                TARGET_ID to M2Value.Raw(target.idBytes),
            )
            when (reply.number(M2Key.ErrorCode)?.takeIf { it != 0L }) {
                null -> Unit
                FAILED, TIMEOUT -> throw DeviceException(
                    ErrorCategory.ConnectionLost,
                    "the agent cannot reach ${target.id}: no such RoMON id, or RoMON is off on the agent",
                )
                NOT_PERMITTED -> throw DeviceException(ErrorCategory.PermissionDenied, "${target.id} refused the login of ${target.user}")
                else -> throw reply.refusal() ?: DeviceException(ErrorCategory.General, "the agent opened no link to ${target.id}")
            }
            val link = reply.number(M2Key.SessionId) ?: throw DeviceException(ErrorCategory.General, "the agent opened no link to ${target.id}")
            route = listOf(PROXY, link)
        } finally {
            source.close()
        }
    }

    internal fun release(id: Int) {
        registry.getAndUpdate { it.without(id) }.sources[id]?.close()
    }

    fun close() {
        stream.close()
        reader.cancel()
        registry.value.sources.values.forEach { it.close() }
    }

    private suspend fun readLoop() {
        var cause: Throwable? = null
        try {
            while (true) {
                val frame = frames.next() ?: break
                for (body in channel.open(frame.payload)) {
                    val message = try {
                        M2Message.decode(body)
                    } catch (e: M2FormatException) {
                        continue
                    }
                    if (endsLink(message)) throw DeviceException(ErrorCategory.ConnectionLost, "the RoMON link ended")
                    val target = message.numbers(M2Key.To)?.getOrNull(1)?.toInt() ?: continue
                    registry.value.sources[target]?.trySend(message)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cause = e as? DeviceException ?: DeviceException(ErrorCategory.ConnectionLost, "connection lost", e)
        } finally {
            val ended = cause ?: DeviceException(ErrorCategory.ConnectionLost, "connection closed")
            stream.close()
            registry.updateAndGet { it.failed(ended) }.sources.values.forEach { it.close(ended) }
        }
    }

    private fun endsLink(message: M2Message): Boolean {
        val link = route.getOrNull(1) ?: return false
        if (message.number(M2Key.Command) != LOGOUT || message.number(M2Key.RequestId) != null) return false
        val from = message.numbers(M2Key.From).orEmpty()
        val tail = from.drop(1)
        // the router names the link behind the proxy, behind the key of a type, or behind both
        return when (from.firstOrNull()) {
            PROXY -> tail.firstOrNull() == link
            LINK_KEY -> tail.firstOrNull() == link || tail.take(2) == listOf(PROXY, link)
            else -> false
        }
    }

    companion object {
        // a router with too many logins at once says so or closes the connection, and such a login is tried again;
        // a refusal of the name or the password is believed at once: a wrong password is tried once
        suspend fun open(host: String, user: String, password: String, scope: CoroutineScope, port: Int = DEFAULT_PORT): NativeConnection {
            var dropped: LoginDropped? = null
            repeat(LOGIN_ATTEMPTS) { attempt ->
                if (attempt > 0) delay(100L shl attempt)
                val stream = openTcp(host, port)
                try {
                    return open(stream, user, password, scope)
                } catch (e: LoginDropped) {
                    stream.close()
                    dropped = e
                } catch (e: Exception) {
                    stream.close()
                    throw e
                }
            }
            throw checkNotNull(dropped)
        }

        suspend fun open(
            stream: ByteStream,
            user: String,
            password: String,
            scope: CoroutineScope,
            loginTimeoutMillis: Long = LOGIN_TIMEOUT_MILLIS,
        ): NativeConnection {
            val frames = FrameReader(stream)
            val keys = try {
                stream.within(loginTimeoutMillis) { login(stream, frames, user, password) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DeviceException) {
                throw e
            } catch (e: Exception) {
                throw LoginDropped(cause = e)
            }
            return NativeConnection(stream, frames, SecureChannel(keys), scope)
        }

        private class Deadline {
            @Volatile
            var finished = false

            @Volatile
            var expired = false
        }

        // a socket read cannot be interrupted, so the wait ends by closing the stream under it
        private suspend fun <T> ByteStream.within(millis: Long, block: suspend () -> T): T = coroutineScope {
            val deadline = Deadline()
            val guard = launch {
                try {
                    delay(millis)
                    deadline.expired = true
                    close()
                } catch (e: CancellationException) {
                    if (!deadline.finished) close()
                    throw e
                }
            }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: DeviceException) {
                throw e
            } catch (e: Exception) {
                ensureActive()
                if (deadline.expired) throw DeviceException(ErrorCategory.ConnectionLost, "the router did not answer in time", e)
                throw e
            } finally {
                deadline.finished = true
                guard.cancel()
            }
        }

        private suspend fun login(stream: ByteStream, frames: FrameReader, user: String, password: String): StreamKeys {
            val srp = EcSrp5Client(user, password)
            stream.write(encodeFrame(TAG_ENCRYPTED, srp.hello()))
            val challenge = frames.nextOrRefuse()
            if (challenge.tag != TAG_ENCRYPTED) throw DeviceException(ErrorCategory.Unsupported, "the router does not speak EC-SRP5")
            stream.write(encodeFrame(TAG_ENCRYPTED, srp.confirm(challenge.payload)))
            val confirmation = frames.nextOrRefuse()
            if (!srp.verify(confirmation.payload)) {
                // the router refuses in words, and its limit of logins is the one refusal that the next try may not meet
                if (TOO_MANY in confirmation.payload.decodeToString()) throw LoginDropped(TOO_MANY)
                throw DeviceException(ErrorCategory.PermissionDenied, WRONG_LOGIN)
            }
            return srp.keys()
        }

        private suspend fun FrameReader.nextOrRefuse(): Frame = next() ?: throw LoginDropped()
    }
}

internal class NativeSource(private val connection: NativeConnection, val id: Int) {
    private var requests = 0

    private val returned = Channel<M2Message>(Channel.UNLIMITED)

    val inbox = Channel<M2Message>(Channel.UNLIMITED) { returned.trySend(it) }

    suspend fun send(to: List<Long>, vararg fields: Pair<Int, M2Value>) {
        val head = arrayOf(
            M2Key.To to M2Value.U32s((connection.route + to).toLongArray()),
            M2Key.From to M2Value.U32s(longArrayOf(0, id.toLong())),
        )
        connection.send(M2Message.of(*head, *fields))
    }

    fun nextRequestId() = requests++ and 0xFF

    suspend fun exchange(to: List<Long>, command: M2Value, vararg fields: Pair<Int, M2Value>): M2Message {
        val id = nextRequestId()
        send(to, M2Key.Request to M2Value.Bool(true), M2Key.RequestId to M2Value.U8(id), M2Key.Command to command, *fields)
        return withTimeoutOrNull(REPLY_TIMEOUT_MILLIS) {
            var message = next()
            while (message != null && message.number(M2Key.RequestId) != id.toLong()) message = next()
            message
        } ?: throw DeviceException(ErrorCategory.ConnectionLost, "the router did not answer")
    }

    suspend fun request(to: List<Long>, command: M2Value, vararg fields: Pair<Int, M2Value>): M2Message =
        exchange(to, command, *fields).also { reply -> reply.refusal()?.let { throw it } }

    suspend fun next(): M2Message? {
        returned.tryReceive().getOrNull()?.let { return it }
        val result = inbox.receiveCatching()
        result.exceptionOrNull()?.let { throw it }
        return result.getOrNull()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun isIdle() = returned.isEmpty && inbox.isEmpty

    fun close() = connection.release(id)
}

// RouterOS 6 answers "not implemented" to the requests of newer windows
internal fun M2Message.refusal(): DeviceException? {
    val code = number(M2Key.ErrorCode)?.takeIf { it != 0L }
    val text = text(M2Key.Error)
    return when {
        code in NOT_IMPLEMENTED -> DeviceException(ErrorCategory.Unsupported, text ?: "the router does not implement this request")
        text != null -> DeviceException(ErrorCategory.General, text)
        code != null -> DeviceException(ErrorCategory.General, "the router refused the request (error 0x${code.toString(16)})")
        else -> null
    }
}
