package com.vasmarfas.networkhelm.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

private const val READ_BUFFER = 64 * 1024

private class HeldInput(input: InputStream) : BufferedInputStream(input, READ_BUFFER) {
    val held: Int get() = count - pos
}

open class SocketStream(private val socket: Socket) : ByteStream {
    private val input = HeldInput(socket.getInputStream())
    private val output = socket.getOutputStream()

    // what the buffer holds is taken without a hop to the threads of blocking calls; anything more is a read of the socket,
    // which Android refuses on the main thread even for bytes that have arrived
    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val held = input.held
        return if (held > 0) input.read(buffer, offset, minOf(length, held)) else withContext(Dispatchers.IO) { input.read(buffer, offset, length) }
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        output.write(bytes)
        output.flush()
    }

    override fun close() = socket.close()
}

// closing the TLS socket sends close_notify, network I/O that Android forbids on the main thread
private class TlsSocketStream(socket: SSLSocket, private val tcp: Socket, override val certificateSha256: String) : SocketStream(socket), TlsStream {
    override fun close() = tcp.close()
}

actual suspend fun openTcp(host: String, port: Int, timeoutMillis: Int): ByteStream = SocketStream(openSocket(host, port, timeoutMillis))

actual val tcpListening = true

actual suspend fun listenTcp(host: String, port: Int): TcpListener = withContext(Dispatchers.IO) {
    val server = ServerSocket(port, 50, InetAddress.getByName(host))
    object : TcpListener {
        override val port = server.localPort

        override suspend fun accept(): ByteStream = SocketStream(withContext(Dispatchers.IO) { server.accept() })

        override fun close() = server.close()
    }
}

actual suspend fun openUdp(host: String, port: Int): UdpLink = withContext(Dispatchers.IO) {
    val socket = DatagramSocket()
    try {
        socket.connect(InetSocketAddress(host, port))
    } catch (e: Exception) {
        socket.close()
        throw e
    }
    object : UdpLink {
        private val buffer = ByteArray(65_535)

        override suspend fun send(datagram: ByteArray) = withContext(Dispatchers.IO) { socket.send(DatagramPacket(datagram, datagram.size)) }

        override suspend fun receive(timeoutMillis: Int): ByteArray? = withContext(Dispatchers.IO) {
            socket.soTimeout = timeoutMillis
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
                buffer.copyOf(packet.length)
            } catch (e: SocketTimeoutException) {
                null
            }
        }

        override fun close() = socket.close()
    }
}

actual suspend fun openTls(host: String, port: Int, pinnedSha256: String?, timeoutMillis: Int): TlsStream {
    val trust = PinnedCertificate(pinnedSha256)
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
    val tcp = openSocket(host, port, timeoutMillis)
    val socket = context.socketFactory.createSocket(tcp, host, port, true) as SSLSocket
    try {
        socket.soTimeout = timeoutMillis
        withContext(Dispatchers.IO) { socket.startHandshake() }
        socket.soTimeout = 0
    } catch (e: Exception) {
        tcp.close()
        val mismatch = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<PinMismatch>().firstOrNull()
        throw mismatch?.let { CertificateMismatchException(it.expected, it.actual) } ?: e
    }
    return TlsSocketStream(socket, tcp, sha256(socket.session.peerCertificates.first()))
}

private fun sha256(certificate: Certificate) =
    MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }

private suspend fun openSocket(host: String, port: Int, timeoutMillis: Int): Socket {
    val socket = Socket()
    try {
        withContext(Dispatchers.IO) { socket.connect(InetSocketAddress(host, port), timeoutMillis) }
        socket.tcpNoDelay = true
    } catch (e: Exception) {
        socket.close()
        throw e
    }
    return socket
}

private class PinMismatch(val expected: String, val actual: String) : CertificateException("certificate SHA-256 $actual, expected $expected")

private class PinnedCertificate(private val pinnedSha256: String?) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        val actual = sha256(chain.first())
        if (pinnedSha256 != null && !pinnedSha256.equals(actual, ignoreCase = true)) throw PinMismatch(pinnedSha256, actual)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        throw CertificateException("client certificates are not accepted")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
