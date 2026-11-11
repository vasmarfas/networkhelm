package com.vasmarfas.networkhelm.transport

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.readByteArray

// one selector for the process: each is a thread and a pipe of its own, and a sweep of a network opens hundreds of sockets at once
private val selector = SelectorManager(Dispatchers.IO)

actual suspend fun openTcp(host: String, port: Int, timeoutMillis: Int): ByteStream {
    // a timeout from withTimeout is a CancellationException, which callers would take for their own cancellation
    val socket = withTimeoutOrNull(timeoutMillis.toLong()) { aSocket(selector).tcp().connect(host, port) }
        ?: throw IOException("$host:$port did not answer in $timeoutMillis ms")
    return stream(socket)
}

actual val tcpListening = true

actual suspend fun listenTcp(host: String, port: Int): TcpListener {
    val server = aSocket(selector).tcp().bind(host, port)
    return object : TcpListener {
        override val port = (server.localAddress as InetSocketAddress).port

        override suspend fun accept(): ByteStream = stream(server.accept())

        override fun close() = server.close()
    }
}

actual suspend fun openUdp(host: String, port: Int): UdpLink {
    val socket = aSocket(selector).udp().connect(InetSocketAddress(host, port))
    return object : UdpLink {
        override suspend fun send(datagram: ByteArray) = socket.send(Datagram(Buffer().apply { write(datagram) }, socket.remoteAddress))

        override suspend fun receive(timeoutMillis: Int): ByteArray? = withTimeoutOrNull(timeoutMillis.toLong()) { socket.receive().packet.readByteArray() }

        override fun close() = socket.close()
    }
}

private fun stream(socket: Socket): ByteStream {
    val input = socket.openReadChannel()
    val output = socket.openWriteChannel(autoFlush = true)
    return object : ByteStream {
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = input.readAvailable(buffer, offset, length)

        override suspend fun write(bytes: ByteArray) = output.writeFully(bytes)

        override fun close() = socket.close()
    }
}
