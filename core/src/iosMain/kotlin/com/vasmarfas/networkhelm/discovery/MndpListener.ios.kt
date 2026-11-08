package com.vasmarfas.networkhelm.discovery

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.transport.DatagramSocket
import com.vasmarfas.networkhelm.transport.ipv4Interfaces
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.io.IOException

// one socket for both families; on a device the broadcasts and multicasts come only with the multicast entitlement of Apple
fun mndpSightings(port: Int = PORT): Flow<MndpSighting> = flow {
    DatagramSocket.udp(port, shared = true, dualStack = true).use { socket ->
        while (true) {
            val packet = socket.receive(RECEIVE_MILLIS) ?: continue
            // a router that announces from 0.0.0.0 comes as :: on some systems; one spelling is what the list checks for
            val source = if (packet.from == "::") "0.0.0.0" else packet.from
            parseMndp(packet.bytes)?.let { emit(MndpSighting(source, it)) }
        }
    }
}

// Apple sends 255.255.255.255 out of the main interface only, so each network gets the broadcast address of its own
suspend fun requestMndpRefresh() {
    var sent = 0
    var failure: IOException? = null
    for (network in ipv4Interfaces()) {
        try {
            DatagramSocket.udp(local = network.address).use { it.send(ByteArray(4), network.broadcast ?: "255.255.255.255", PORT) }
            sent++
        } catch (e: IOException) {
            failure = e
        }
    }
    if (sent == 0) throw DeviceException(ErrorCategory.General, "no network interface took the MNDP request", failure)
}

private const val PORT = 5678
private const val RECEIVE_MILLIS = 1_000
