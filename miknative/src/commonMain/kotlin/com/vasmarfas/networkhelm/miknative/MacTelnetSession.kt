package com.vasmarfas.networkhelm.miknative

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.routeros.LoginTimeoutException
import com.vasmarfas.networkhelm.ssh.ExecResult
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.ByteStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

private val RETRANSMIT_INTERVALS_MS = longArrayOf(15, 20, 30, 50, 90, 170, 330, 660, 1000)
private const val START_RETRANSMIT_MS = 250L
private const val WRITE_CHUNK = 1024
private const val NATIVE_WRITE_CHUNK = 1450
private const val NATIVE_START_MS = 4_000L
private const val KEEPALIVE_MS = 5_000L

private val clock = TimeSource.Monotonic.markNow()

private fun now() = clock.elapsedNow().inWholeMilliseconds

internal fun randomKey() = secureRandomBytes(2).let { ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) }

class MacTelnetSession internal constructor(
    private val sink: MacTelnetTransport,
    val sourceMac: MacAddress,
    val destinationMac: MacAddress,
    private val user: String,
    private val password: String,
    val sessionKey: Int = randomKey(),
    private var terminalWidth: Int = 80,
    private var terminalHeight: Int = 24,
    private val terminalType: String = "vt102",
    private val offerMtwei: Boolean = true,
    private val native: Boolean = false,
    private val destinationAddress: String = BROADCAST,
    private val keepAliveMillis: Long = KEEPALIVE_MS,
    private val scope: CoroutineScope,
) : ShellChannel {

    private enum class State {
        INIT,
        SESSION_START_SENT,
        AUTH_BEGIN_SENT,
        AUTH_SENT,
        AUTH_COMPLETE,
        READY,
        CLOSED,
    }

    private val writeLock = Mutex()
    private val stateLock = reentrantLock()
    private var state = State.INIT
    private var outCounter = 0L
    private var expectedInCounter = 0L
    private var maxOutAck = -1L
    private var srpClient: EcSrp5Client? = null
    private val clientType = if (native) MAC_NATIVE_CLIENT_TYPE else MAC_TELNET_CLIENT_TYPE
    private val writeChunk = if (native) NATIVE_WRITE_CHUNK else WRITE_CHUNK

    private class PendingFrame(
        val bytes: ByteArray,
        val ackTarget: Long,
        val ackDeferred: CompletableDeferred<Unit>? = null,
        var attempts: Int = 0,
    )
    private var pending: PendingFrame? = null

    private val incomingChunks = Channel<ByteArray>(Channel.UNLIMITED)
    private var currentChunk: ByteArray? = null
    private var currentOffset = 0
    private var ended = false
    private var failure: Throwable? = null

    @Volatile
    private var lastSent = now()

    val readyDeferred = CompletableDeferred<Unit>()
    val closedDeferred = CompletableDeferred<Unit>()

    private val retransmitJob = scope.launch {
        retransmissionLoop()
    }

    private suspend fun retransmissionLoop() {
        while (scope.isActive && state != State.CLOSED) {
            val toResend = stateLock.locked {
                if (state == State.CLOSED) return@locked null
                val frame = pending ?: return@locked null
                if (frame.attempts >= 15) {
                    fail(DeviceException(ErrorCategory.ConnectionLost, "MAC-Telnet peer not responding"))
                    return@locked null
                }
                val delayTime = when {
                    state == State.SESSION_START_SENT -> START_RETRANSMIT_MS
                    frame.attempts < RETRANSMIT_INTERVALS_MS.size -> RETRANSMIT_INTERVALS_MS[frame.attempts]
                    else -> 1000L
                }
                frame to delayTime
            }
            if (toResend == null) {
                keepAlive()
                delay(50)
                continue
            }
            val (frame, waitMs) = toResend
            delay(waitMs)
            stateLock.locked {
                if (pending === frame && state != State.CLOSED) {
                    frame.attempts++
                    try {
                        emit(frame.bytes)
                    } catch (e: Exception) {
                        fail(e)
                    }
                }
            }
        }
    }

    private fun keepAlive() {
        stateLock.locked {
            if (state == State.READY && now() - lastSent >= keepAliveMillis) {
                try {
                    acknowledge(expectedInCounter)
                } catch (e: Exception) {
                    fail(e)
                }
            }
        }
    }

    fun start() {
        stateLock.locked {
            if (state != State.INIT) return
            state = State.SESSION_START_SENT
            val frame = packet(MacTelnetPacketType.SESSION_START, outCounter)
            pending = PendingFrame(frame, ackTarget = 0)
            emit(frame)
        }
    }

    fun handlePacket(bytes: ByteArray) {
        val header = try {
            decodeHeader(bytes, fromServer = true)
        } catch (e: DeviceException) {
            return
        }

        stateLock.locked {
            if (state == State.CLOSED) return
            if (header.version != 1 || header.sessionKey != sessionKey) return
            if (header.sourceMac != destinationMac || header.destinationMac != sourceMac) return

            when (header.type) {
                MacTelnetPacketType.ACK -> onAck(header)
                MacTelnetPacketType.DATA -> onData(header, bytes)
                MacTelnetPacketType.END -> onEnd()
                MacTelnetPacketType.PING -> onPing(header)
            }
        }
    }

    private fun onPing(header: MacTelnetHeader) {
        emit(packet(MacTelnetPacketType.PONG, header.counter))
    }

    private fun onAck(header: MacTelnetHeader) {
        maxOutAck = maxOf(maxOutAck, header.counter)
        val currentPending = pending
        if (currentPending != null && maxOutAck >= currentPending.ackTarget) {
            currentPending.ackDeferred?.complete(Unit)
            pending = null
        }
        if (state != State.SESSION_START_SENT) return

        if (native) {
            state = State.READY
            readyDeferred.complete(Unit)
            return
        }
        state = State.AUTH_BEGIN_SENT
        var blocks = encodeControlBlock(MacTelnetControlType.BEGIN_AUTH)
        if (offerMtwei) {
            val client = EcSrp5Client(identityUser(), password)
            srpClient = client
            blocks += encodeControlBlock(MacTelnetControlType.PASSWORD_SALT, client.hello())
        }
        sendData(blocks)
    }

    private fun identityUser(): String = user.substringBefore('+')

    private fun onData(header: MacTelnetHeader, bytes: ByteArray) {
        val payload = bytes.copyOfRange(MAC_TELNET_HEADER_LEN, bytes.size)
        if (header.counter == expectedInCounter) {
            expectedInCounter += payload.size
            acknowledge(expectedInCounter)
            if (native) {
                if (payload.isNotEmpty()) incomingChunks.trySend(payload)
                return
            }
            val blocks = try {
                parseControlBlocks(payload)
            } catch (e: Exception) {
                fail(e)
                return
            }
            for (block in blocks) {
                handleControlBlock(block)
            }
        } else {
            acknowledge(expectedInCounter)
        }
    }

    private fun handleControlBlock(block: ParsedControlBlock) {
        if (block.isPlainData) {
            handleTerminalData(block.value)
            return
        }
        when (block.type) {
            MacTelnetControlType.PASSWORD_SALT -> handlePasswordSalt(block.value)
            MacTelnetControlType.END_AUTH -> {
                if (state == State.AUTH_SENT) {
                    state = State.AUTH_COMPLETE
                }
            }
            MacTelnetControlType.PACKET_ERROR -> {
                val message = block.value.decodeToString()
                fail(DeviceException(ErrorCategory.General, "MAC-Telnet error from router: $message"))
            }
        }
    }

    private fun handlePasswordSalt(salt: ByteArray) {
        if (state != State.AUTH_BEGIN_SENT) return

        val passwordValue = when {
            salt.size == 16 -> macTelnetMd5(password, salt)
            salt.size == 49 -> {
                val client = srpClient ?: run {
                    fail(DeviceException(ErrorCategory.Unsupported, "router requested EC-SRP5 but MTWEI was not initialized"))
                    return
                }
                try {
                    client.confirm(salt)
                } catch (e: Exception) {
                    fail(e)
                    return
                }
            }
            else -> {
                fail(DeviceException(ErrorCategory.Unsupported, "unsupported salt size: ${salt.size} bytes"))
                return
            }
        }

        val blocks = encodeControlBlock(MacTelnetControlType.PASSWORD, passwordValue) +
            encodeControlBlock(MacTelnetControlType.USERNAME, user.encodeToByteArray()) +
            encodeControlBlock(MacTelnetControlType.TERMINAL_TYPE, terminalType.encodeToByteArray()) +
            encodeControlBlock(MacTelnetControlType.TERMINAL_WIDTH, encodeTerminalDimension(terminalWidth)) +
            encodeControlBlock(MacTelnetControlType.TERMINAL_HEIGHT, encodeTerminalDimension(terminalHeight))

        state = State.AUTH_SENT
        sendData(blocks)
    }

    private fun handleTerminalData(data: ByteArray) {
        if (state == State.AUTH_COMPLETE) {
            val text = data.decodeToString()
            if (text.contains("login failed", ignoreCase = true)) {
                fail(DeviceException(ErrorCategory.PermissionDenied, "MAC-Telnet login failed: invalid user or password"))
                return
            }
            state = State.READY
            readyDeferred.complete(Unit)
            if (data.isNotEmpty()) {
                incomingChunks.trySend(data)
            }
            return
        }
        if (state == State.READY && data.isNotEmpty()) {
            incomingChunks.trySend(data)
        }
    }

    private fun onEnd() {
        emit(packet(MacTelnetPacketType.END, 0))
        if (state == State.AUTH_COMPLETE || state == State.AUTH_SENT) {
            fail(DeviceException(ErrorCategory.PermissionDenied, "MAC-Telnet login failed: session closed by router"))
            return
        }
        if (state != State.READY) {
            fail(DeviceException(ErrorCategory.ConnectionLost, "MAC-Telnet session closed by router before ready"))
            return
        }
        close()
    }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        while (true) {
            val chunk = currentChunk
            if (chunk != null && currentOffset < chunk.size) {
                val available = chunk.size - currentOffset
                val count = minOf(length, available)
                chunk.copyInto(buffer, offset, currentOffset, currentOffset + count)
                currentOffset += count
                return count
            }
            if (ended) return -1
            val received = incomingChunks.receiveCatching().getOrNull()
            if (received == null) {
                ended = true
                failure?.let { throw it }
                return -1
            }
            currentChunk = received
            currentOffset = 0
        }
    }

    override suspend fun write(bytes: ByteArray) {
        if (state != State.READY) {
            readyDeferred.await()
        }
        writeLock.withLock {
            for (start in bytes.indices step writeChunk) {
                sendAndAwaitAck(bytes.copyOfRange(start, minOf(start + writeChunk, bytes.size)))
            }
        }
    }

    override fun resize(columns: Int, rows: Int) {
        terminalWidth = columns
        terminalHeight = rows
        val ready = stateLock.locked { state == State.READY }
        if (!ready) return
        val blocks = encodeControlBlock(MacTelnetControlType.TERMINAL_WIDTH, encodeTerminalDimension(columns)) +
            encodeControlBlock(MacTelnetControlType.TERMINAL_HEIGHT, encodeTerminalDimension(rows))
        scope.launch {
            try {
                writeLock.withLock { sendAndAwaitAck(blocks) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private suspend fun sendAndAwaitAck(payload: ByteArray) {
        val ackDeferred = CompletableDeferred<Unit>()
        stateLock.locked {
            failure?.let { throw it }
            if (state == State.CLOSED) throw DeviceException(ErrorCategory.ConnectionLost, "MAC-Telnet session is closed")
            val frame = packet(MacTelnetPacketType.DATA, outCounter) + payload
            outCounter += payload.size
            pending = PendingFrame(frame, ackTarget = outCounter, ackDeferred = ackDeferred)
            emit(frame)
        }
        ackDeferred.await()
    }

    override fun close() {
        stateLock.locked {
            if (state == State.CLOSED) return
            state = State.CLOSED
            val previousPending = pending
            pending = null
            retransmitJob.cancel()
            if (!readyDeferred.isCompleted) {
                readyDeferred.completeExceptionally(DeviceException(ErrorCategory.ConnectionLost, "MAC-Telnet session closed before ready"))
            }
            previousPending?.ackDeferred?.completeExceptionally(DeviceException(ErrorCategory.ConnectionLost, "MAC-Telnet session closed"))
            runCatching { emit(packet(MacTelnetPacketType.END, outCounter)) }
            sink.close()
            incomingChunks.close()
            closedDeferred.complete(Unit)
        }
    }

    private fun fail(error: Throwable) {
        stateLock.locked {
            if (state == State.CLOSED) return
            state = State.CLOSED
            val previousPending = pending
            pending = null
            failure = error
            retransmitJob.cancel()
            if (!readyDeferred.isCompleted) {
                readyDeferred.completeExceptionally(error)
            }
            previousPending?.ackDeferred?.completeExceptionally(error)
            sink.close()
            incomingChunks.close()
            closedDeferred.complete(Unit)
        }
    }

    private fun acknowledge(counter: Long) {
        emit(packet(MacTelnetPacketType.ACK, counter))
    }

    private fun sendData(payload: ByteArray) {
        val frame = packet(MacTelnetPacketType.DATA, outCounter) + payload
        outCounter += payload.size
        pending = PendingFrame(frame, ackTarget = outCounter)
        emit(frame)
    }

    private fun packet(type: Int, counter: Long) = encodeHeader(type, sourceMac, destinationMac, sessionKey, counter, clientType = clientType)

    private fun emit(bytes: ByteArray) {
        sink.send(bytes, destinationAddress, MAC_TELNET_PORT)
        lastSent = now()
    }
}

// a terminal is a login of its own: the first shell takes the session that opened the connection,
// the others log in again with a key and a socket of their own
class MacTelnetSsh internal constructor(
    first: MacTelnetSession,
    private val open: suspend (term: String, columns: Int, rows: Int, key: Int) -> MacTelnetSession,
) : SshSession {
    private val lock = reentrantLock()
    private val sessions = mutableListOf(first)
    private var spare: MacTelnetSession? = first

    @Volatile
    private var closed = false

    override suspend fun exec(command: String): ExecResult = throw unsupported("exec")

    override suspend fun process(command: String): ByteStream = throw unsupported("process")

    override suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel {
        if (closed) throw DeviceException(ErrorCategory.ConnectionLost, "the MAC-Telnet connection is closed")
        val reused = lock.locked { spare.also { spare = null } }
        if (reused != null) {
            reused.resize(columns, rows)
            return reused
        }
        val key = lock.locked {
            sessions.removeAll { it.closedDeferred.isCompleted }
            var candidate: Int
            do {
                candidate = randomKey()
            } while (sessions.any { it.sessionKey == candidate })
            candidate
        }
        val session = open(term, columns, rows, key)
        lock.locked { sessions += session }
        if (closed) session.close()
        return session
    }

    override suspend fun sftp(): SftpClient = throw unsupported("sftp")

    override suspend fun forward(host: String, port: Int): ByteStream = throw unsupported("port forwarding")

    override val canForward = false

    override fun close() {
        closed = true
        lock.locked { sessions.toList() }.forEach { it.close() }
    }

    private fun unsupported(what: String) =
        DeviceException(ErrorCategory.Unsupported, "MAC-Telnet does not support $what (terminal console only)")
}

suspend fun connectMacTelnet(
    destinationMac: MacAddress,
    user: String,
    password: String,
    scope: CoroutineScope,
    sourceMac: MacAddress,
    transport: MacTelnetTransport = macTelnetTransport(),
    destinationAddress: String = BROADCAST,
    terminalWidth: Int = 80,
    terminalHeight: Int = 24,
    timeoutMillis: Long = 10_000L,
    transports: () -> MacTelnetTransport = ::macTelnetTransport,
    terminalType: String = "vt102",
    keepAliveMillis: Long = KEEPALIVE_MS,
): MacTelnetSsh {
    suspend fun login(sink: MacTelnetTransport, term: String, columns: Int, rows: Int, key: Int): MacTelnetSession {
        val session = MacTelnetSession(
            sink = sink,
            sourceMac = sourceMac,
            destinationMac = destinationMac,
            user = user,
            password = password,
            sessionKey = key,
            terminalWidth = columns,
            terminalHeight = rows,
            terminalType = term,
            destinationAddress = destinationAddress,
            keepAliveMillis = keepAliveMillis,
            scope = scope,
        )
        sink.onMessage { bytes, _, _ ->
            session.handlePacket(bytes)
        }
        try {
            session.start()
            withTimeout(timeoutMillis) {
                session.readyDeferred.await()
            }
            return session
        } catch (e: TimeoutCancellationException) {
            session.close()
            throw LoginTimeoutException()
        } catch (t: Throwable) {
            session.close()
            throw t
        }
    }
    val first = login(transport, terminalType, terminalWidth, terminalHeight, randomKey())
    return MacTelnetSsh(first) { term, columns, rows, key -> login(transports(), term, columns, rows, key) }
}

// the stream of port 8291 carried by the packets of MAC-Telnet: the router takes an empty start of the other client type
// and then answers the frames of the login as it does on TCP
suspend fun connectMacNative(
    destinationMac: MacAddress,
    user: String,
    password: String,
    scope: CoroutineScope,
    sourceMac: MacAddress,
    transport: MacTelnetTransport = macTelnetTransport(),
    destinationAddress: String = BROADCAST,
    timeoutMillis: Long = NATIVE_START_MS,
): NativeConnection {
    val session = MacTelnetSession(
        sink = transport,
        sourceMac = sourceMac,
        destinationMac = destinationMac,
        user = user,
        password = password,
        native = true,
        destinationAddress = destinationAddress,
        scope = scope,
    )
    transport.onMessage { bytes, _, _ -> session.handlePacket(bytes) }
    try {
        session.start()
        withTimeout(timeoutMillis) { session.readyDeferred.await() }
        return NativeConnection.open(session, user, password, scope)
    } catch (e: TimeoutCancellationException) {
        session.close()
        throw DeviceException(ErrorCategory.ConnectionLost, "MAC-Telnet connection to $destinationMac timed out", e)
    } catch (t: Throwable) {
        session.close()
        throw t
    }
}
