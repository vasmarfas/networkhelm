@file:OptIn(ExperimentalForeignApi::class)

package com.vasmarfas.networkhelm.nettools

import com.vasmarfas.networkhelm.transport.DatagramSocket
import com.vasmarfas.networkhelm.transport.SocketException
import com.vasmarfas.networkhelm.transport.ipv4Interfaces
import com.vasmarfas.networkhelm.transport.lookupAll
import com.vasmarfas.networkhelm.transport.socketAddress
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSBundle
import platform.Foundation.NSNumber
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.EMSGSIZE
import platform.posix.IPPROTO_IP
import platform.posix.IPPROTO_IPV6
import platform.posix.IPV6_UNICAST_HOPS
import platform.posix.IP_DONTFRAG
import platform.posix.IP_TTL
import platform.posix.NI_NAMEREQD
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVBUF
import platform.posix.SO_SNDBUF
import platform.posix.getnameinfo
import kotlin.random.Random
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

private const val DEFAULT_PAYLOAD = 56
// a raw socket of Apple holds 8 KB each way, and during a scan every ICMP message of the host passes through each one
private const val BUFFER_BYTES = 65_536
private const val REVERSE_TIMEOUT_MILLIS = 2_500L
private const val NAME_BYTES = 1_025
private const val BROADCASTS_KEY = "NetworkHelmBroadcasts"

private object IosHostOps : HostOps {
    private val lookups = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // a device sends and hears broadcasts only with the multicast entitlement of Apple, Info.plist says when the app has it
    override val broadcasts = (NSBundle.mainBundle.objectForInfoDictionaryKey(BROADCASTS_KEY) as? NSNumber)?.boolValue == true

    // the identifier and the number are random: every echo socket of the host hears the answers to all the others
    override suspend fun ping(host: String, timeoutMillis: Int, ttl: Int?, size: Int?, dontFragment: Boolean): PingReply {
        val ipv6 = ':' in host
        val payload = size ?: DEFAULT_PAYLOAD
        DatagramSocket.icmp(ipv6).use { socket ->
            socket.set(SOL_SOCKET, SO_SNDBUF, BUFFER_BYTES + payload)
            socket.set(SOL_SOCKET, SO_RCVBUF, BUFFER_BYTES + payload)
            if (ttl != null) {
                if (ipv6) socket.set(IPPROTO_IPV6, IPV6_UNICAST_HOPS, ttl) else socket.set(IPPROTO_IP, IP_TTL, ttl)
            }
            if (dontFragment && !ipv6) socket.set(IPPROTO_IP, IP_DONTFRAG, 1)
            val identifier = Random.nextInt(0x10000)
            val number = Random.nextInt(0x10000)
            val started = TimeSource.Monotonic.markNow()
            try {
                socket.send(echo(ipv6, identifier, number, payload), host, 0)
            } catch (e: SocketException) {
                // an interface of this device narrower than the echo says so at once; the system ping counts any other refusal as a loss
                return PingReply(null, null, null, fragmentationNeeded = dontFragment && e.code == EMSGSIZE)
            }
            while (true) {
                val left = timeoutMillis - started.elapsedNow().inWholeMilliseconds.toInt()
                val packet = (if (left > 0) socket.receive(left) else null) ?: return PingReply(null, null, null)
                answer(packet.bytes, packet.from, ipv6, identifier, number, started.elapsedNow().toDouble(DurationUnit.MILLISECONDS))?.let { return it }
            }
        }
    }

    override suspend fun wake(packet: ByteArray, broadcast: String, port: Int) = withContext(Dispatchers.IO) {
        DatagramSocket.udp().use { it.send(packet, broadcast, port) }
    }

    override suspend fun resolve(host: String): List<String> = withContext(Dispatchers.IO) { lookupAll(host) }

    // a lookup stuck in the system resolver goes on after its caller gave up, so it must not belong to the caller
    override suspend fun reverse(address: String): String? = withTimeoutOrNull(REVERSE_TIMEOUT_MILLIS) {
        lookups.async { nameOf(address) }.await()
    }

    override suspend fun localNetworks(): List<LocalNetwork> = withContext(Dispatchers.IO) {
        ipv4Interfaces().filter { !cellular(it.name) }.map { LocalNetwork(it.name, it.address, it.prefix) }
    }
}

private fun echo(ipv6: Boolean, identifier: Int, number: Int, payload: Int): ByteArray {
    val packet = ByteArray(8 + payload) { if (it < 8) 0 else it.toByte() }
    packet[0] = (if (ipv6) 128 else 8).toByte()
    packet.put16(4, identifier)
    packet.put16(6, number)
    // the sum of ICMPv6 covers a pseudo header with the source address, which only the kernel knows
    if (!ipv6) packet.put16(2, checksum(packet))
    return packet
}

// what one ICMP message says about the echo, null when it is about another; an error quotes the IP header and the first
// eight bytes of the echo it is about, which hold the identifier and the number
internal fun answer(bytes: ByteArray, from: String, ipv6: Boolean, identifier: Int, number: Int, elapsedMillis: Double): PingReply? {
    if (bytes.size < 8) return null
    val start = if (ipv6) 0 else (bytes[0].toInt() and 0x0F) * 4
    if (bytes.size < start + 8) return null
    val type = bytes[start].toInt() and 0xFF
    val code = bytes[start + 1].toInt() and 0xFF
    val quoted = start + 8 + if (ipv6) 40 else (bytes.getOrElse(start + 8) { 0 }.toInt() and 0x0F) * 4
    fun ours(echo: Int) = bytes.size >= echo + 8 && bytes.get16(echo + 4) == identifier && bytes.get16(echo + 6) == number
    return when {
        type == (if (ipv6) 129 else 0) && ours(start) -> PingReply(elapsedMillis, if (ipv6) null else bytes[8].toInt() and 0xFF, from)
        type == (if (ipv6) 3 else 11) && ours(quoted) -> PingReply(elapsedMillis, null, from, expired = true)
        (if (ipv6) type == 2 else type == 3 && code == 4) && ours(quoted) -> PingReply(null, null, from, fragmentationNeeded = true)
        else -> null
    }
}

internal fun checksum(bytes: ByteArray): Int {
    var sum = 0
    for (i in bytes.indices step 2) sum += ((bytes[i].toInt() and 0xFF) shl 8) or (bytes.getOrElse(i + 1) { 0 }.toInt() and 0xFF)
    while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
    return sum.inv() and 0xFFFF
}

private fun ByteArray.put16(at: Int, value: Int) {
    this[at] = (value shr 8).toByte()
    this[at + 1] = value.toByte()
}

private fun ByteArray.get16(at: Int) = ((this[at].toInt() and 0xFF) shl 8) or (this[at + 1].toInt() and 0xFF)

private fun nameOf(address: String): String? = memScoped {
    val (socket, size) = socketAddress(address, 0, if (':' in address) AF_INET6 else AF_INET)
    val name = allocArray<ByteVar>(NAME_BYTES)
    if (getnameinfo(socket, size, name, NAME_BYTES.convert(), null, 0u, NI_NAMEREQD) == 0) name.toKString() else null
}

actual fun hostOps(): HostOps? = IosHostOps
