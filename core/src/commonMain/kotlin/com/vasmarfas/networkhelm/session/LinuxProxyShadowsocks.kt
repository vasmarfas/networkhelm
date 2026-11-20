package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.urlEncoded
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.io.encoding.Base64

// shadowsocks-libev of a Linux host: the server of its config.json, which ss-server reads at its start, and the ss:// link of SIP002 for
// a client, whose QR the window of its output draws. The password stays in the file as ss-server needs it; the read masks it, and the
// link comes only at its button. The keys of the file the window does not know stay as they are
internal class LinuxProxyShadowsocks(private val ssh: SshSession, private val root: Root, private val tools: suspend () -> Set<String>) {
    val tables: Map<String, HostTable> = mapOf(
        "proxy/shadowsocks" to HostTable(
            SS_FIELDS, { listOfNotNull(state().row()) }, settings = true, slow = true, arguments = { SS_ARGUMENTS }, set = ::set,
            commands = listOf("up", "down", "client-link"), run = ::command, present = { "ss-server" in tools() },
        ),
    )

    private suspend fun state() = parseShadowsocks(root.output(SS_STATE))

    private suspend fun command(command: String, arguments: Map<String, String>): List<Item> {
        val host = state()
        if (!host.found) throw DeviceException(ErrorCategory.MissingItem, "the host has no config file $SS_CONF")
        if (command != "client-link") {
            ProxyService(root, tools, host.unit).bring(command)
            return emptyList()
        }
        val config = ssJson(root.output("cat $SS_CONF"))
        val server = ssValue(config["server"]).split(", ").firstOrNull { it !in WILDCARDS } ?: localAddress(ssh)
        val address = if (':' in server) "[$server]" else server
        val login = Base64.UrlSafe.encode("${ssValue(config["method"])}:${ssValue(config["password"])}".encodeToByteArray()).trimEnd('=')
        val plugin = ssValue(config["plugin"]).ifEmpty { null }?.let { name ->
            "/?plugin=" + urlEncoded(listOf(name, ssValue(config["plugin_opts"])).filter { it.isNotEmpty() }.joinToString(";"))
        }.orEmpty()
        return listOf(Item("", mapOf("conf" to "ss://$login@$address:${ssValue(config["server_port"])}$plugin#${urlEncoded(server)}")))
    }

    private suspend fun set(id: String?, changes: Map<String, String?>) {
        val host = state()
        if (!host.found) throw DeviceException(ErrorCategory.MissingItem, "the host has no config file $SS_CONF")
        val fields = changes.filterKeys { it in SS_KEYS }.mapValues { (field, value) -> if (field == "password") value.orEmpty() else value.orEmpty().trim() }
        fields.forEach { (field, value) -> ssCheck(field, value) }
        val service = ProxyService(root, tools, host.unit)
        if (fields.isNotEmpty()) {
            val before = root.output("cat $SS_CONF")
            val config = ssJson(before).toMutableMap()
            for ((field, value) in fields) {
                val key = SS_KEYS.getValue(field)
                when {
                    field == "password" && value.isEmpty() -> Unit
                    value.isEmpty() -> config.remove(key)
                    field == "server" -> config[key] = value.split(SS_LIST).filter { it.isNotEmpty() }.let { items ->
                        if (items.size == 1) JsonPrimitive(items.single()) else JsonArray(items.map { JsonPrimitive(it) })
                    }
                    field == "server-port" || field == "timeout" -> config[key] = JsonPrimitive(value.toInt())
                    field in SS_SWITCHES -> config[key] = JsonPrimitive(value in ValueType.Flag.on)
                    field == "nameserver" -> config[key] = JsonPrimitive(value.split(SS_LIST).filter { it.isNotEmpty() }.joinToString(","))
                    else -> config[key] = JsonPrimitive(value)
                }
            }
            val after = PRETTY.encodeToString(JsonObject.serializer(), JsonObject(config))
            if (after.trimEnd() != before.trimEnd()) service.rewrite(SS_CONF, before, after, running(host.state), "600")
        }
        changes["autostart"]?.let { service.autostart(it in ValueType.Flag.on) }
    }
}

// what the host has of shadowsocks-libev at one read: the unit or init script that runs the server, its state, and config.json with the
// password masked
internal class ShadowsocksState(val found: Boolean, val unit: String, val state: String, val autostart: Boolean, val config: JsonObject) {
    fun row(): Item? = if (!found) null else Item(
        "shadowsocks",
        linkedMapOf("state" to state, "autostart" to "$autostart", "service" to unit) + SS_KEYS.mapValues { (field, key) -> if (field == "password") "" else ssValue(config[key]) } +
            ("config" to SS_CONF),
    )
}

internal fun parseShadowsocks(text: String): ShadowsocksState {
    val head = text.lines().firstOrNull { it.startsWith("--nh-ss-- ") }?.split(' ')?.drop(1) ?: return ShadowsocksState(false, "", "", false, JsonObject(emptyMap()))
    return ShadowsocksState(true, head.getOrElse(0) { "" }, head.getOrElse(1) { "" }, head.getOrElse(2) { "" } == "enabled", ssJson(text.substringAfter("--nh-ss-conf--\n", "")))
}

private fun ssJson(text: String): JsonObject = try {
    Json.parseToJsonElement(text) as? JsonObject
} catch (e: SerializationException) {
    null
} ?: throw DeviceException(ErrorCategory.Api, "$SS_CONF is no JSON object")

// a list of addresses stands as one string with commas, a number or a word as it is
private fun ssValue(element: JsonElement?): String = when (element) {
    is JsonArray -> element.joinToString(", ") { ssValue(it) }
    is JsonPrimitive -> element.contentOrNull.orEmpty()
    else -> ""
}

private fun ssCheck(field: String, value: String) {
    val valid = when (field) {
        "server" -> value.split(SS_LIST).filter { it.isNotEmpty() }.let { items -> items.isNotEmpty() && items.all(SS_HOST::matches) }
        "server-port" -> value.toIntOrNull() in 1..65535
        "method" -> value in SS_METHODS
        "mode" -> value.isEmpty() || value in SS_MODES
        "timeout" -> value.isEmpty() || value.toIntOrNull()?.let { it > 0 } == true
        "nameserver" -> value.isEmpty() || value.split(SS_LIST).filter { it.isNotEmpty() }.all(SS_ADDRESS::matches)
        in SS_SWITCHES -> value.isEmpty() || value in ValueType.Flag.on || value.lowercase() in listOf("0", "no", "off", "false")
        else -> true
    }
    if (field == "password" && value.any { it == '\n' || it == '\r' }) throw DeviceException(ErrorCategory.ArgumentValue, "the password may hold no line break")
    if (!valid || field != "password" && value.any { it == '\n' || it == '\r' }) throw DeviceException(ErrorCategory.ArgumentValue, "$field: $value is not a value it takes")
}

internal const val SS_CONF = "/etc/shadowsocks-libev/config.json"

private val PRETTY = Json { prettyPrint = true }
private val SS_LIST = Regex("[,;\\s]+")
private val SS_HOST = Regex("[0-9A-Za-z.:-]+")
private val SS_ADDRESS = Regex("[0-9A-Fa-f.:]+")

// the switches of ss-server, which takes them as true and false of JSON
private val SS_SWITCHES = setOf("fast-open", "reuse-port", "no-delay", "ipv6-first")
private val WILDCARDS = setOf("", "0.0.0.0", "::", "::0", "127.0.0.1", "::1", "localhost")

// AEAD alone: the stream ciphers of the old protocol are open to replay and spoofing
private val SS_METHODS = listOf("chacha20-ietf-poly1305", "xchacha20-ietf-poly1305", "aes-256-gcm", "aes-192-gcm", "aes-128-gcm")
private val SS_MODES = listOf("tcp_only", "tcp_and_udp", "udp_only")

private val SS_KEYS = linkedMapOf(
    "server" to "server", "server-port" to "server_port", "password" to "password", "method" to "method", "mode" to "mode", "timeout" to "timeout",
    "plugin" to "plugin", "plugin-opts" to "plugin_opts", "nameserver" to "nameserver", "fast-open" to "fast_open", "reuse-port" to "reuse_port",
    "no-delay" to "no_delay", "ipv6-first" to "ipv6_first",
)
private val SS_FIELDS = listOf("state", "autostart", "service") + SS_KEYS.keys + "config"
private val SS_ARGUMENTS = listOf(
    Argument("server", ValueType.Text(), placeholder = "0.0.0.0, ::"),
    Argument("server-port", ValueType.Integer(1, 65535), placeholder = "8388"),
    Argument("password", ValueType.Secret, optional = true),
    Argument("method", ValueType.Enumeration(closed = true), SS_METHODS, default = SS_METHODS.first()),
    Argument("mode", ValueType.Enumeration(closed = true), SS_MODES, optional = true),
    Argument("timeout", ValueType.Integer(1, 1_000_000, unit = "s"), optional = true),
    Argument("plugin", ValueType.Text(), optional = true, placeholder = "v2ray-plugin"),
    Argument("plugin-opts", ValueType.Text(), optional = true, placeholder = "server"),
    Argument("nameserver", ValueType.Text(), optional = true, placeholder = "1.1.1.1, 8.8.8.8"),
    Argument("fast-open", ValueType.YesNo, optional = true),
    Argument("reuse-port", ValueType.YesNo, optional = true),
    Argument("no-delay", ValueType.YesNo, optional = true),
    Argument("ipv6-first", ValueType.YesNo, optional = true),
    Argument("autostart", ValueType.YesNo, optional = true),
)

// the unit of the server, the default instance of Debian or the template of Arch, or the init script of OpenRC; config.json with the
// password masked
private val SS_STATE = $$"""c=$$SS_CONF
[ -f "$c" ] || exit 0
$$SERVICE_STATE
u=shadowsocks-libev
for n in shadowsocks-libev shadowsocks-libev-server@config ss-server; do if systemctl cat "$n" >/dev/null 2>&1 || [ -e "/etc/init.d/$n" ]; then u=$n; break; fi; done
echo "--nh-ss-- $u $(s "$u")"
echo --nh-ss-conf--
sed 's/\("password"[[:space:]]*:[[:space:]]*"\)[^"]*"/\1(hidden)"/' "$c"
true"""
