package com.vasmarfas.networkhelm.nettools

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class PingReply(
    val timeMillis: Double?,
    val ttl: Int?,
    val from: String?,
    val expired: Boolean = false,
    val fragmentationNeeded: Boolean = false,
)

class Hop(val number: Int, val address: String?, val timeMillis: Double?)

class LocalNetwork(val name: String, val address: String, val prefix: Int)

private val CELLULAR = Regex("^(?:v4-)?(?:rmnet|ccmni|pdp_ip|wwan)")

// the addresses of a mobile interface belong to the carrier, there is no network of the user to scan behind them
fun cellular(name: String) = CELLULAR.containsMatchIn(name)

interface HostOps {
    suspend fun ping(host: String, timeoutMillis: Int, ttl: Int? = null, size: Int? = null, dontFragment: Boolean = false): PingReply

    suspend fun wake(packet: ByteArray, broadcast: String, port: Int)

    suspend fun resolve(host: String): List<String>

    // null where the address has no name or the lookup took too long
    suspend fun reverse(address: String): String? = null

    // MAC addresses of the neighbours by IPv4 address; empty where the system does not let the table be read
    suspend fun arp(): Map<String, String> = emptyMap()

    suspend fun localNetworks(): List<LocalNetwork> = emptyList()
}

// the system ping on the desktop and Android; iOS and the browser have none
expect fun hostOps(): HostOps?

private val ipv4Pattern = Regex("""(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})""")
private val ttlPattern = Regex("""\bttl\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
private val windowsTime = Regex("""[=<]\s*(\d+)\s*[^\s=<]*\s+TTL=""")
private val unixTime = Regex("""time[=<]\s*([\d.]+)""")
private val windowsV6Time = Regex("""[=<]\s*(\d+)\s*[^\s=<]*\s*$""")
private val fragmentation = Regex("""frag|too (?:long|big)|фрагмент|слишком длин""", RegexOption.IGNORE_CASE)

// the output of one echo in any language: a reply line has a TTL, Windows writes its time in the local language
// before it, and an expired TTL names the hop as a second address; the words about fragmentation are known in English and Russian
fun parsePing(output: String, ttl: Int?, ipv6: Boolean, elapsedMillis: Double): PingReply {
    val lines = output.lines()
    val reply = lines.firstOrNull { ttlPattern.containsMatchIn(it) }
    if (reply != null) {
        val time = (windowsTime.find(reply) ?: unixTime.find(reply))?.groupValues?.get(1)?.toDoubleOrNull()
        return PingReply(time ?: elapsedMillis, ttlPattern.find(reply)?.groupValues?.get(1)?.toIntOrNull(), ipv4Pattern.find(reply)?.value)
    }
    if (ipv6) {
        val time = lines.firstNotNullOfOrNull { windowsV6Time.find(it) }?.groupValues?.get(1)?.toDoubleOrNull()
        if (time != null) return PingReply(time, null, null)
    }
    val addresses = ipv4Pattern.findAll(output).map { it.value }.toList()
    val hop = addresses.firstOrNull { it != addresses.first() }
    if (fragmentation.containsMatchIn(output)) return PingReply(null, null, hop, fragmentationNeeded = true)
    return if (ttl != null && hop != null) PingReply(elapsedMillis, null, hop, expired = true) else PingReply(null, null, null)
}

// a null count goes on until the collector stops
fun pings(count: Int?, intervalMillis: Long, send: suspend () -> PingReply): Flow<PingReply> = flow {
    var sent = 0
    while (count == null || sent < count) {
        val started = TimeSource.Monotonic.markNow()
        emit(send())
        sent++
        if (count == null || sent < count) delay(intervalMillis.milliseconds - started.elapsedNow())
    }
}

// eight silent hops in a row end the trace: the rest of the path drops ICMP
fun traceroute(ops: HostOps, address: String, maxHops: Int = 30, timeoutMillis: Int = 2_500): Flow<Hop> = flow {
    var silent = 0
    for (ttl in 1..maxHops) {
        val reply = ops.ping(address, timeoutMillis, ttl)
        val reached = !reply.expired && reply.timeMillis != null && (reply.from == address || reply.from == null)
        emit(Hop(ttl, reply.from ?: if (reached) address else null, reply.timeMillis))
        if (reached) break
        silent = if (reply.from == null) silent + 1 else 0
        if (silent >= 8) break
    }
}

class PingSummary(replies: List<PingReply>) {
    private val times = replies.mapNotNull { it.timeMillis }
    val sent = replies.size
    val received = times.size
    val lost = sent - received
    val loss = if (sent == 0) 0.0 else lost * 100.0 / sent
    val min = times.minOrNull()
    val average = times.takeIf { it.isNotEmpty() }?.average()
    val max = times.maxOrNull()
    val jitter = average?.let { mean -> sqrt(times.sumOf { (it - mean) * (it - mean) } / times.size) }
}

fun millis(value: Double): String {
    val tenths = (value * 10).roundToLong()
    return "${tenths / 10}.${tenths % 10}"
}
