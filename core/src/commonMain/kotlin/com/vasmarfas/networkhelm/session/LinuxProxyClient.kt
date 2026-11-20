package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.openwrt.quote
import com.vasmarfas.networkhelm.ssh.SshSession
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.io.encoding.Base64
import kotlin.random.Random

// clients of sing-box and Xray on a Linux host, each made of a link a server gave: a file of its own in /etc/networkhelm/<program> and a
// service of its own, so a server of the same program on the host keeps its config. The client takes SOCKS on 127.0.0.1, sing-box HTTP on
// the same port as well, and sends everything to the server of the link
internal class LinuxProxyClient(private val ssh: SshSession, private val root: Root, private val tools: suspend () -> Set<String>) {
    val tables: Map<String, HostTable> = JSON_KINDS.associate { kind ->
        "proxy/${kind.id}/client" to HostTable(
            CLIENT_FIELDS, { clients(kind) }, slow = true, arguments = { clientArguments(kind) }, add = { values -> addClient(kind, values) },
            set = { id, changes -> changes["autostart"]?.let { ProxyService(root, tools, serviceOf(kind, id.orEmpty())).autostart(it in ValueType.Flag.on) } },
            remove = { ids -> removeClients(kind, ids) }, commands = listOf("up", "down"), run = { command, arguments -> bring(kind, command, arguments["numbers"].orEmpty()) },
            present = { kind.id in tools() },
        )
    }

    private suspend fun clients(kind: JsonKind) = parseClients(root.output(clientState(kind)), kind)

    private suspend fun clientArguments(kind: JsonKind): List<Argument> {
        val clients = clients(kind)
        return listOf(
            Argument("name", ValueType.Text(), fixed = true, default = generateSequence(1) { it + 1 }.map { if (it == 1) "client" else "client-$it" }.first { name -> clients.none { it.id == name } }),
            Argument("link", ValueType.Secret, fixed = true, placeholder = "vless://, vmess://, trojan://, ss://, hy2://"),
            Argument("local-port", ValueType.Integer(1, 65535), default = "${freePort(clients, listeningPorts(ssh))}", fixed = true),
            Argument("autostart", ValueType.YesNo, optional = true),
        )
    }

    // the file of the client goes through the check of the program, then its service starts, and at boot unless asked otherwise
    private suspend fun addClient(kind: JsonKind, values: Map<String, String>): String {
        val name = values["name"].orEmpty()
        if (!CLIENT_NAME.matches(name)) throw DeviceException(ErrorCategory.ArgumentValue, "name: $name is not a value it takes")
        val clients = clients(kind)
        if (clients.any { it.id == name }) throw DeviceException(ErrorCategory.ArgumentValue, "${kind.id} has the client $name already")
        val link = parseProxyLink(values["link"].orEmpty())
        if (kind.sing) refuseMlkem(ssh, link, "make it on the Clients tab of Xray, which Install Now of Proxy Overview installs")
        val listening = listeningPorts(ssh)
        val port = values["local-port"]?.ifEmpty { null }?.let { given ->
            given.toIntOrNull()?.takeIf { it in 1..65535 } ?: throw DeviceException(ErrorCategory.ArgumentValue, "local-port: $given is not a value it takes")
        } ?: freePort(clients, listening)
        if ("$port/tcp" in listening || clients.any { it["local-port"] == "$port" }) throw DeviceException(ErrorCategory.ArgumentValue, "port $port is taken on the host")
        val text = PRETTY.encodeToString(JsonObject.serializer(), if (kind.sing) singBoxClient(link, port) else xrayClient(link, port))
        val program = ssh.exec("command -v ${kind.id}").output.trim().ifEmpty { throw DeviceException(ErrorCategory.Unsupported, "the login finds no ${kind.id} on its PATH") }
        val file = "${clientDir(kind)}/$name.json"
        root.write(file, text, checkLine(kind, file))
        root.proxied(serviceUnit(serviceOf(kind, name), program, if (kind.sing) "-c $file run" else "run -config $file"))
        val service = ProxyService(root, tools, serviceOf(kind, name))
        service.bring("up")
        if (values["autostart"]?.let { it in ValueType.Flag.on } != false) service.autostart(true)
        return name
    }

    private suspend fun removeClients(kind: JsonKind, ids: List<String>) {
        val names = clients(kind).map { it.id }
        ids.firstOrNull { it !in names }?.let { throw DeviceException(ErrorCategory.MissingItem, "${kind.id} has no client $it") }
        for (name in ids) {
            val service = serviceOf(kind, name)
            root.proxied(
                "if command -v systemctl >/dev/null 2>&1; then systemctl disable --now $service >/dev/null 2>&1; rm -f /etc/systemd/system/$service.service; systemctl daemon-reload; " +
                    "else rc-service $service stop >/dev/null 2>&1; rc-update del $service default >/dev/null 2>&1; rm -f /etc/init.d/$service; fi; rm -f ${quote("${clientDir(kind)}/$name.json")}",
            )
        }
    }

    private suspend fun bring(kind: JsonKind, command: String, numbers: String): List<Item> {
        val names = clients(kind).map { it.id }
        for (name in numbers.split(',').filter { it.isNotEmpty() }) {
            if (name !in names) throw DeviceException(ErrorCategory.MissingItem, "${kind.id} has no client $name")
            ProxyService(root, tools, serviceOf(kind, name)).bring(command)
        }
        return emptyList()
    }

    // 1080, the port of SOCKS, then 10808, then a port above 20000 nothing has taken
    private fun freePort(clients: List<Item>, listening: Set<String>) =
        (listOf(1080, 10808) + List(32) { Random.nextInt(20000, 60000) }).first { port -> "$port/tcp" !in listening && clients.none { it["local-port"] == "$port" } }
}

// a link of a server as the windows of servers give it and clients take it; the secret is its UUID or password, the query its parameters
internal class ProxyLink(val type: String, val host: String, val port: Int, val secret: String, val method: String, val query: Map<String, String>) {
    val security = query["security"].orEmpty().ifEmpty { if (type == "trojan" || type == "hysteria2") "tls" else "none" }
    val network = query["type"].orEmpty().ifEmpty { "tcp" }
    val serverName = query["sni"].orEmpty().ifEmpty { query["peer"].orEmpty() }
}

// vless://, trojan://, ss:// of SIP002 (base64 or plain), hy2:// and vmess:// with its JSON in base64; what fails names neither the link nor
// its secret
internal fun parseProxyLink(text: String): ProxyLink {
    val link = text.trim()
    val scheme = link.substringBefore("://", "").lowercase()
    val rest = link.substringAfter("://")
    if (scheme == "vmess") return vmessLink(rest)
    val type = LINK_TYPES[scheme] ?: throw DeviceException(ErrorCategory.ArgumentValue, "the link is none of vless://, vmess://, trojan://, ss:// and hy2://")
    val body = rest.substringBefore('#')
    val query = body.substringAfter('?', "").split('&').filter { '=' in it }.associate { urlDecoded(it.substringBefore('=')) to urlDecoded(it.substringAfter('=')) }
    val authority = body.substringBefore('?').removeSuffix("/")
    val user = urlDecoded(authority.substringBeforeLast('@', ""))
    val address = authority.substringAfterLast('@')
    val host = if (address.startsWith("[")) address.substringBefore(']').removePrefix("[") else address.substringBeforeLast(':', "")
    val port = address.substringAfterLast(':', "").toIntOrNull()?.takeIf { it in 1..65535 }
    if (host.isEmpty() || port == null || user.isEmpty()) throw unreadable()
    if (type != "shadowsocks") return ProxyLink(type, host, port, user, "", query)
    val pair = if (':' in user) user else decoded(user) ?: throw unreadable()
    if (':' !in pair) throw unreadable()
    return ProxyLink(type, host, port, pair.substringAfter(':'), pair.substringBefore(':'), query)
}

private fun vmessLink(text: String): ProxyLink {
    val json = decoded(text.substringBefore('#').trim())?.let {
        try {
            Json.parseToJsonElement(it) as? JsonObject
        } catch (e: SerializationException) {
            null
        }
    } ?: throw unreadable()
    val port = json.str("port").toIntOrNull()?.takeIf { it in 1..65535 }
    if (json.str("add").isEmpty() || json.str("id").isEmpty() || port == null) throw unreadable()
    val net = json.str("net")
    val query = mapOf(
        "security" to (if (json.str("tls") == "tls") "tls" else "none"), "sni" to json.str("sni"), "type" to net, "host" to json.str("host"), "fp" to json.str("fp"),
        "scy" to json.str("scy"), "aid" to json.str("aid"), (if (net == "grpc") "serviceName" else "path") to json.str("path"),
    ).filterValues { it.isNotEmpty() }
    return ProxyLink("vmess", json.str("add"), port, json.str("id"), "", query)
}

private fun unreadable() = DeviceException(ErrorCategory.ArgumentValue, "the link names no server, port or secret")

// base64 of either alphabet, with or without its padding
private fun decoded(text: String): String? = try {
    val standard = text.replace('-', '+').replace('_', '/')
    Base64.decode(standard + "=".repeat((4 - standard.length % 4) % 4)).decodeToString()
} catch (e: IllegalArgumentException) {
    null
}

// SOCKS and HTTP on one port of 127.0.0.1, and the server of the link as the only outbound
private fun singBoxClient(link: ProxyLink, port: Int) = buildJsonObject {
    putJsonObject("log") { put("level", "warn") }
    putJsonArray("inbounds") { addJsonObject { put("type", "mixed"); put("tag", "mixed-in"); put("listen", "127.0.0.1"); put("listen_port", port) } }
    putJsonArray("outbounds") { add(singBoxOutbound(link, "proxy")) }
}

internal fun singBoxOutbound(link: ProxyLink, tag: String) = buildJsonObject {
    put("type", link.type)
    put("tag", tag)
    put("server", link.host)
    put("server_port", link.port)
    when (link.type) {
        "vless" -> {
            put("uuid", link.secret)
            link.query["flow"]?.ifEmpty { null }?.let { put("flow", it) }
        }
        "vmess" -> {
            put("uuid", link.secret)
            put("security", link.query["scy"] ?: "auto")
            put("alter_id", link.query["aid"]?.toIntOrNull() ?: 0)
        }
        "shadowsocks" -> {
            put("method", link.method)
            put("password", link.secret)
        }
        else -> put("password", link.secret)
    }
    if (link.type == "hysteria2" && link.query["obfs"] == "salamander") putJsonObject("obfs") { put("type", "salamander"); put("password", link.query["obfs-password"].orEmpty()) }
    if (link.security != "none") putJsonObject("tls") {
        put("enabled", true)
        link.serverName.ifEmpty { null }?.let { put("server_name", it) }
        if (link.query["insecure"] == "1" || link.query["allowInsecure"] == "1") put("insecure", true)
        if (link.security == "reality" || link.query["fp"] != null) putJsonObject("utls") { put("enabled", true); put("fingerprint", link.query["fp"] ?: "chrome") }
        if (link.security == "reality") putJsonObject("reality") { put("enabled", true); put("public_key", link.query["pbk"].orEmpty()); put("short_id", link.query["sid"].orEmpty()) }
    }
    if (link.type in TRANSPORTED) {
        when (link.network) {
            "tcp", "raw" -> {}
            "ws" -> putJsonObject("transport") {
                put("type", "ws")
                put("path", link.query["path"] ?: "/")
                link.query["host"]?.let { host -> putJsonObject("headers") { put("Host", host) } }
            }
            "grpc" -> putJsonObject("transport") { put("type", "grpc"); put("service_name", link.query["serviceName"].orEmpty()) }
            "httpupgrade" -> putJsonObject("transport") { put("type", "httpupgrade"); put("path", link.query["path"] ?: "/"); link.query["host"]?.let { put("host", it) } }
            else -> throw DeviceException(ErrorCategory.Unsupported, "the client takes no transport ${link.network}")
        }
    }
}

// SOCKS on 127.0.0.1, which Xray 24 and newer serves HTTP on as well, and the server of the link as the only outbound
private fun xrayClient(link: ProxyLink, port: Int) = buildJsonObject {
    putJsonObject("log") { put("loglevel", "warning") }
    putJsonArray("inbounds") {
        addJsonObject { put("tag", "socks-in"); put("listen", "127.0.0.1"); put("port", port); put("protocol", "socks"); putJsonObject("settings") { put("udp", true) } }
    }
    putJsonArray("outbounds") { add(xrayOutbound(link, "proxy")) }
}

// vnext and servers, which all versions of Xray read; Xray has no client of Hysteria 2 here
internal fun xrayOutbound(link: ProxyLink, tag: String) = buildJsonObject {
    // Xray 26 has no allowInsecure: a certificate it cannot check goes by its pin alone
    if (link.type in TRANSPORTED && link.security == "tls" && (link.query["allowInsecure"] == "1" || link.query["insecure"] == "1") && link.query["pcs"] == null) {
        throw DeviceException(ErrorCategory.Unsupported, "the client of Xray checks every certificate, and the link gives no pcs to pin this one")
    }
    put("tag", tag)
    put("protocol", link.type)
    putJsonObject("settings") {
        when (link.type) {
            "vless", "vmess" -> putJsonArray("vnext") {
                addJsonObject {
                    put("address", link.host)
                    put("port", link.port)
                    putJsonArray("users") {
                        addJsonObject {
                            put("id", link.secret)
                            if (link.type == "vless") {
                                put("encryption", link.query["encryption"] ?: "none")
                                link.query["flow"]?.ifEmpty { null }?.let { put("flow", it) }
                            } else {
                                put("alterId", link.query["aid"]?.toIntOrNull() ?: 0)
                                put("security", link.query["scy"] ?: "auto")
                            }
                        }
                    }
                }
            }
            "trojan", "shadowsocks" -> putJsonArray("servers") {
                addJsonObject {
                    put("address", link.host)
                    put("port", link.port)
                    if (link.type == "shadowsocks") put("method", link.method)
                    put("password", link.secret)
                }
            }
            else -> throw DeviceException(ErrorCategory.Unsupported, "the client of Xray takes no link of ${link.type}")
        }
    }
    if (link.type in TRANSPORTED) putJsonObject("streamSettings") {
        put("network", link.network)
        put("security", link.security)
        when (link.security) {
            "reality" -> putJsonObject("realitySettings") {
                put("serverName", link.serverName)
                put("fingerprint", link.query["fp"] ?: "chrome")
                put("publicKey", link.query["pbk"].orEmpty())
                put("shortId", link.query["sid"].orEmpty())
                put("spiderX", link.query["spx"] ?: "/")
            }
            "tls" -> putJsonObject("tlsSettings") {
                link.serverName.ifEmpty { null }?.let { put("serverName", it) }
                link.query["fp"]?.let { put("fingerprint", it) }
                link.query["pcs"]?.let { put("pinnedPeerCertSha256", it) }
            }
        }
        when (link.network) {
            "tcp", "raw" -> {}
            "ws" -> putJsonObject("wsSettings") { put("path", link.query["path"] ?: "/"); link.query["host"]?.let { put("host", it) } }
            "grpc" -> putJsonObject("grpcSettings") { put("serviceName", link.query["serviceName"].orEmpty()) }
            "httpupgrade" -> putJsonObject("httpupgradeSettings") { put("path", link.query["path"] ?: "/"); link.query["host"]?.let { put("host", it) } }
            else -> throw DeviceException(ErrorCategory.Unsupported, "the client takes no transport ${link.network}")
        }
    }
}

// a client in the table: what its file says, masked, and the state of its service
private fun parseClients(text: String, kind: JsonKind): List<Item> = text.split("--nh-client-- ").drop(1).map { section ->
    val head = section.substringBefore('\n').trim().split(' ')
    val name = head.first()
    val file = "${clientDir(kind)}/$name.json"
    val json = configJson(section.substringAfter('\n', ""), file)
    val outbound = json.listed("outbounds").firstOrNull()
    Item(
        name,
        linkedMapOf(
            "name" to name, "type" to outbound?.let(kind::type).orEmpty(), "server" to kind.serverOf(outbound),
            "local-port" to json.listed("inbounds").firstOrNull()?.let(kind::port).orEmpty(), "state" to head.getOrElse(1) { "" },
            "autostart" to "${head.getOrElse(2) { "" } == "enabled"}", "config" to file,
        ),
    )
}

// the server an outbound dials, as host:port
internal fun JsonKind.serverOf(outbound: JsonObject?): String {
    val settings = outbound?.get("settings") as? JsonObject
    val target = if (sing) outbound else ((settings?.get("vnext") ?: settings?.get("servers")) as? JsonArray)?.firstOrNull() as? JsonObject
    val host = target.str(if (sing) "server" else "address")
    return if (host.isEmpty()) "" else "${if (':' in host) "[$host]" else host}:${target.str(if (sing) "server_port" else "port")}"
}

// a sing-box that leaves X25519MLKEM768 out reaches no server of REALITY on Xray, whose links carry spx; the refusal says what to do instead
internal suspend fun refuseMlkem(ssh: SshSession, link: ProxyLink, instead: String) {
    if (link.security != "reality" || "spx" !in link.query) return
    val version = ssh.exec("sing-box version").output.lineSequence().firstOrNull().orEmpty()
    if (dropsMlkem(version)) throw DeviceException(ErrorCategory.Unsupported, "sing-box ${SING_BOX_VERSION.find(version)?.value ?: version.trim()} $MLKEM_DROPPED: $instead")
}

// sing-box left X25519MLKEM768 out of its Client Hello of Reality up to 1.14.4 and 1.15.0-alpha.13, and REALITY of Xray refuses such a hello
// since September 2026; sing-box sends it again since 14.11.2026. Links of Xray carry spx
private fun dropsMlkem(version: String): Boolean {
    val found = SING_BOX_VERSION.find(version) ?: return true
    val (major, minor, patch) = found.groupValues.subList(1, 4).map { it.toInt() }
    val alpha = found.groupValues[4].toIntOrNull()
    return when {
        major != 1 -> major < 1
        minor < 14 -> true
        minor == 14 -> patch <= 4
        else -> minor == 15 && patch == 0 && alpha != null && alpha <= 13
    }
}

private fun clientDir(kind: JsonKind) = "/etc/networkhelm/${kind.id}"

private fun serviceOf(kind: JsonKind, name: String): String {
    if (!CLIENT_NAME.matches(name)) throw DeviceException(ErrorCategory.MissingItem, "${kind.id} has no client $name")
    return "${kind.id}-client-$name"
}

private fun clientState(kind: JsonKind) = $$"""$$SERVICE_STATE
for f in $${clientDir(kind)}/*.json; do [ -f "$f" ] || continue; n=${f##*/}; n=${n%.json}; echo "--nh-client-- $n $(s $${kind.id}-client-$n)"; $$JSON_MASK "$f"; done
true"""

private fun JsonObject?.str(key: String) = (this?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

private val LINK_TYPES = mapOf("vless" to "vless", "trojan" to "trojan", "ss" to "shadowsocks", "hy2" to "hysteria2", "hysteria2" to "hysteria2")
private val TRANSPORTED = setOf("vless", "vmess", "trojan")
private val CLIENT_NAME = Regex("[A-Za-z0-9_-]{1,32}")
private val SING_BOX_VERSION = Regex("(\\d+)\\.(\\d+)\\.(\\d+)(?:-alpha\\.(\\d+))?")
private const val MLKEM_DROPPED = "leaves X25519MLKEM768 out of its Client Hello of Reality, and servers of Xray refuse such a client"
private val CLIENT_FIELDS = listOf("name", "type", "server", "local-port", "state", "autostart", "config")
private val PRETTY = Json { prettyPrint = true }
