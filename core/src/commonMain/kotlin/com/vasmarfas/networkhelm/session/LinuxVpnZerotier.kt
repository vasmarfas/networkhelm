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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// ZeroTier One of a Linux host: the node with its address, whether it reaches the roots and its settings of local.conf, the networks it
// has joined with their status, device, addresses, routes and what the network may set on the host, and the peers it knows. Joining
// asks no account here: the controller of the network lets the node in or not. A network allowed the default route goes under the
// guard, as it may take the way this session comes by, and so does the restart that gives the node its new settings
internal class LinuxVpnZerotier(private val ssh: SshSession, private val root: Root, private val tools: suspend () -> Set<String>, private val guard: LinuxGuard) {
    val tables: Map<String, HostTable> = mapOf(
        "vpn/zerotier" to HostTable(
            ZT_FIELDS, ::networks, slow = true, arguments = { ZT_ARGUMENTS }, add = ::join, set = ::set, remove = ::leave, present = { "zerotier-cli" in tools() },
        ),
        "vpn/zerotier/node" to HostTable(
            ZT_NODE, { listOf(parseZerotierNode(root.masked("zerotier-cli -j info 2>&1; true"))) }, settings = true, arguments = { ZT_NODE_ARGUMENTS }, set = ::setNode,
            present = { "zerotier-cli" in tools() },
        ),
        "vpn/zerotier/peer" to HostTable(ZT_PEER, { parseZerotierPeers(root.masked("zerotier-cli -j peers")) }, slow = true, present = { "zerotier-cli" in tools() }),
    )

    private suspend fun networks(): List<Item> = parseZerotierNetworks(root.masked("zerotier-cli -j listnetworks"))

    private suspend fun join(values: Map<String, String>): String {
        val id = values["id"].orEmpty().lowercase()
        if (!ZT_NETWORK.matches(id)) throw DeviceException(ErrorCategory.ArgumentValue, "id: $id is not a value it takes")
        root.masked("zerotier-cli join $id >&2")
        set(id, values - "id")
        return id
    }

    // what the network may set on the host: its managed addresses, global addresses, the default route and DNS
    private suspend fun set(id: String?, changes: Map<String, String?>) {
        val network = id.orEmpty().also { if (!ZT_NETWORK.matches(it)) throw DeviceException(ErrorCategory.MissingItem, "the host has no ZeroTier network $it") }
        for ((field, setting) in ZT_SETTINGS) {
            val on = changes[field]?.let { if (it in ValueType.Flag.on) 1 else 0 } ?: continue
            val command = "zerotier-cli set $network $setting=$on >/dev/null"
            if (setting == "allowDefault" && on == 1) guard.guarded(LinuxGuard.Backup("vpn", "true", "zerotier-cli set $network allowDefault=0")) { root.masked(command) } else root.masked(command)
        }
    }

    // the settings of the node go to the settings of local.conf, the rest of the file as it is, and the service starts again to take them;
    // an empty field takes the setting out, so ZeroTier goes by its own default
    private suspend fun setNode(id: String?, changes: Map<String, String?>) {
        val given = ZT_LOCAL.filter { (field, _) -> field in changes }
        if (given.isEmpty()) return
        changes["primary-port"]?.ifEmpty { null }?.let { if (it.toIntOrNull() !in 1..65535) throw DeviceException(ErrorCategory.ArgumentValue, "primary-port: $it is not a value it takes") }
        val file = quote(ZT_LOCAL_CONF)
        val conf = ztJson(root.masked("cat $file 2>/dev/null; true")) as? JsonObject ?: JsonObject(emptyMap())
        val settings = (conf["settings"] as? JsonObject).orEmpty().toMutableMap()
        for ((field, key) in given) {
            val value = changes[field].orEmpty()
            if (value.isEmpty()) settings.remove(key) else settings[key] = if (field == "primary-port") JsonPrimitive(value.toInt()) else JsonPrimitive(value in ValueType.Flag.on)
        }
        val restart = if ("systemctl" in tools()) "systemctl restart zerotier-one" else "rc-service zerotier-one restart"
        val backup = LinuxGuard.Backup(
            "vpn", "if [ -e $file ]; then cp -p $file \"\$B/local\"; fi", "if [ -e \"\$1/local\" ]; then cp -p \"\$1/local\" $file; else rm -f $file; fi; $restart",
        )
        guard.guarded(backup) {
            root.write(ZT_LOCAL_CONF, JsonObject(conf + ("settings" to JsonObject(settings))).toString(), "true", "644")
            root.masked(restart)
        }
    }

    // a network this session comes in by is not left
    private suspend fun leave(ids: List<String>) {
        val host = networks()
        for (id in ids) {
            val network = host.firstOrNull { it.id == id } ?: throw DeviceException(ErrorCategory.MissingItem, "the host has no ZeroTier network $id")
            network["device"]?.ifEmpty { null }?.let { keepSession(ssh, it) }
            root.masked("zerotier-cli leave $id >&2")
        }
    }
}

// the networks of zerotier-cli -j listnetworks by their id
internal fun parseZerotierNetworks(text: String): List<Item> = (ztJson(text) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { network ->
    val id = network.zt("nwid").ifEmpty { network.zt("id") }
    Item(
        id,
        linkedMapOf(
            "id" to id, "name" to network.zt("name"), "status" to network.zt("status"), "type" to network.zt("type"), "mac-address" to network.zt("mac").uppercase(),
            "device" to network.zt("portDeviceName"), "addresses" to (network["assignedAddresses"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(", "),
            "mtu" to network.zt("mtu"), "routes" to (network["routes"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.joinToString(", ") { route ->
                route.zt("target") + route.zt("via").ifEmpty { null }?.let { " via $it" }.orEmpty()
            },
            "dns" to ((network["dns"] as? JsonObject)?.get("servers") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(", "),
            "dns-domain" to (network["dns"] as? JsonObject)?.zt("domain").orEmpty(),
        ) + ZT_SETTINGS.associate { (field, setting) -> field to network.zt(setting) },
    )
}

// the peers of zerotier-cli -j peers: the roots and the nodes this one talks to, how far each is and the way it goes, the preferred
// path or else the first active one; a latency of -1 and a version of -1.-1.-1 are not known
internal fun parseZerotierPeers(text: String): List<Item> = (ztJson(text) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { peer ->
    val paths = (peer["paths"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val path = paths.firstOrNull { it.zt("preferred") == "true" } ?: paths.firstOrNull { it.zt("active") == "true" }
    Item(
        peer.zt("address"),
        linkedMapOf(
            "address" to peer.zt("address"), "role" to peer.zt("role"), "version" to peer.zt("version").takeIf { !it.startsWith("-1") }.orEmpty(),
            "latency" to peer.zt("latency").takeIf { it != "-1" }.orEmpty(), "path" to path?.zt("address").orEmpty(), "paths" to "${paths.size}",
        ),
    )
}

// the node of zerotier-cli -j info: its address, version, whether it reaches the roots and the settings of local.conf it runs with
internal fun parseZerotierNode(text: String): Item {
    val info = ztJson(text) as? JsonObject ?: JsonObject(emptyMap())
    val settings = ((info["config"] as? JsonObject)?.get("settings") as? JsonObject) ?: JsonObject(emptyMap())
    return Item(
        "node",
        linkedMapOf("address" to info.zt("address"), "version" to info.zt("version"), "online" to info.zt("online")) + ZT_LOCAL.associate { (field, key) -> field to settings.zt(key) },
    )
}

private fun ztJson(text: String): JsonElement? = try {
    Json.parseToJsonElement(text.trim().ifEmpty { "null" })
} catch (e: SerializationException) {
    null
}

private fun JsonObject.zt(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

private val ZT_NETWORK = Regex("[0-9a-f]{16}")
private val ZT_SETTINGS = listOf("allow-managed" to "allowManaged", "allow-global" to "allowGlobal", "allow-default" to "allowDefault", "allow-dns" to "allowDNS")
private val ZT_FIELDS = listOf("id", "name", "status", "type", "mac-address", "device", "addresses", "mtu", "routes", "dns", "dns-domain") + ZT_SETTINGS.map { it.first }
// the settings of local.conf the node form changes, by the names of its fields
private val ZT_LOCAL = listOf("primary-port" to "primaryPort", "tcp-fallback" to "allowTcpFallbackRelay", "force-tcp" to "forceTcpRelay", "port-mapping" to "portMappingEnabled")
private val ZT_NODE = listOf("address", "version", "online") + ZT_LOCAL.map { it.first }
private val ZT_NODE_ARGUMENTS = listOf(Argument("primary-port", ValueType.Integer(1, 65535), optional = true, placeholder = "9993")) +
    ZT_LOCAL.drop(1).map { Argument(it.first, ValueType.YesNo, optional = true) }
private val ZT_PEER = listOf("address", "role", "version", "latency", "path", "paths")
private const val ZT_LOCAL_CONF = "/var/lib/zerotier-one/local.conf"
private val ZT_ARGUMENTS = listOf(Argument("id", ValueType.Text(), fixed = true, placeholder = "8056c2e21c000001")) + ZT_SETTINGS.map { Argument(it.first, ValueType.YesNo, optional = true) }
