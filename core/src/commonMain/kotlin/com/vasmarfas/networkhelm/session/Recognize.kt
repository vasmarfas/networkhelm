package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.transport.BufferedStream
import com.vasmarfas.networkhelm.transport.closingAfter
import com.vasmarfas.networkhelm.transport.httpRequest
import com.vasmarfas.networkhelm.transport.openTcp
import com.vasmarfas.networkhelm.transport.openTls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.time.Duration.Companion.milliseconds

// the certificate is not checked: nothing secret is sent
suspend fun recognize(host: String, open: Set<Int>, timeoutMillis: Int): Platform? = coroutineScope {
    if (8728 in open || 8729 in open) return@coroutineScope Platform.RouterOs
    val banner = async { if (22 in open) quietly { openTcp(host, 22, timeoutMillis).closingAfter(timeoutMillis.milliseconds) { BufferedStream(it).readLine() } } else null }
    val pages = listOf(80 to false, 443 to true).filter { it.first in open }.map { (port, tls) -> async { page(host, port, tls, "/", timeoutMillis) } }.mapNotNull { it.await() }
    val more = if (recognized(open, null, pages) == null && 443 in open) listOfNotNull(page(host, 443, true, "/info", timeoutMillis)) else emptyList()
    recognized(open, null, pages + more)?.also { banner.cancel() } ?: recognized(open, banner.await(), pages + more)
}

// the domains KeenDNS and CrazeDNS give routers of Keenetic and Netcraze; through the cloud a router takes HTTPS only
private val keeneticClouds = listOf("keenetic.link", "keenetic.pro", "keenetic.name", "keenetic.io", "netcraze.link", "netcraze.pro", "netcraze.club", "netcraze.io", "crazedns.ru")

fun keeneticCloud(host: String): Boolean {
    val name = host.lowercase().trimEnd('.')
    return keeneticClouds.any { name.endsWith(".$it") }
}

fun recognized(open: Set<Int>, banner: String?, pages: List<String>): Platform? {
    val text = pages.joinToString("\n").lowercase()
    return when {
        8728 in open || 8729 in open || banner?.contains("ROSSSH") == true || "routeros" in text || "webfig" in text -> Platform.RouterOs
        "opnsense" in text -> Platform.Opnsense
        "pfsense" in text -> Platform.Pfsense
        "keenetic" in text || "netcraze" in text -> Platform.Keenetic
        "welcome to vyos" in text -> Platform.Vyos
        "cgi-bin/luci" in text || "openwrt" in text || banner?.contains("dropbear", ignoreCase = true) == true -> Platform.OpenWrt
        else -> null
    }
}

private suspend fun page(host: String, port: Int, tls: Boolean, target: String, timeoutMillis: Int): String? = quietly {
    val stream = if (tls) openTls(host, port, null, timeoutMillis) else openTcp(host, port, timeoutMillis)
    val answer = stream.closingAfter(timeoutMillis.milliseconds) { httpRequest(it, host, "GET", target, emptyMap(), ByteArray(0)) }
    answer.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" } + "\n" + answer.body.decodeToString()
}

private suspend fun <T> quietly(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
