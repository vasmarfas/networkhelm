package com.vasmarfas.networkhelm.miknative

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.transport.ByteStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val PACKET_PAYLOAD = 1450

// the router end of a stream as MAC-Telnet packets: an ack for a start, an ack with the count of bytes taken for data
private class MacBridge(
    private val stream: ByteStream,
    private val scope: CoroutineScope,
    private var dropData: Int = 0,
    private val startAckDelayMillis: Long = 0,
) : MacTelnetTransport {
    private val routerMac = MacAddress.parse("66:77:88:99:aa:bb")
    private val clientMac = MacAddress.parse("00:11:22:33:44:55")
    private val loopback = "127.0.0.1"
    private var deliver: ((ByteArray, String, Int) -> Unit)? = null
    private var key = 0
    private var taken = 0L
    private var sent = 0L
    val seen = CopyOnWriteArrayList<ByteArray>()

    init {
        scope.launch {
            val buffer = ByteArray(4096)
            while (true) {
                val count = stream.read(buffer, 0, buffer.size)
                if (count < 0) break
                for (start in 0 until count step PACKET_PAYLOAD) {
                    val chunk = buffer.copyOfRange(start, minOf(start + PACKET_PAYLOAD, count))
                    reply(MacTelnetPacketType.DATA, sent, chunk)
                    sent += chunk.size
                }
            }
            reply(MacTelnetPacketType.END, sent)
        }
    }

    override fun onMessage(handler: (bytes: ByteArray, source: String, port: Int) -> Unit) {
        deliver = handler
    }

    override fun send(bytes: ByteArray, destination: String, port: Int) {
        seen += bytes
        val header = decodeHeader(bytes, fromServer = false)
        key = header.sessionKey
        when (header.type) {
            MacTelnetPacketType.SESSION_START -> scope.launch {
                delay(startAckDelayMillis)
                reply(MacTelnetPacketType.ACK, 0)
            }
            MacTelnetPacketType.DATA -> {
                if (header.counter == taken) {
                    if (dropData > 0) {
                        dropData--
                        return
                    }
                    val payload = bytes.copyOfRange(MAC_TELNET_HEADER_LEN, bytes.size)
                    taken += payload.size
                    scope.launch { stream.write(payload) }
                }
                reply(MacTelnetPacketType.ACK, taken)
            }
            MacTelnetPacketType.END -> stream.close()
        }
    }

    override fun close() {}

    private fun reply(type: Int, counter: Long, payload: ByteArray = ByteArray(0)) {
        val header = encodeHeader(type, routerMac, clientMac, key, counter, fromServer = true, clientType = MAC_NATIVE_CLIENT_TYPE)
        deliver?.invoke(header + payload, loopback, MAC_TELNET_PORT)
    }

    fun dataSizes() = seen.filter { it[1].toInt() and 0xFF == MacTelnetPacketType.DATA }.map { it.size - MAC_TELNET_HEADER_LEN }
}

class MacNativeSessionTest {
    private val routerMac = MacAddress.parse("66:77:88:99:aa:bb")
    private val clientMac = MacAddress.parse("00:11:22:33:44:55")
    private val loopback = "127.0.0.1"

    private fun CoroutineScope.open(bridge: MacBridge): MacTelnetSession {
        val session = MacTelnetSession(bridge, clientMac, routerMac, "admin", "secret", native = true, destinationAddress = loopback, scope = this)
        bridge.onMessage { bytes, _, _ -> session.handlePacket(bytes) }
        session.start()
        return session
    }

    @Test
    fun theLoginAndRequestsRideOnMacPackets() = runBlocking {
        val (end, server) = pipe()
        val router = FakeRouter(this, server, "admin", "secret") { message ->
            if (message.number(M2Key.Command) == 7L) reply(message, 5 to M2Value.Text("CHR"))
        }
        val bridge = MacBridge(end, this)
        val connection = connectMacNative(routerMac, "admin", "secret", this, clientMac, bridge, loopback, timeoutMillis = 5_000L)
        try {
            assertEquals("CHR", connection.newSource().request(listOf(13, 4), M2Value.U8(7)).text(5))
            val start = bridge.seen.first()
            assertEquals(MacTelnetPacketType.SESSION_START, start[1].toInt() and 0xFF)
            assertEquals(MAC_TELNET_HEADER_LEN, start.size)
            assertContentEquals(MAC_NATIVE_CLIENT_TYPE, start.copyOfRange(16, 18))
        } finally {
            connection.close()
            router.close()
        }
    }

    @Test
    fun aWrongPasswordIsRefusedAsOverTcp() = runBlocking {
        val (end, server) = pipe()
        FakeRouter(this, server, "admin", "secret") {}
        val bridge = MacBridge(end, this)
        val error = assertFailsWith<DeviceException> {
            connectMacNative(routerMac, "admin", "guess", this, clientMac, bridge, loopback, timeoutMillis = 5_000L)
        }
        assertEquals(ErrorCategory.PermissionDenied, error.category)
    }

    @Test
    fun largeWritesGoInPacketsOfAtMost1450Bytes() = runBlocking {
        val (end, server) = pipe()
        val bridge = MacBridge(end, this)
        val session = open(bridge)
        try {
            val data = ByteArray(4000) { it.toByte() }
            session.write(data)
            val received = ByteArray(data.size)
            var count = 0
            while (count < data.size) count += withTimeout(2_000) { server.read(received, count, data.size - count) }
            assertContentEquals(data, received)
            assertEquals(listOf(1450, 1450, 1100), bridge.dataSizes())
        } finally {
            session.close()
            server.close()
        }
    }

    @Test
    fun dataFromTheRouterReadsAsOneStream() = runBlocking {
        val (end, server) = pipe()
        val bridge = MacBridge(end, this)
        val session = open(bridge)
        try {
            val data = ByteArray(3000) { (it * 7).toByte() }
            server.write(data)
            val received = ByteArray(data.size)
            var count = 0
            while (count < data.size) count += withTimeout(2_000) { session.read(received, count, data.size - count) }
            assertContentEquals(data, received)
        } finally {
            session.close()
            server.close()
        }
    }

    @Test
    fun aLostPacketIsSentAgain() = runBlocking {
        val (end, server) = pipe()
        val bridge = MacBridge(end, this, dropData = 1)
        val session = open(bridge)
        try {
            val data = "frame".encodeToByteArray()
            withTimeout(2_000) { session.write(data) }
            val received = ByteArray(data.size)
            withTimeout(2_000) { server.read(received, 0, received.size) }
            assertContentEquals(data, received)
            assertEquals(listOf(data.size, data.size), bridge.dataSizes())
        } finally {
            session.close()
            server.close()
        }
    }

    @Test
    fun aStartThatIsAnsweredSlowlyIsNotRepeated() = runBlocking {
        val (end, server) = pipe()
        val bridge = MacBridge(end, this, startAckDelayMillis = 100)
        val session = open(bridge)
        try {
            withTimeout(2_000) { session.readyDeferred.await() }
            assertEquals(1, bridge.seen.count { it[1].toInt() and 0xFF == MacTelnetPacketType.SESSION_START })
        } finally {
            session.close()
            server.close()
        }
    }

    @Test
    fun aRouterThatDoesNotAnswerTheStartFails() = runBlocking {
        val silent = object : MacTelnetTransport {
            override fun send(bytes: ByteArray, destination: String, port: Int) {}
            override fun onMessage(handler: (bytes: ByteArray, source: String, port: Int) -> Unit) {}
            override fun close() {}
        }
        val error = assertFailsWith<DeviceException> {
            connectMacNative(routerMac, "admin", "secret", this, clientMac, silent, loopback, timeoutMillis = 50)
        }
        assertEquals(ErrorCategory.ConnectionLost, error.category)
    }
}
