package com.vasmarfas.networkhelm.upnp

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.routeros.api.md5
import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.transport.UdpLink
import com.vasmarfas.networkhelm.transport.closingAfter
import com.vasmarfas.networkhelm.transport.httpRequest
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

// a description and an answer to an action are a few kilobytes that a device on the local network gives at once
private val REQUEST_TIME = 10.seconds
private const val ANSWER_MOST = 1024 * 1024

class Description(val name: String, val manufacturer: String, val model: String, val description: String, val controls: Map<String, String>)

class UpnpFault(val code: Int, description: String) : Exception("UPnP error $code: $description")

class UpnpClient(
    private val host: String,
    private val port: Int,
    private val open: suspend (String, Int) -> ByteStream,
    private val user: String = "",
    private val password: String = "",
) {
    private val authority = if (port == 80) host else "$host:$port"

    suspend fun description(path: String): Description {
        val response = request("GET", path, emptyMap(), ByteArray(0))
        if (response.first != 200) throw DeviceException(if (response.first == 404) ErrorCategory.MissingItem else ErrorCategory.Api, "the description $path answered ${response.first}")
        return descriptionOf(response.second.decodeToString())
    }

    suspend fun call(service: String, control: String, action: String, arguments: Map<String, String> = emptyMap()): Map<String, String> {
        val body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$action xmlns:u=\"$service\">" +
            arguments.entries.joinToString("") { (name, value) -> "<$name>${escaped(value)}</$name>" } +
            "</u:$action></s:Body></s:Envelope>"
        val headers = mapOf("Content-Type" to "text/xml; charset=\"utf-8\"", "SOAPAction" to "\"$service#$action\"")
        val (status, bytes) = request("POST", control, headers, body.encodeToByteArray())
        val text = bytes.decodeToString()
        if (status == 500) {
            val code = element(text, "errorCode")?.trim()?.toIntOrNull() ?: 0
            throw UpnpFault(code, element(text, "errorDescription").orEmpty())
        }
        if (status == 401) throw DeviceException(ErrorCategory.PermissionDenied, "the device refused the user and password")
        if (status != 200) throw DeviceException(ErrorCategory.Api, "$action answered $status")
        val answer = element(text, "${action}Response") ?: throw DeviceException(ErrorCategory.Api, "$action answered no ${action}Response")
        return children(answer)
    }

    private suspend fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray): Pair<Int, ByteArray> {
        val first = asked(method, path, headers, body)
        val challenge = first.headers["www-authenticate"]?.takeIf { first.status == 401 && it.startsWith("Digest", ignoreCase = true) && user.isNotEmpty() }
            ?: return first.status to first.body
        val again = asked(method, path, headers + ("Authorization" to digest(challenge, method, path)), body)
        return again.status to again.body
    }

    private suspend fun asked(method: String, path: String, headers: Map<String, String>, body: ByteArray) =
        open(host, port).closingAfter(REQUEST_TIME) { httpRequest(it, authority, method, path, headers, body, ANSWER_MOST) }

    private fun digest(challenge: String, method: String, path: String): String {
        val fields = Regex("""(\w+)=(?:"([^"]*)"|([^,\s]*))""").findAll(challenge).associate { it.groupValues[1].lowercase() to (it.groupValues[2].ifEmpty { it.groupValues[3] }) }
        val realm = fields["realm"].orEmpty()
        val nonce = fields["nonce"].orEmpty()
        val cnonce = hex(Random.nextBytes(8))
        val first = hex(md5("$user:$realm:$password".encodeToByteArray()))
        val second = hex(md5("$method:$path".encodeToByteArray()))
        val qop = fields["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" }
        val response = hex(md5((if (qop != null) "$first:$nonce:00000001:$cnonce:auth:$second" else "$first:$nonce:$second").encodeToByteArray()))
        return "Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$path\", response=\"$response\"" +
            (if (qop != null) ", qop=auth, nc=00000001, cnonce=\"$cnonce\"" else "") + (fields["opaque"]?.let { ", opaque=\"$it\"" } ?: "")
    }
}

// answers are expected from port 1900, as miniupnpd sends them; a device answering from another port is not heard
suspend fun search(link: UdpLink, host: String, timeoutMillis: Int = 2_000): String? {
    val request = "M-SEARCH * HTTP/1.1\r\nHOST: $host:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n"
    repeat(2) {
        link.send(request.encodeToByteArray())
        val answer = link.receive(timeoutMillis)?.decodeToString() ?: return@repeat
        return answer.lineSequence().firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }?.substringAfter(':')?.trim()
    }
    return null
}

internal fun descriptionOf(xml: String): Description {
    val root = element(xml, "device") ?: throw DeviceException(ErrorCategory.Api, "the description names no device")
    val controls = Regex("""<(?:\w+:)?service>([\s\S]*?)</(?:\w+:)?service>""").findAll(xml).mapNotNull { service ->
        val type = element(service.groupValues[1], "serviceType")?.trim() ?: return@mapNotNull null
        val control = element(service.groupValues[1], "controlURL")?.trim() ?: return@mapNotNull null
        type to path(control)
    }.toMap()
    val head = root.substringBefore("<deviceList")
    return Description(field(head, "friendlyName"), field(head, "manufacturer"), field(head, "modelName"), field(head, "modelDescription"), controls)
}

internal fun path(url: String): String = when {
    url.startsWith("http://") || url.startsWith("https://") -> "/" + url.substringAfter("://").substringAfter('/', "")
    url.startsWith("/") -> url
    else -> "/$url"
}

private fun field(xml: String, name: String) = element(xml, name)?.trim()?.let(::unescaped).orEmpty()

internal fun element(xml: String, name: String): String? =
    Regex("""<(?:[\w-]+:)?$name(?:\s[^>]*)?(?:/>|>([\s\S]*?)</(?:[\w-]+:)?$name>)""").find(xml)?.groupValues?.get(1)

private fun children(xml: String): Map<String, String> =
    Regex("""<(?:[\w-]+:)?([\w-]+)(?:\s[^>]*)?(?:/>|>([\s\S]*?)</(?:[\w-]+:)?\1>)""").findAll(xml)
        .associate { it.groupValues[1] to unescaped(it.groupValues[2]) }

private fun escaped(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private fun unescaped(text: String) = text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
