package com.vasmarfas.networkhelm.discovery

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

fun mndpSightings(port: Int = PORT): Flow<MndpSighting> = flow {
    DatagramSocket(null).use { socket ->
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port))
        socket.soTimeout = 1000
        val buffer = ByteArray(2048)
        while (currentCoroutineContext().isActive) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            }
            val address = packet.address ?: continue
            // a router that announces from 0.0.0.0 comes as :: on some systems; one spelling is what the list checks for
            val source = if (address.isAnyLocalAddress) "0.0.0.0" else address.hostAddress
            parseMndp(packet.data.copyOf(packet.length))?.let { emit(MndpSighting(source, it)) }
        }
    }
}.flowOn(Dispatchers.IO)

// RouterOS 7 answers with its announcement within two seconds; 6.42 does not and announces itself once a minute
suspend fun requestMndpRefresh() = withContext(Dispatchers.IO) {
    val request = ByteArray(4)
    var sent = 0
    var failure: IOException? = null
    // an interface may go between the listing and the question whether it is up, and the system tells that with an error
    val interfaces = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
    } catch (e: IOException) {
        failure = e
        emptyList()
    }
    for (nic in interfaces) {
        try {
            if (!nic.isUp || nic.isLoopback) continue
            for (address in nic.inetAddresses) {
                val target = when {
                    address is Inet4Address -> InetAddress.getByName("255.255.255.255")
                    address is Inet6Address && address.isLinkLocalAddress -> Inet6Address.getByAddress(null, ALL_NODES, nic)
                    else -> continue
                }
                // bound to each local address, so the broadcast leaves by its interface and a router of another network hears it
                try {
                    DatagramSocket(InetSocketAddress(address, 0)).use { socket ->
                        socket.broadcast = true
                        socket.send(DatagramPacket(request, request.size, target, PORT))
                    }
                    sent++
                } catch (e: IOException) {
                    failure = e
                }
            }
        } catch (e: IOException) {
            failure = e
        }
    }
    if (sent == 0) throw DeviceException(ErrorCategory.General, "no network interface took the MNDP request", failure)
}

private const val PORT = 5678

private val ALL_NODES = byteArrayOf(0xFF.toByte(), 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
