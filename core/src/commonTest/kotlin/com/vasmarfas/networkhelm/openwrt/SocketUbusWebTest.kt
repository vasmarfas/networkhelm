package com.vasmarfas.networkhelm.openwrt

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.transport.ByteStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class SocketUbusWebTest {

    private class Server(private val style: String = "length", private val dropsAfterAnswer: Boolean = false) {
        var opened = 0
        var requests = 0
        var closed = 0
        private var active = 0
        var peak = 0

        fun connect(): ByteStream {
            opened++
            return object : ByteStream {
                private var pending = ByteArray(0)
                private var dropped = false

                override suspend fun write(bytes: ByteArray) {
                    requests++
                    active++
                    peak = maxOf(peak, active)
                    val request = bytes.decodeToString()
                    val body = request.substringAfter("\r\n\r\n")
                    val answer = when (style) {
                        "lost" -> ""
                        "chunked" -> "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: Keep-Alive\r\n\r\n${body.length.toString(16)}\r\n$body\r\n0\r\n\r\n"
                        "close" -> "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                        else -> "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: Keep-Alive\r\n\r\n$body"
                    }
                    if (!dropped) pending = answer.encodeToByteArray()
                    dropped = dropsAfterAnswer
                }

                override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (pending.isEmpty()) return -1
                    delay(5)
                    val count = minOf(length, pending.size)
                    pending.copyInto(buffer, offset, 0, count)
                    pending = pending.copyOfRange(count, pending.size)
                    if (pending.isEmpty()) active--
                    return count
                }

                override fun close() {
                    closed++
                }
            }
        }
    }

    // as a read of a socket, it is deaf to the cancelling of its coroutine and ends only when the stream is closed
    private class Silent : ByteStream {
        private val gone = CompletableDeferred<Unit>()
        var closed = false
            private set

        override suspend fun write(bytes: ByteArray) = Unit

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            withContext(NonCancellable) { gone.await() }
            return -1
        }

        override fun close() {
            closed = true
            gone.complete(Unit)
        }
    }

    private fun poll(call: Int, obj: String = "system", method: String = "info") = """{"method":"call","params":["sid","$obj","$method",{}],"n":$call}"""

    private val change = """{"method":"call","params":["sid","uci","add",{}]}"""

    @Test
    fun callsOneAfterAnotherShareAConnection() = runTest {
        for (style in listOf("length", "chunked")) {
            val server = Server(style)
            val web = SocketUbusWeb("router") { server.connect() }
            repeat(5) { call -> assertEquals(poll(call), web.post(poll(call)).body.decodeToString()) }
            assertEquals(1, server.opened, style)
            assertEquals(0, server.closed, style)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aRouterThatGoesSilentEndsTheRequestInsteadOfHoldingItsSlotForever() = runTest {
        val web = SocketUbusWeb("router") { Silent() }
        val failure = assertFailsWith<DeviceException> { web.post(poll(0)) }
        assertEquals(ErrorCategory.ConnectionLost, failure.category)
        assertTrue(failure !is RequestOutcomeUnknownException)
        assertEquals(120_000, testScheduler.currentTime)
        assertFailsWith<RequestOutcomeUnknownException> { web.post(change) }
        assertEquals(240_000, testScheduler.currentTime)
    }

    @Test
    fun aRequestThatIsCancelledClosesItsConnection() = runTest {
        val silent = Silent()
        val web = SocketUbusWeb("router") { silent }
        val request = launch { web.post(poll(0)) }
        delay(1)
        request.cancelAndJoin()
        assertTrue(silent.closed)
    }

    @Test
    fun aConnectionTheServerClosesIsNotKept() = runTest {
        val server = Server("close")
        val web = SocketUbusWeb("router") { server.connect() }
        repeat(3) { web.post(poll(it)) }
        assertEquals(3, server.opened)
    }

    @Test
    fun aConnectionDroppedWhileIdleIsReplaced() = runTest {
        val server = Server(dropsAfterAnswer = true)
        val web = SocketUbusWeb("router") { server.connect() }
        val polls = listOf("system" to "info", "luci-rpc" to "getDHCPLeases", "iwinfo" to "assoclist", "log" to "read")
        for ((call, poll) in polls.withIndex()) {
            val request = poll(call, poll.first, poll.second)
            assertEquals(request, web.post(request).body.decodeToString())
        }
        assertEquals(4, server.opened)
    }

    @Test
    fun aChangeWhoseAnswerWasLostIsNeverSentAgain() = runTest {
        for (request in listOf(change, """{"method":"call","params":["sid","unknown","get",{}]}""")) {
            val server = Server("lost")
            val web = SocketUbusWeb("router") { server.connect() }
            assertFailsWith<RequestOutcomeUnknownException> { web.post(request) }
            assertEquals(1, server.requests)
            assertEquals(1, server.opened)
        }
    }

    @Test
    fun aReadWithoutAnAnswerIsAPlainLostConnection() = runTest {
        val server = Server("lost")
        val web = SocketUbusWeb("router") { server.connect() }
        val failure = assertFailsWith<DeviceException> { web.post(poll(0)) }
        assertEquals(ErrorCategory.ConnectionLost, failure.category)
        assertTrue(failure !is RequestOutcomeUnknownException)
    }

    @Test
    fun aChangeNeverTakesAPooledConnection() = runTest {
        val server = Server(dropsAfterAnswer = true)
        val web = SocketUbusWeb("router") { server.connect() }
        web.post(poll(0))
        assertEquals(change, web.post(change).body.decodeToString())
        assertEquals(2, server.opened)
        assertEquals(2, server.requests)
    }

    @Test
    fun aConnectionIdleForFifteenSecondsIsClosedBeforeWriting() = runTest {
        val server = Server(dropsAfterAnswer = true)
        val time = TestTimeSource()
        val web = SocketUbusWeb("router", time) { server.connect() }
        web.post(poll(0))
        time += 15.seconds
        web.post(poll(1))
        assertEquals(2, server.requests)
        assertEquals(2, server.opened)
    }

    @Test
    fun noMoreThanThreeRequestsAreInFlight() = runTest {
        val server = Server()
        val web = SocketUbusWeb("router") { server.connect() }
        val answers = (1..12).map { call -> async { web.post(poll(call)).body.decodeToString() } }.awaitAll()
        assertEquals((1..12).map { poll(it) }, answers)
        assertTrue(server.peak <= 3, "${server.peak} requests at once")
        assertTrue(server.opened <= 3, "${server.opened} connections")
    }
}
