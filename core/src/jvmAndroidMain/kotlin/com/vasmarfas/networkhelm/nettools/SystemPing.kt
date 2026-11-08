package com.vasmarfas.networkhelm.nettools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

private val osName = System.getProperty("os.name").orEmpty().lowercase()
private val isWindows = "win" in osName
private val isMac = "mac" in osName

// Android runs /system/bin/ping, which needs no root; its JVM says Linux, so the vendor and the binary tell it apart
private val isAndroid = System.getProperty("java.vendor").orEmpty().lowercase().contains("android") ||
    System.getProperty("java.vm.vendor").orEmpty().lowercase().contains("android") ||
    osName == "linux" && File("/system/bin/ping").exists()

private const val REVERSE_TIMEOUT_MILLIS = 2_500L
private const val ARP_LIMIT_MILLIS = 10_000L

private object SystemHostOps : HostOps {
    // a lookup stuck in the system resolver goes on after its caller gave up, so it must not belong to the caller
    private val lookups = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun ping(host: String, timeoutMillis: Int, ttl: Int?, size: Int?, dontFragment: Boolean): PingReply {
        val started = System.nanoTime()
        val output = capture(pingCommand(host, timeoutMillis, ttl, size, dontFragment), timeoutMillis + 2_000L)
        return parsePing(output, ttl, isWindows && ':' in host, (System.nanoTime() - started) / 1_000_000.0)
    }

    override suspend fun wake(packet: ByteArray, broadcast: String, port: Int) = withContext(Dispatchers.IO) {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName(broadcast), port))
        }
    }

    override suspend fun resolve(host: String): List<String> = withContext(Dispatchers.IO) {
        InetAddress.getAllByName(host).mapNotNull { it.hostAddress }
    }

    override suspend fun reverse(address: String): String? = withTimeoutOrNull(REVERSE_TIMEOUT_MILLIS) {
        lookups.async {
            val inet = InetAddress.getByName(address)
            inet.canonicalHostName.takeIf { it != inet.hostAddress }
        }.await()
    }

    override suspend fun arp(): Map<String, String>? = when {
        isWindows -> parseArp(capture(listOf("arp", "-a"), ARP_LIMIT_MILLIS))
        isMac -> parseArp(capture(listOf("arp", "-an"), ARP_LIMIT_MILLIS))
        else -> withContext(Dispatchers.IO) { procArp() }
    }

    override suspend fun localNetworks(): List<LocalNetwork> = withContext(Dispatchers.IO) {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback && !cellular(it.name) }.flatMap { network ->
            network.interfaceAddresses.mapNotNull { entry ->
                val host = (entry.address as? Inet4Address)?.hostAddress ?: return@mapNotNull null
                LocalNetwork(network.name, host, entry.networkPrefixLength.toInt())
            }
        }
    }
}

// the output of a command; one that runs past the limit is killed, and so is the one whose coroutine was cancelled
private suspend fun capture(command: List<String>, limitMillis: Long): String = coroutineScope {
    val process = withContext(Dispatchers.IO) { ProcessBuilder(command).redirectErrorStream(true).start() }
    // Windows writes in the console code page; the parsers look only at digits, "TTL" and addresses
    val output = async(Dispatchers.IO) { process.inputStream.bufferedReader(if (isWindows) Charsets.ISO_8859_1 else Charsets.UTF_8).use { it.readText() } }
    try {
        withTimeoutOrNull(limitMillis) { output.await() }
    } finally {
        process.destroy()
    }
    output.await()
}

// Android 10 closes /proc/net to apps
private fun procArp(): Map<String, String>? = try {
    parseArp(File("/proc/net/arp").readText())
} catch (e: IOException) {
    null
}

private fun pingCommand(host: String, timeoutMillis: Int, ttl: Int?, size: Int?, dontFragment: Boolean): List<String> = buildList {
    add(
        when {
            isAndroid && ':' in host -> "/system/bin/ping6"
            isAndroid -> "/system/bin/ping"
            isMac && ':' in host -> "ping6"
            else -> "ping"
        },
    )
    when {
        isWindows -> {
            if (':' !in host) add("-4")
            addAll(listOf("-n", "1", "-w", timeoutMillis.toString()))
            if (ttl != null) addAll(listOf("-i", ttl.toString()))
            if (size != null) addAll(listOf("-l", size.toString()))
            if (dontFragment && ':' !in host) add("-f")
        }
        isMac && ':' in host -> {
            addAll(listOf("-c", "1"))
            if (ttl != null) addAll(listOf("-h", ttl.toString()))
            if (size != null) addAll(listOf("-s", size.toString()))
        }
        isMac -> {
            addAll(listOf("-c", "1", "-W", timeoutMillis.toString()))
            if (ttl != null) addAll(listOf("-m", ttl.toString()))
            if (size != null) addAll(listOf("-s", size.toString()))
            if (dontFragment) add("-D")
        }
        else -> {
            addAll(listOf("-c", "1", "-W", ((timeoutMillis + 999) / 1000).coerceAtLeast(1).toString()))
            if (ttl != null) addAll(listOf("-t", ttl.toString()))
            if (size != null) addAll(listOf("-s", size.toString()))
            if (dontFragment) addAll(listOf("-M", "do"))
        }
    }
    add(host)
}

actual fun hostOps(): HostOps? = SystemHostOps
