package com.vasmarfas.networkhelm.miknative

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.routeros.api.ApiEncoding
import com.vasmarfas.networkhelm.routeros.schema.MemorySchemaCache
import com.vasmarfas.networkhelm.routeros.schema.SchemaCache
import com.vasmarfas.networkhelm.session.DeviceSession
import kotlinx.coroutines.CoroutineScope
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

fun listBroadcastInterfaces(): List<MacBroadcastInterface> {
    val interfaces = try {
        NetworkInterface.getNetworkInterfaces()
    } catch (e: SocketException) {
        null
    } ?: return emptyList()
    val result = mutableListOf<MacBroadcastInterface>()
    for (iface in interfaces) {
        try {
            if (!iface.isUp || iface.isLoopback) continue
            val hw = iface.hardwareAddress ?: continue
            if (hw.size != 6 || hw.all { it == 0.toByte() }) continue
            val mac = MacAddress(hw)
            for (binding in iface.interfaceAddresses) {
                val broadcast = binding.broadcast?.hostAddress ?: continue
                val address = binding.address?.takeIf { it.address.size == 4 }?.hostAddress ?: continue
                result.add(MacBroadcastInterface(iface.name, iface.displayName ?: iface.name, address, broadcast, mac))
            }
        } catch (e: SocketException) {
            continue
        }
    }
    return result
}

class UdpMacTelnetTransport(
    private val socket: DatagramSocket = DatagramSocket(null).apply {
        reuseAddress = true
        broadcast = true
        bind(InetSocketAddress(0))
    },
) : MacTelnetTransport {
    private val closed = AtomicBoolean(false)
    private var messageHandler: ((ByteArray, String, Int) -> Unit)? = null
    private val readerThread = Thread {
        val buffer = ByteArray(65535)
        while (!closed.get()) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val bytes = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                val source = packet.address?.hostAddress ?: continue
                messageHandler?.invoke(bytes, source, packet.port)
            } catch (e: Exception) {
                if (closed.get() || socket.isClosed) break
                close()
                break
            }
        }
    }.apply {
        isDaemon = true
        name = "mactelnet-udp-reader"
        start()
    }

    override fun onMessage(handler: (bytes: ByteArray, source: String, port: Int) -> Unit) {
        messageHandler = handler
    }

    override fun send(bytes: ByteArray, destination: String, port: Int) {
        if (closed.get()) return
        val packet = DatagramPacket(bytes, bytes.size, InetAddress.getByName(destination), port)
        socket.send(packet)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            socket.close()
        }
    }
}

internal actual fun macTelnetTransport(): MacTelnetTransport = UdpMacTelnetTransport()

// Windows names its interfaces eth6 and wlan3 for Java; the description of the adapter says which is which
fun MacBroadcastInterface.label() = "$displayName, $address (${mac.format()})"

fun macTelnetInterfaces(): List<String> = listBroadcastInterfaces().map { it.label() }

suspend fun connectMacTelnetOnInterface(macStr: String, user: String, pass: String, ifaceName: String?, encoding: ApiEncoding, scope: CoroutineScope, schemaCache: SchemaCache = MemorySchemaCache()): DeviceSession {
    val ifaces = listBroadcastInterfaces()
    val chosen = ifaces.firstOrNull { it.label() == ifaceName || it.name == ifaceName }
        ?: ifaces.firstOrNull()
        ?: throw DeviceException(ErrorCategory.ConnectionLost, "No broadcast network interface available")
    return connectMacTelnetHost(MacAddress.parse(macStr), user, pass, chosen, encoding, scope, schemaCache)
}
