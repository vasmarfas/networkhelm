package com.vasmarfas.networkhelm.miknative

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.nettools.cellular
import com.vasmarfas.networkhelm.routeros.api.ApiEncoding
import com.vasmarfas.networkhelm.routeros.schema.MemorySchemaCache
import com.vasmarfas.networkhelm.routeros.schema.SchemaCache
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.transport.DatagramSocket
import com.vasmarfas.networkhelm.transport.ipv4Interfaces
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

private const val POLL_MILLIS = 1_000

private class UdpMacTelnetTransport : MacTelnetTransport {
    private val socket = DatagramSocket.udp()
    private val reader = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var handler: ((ByteArray, String, Int) -> Unit)? = null

    @Volatile
    private var closed = false

    init {
        // the reader closes the socket once it stops, so its number cannot go to another socket while a poll still waits on it
        reader.launch {
            try {
                socket.use {
                    while (true) {
                        val packet = it.receive(POLL_MILLIS) ?: continue
                        handler?.invoke(packet.bytes, packet.from, packet.port)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // as on the desktop, a broken socket ends the transport, and the session gives up by its own timers
                closed = true
            }
        }
    }

    override fun onMessage(handler: (bytes: ByteArray, source: String, port: Int) -> Unit) {
        this.handler = handler
    }

    override fun send(bytes: ByteArray, destination: String, port: Int) {
        if (!closed) socket.send(bytes, destination, port)
    }

    override fun close() {
        closed = true
        reader.cancel()
    }
}

internal actual fun macTelnetTransport(): MacTelnetTransport = UdpMacTelnetTransport()

// iOS tells apps neither the MAC of the device nor lets them pick the interface of a broadcast, so the network of the
// Wi-Fi or of a wired adapter carries it, and the MAC is the one the user typed: the router sends its answers to it
suspend fun connectMacTelnetFrom(macStr: String, ownMac: String, user: String, pass: String, encoding: ApiEncoding, scope: CoroutineScope, schemaCache: SchemaCache = MemorySchemaCache()): DeviceSession {
    val link = ipv4Interfaces().firstNotNullOfOrNull { network ->
        network.broadcast?.takeUnless { cellular(network.name) }?.let { MacBroadcastInterface(network.name, network.name, network.address, it, MacAddress.parse(ownMac)) }
    } ?: throw DeviceException(ErrorCategory.ConnectionLost, "No broadcast network interface available")
    return connectMacTelnetHost(MacAddress.parse(macStr), user, pass, link, encoding, scope, schemaCache)
}
