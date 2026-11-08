package com.vasmarfas.networkhelm.miknative

internal const val BROADCAST = "255.255.255.255"

interface MacTelnetTransport {
    fun send(bytes: ByteArray, destination: String = BROADCAST, port: Int = MAC_TELNET_PORT)
    fun onMessage(handler: (bytes: ByteArray, source: String, port: Int) -> Unit)
    fun close()
}

class MacBroadcastInterface(
    val name: String,
    val displayName: String,
    val address: String,
    val broadcast: String,
    val mac: MacAddress,
)

internal expect fun macTelnetTransport(): MacTelnetTransport
