package com.vasmarfas.networkhelm.transport

import com.vasmarfas.networkhelm.discovery.mndpSightings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

class IosDatagramTest {

    @Test
    fun aDatagramGoesThroughTheLoopback() = runTest {
        DatagramSocket.udp(local = "127.0.0.1").use { receiver ->
            DatagramSocket.udp().use { sender ->
                sender.send("hello".encodeToByteArray(), "127.0.0.1", receiver.port)
                val received = assertNotNull(receiver.receive(5_000))
                assertEquals("hello", received.bytes.decodeToString())
                assertEquals("127.0.0.1", received.from)
                assertEquals(sender.port, received.port)
            }
        }
    }

    @Test
    fun twoProgramsListenOnOnePort() {
        DatagramSocket.udp(shared = true, dualStack = true).use { first ->
            DatagramSocket.udp(first.port, shared = true, dualStack = true).use { second -> assertEquals(first.port, second.port) }
        }
    }

    @Test
    fun theNeighboursAreHeardOnTheLoopback() = runTest {
        val port = DatagramSocket.udp().use { it.port }
        val packet = byteArrayOf(0, 0, 0, 0, 0, 5, 0, 3) + "lab".encodeToByteArray()
        withContext(Dispatchers.Default) {
            withTimeout(10.seconds) {
                val sighting = async { mndpSightings(port).first() }
                delay(500)
                DatagramSocket.udp().use { it.send(packet, "127.0.0.1", port) }
                val received = sighting.await()
                assertEquals("lab", received.announcement.identity)
                assertEquals("127.0.0.1", received.sourceAddress)
            }
        }
    }
}
