@file:OptIn(ExperimentalForeignApi::class)

package com.vasmarfas.networkhelm.transport

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import platform.darwin.inet_ntop
import platform.darwin.inet_pton
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.AF_UNSPEC
import platform.posix.INET6_ADDRSTRLEN
import platform.posix.IPPROTO_ICMP
import platform.posix.IPPROTO_ICMPV6
import platform.posix.IPPROTO_IPV6
import platform.posix.IPV6_V6ONLY
import platform.posix.POLLIN
import platform.posix.SOCK_DGRAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_BROADCAST
import platform.posix.SO_REUSEADDR
import platform.posix.SO_REUSEPORT
import platform.posix.addrinfo
import platform.posix.bind
import platform.posix.errno
import platform.posix.freeaddrinfo
import platform.posix.gai_strerror
import platform.posix.getaddrinfo
import platform.posix.getsockname
import platform.posix.memset
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recvfrom
import platform.posix.sendto
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.sockaddr_in6
import platform.posix.sockaddr_storage
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.strerror
import platform.posix.close as closeSocket

class Received(val from: String, val port: Int, val bytes: ByteArray)

class SocketException(val code: Int, message: String) : IOException(message)

// datagrams of BSD sockets for what the sockets of ktor do not offer: broadcasts both ways, a local port other programs
// listen on too and the echo socket of ICMP
class DatagramSocket private constructor(private val fd: Int, private val family: Int) : AutoCloseable {
    private val buffer = ByteArray(MAX_DATAGRAM)
    private var closed = false

    val port: Int get() = memScoped {
        val address = alloc<sockaddr_storage>()
        val size = alloc<socklen_tVar>()
        size.value = sizeOf<sockaddr_storage>().convert()
        getsockname(fd, address.ptr.reinterpret(), size.ptr)
        portOf(address.ptr.reinterpret())
    }

    fun set(level: Int, name: Int, value: Int) = memScoped {
        if (!flag(fd, level, name, value)) throw socketError("option $name")
    }

    fun send(datagram: ByteArray, host: String, port: Int) = memScoped {
        val (address, size) = socketAddress(host, port, family)
        val sent = datagram.usePinned { sendto(fd, it.addressOf(0), datagram.size.convert(), 0, address, size) }
        if (sent < 0) throw socketError("$host:$port")
    }

    // null when nothing came in the time; the wait is cut into short polls, so a cancelled caller is not kept waiting
    suspend fun receive(timeoutMillis: Int): Received? = withContext(Dispatchers.IO) {
        var left = timeoutMillis
        while (left > 0) {
            currentCoroutineContext().ensureActive()
            val slice = minOf(left, POLL_SLICE_MILLIS)
            if (ready(slice)) return@withContext read()
            left -= slice
        }
        null
    }

    override fun close() {
        if (!closed) {
            closed = true
            closeSocket(fd)
        }
    }

    private fun ready(millis: Int): Boolean = memScoped {
        val wait = alloc<pollfd>()
        wait.fd = fd
        wait.events = POLLIN.toShort()
        wait.revents = 0
        poll(wait.ptr, 1u, millis) > 0
    }

    private fun read(): Received = memScoped {
        val from = alloc<sockaddr_storage>()
        val size = alloc<socklen_tVar>()
        size.value = sizeOf<sockaddr_storage>().convert()
        val count = buffer.usePinned { recvfrom(fd, it.addressOf(0), buffer.size.convert(), 0, from.ptr.reinterpret(), size.ptr) }
        if (count < 0) throw socketError()
        Received(textOf(from.ptr.reinterpret()), portOf(from.ptr.reinterpret()), buffer.copyOf(count.toInt()))
    }

    companion object {
        // shared lets other programs (WinBox) listen on the same port; dual stack hears IPv4 and IPv6 on one socket
        fun udp(port: Int = 0, local: String? = null, shared: Boolean = false, dualStack: Boolean = false): DatagramSocket {
            val family = if (dualStack) AF_INET6 else AF_INET
            val fd = socket(family, SOCK_DGRAM, 0)
            if (fd < 0) throw socketError()
            try {
                memScoped {
                    flag(fd, SOL_SOCKET, SO_BROADCAST)
                    if (shared) {
                        flag(fd, SOL_SOCKET, SO_REUSEADDR)
                        flag(fd, SOL_SOCKET, SO_REUSEPORT)
                    }
                    if (dualStack) flag(fd, IPPROTO_IPV6, IPV6_V6ONLY, 0)
                    val (address, size) = socketAddress(local ?: if (dualStack) "::" else "0.0.0.0", port, family)
                    if (bind(fd, address, size) != 0) throw socketError("port $port")
                }
            } catch (e: Exception) {
                closeSocket(fd)
                throw e
            }
            return DatagramSocket(fd, family)
        }

        // the kernel of Apple lets any app send echo requests this way and hands the socket every ICMP message of the host,
        // an IPv4 one with its IP header
        fun icmp(ipv6: Boolean): DatagramSocket {
            val family = if (ipv6) AF_INET6 else AF_INET
            val fd = socket(family, SOCK_DGRAM, if (ipv6) IPPROTO_ICMPV6 else IPPROTO_ICMP)
            if (fd < 0) throw socketError()
            return DatagramSocket(fd, family)
        }
    }
}

private const val MAX_DATAGRAM = 65_535
private const val POLL_SLICE_MILLIS = 250

private fun MemScope.flag(fd: Int, level: Int, name: Int, value: Int = 1): Boolean {
    val on = alloc<IntVar>()
    on.value = value
    return setsockopt(fd, level, name, on.ptr, sizeOf<IntVar>().convert()) == 0
}

// a numeric address goes as it is, a name is looked up; an IPv4 address on an IPv6 socket goes as ::ffff:a.b.c.d
internal fun MemScope.socketAddress(host: String, port: Int, family: Int): Pair<CPointer<sockaddr>, UInt> {
    if (family == AF_INET) {
        val address = alloc<sockaddr_in>()
        memset(address.ptr, 0, sizeOf<sockaddr_in>().convert())
        address.sin_len = sizeOf<sockaddr_in>().convert()
        address.sin_family = AF_INET.convert()
        address.sin_port = network(port)
        if (inet_pton(AF_INET, host, address.sin_addr.ptr) != 1) {
            val found = lookup(host, AF_INET) ?: throw IOException("$host: no IPv4 address")
            inet_pton(AF_INET, found, address.sin_addr.ptr)
        }
        return address.ptr.reinterpret<sockaddr>() to sizeOf<sockaddr_in>().convert()
    }
    val address = alloc<sockaddr_in6>()
    memset(address.ptr, 0, sizeOf<sockaddr_in6>().convert())
    address.sin6_len = sizeOf<sockaddr_in6>().convert()
    address.sin6_family = AF_INET6.convert()
    address.sin6_port = network(port)
    val numeric = when {
        ':' in host -> host
        inet_pton(AF_INET, host, alloc<sockaddr_in>().sin_addr.ptr) == 1 -> "::ffff:$host"
        else -> lookup(host, AF_INET6) ?: throw IOException("$host: no IPv6 address")
    }
    if (inet_pton(AF_INET6, numeric, address.sin6_addr.ptr) != 1) throw IOException("$host: not an address")
    return address.ptr.reinterpret<sockaddr>() to sizeOf<sockaddr_in6>().convert()
}

// the first address of a name in the family asked for, or of any family with AF_UNSPEC
internal fun lookup(host: String, family: Int = AF_UNSPEC): String? = lookupAll(host, family).firstOrNull()

fun lookupAll(host: String, family: Int = AF_UNSPEC): List<String> = memScoped {
    val hints = alloc<addrinfo>()
    memset(hints.ptr, 0, sizeOf<addrinfo>().convert())
    hints.ai_family = family
    hints.ai_socktype = SOCK_DGRAM
    val found = allocPointerTo<addrinfo>()
    val code = getaddrinfo(host, null, hints.ptr, found.ptr)
    if (code != 0) throw IOException("$host: ${gai_strerror(code)?.toKString()}")
    try {
        buildList {
            var entry = found.value
            while (entry != null) {
                val info = entry.pointed
                info.ai_addr?.let { add(textOf(it)) }
                entry = info.ai_next
            }
        }.distinct()
    } finally {
        freeaddrinfo(found.value)
    }
}

// an IPv4 address that came on an IPv6 socket reads as a plain IPv4 address
internal fun textOf(address: CPointer<sockaddr>): String = memScoped {
    val text = allocArray<ByteVar>(INET6_ADDRSTRLEN)
    if (address.pointed.sa_family.toInt() == AF_INET) {
        inet_ntop(AF_INET, address.reinterpret<sockaddr_in>().pointed.sin_addr.ptr, text, INET6_ADDRSTRLEN.convert())
    } else {
        inet_ntop(AF_INET6, address.reinterpret<sockaddr_in6>().pointed.sin6_addr.ptr, text, INET6_ADDRSTRLEN.convert())
    }
    text.toKString().removePrefix("::ffff:")
}

private fun portOf(address: CPointer<sockaddr>): Int {
    val raw = if (address.pointed.sa_family.toInt() == AF_INET) address.reinterpret<sockaddr_in>().pointed.sin_port else address.reinterpret<sockaddr_in6>().pointed.sin6_port
    return host(raw)
}

private fun network(port: Int): UShort = (((port and 0xFF) shl 8) or ((port shr 8) and 0xFF)).toUShort()

private fun host(port: UShort): Int = port.toInt().let { ((it and 0xFF) shl 8) or ((it shr 8) and 0xFF) }

private fun socketError(subject: String? = null): SocketException {
    val code = errno
    val text = strerror(code)?.toKString() ?: "errno $code"
    return SocketException(code, if (subject == null) text else "$subject: $text")
}
