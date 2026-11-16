package com.vasmarfas.networkhelm.keenetic

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlin.io.encoding.Base64

private const val ANY_WAY = "any"
private const val NO_WAY = "disabled"
private const val AUTO_SOURCE = "auto"

private val DOTTED = Regex("\\d{1,3}(\\.\\d{1,3}){3}")

// the IP tunnels the page offers and the component each needs
private val IP_TUNNELS = linkedMapOf("IPIP" to "ipip", "Gre" to "gre", "EoIP" to "eoip")

private val GLOBAL = JsonObject(mapOf("auto" to JsonPrimitive(true)))

// the connections of one section of Other Connections: the interfaces of its types, made under the first name of the type no
// interface has, as the web interface names them, and removed as Tunnels removes them
internal abstract class VpnConnections(path: String, protected val api: KeeneticCall, private val kinds: Set<String>) : Node(path) {
    override val commands = listOf("print", "add", "set", "remove")

    protected abstract fun settings(row: Item, own: JsonObject): Map<String, String>

    override suspend fun read(): List<Item> {
        val (state, config) = api.shows("interface", "rc/interface")
        val rows = interfaces(state, config).filter { it["type"] in kinds && it["wireguard.server-owned"] != "true" }
        val counters = api.stats(rows.map { it.id })
        return rows.map { row ->
            val own = (config as? JsonObject)?.get(row.id) as? JsonObject ?: JsonObject(emptyMap())
            Item(
                row.id,
                linkedMapOf(
                    "name" to row.id,
                    "type" to row["type"].orEmpty(),
                    "description" to row["description"].orEmpty(),
                    "disabled" to row["disabled"].orEmpty(),
                    "connected" to row["connected"].orEmpty(),
                    "global" to "${inside(own, "ip", "global") != null}",
                    "schedule" to own.text("schedule").orEmpty(),
                    "uptime" to row["uptime"].orEmpty(),
                ) + settings(row, own) + counters[row.id].orEmpty(),
            )
        }
    }

    override suspend fun remove(ids: List<String>) {
        val known = read().map { it.id }.toSet()
        ids.firstOrNull { it !in known }?.let { throw DeviceException(ErrorCategory.MissingItem, "$path has no $it") }
        api.send(ids.map { JsonObject(mapOf("parse" to JsonPrimitive("no interface $it"))) })
    }

    protected suspend fun existing(id: String?): Item {
        val name = id ?: throw DeviceException(ErrorCategory.MissingItem, "$path holds connections, not settings")
        return read().firstOrNull { it.id == name } ?: throw DeviceException(ErrorCategory.MissingItem, "$path has no $name")
    }

    protected suspend fun free(type: String): String {
        val taken = interfaces(api.shows("interface").single(), JsonObject(emptyMap())).map { it.id }.toSet()
        return generateSequence(0) { it + 1 }.map { "$type$it" }.first { it !in taken }
    }

    // a new connection comes to be with its name and goes up last, once it has its settings
    protected fun made(name: String, values: Map<String, String>, settings: List<JsonObject>): List<JsonObject> =
        listOf(interfaceSetting(name, "description", JsonPrimitive(values["description"].orEmpty().ifEmpty { name }))) + settings +
            listOfNotNull(interfaceChange(name, mapOf("disabled" to (values["disabled"] ?: "no"))))

    // a way to the internet is the global flag the router gives a place in the order of connections itself
    protected fun common(name: String, changes: Map<String, String?>): List<JsonObject> = listOfNotNull(
        changes["global"]?.let { interfaceSetting(name, "ip.global", if (it in ValueType.Flag.on) GLOBAL else NO) },
        changes["schedule"]?.let { interfaceSetting(name, "schedule", noIfEmpty(it)) },
    )

    protected suspend fun schedules(): Map<String, String> = scheduleNames(api.shows("rc/schedule").single())

    // the connections a tunnel can go through, as the web interface offers them: those of the public level that can be a way
    // to the internet
    protected suspend fun ways(): Map<String, String> =
        interfaces(api.shows("interface").single(), JsonObject(emptyMap())).filter { it["security-level"] == "public" && it["global"] != null }.let(::titles)
}

// the WireGuard connections. A new one comes from the text of a .conf, which the router reads itself as Load from File of the
// web interface does, or from the fields with a key pair the router makes; the private key is never read back, so an empty
// one leaves the key the router has, and only the command for a new pair replaces it
internal class WireguardConnections(api: KeeneticCall) : VpnConnections("vpn/wireguard", api, setOf("Wireguard")), Commanded {
    override val commands = listOf("print", "add", "set", "remove", "generate-keys")

    override suspend fun arguments(): List<Argument> {
        val plans = schedules()
        return listOf(
            Argument("name", ValueType.Text(), readOnly = true),
            Argument("description", ValueType.Text(), optional = true),
            Argument("config", ValueType.Text(), optional = true, fixed = true, description = "the text of a .conf file: the router makes the connection and its peers of it"),
            Argument("disabled", ValueType.YesNo),
            Argument("global", ValueType.YesNo),
            Argument("private-key", ValueType.Secret, optional = true),
            Argument("public-key", ValueType.Text(), readOnly = true),
            Argument("address", ValueType.Text(), optional = true, placeholder = "10.0.0.2/32"),
            Argument("listen-port", ValueType.Integer(1, 65535), optional = true),
            Argument("dns", ValueType.Text(), optional = true),
            Argument("mtu", ValueType.Integer(576, 1500), optional = true),
            Argument("adjust-mss", ValueType.YesNo, default = "yes"),
            Argument("schedule", ValueType.Enumeration(closed = true), listOf("") + plans.keys, optional = true, labels = plans),
        )
    }

    override fun settings(row: Item, own: JsonObject): Map<String, String> {
        val address = inside(own, "ip", "address") as? JsonObject
        val values = fields(own)
        return linkedMapOf(
            "public-key" to row["wireguard.public-key"].orEmpty(),
            "address" to address?.let { cidr(it.text("address").orEmpty(), it.text("mask").orEmpty()) }.orEmpty(),
            "listen-port" to values["wireguard.listen-port.port"].orEmpty(),
            "dns" to nameServersOf(inside(own, "ip", "name-server"), ported = true),
            "mtu" to values["ip.mtu"].orEmpty(),
            "adjust-mss" to "${inside(own, "ip", "tcp", "adjust-mss") != null}",
            "peers" to "${objectsOf(inside(own, "wireguard", "peer")).size}",
        )
    }

    override suspend fun add(values: Map<String, String>): String {
        val text = values["config"].orEmpty()
        if (text.isNotBlank()) return imported(text, values)
        val name = free("Wireguard")
        val key = interfaceSetting(name, "wireguard.private-key", JsonPrimitive(values["private-key"].orEmpty()))
        api.send(made(name, values, configured(name, values - "private-key") + key))
        return name
    }

    override suspend fun set(id: String?, changes: Map<String, String?>) {
        val old = existing(id)
        api.send(listOfNotNull(interfaceChange(old.id, changes.filterKeys { it == "description" || it == "disabled" })) + configured(old.id, changes))
    }

    // an empty private key is the command of the router for a new pair, as the button of the web interface sends it
    override suspend fun run(command: String, arguments: Map<String, String>): List<Item> {
        if (command != "generate-keys") throw DeviceException(ErrorCategory.MissingItem, "Keenetic has no $path/$command")
        val ids = arguments["numbers"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val known = read().map { it.id }.toSet()
        ids.firstOrNull { it !in known }?.let { throw DeviceException(ErrorCategory.MissingItem, "$path has no $it") }
        api.send(ids.map { interfaceSetting(it, "wireguard.private-key", JsonPrimitive("")) })
        return emptyList()
    }

    // the router names the connection it makes of the file and gives its peers with it; the name of the file names it
    private suspend fun imported(text: String, values: Map<String, String>): String {
        val file = "${values["description"].orEmpty().ifEmpty { "wireguard" }}.conf"
        val body = JsonObject(mapOf("import" to JsonPrimitive(Base64.encode(text.encodeToByteArray())), "name" to JsonPrimitive(""), "filename" to JsonPrimitive(file)))
        val answer = api.post(listOf(nested(listOf("interface", "wireguard", "import"), body))).firstOrNull()
        val name = (inside(answer, "interface", "wireguard", "import", "created") as? JsonPrimitive)?.contentOrNull?.ifEmpty { null }
            ?: throw DeviceException(ErrorCategory.Api, "the router made no connection of the .conf")
        api.send(listOfNotNull(interfaceChange(name, values.filterKeys { it == "description" } + ("disabled" to (values["disabled"] ?: "no")))) + common(name, values))
        return name
    }

    private fun configured(name: String, changes: Map<String, String?>): List<JsonObject> = common(name, changes) + listOfNotNull(
        changes["address"]?.let { interfaceSetting(name, "ip.address", tunnelAddress(it)) },
        changes["listen-port"]?.let { interfaceSetting(name, "wireguard.listen-port", if (it.isEmpty()) NO else JsonObject(mapOf("port" to JsonPrimitive(it)))) },
        changes["mtu"]?.let { interfaceSetting(name, "ip.mtu", noIfEmpty(it)) },
        changes["adjust-mss"]?.let { interfaceSetting(name, "ip.tcp.adjust-mss", if (it in ValueType.Flag.on) JsonObject(mapOf("pmtu" to JsonPrimitive(true))) else NO) },
        changes["dns"]?.let { interfaceSetting(name, "ip.name-server", nameServers(it, ported = true)) },
        changes["private-key"]?.ifEmpty { null }?.let { interfaceSetting(name, "wireguard.private-key", JsonPrimitive(it)) },
    )
}

// the peers of the WireGuard connections, each known by its public key within its connection; a peer with another key is
// another peer, which goes in before the old one goes
internal class WireguardPeers(private val api: KeeneticCall) : Node("vpn/wireguard/peer") {
    override val commands = listOf("print", "add", "set", "remove")

    override suspend fun arguments(): List<Argument> {
        val (state, config) = api.shows("interface", "rc/interface")
        val all = interfaces(state, config)
        val connections = titles(all.filter { it["type"] == "Wireguard" })
        val ways = linkedMapOf(ANY_WAY to "Any internet connection") + all.filter { it["security-level"] == "public" && it["global"] != null }.let(::titles) +
            (NO_WAY to "Disable")
        return listOf(
            Argument("interface", ValueType.Enumeration(closed = true), connections.keys.toList(), labels = connections, default = connections.keys.firstOrNull(), fixed = true),
            Argument("public-key", ValueType.Text()),
            Argument("comment", ValueType.Text(), optional = true),
            Argument("endpoint", ValueType.Text(), optional = true, placeholder = "vpn.example.net:51820"),
            Argument("allowed-ips", ValueType.Text(), optional = true, placeholder = "0.0.0.0/0, ::/0"),
            Argument("keepalive", ValueType.Integer(1, 65535, unit = "s"), optional = true),
            Argument("preshared-key", ValueType.Secret, optional = true),
            Argument("via", ValueType.Enumeration(closed = true), ways.keys.toList(), labels = ways, default = ANY_WAY),
        )
    }

    override suspend fun read(): List<Item> {
        val (state, config) = api.shows("interface", "rc/interface")
        val raw = (state as? JsonObject).orEmpty().values.mapNotNull { it as? JsonObject }
        return interfaces(state, config).filter { it["type"] == "Wireguard" && it["wireguard.server-owned"] != "true" }.flatMap { connection ->
            val live = objectsOf(inside(raw.firstOrNull { it.text("id") == connection.id }, "wireguard", "peer")).associateBy { it.text("public-key") }
            objectsOf(inside(config, connection.id, "wireguard", "peer")).mapNotNull { peer ->
                val key = peer.text("key") ?: return@mapNotNull null
                val seen = live[key]
                val connect = peer["connect"] as? JsonObject
                Item(
                    "${connection.id}/$key",
                    linkedMapOf(
                        "interface" to connection.id,
                        "connection" to title(connection),
                        "public-key" to key,
                        "comment" to peer.text("comment").orEmpty(),
                        "endpoint" to (inside(peer, "endpoint", "address") as? JsonPrimitive)?.contentOrNull.orEmpty(),
                        "allowed-ips" to objectsOf(peer["allow-ips"]).joinToString(", ") { cidr(it.text("address").orEmpty(), it.text("mask").orEmpty()) },
                        "keepalive" to (inside(peer, "keepalive-interval", "interval") as? JsonPrimitive)?.contentOrNull.orEmpty(),
                        "preshared-key" to peer.text("preshared-key").orEmpty(),
                        "via" to if ((connect?.get("no") as? JsonPrimitive)?.booleanOrNull == true) NO_WAY else connect?.text("via")?.ifEmpty { null } ?: ANY_WAY,
                        "online" to seen?.text("online").orEmpty(),
                        "last-handshake" to seen?.text("last-handshake").orEmpty(),
                        "rxbytes" to seen?.text("rxbytes").orEmpty(),
                        "txbytes" to seen?.text("txbytes").orEmpty(),
                    ),
                )
            }
        }
    }

    override suspend fun add(values: Map<String, String>): String {
        val connection = values["interface"].orEmpty()
        val key = values["public-key"].orEmpty().trim()
        if (key.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "a peer needs its public key")
        val (state, config) = api.shows("interface", "rc/interface")
        if (interfaces(state, config).none { it.id == connection && it["type"] == "Wireguard" }) throw DeviceException(ErrorCategory.ArgumentValue, "a peer needs the WireGuard connection it belongs to")
        api.send(listOf(peer(connection, key, values.filterValues { it.isNotEmpty() })))
        return "$connection/$key"
    }

    override suspend fun set(id: String?, changes: Map<String, String?>) {
        val old = read().firstOrNull { it.id == id } ?: throw DeviceException(ErrorCategory.MissingItem, "$path has no $id")
        if (changes["interface"]?.let { it != old["interface"] } == true) throw DeviceException(ErrorCategory.Unsupported, "a peer stays with its connection; add it to the other one and remove this one")
        val connection = old["interface"].orEmpty()
        val key = changes["public-key"]?.trim()?.takeIf { it != old["public-key"] }
        when {
            key == null -> api.send(listOf(peer(connection, old["public-key"].orEmpty(), changes)))
            key.isEmpty() -> throw DeviceException(ErrorCategory.ArgumentValue, "a peer needs its public key")
            else -> {
                val moved = PEER_FIELDS.associateWith { changes[it] ?: old[it].orEmpty() }.filterValues { it.isNotEmpty() }
                api.send(listOf(peer(connection, key, moved), removal(connection, old["public-key"].orEmpty())))
            }
        }
    }

    override suspend fun remove(ids: List<String>) {
        val peers = read().associateBy { it.id }
        api.send(ids.map { id -> (peers[id] ?: throw DeviceException(ErrorCategory.MissingItem, "$path has no $id")).let { removal(it["interface"].orEmpty(), it["public-key"].orEmpty()) } })
    }

    private fun removal(connection: String, key: String) = interfaceSetting(connection, "wireguard.peer", JsonObject(mapOf("key" to JsonPrimitive(key), "no" to JsonPrimitive(true))))

    // a peer as the web interface writes it, with only what changed beside its key; the allowed subnets go anew
    private fun peer(connection: String, key: String, changes: Map<String, String?>): JsonObject {
        val parts = buildMap<String, JsonElement> {
            put("key", JsonPrimitive(key))
            changes["allowed-ips"]?.let { put("allow-ips", JsonArray(listOf(NO) + allowed(it))) }
            changes["comment"]?.let { put("comment", noIfEmpty(it)) }
            changes["keepalive"]?.let { put("keepalive-interval", if (it.isEmpty()) NO else JsonObject(mapOf("interval" to JsonPrimitive(seconds(it))))) }
            changes["preshared-key"]?.let { put("preshared-key", noIfEmpty(it)) }
            changes["endpoint"]?.let { put("endpoint", if (it.isEmpty()) NO else JsonObject(mapOf("address" to JsonPrimitive(it)))) }
            changes["via"]?.let {
                put(
                    "connect",
                    when (it) {
                        ANY_WAY, "" -> JsonObject(emptyMap())
                        NO_WAY -> NO
                        else -> JsonObject(mapOf("via" to JsonPrimitive(it)))
                    },
                )
            }
        }
        return interfaceSetting(connection, "wireguard.peer", JsonObject(parts))
    }

    private fun seconds(text: String) = text.toIntOrNull() ?: throw DeviceException(ErrorCategory.ArgumentValue, "$text is no number of seconds")

    // the subnets as the router keeps them: IPv4 with a mask of four numbers, IPv6 with the length of its prefix
    private fun allowed(text: String): List<JsonObject> = text.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }.map { subnet ->
        val (host, mask) = if (':' in subnet) subnet.substringBefore('/') to subnet.substringAfter('/', "128") else address(subnet)
        JsonObject(mapOf("address" to JsonPrimitive(host), "mask" to JsonPrimitive(mask)))
    }

    private companion object {
        val PEER_FIELDS = listOf("comment", "endpoint", "allowed-ips", "keepalive", "preshared-key", "via")
    }
}

// the OpenVPN connections: the whole .ovpn as the router keeps it, whether the routes of the server are taken, and the
// connection the tunnel goes through
internal class OpenVpnConnections(api: KeeneticCall) : VpnConnections("vpn/openvpn", api, setOf("OpenVPN")) {
    override suspend fun arguments(): List<Argument> {
        val plans = schedules()
        val through = linkedMapOf(ANY_WAY to "Any internet connection") + ways()
        return listOf(
            Argument("name", ValueType.Text(), readOnly = true),
            Argument("description", ValueType.Text(), optional = true),
            Argument("config", ValueType.Text(), description = "the whole text of the .ovpn file"),
            Argument("accept-routes", ValueType.YesNo),
            Argument("via", ValueType.Enumeration(closed = true), through.keys.toList(), labels = through, default = ANY_WAY),
            Argument("global", ValueType.YesNo),
            Argument("schedule", ValueType.Enumeration(closed = true), listOf("") + plans.keys, optional = true, labels = plans),
            Argument("disabled", ValueType.YesNo),
        )
    }

    override fun settings(row: Item, own: JsonObject): Map<String, String> = linkedMapOf(
        "config" to (inside(own, "openvpn", "config", "config") as? JsonPrimitive)?.contentOrNull.orEmpty(),
        "accept-routes" to "${(inside(own, "openvpn", "accept-routes") as? JsonPrimitive)?.booleanOrNull == true}",
        "via" to ((inside(own, "openvpn", "connect", "via") as? JsonPrimitive)?.contentOrNull?.ifEmpty { null } ?: ANY_WAY),
        "address" to row["address"].orEmpty(),
    )

    override suspend fun add(values: Map<String, String>): String {
        if (values["config"].isNullOrBlank()) throw DeviceException(ErrorCategory.ArgumentValue, "an OpenVPN connection needs the text of its .ovpn file")
        val name = free("OpenVPN")
        api.send(made(name, values, listOf(interfaceSetting(name, "role", JsonArray(listOf(JsonPrimitive("misc"))))) + configured(name, values)))
        return name
    }

    override suspend fun set(id: String?, changes: Map<String, String?>) {
        val old = existing(id)
        if (changes["config"]?.isBlank() == true) throw DeviceException(ErrorCategory.ArgumentValue, "an OpenVPN connection needs the text of its .ovpn file")
        api.send(listOfNotNull(interfaceChange(old.id, changes.filterKeys { it == "description" || it == "disabled" })) + configured(old.id, changes))
    }

    private fun configured(name: String, changes: Map<String, String?>): List<JsonObject> = common(name, changes) + listOfNotNull(
        changes["config"]?.let { interfaceSetting(name, "openvpn.config", JsonObject(mapOf("config" to JsonPrimitive(it)))) },
        changes["accept-routes"]?.let { interfaceSetting(name, "openvpn.accept-routes", JsonPrimitive(it in ValueType.Flag.on)) },
        changes["via"]?.let { interfaceSetting(name, "openvpn.connect", JsonObject(mapOf("via" to JsonPrimitive(it.takeIf { way -> way != ANY_WAY }.orEmpty())))) },
    )
}

// the IP tunnels, IPIP, GRE and EoIP, each made by its component: an address of its own, the far end, and the source the router
// sends from, which it chooses itself, an address or an interface. A way to the internet through IPIP or GRE takes a default
// route through the tunnel, as the web interface gives it one
internal class IpTunnels(api: KeeneticCall, private val components: Set<String>?) : VpnConnections("vpn/tunnel", api, IP_TUNNELS.keys) {
    override suspend fun arguments(): List<Argument> {
        val types = IP_TUNNELS.filterValues { components == null || it in components }.keys.toList()
        val sources = interfaces(api.shows("interface").single(), JsonObject(emptyMap())).filter { it["type"] !in setOf("Port", "AccessPoint", "WifiMaster") && it["type"] !in IP_TUNNELS }
            .let(::titles)
        val plans = schedules()
        return listOf(
            Argument("name", ValueType.Text(), readOnly = true),
            Argument("type", ValueType.Enumeration(closed = true), types, default = types.firstOrNull(), fixed = true),
            Argument("description", ValueType.Text(), optional = true),
            Argument("address", ValueType.Text(), optional = true, placeholder = "172.16.0.1/30"),
            Argument("destination", ValueType.Text()),
            Argument("source", ValueType.Enumeration(), listOf(AUTO_SOURCE) + sources.keys, labels = mapOf(AUTO_SOURCE to "Auto") + sources, default = AUTO_SOURCE),
            Argument("eoip-id", ValueType.Integer(1, 65535), depends = listOf(mapOf("type" to "EoIP"))),
            Argument("mtu", ValueType.Integer(576, 1500), optional = true),
            Argument("global", ValueType.YesNo),
            Argument("schedule", ValueType.Enumeration(closed = true), listOf("") + plans.keys, optional = true, labels = plans),
            Argument("disabled", ValueType.YesNo),
        )
    }

    override fun settings(row: Item, own: JsonObject): Map<String, String> {
        val values = fields(own)
        val address = inside(own, "ip", "address") as? JsonObject
        return linkedMapOf(
            "address" to address?.let { cidr(it.text("address").orEmpty(), it.text("mask").orEmpty()) }.orEmpty(),
            "destination" to values["tunnel.destination"].orEmpty(),
            "source" to (values["tunnel.source.address"] ?: values["tunnel.source.interface"] ?: AUTO_SOURCE),
            "eoip-id" to values["tunnel.eoip.id"].orEmpty(),
            "mtu" to values["ip.mtu"].orEmpty(),
            "local" to row["tunnel-local-source"].orEmpty(),
            "remote" to row["tunnel-remote-destination"].orEmpty(),
        )
    }

    override suspend fun add(values: Map<String, String>): String {
        val type = values["type"]?.ifEmpty { null } ?: IP_TUNNELS.keys.first()
        if (type !in IP_TUNNELS) throw DeviceException(ErrorCategory.ArgumentValue, "$type is no IP tunnel of Keenetic")
        if (values["destination"].isNullOrEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "a tunnel needs the address of its far end")
        val name = free(type)
        api.send(made(name, values, configured(name, type, values, routed = false)))
        return name
    }

    override suspend fun set(id: String?, changes: Map<String, String?>) {
        val old = existing(id)
        if (changes["type"]?.let { it != old["type"] } == true) throw DeviceException(ErrorCategory.Unsupported, "a tunnel keeps its type; add one of the other type and remove this one")
        val routed = "global" in changes && rows(api.shows("rc/ip/route").single()).any { it["default"] == "true" && it["interface"] == old.id }
        api.send(listOfNotNull(interfaceChange(old.id, changes.filterKeys { it == "description" || it == "disabled" })) + configured(old.id, old["type"].orEmpty(), changes, routed))
    }

    private fun configured(name: String, type: String, changes: Map<String, String?>, routed: Boolean): List<JsonObject> = common(name, changes) + listOfNotNull(
        changes["global"]?.let { it in ValueType.Flag.on }?.takeIf { type != "EoIP" && (it || routed) }?.let { on ->
            nested(listOf("ip", "route"), JsonObject(mapOf("default" to JsonPrimitive(true), "interface" to JsonPrimitive(name), "no" to JsonPrimitive(!on))))
        },
        changes["address"]?.let { interfaceSetting(name, "ip.address", tunnelAddress(it)) },
        changes["destination"]?.let { interfaceSetting(name, "tunnel.destination", noIfEmpty(it)) },
        changes["source"]?.let { interfaceSetting(name, "tunnel.source", sourceOf(it)) },
        changes["eoip-id"]?.let { interfaceSetting(name, "tunnel.eoip.id", noIfEmpty(it)) },
        changes["mtu"]?.let { interfaceSetting(name, "ip.mtu", noIfEmpty(it)) },
    )

    private fun sourceOf(text: String): JsonObject = when {
        text.isEmpty() || text == AUTO_SOURCE -> JsonObject(mapOf("auto" to JsonPrimitive(AUTO_SOURCE)))
        text.matches(DOTTED) -> JsonObject(mapOf("address" to JsonPrimitive(text)))
        else -> JsonObject(mapOf("interface" to JsonPrimitive(text)))
    }
}

// the tunnels of IPv6 over IPv4 to a broker: its IPv4 address, and the IPv6 address and prefix it gave
internal class SixInFourTunnels(api: KeeneticCall) : VpnConnections("vpn/6in4", api, setOf("TunnelSixInFour")) {
    override suspend fun arguments() = listOf(
        Argument("name", ValueType.Text(), readOnly = true),
        Argument("description", ValueType.Text(), optional = true),
        Argument("server", ValueType.Ip4),
        Argument("ipv6-address", ValueType.Text()),
        Argument("ipv6-prefix", ValueType.Text(), optional = true),
        Argument("global", ValueType.YesNo),
        Argument("disabled", ValueType.YesNo),
    )

    // the router keeps the address with the length 128, which the web interface leaves out
    override fun settings(row: Item, own: JsonObject): Map<String, String> = linkedMapOf(
        "server" to fields(own)["tunnel.destination"].orEmpty(),
        "ipv6-address" to objectsOf(inside(own, "ipv6", "address")).firstNotNullOfOrNull { it.text("block") ?: it.text("address") }.orEmpty().removeSuffix("/128"),
        "ipv6-prefix" to objectsOf(inside(own, "ipv6", "prefix")).firstNotNullOfOrNull { it.text("prefix") }.orEmpty(),
    )

    override suspend fun add(values: Map<String, String>): String {
        if (values["server"].isNullOrEmpty() || values["ipv6-address"].isNullOrEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "a tunnel to a broker needs its IPv4 address and the IPv6 address it gave")
        val name = free("TunnelSixInFour")
        api.send(made(name, values, configured(name, values)))
        return name
    }

    override suspend fun set(id: String?, changes: Map<String, String?>) {
        val old = existing(id)
        api.send(listOfNotNull(interfaceChange(old.id, changes.filterKeys { it == "description" || it == "disabled" })) + configured(old.id, changes))
    }

    private fun configured(name: String, changes: Map<String, String?>): List<JsonObject> = common(name, changes) + listOfNotNull(
        changes["server"]?.let { interfaceSetting(name, "tunnel.destination", noIfEmpty(it)) },
        changes["ipv6-address"]?.let { interfaceSetting(name, "ipv6.address", if (it.isEmpty()) NO else JsonArray(listOf(NO, JsonObject(mapOf((if ('/' in it) "block" else "address") to JsonPrimitive(it)))))) },
        changes["ipv6-prefix"]?.let { interfaceSetting(name, "ipv6.prefix", if (it.isEmpty()) NO else JsonArray(listOf(NO, JsonObject(mapOf("prefix" to JsonPrimitive(it)))))) },
    )
}

// an address of a tunnel as the web interface sends it: the old one taken off, then the new one with its mask
private fun tunnelAddress(text: String): JsonArray {
    if (text.isBlank()) return JsonArray(listOf(NO))
    val (host, mask) = address(text)
    return JsonArray(listOf(NO, JsonObject(mapOf("address" to JsonPrimitive(host), "mask" to JsonPrimitive(mask)))))
}

// an address with its mask as the web interface shows it, the mask of IPv4 as the length of its prefix
private fun cidr(address: String, mask: String): String {
    if (address.isEmpty()) return ""
    val parts = mask.split('.').mapNotNull { it.toIntOrNull() }
    val length = if (parts.size == 4) parts.sumOf { it.countOneBits() } else mask.toIntOrNull()
    return if (length == null) address else "$address/$length"
}
