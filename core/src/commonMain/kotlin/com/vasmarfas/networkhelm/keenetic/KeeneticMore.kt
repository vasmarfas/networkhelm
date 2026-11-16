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

private const val TOP_COUNT = 10

private val POLICY = Regex("[A-Za-z0-9_]+")

// the clients that took and sent the most in the last three minutes, as the Traffic Monitor of the web interface counts
// them: their rates and bytes, and the rest of the clients in one line without a name
internal class Top(private val api: KeeneticCall) : Node("traffic") {
    override suspend fun read(): List<Item> {
        fun summary(attribute: String) = JsonObject(
            mapOf("show" to nested(listOf("ip", "hotspot", "summary"), JsonObject(mapOf("attribute" to JsonPrimitive(attribute), "detail" to JsonPrimitive(0), "count" to JsonPrimitive(TOP_COUNT))))),
        )
        val (speed, bytes) = api.post(listOf(summary("sumspeed"), summary("sumbytes")), raising = false).map { answer ->
            if (failure(answer) == null) rows(shown(answer, "ip/hotspot/summary")) else emptyList()
        }
        val counted = bytes.associateBy { it["mac"].orEmpty() }
        return speed.map { host ->
            val mac = host["mac"].orEmpty()
            val total = counted[mac]
            Item(
                mac.ifEmpty { "others" },
                linkedMapOf(
                    "name" to host["name"].orEmpty(),
                    "mac" to mac,
                    "online" to (host["active"] ?: "false"),
                    "rx-rate" to host["rxspeed"].orEmpty(),
                    "tx-rate" to host["txspeed"].orEmpty(),
                    "rxbytes" to total?.get("rxbytes").orEmpty(),
                    "txbytes" to total?.get("txbytes").orEmpty(),
                ),
            )
        }
    }
}

// the tunnels to other networks, Other Connections of the web interface: VPN clients of every kind the router has; a new one
// is PPTP, L2TP or SSTP, as RCI takes a server and an account for those, and the rest of its settings are in All Settings
internal class Tunnels(private val api: KeeneticCall) : Node("vpn") {
    override val commands = listOf("print", "add", "set", "remove")

    override suspend fun arguments() = listOf(
        Argument("type", ValueType.Enumeration(closed = true), MADE, default = MADE.first()),
        Argument("description", ValueType.Text()),
        Argument("server", ValueType.Text(), optional = true),
        Argument("user", ValueType.Text(), optional = true),
        Argument("password", ValueType.Secret, optional = true),
        Argument("disabled", ValueType.YesNo),
    )

    override suspend fun read(): List<Item> {
        val (shown, config) = api.shows("interface", "rc/interface")
        val rows = interfaces(shown, config).filter { it["type"] in TUNNELS && it["type"] !in APART }
        val counters = api.stats(rows.map { it.id })
        return rows.map { row ->
            val own = (config as? JsonObject)?.get(row.id) as? JsonObject
            val settings = own?.let(::fields).orEmpty()
            val endpoint = (((own?.get("wireguard") as? JsonObject)?.get("peer") as? JsonArray)?.firstOrNull() as? JsonObject)?.let(::fields)?.get("endpoint.address")
            Item(
                row.id,
                linkedMapOf(
                    "name" to row.id,
                    "type" to row["type"].orEmpty(),
                    "description" to row["description"].orEmpty(),
                    "server" to (settings["peer"] ?: endpoint).orEmpty(),
                    "user" to settings["authentication.identity"].orEmpty(),
                    "connected" to row["connected"].orEmpty(),
                    "disabled" to row["disabled"].orEmpty(),
                    "address" to row["address"].orEmpty(),
                    "uptime" to row["uptime"].orEmpty(),
                    "default" to (row["defaultgw"] ?: "false"),
                    "security-level" to row["security-level"].orEmpty(),
                ) + counters[row.id].orEmpty(),
            )
        }
    }

    override suspend fun add(values: Map<String, String>): String {
        val type = values["type"]?.ifEmpty { null } ?: MADE.first()
        if (type !in MADE) throw DeviceException(ErrorCategory.ArgumentValue, "a new connection is one of ${MADE.joinToString()}: the router takes no server for the rest over RCI")
        val taken = interfaces(api.shows("interface").single(), JsonObject(emptyMap())).map { it.id }.toSet()
        val name = generateSequence(0) { it + 1 }.map { "$type$it" }.first { it !in taken }
        val parts = buildMap<String, JsonElement> {
            put("description", JsonPrimitive(values["description"].orEmpty().ifEmpty { name }))
            values["server"]?.ifEmpty { null }?.let { put("peer", JsonPrimitive(it)) }
            account(values, emptyMap())?.let { put("authentication", it) }
            put("up", if (values["disabled"] in ValueType.Flag.on) NO else JsonPrimitive(true))
        }
        api.send(listOf(nested(listOf("interface", name), JsonObject(parts))))
        return name
    }

    override suspend fun set(id: String?, changes: Map<String, String?>) {
        val name = id ?: throw DeviceException(ErrorCategory.MissingItem, "vpn holds connections, not settings")
        val old = read().firstOrNull { it.id == name } ?: throw DeviceException(ErrorCategory.MissingItem, "vpn has no $name")
        val parts = interfaceParts(changes.filterKeys { it == "description" || it == "disabled" }).toMutableMap()
        changes["server"]?.let { parts["peer"] = if (it.isEmpty()) NO else JsonPrimitive(it) }
        account(changes, old.values)?.let { parts["authentication"] = it }
        if (parts.isNotEmpty()) api.send(listOf(nested(listOf("interface", name), JsonObject(parts))))
    }

    override suspend fun remove(ids: List<String>) {
        val known = read().map { it.id }.toSet()
        ids.firstOrNull { it !in known }?.let { throw DeviceException(ErrorCategory.MissingItem, "vpn has no $it") }
        api.send(ids.map { JsonObject(mapOf("parse" to JsonPrimitive("no interface $it"))) })
    }

    // the user and the password go together, as the router keeps them; a password left empty stays as it was
    private fun account(changes: Map<String, String?>, old: Map<String, String>): JsonObject? {
        val user = changes["user"]
        val password = changes["password"]?.ifEmpty { null }
        if (user == null && password == null) return null
        return JsonObject(buildMap {
            (user ?: old["user"])?.ifEmpty { null }?.let { put("identity", JsonPrimitive(it)) }
            password?.let { put("password", JsonPrimitive(it)) }
        })
    }

    private companion object {
        val MADE = listOf("PPTP", "L2TP", "SSTP")
        // the connections that have tabs of their own in the same window
        val APART = setOf("Wireguard", "OpenVPN", "IPIP", "Gre", "EoIP", "TunnelSixInFour")
    }
}

internal val TUNNELS = setOf("PPTP", "L2TP", "SSTP", "OpenVPN", "OpenConnect", "Wireguard", "IKE", "IPIP", "Gre", "EoIP", "TunnelSixInFour", "ZeroTier", "Proxy")
internal val MODEMS = setOf("UsbLte", "UsbModem", "UsbQmi", "UsbNet", "Yota")
internal const val WISP = "WifiStation"

private class App(val id: String, val title: String, val components: List<String>, val any: Boolean = false)

// the applications of the web interface and the components each needs, all of them or, for Personal Cloud, one; the servers
// the router has keep their settings in tabs of their own
private val APPLICATIONS = listOf(
    App("cifs", "SMB Server", listOf("tsmb")),
    App("private-cloud", "Personal Cloud", listOf("webdav", "sftp", "ftp"), any = true),
    App("torrent", "Download Station", listOf("transmission")),
    App("dlna", "Media Server", listOf("dlna")),
    App("vpn", "PPTP VPN Server", listOf("vpnserver")),
    App("vpn-l2tp", "L2TP/IPsec VPN Server", listOf("vpnserver-l2tp")),
    App("vpn-sstp", "SSTP VPN Server", listOf("sstp-server", "ndns")),
    App("wireguard-server", "WireGuard VPN Server", listOf("wireguard-server", "ndns")),
    App("vpn-openconnect", "OpenConnect VPN Server", listOf("ocserver")),
    App("virtualIP", "IKEv1/IPsec VPN Server", listOf("virtual-ip-server")),
    App("vpn-ikev2", "IKEv2/IPsec VPN Server", listOf("virtual-ip-server", "ndns")),
    App("udpxy", "UDPXY Server", listOf("udpxy")),
)

internal val APP_KEYS = APPLICATIONS.associate { it.title to "apps.${it.id}.card-title" }

internal class Apps(private val components: Set<String>?, private val words: () -> Map<String, String>) : Node("apps") {
    override suspend fun read(): List<Item> = APPLICATIONS.map { app ->
        val installed = components?.let { have -> if (app.any) app.components.any { it in have } else app.components.all { it in have } }
        val status = when (installed) {
            true -> "Installed"
            false -> "Not installed"
            null -> ""
        }
        Item(app.id, linkedMapOf("name" to (words()[app.title] ?: app.title), "status" to (words()[status] ?: status), "components" to app.components.joinToString(", ")))
    }
}

// the policies of access to the internet, Connection Policies of the web interface: a name the router keeps it under, a
// description, multipath and the connections it lets clients use; the default policy is not one of them
internal class Policies(private val api: KeeneticCall) : Node("ip/policy") {
    override val commands = listOf("print", "add", "set", "remove")

    override suspend fun arguments() = listOf(
        Argument("name", ValueType.Text()),
        Argument("description", ValueType.Text(), optional = true),
        Argument("multipath", ValueType.YesNo),
        Argument("connections", ValueType.Text(), readOnly = true),
    )

    override suspend fun read(): List<Item> {
        val (config, shown, rc) = api.shows("rc/ip/policy", "interface", "rc/interface")
        val names = titles(interfaces(shown, rc))
        return (config as? JsonObject).orEmpty().map { (name, policy) ->
            val own = policy as? JsonObject
            val permitted = (own?.get("permit") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .filter { (it["enabled"] as? JsonPrimitive)?.content != "false" && "no" !in it }
                .mapNotNull { it.text("interface") }
            Item(
                name,
                linkedMapOf(
                    "name" to name,
                    "description" to own?.text("description").orEmpty(),
                    "multipath" to "${(own?.get("multipath") as? JsonPrimitive)?.content == "true"}",
                    "connections" to permitted.joinToString(", ") { names[it] ?: it },
                ),
            )
        }
    }

    override suspend fun add(values: Map<String, String>): String {
        val name = values["name"].orEmpty()
        if (!name.matches(POLICY)) throw DeviceException(ErrorCategory.ArgumentValue, "the name of a policy is of letters, digits and '_'")
        api.send(listOf(nested(listOf("ip", "policy", name), JsonObject(parts(values)))))
        return name
    }

    override suspend fun set(id: String?, changes: Map<String, String?>) {
        val name = id ?: throw DeviceException(ErrorCategory.MissingItem, "ip/policy holds policies, not settings")
        if (read().none { it.id == name }) throw DeviceException(ErrorCategory.MissingItem, "ip/policy has no $name")
        parts(changes).takeIf { it.isNotEmpty() }?.let { api.send(listOf(nested(listOf("ip", "policy", name), JsonObject(it)))) }
    }

    override suspend fun remove(ids: List<String>) {
        if (ids.any { !it.matches(POLICY) }) throw DeviceException(ErrorCategory.ArgumentValue, "the name of a policy is of letters, digits and '_'")
        api.send(ids.map { JsonObject(mapOf("parse" to JsonPrimitive("no ip policy $it"))) })
    }

    private fun parts(changes: Map<String, String?>): Map<String, JsonElement> = buildMap {
        changes["description"]?.let { put("description", if (it.isEmpty()) NO else JsonPrimitive(it)) }
        changes["multipath"]?.let { put("multipath", if (it in ValueType.Flag.on) JsonPrimitive(true) else NO) }
    }
}

// the access list of the MACs of each access point, Wireless ACL of the web interface: none, a white list that lets only
// these in, or a black list that keeps them out; the router takes only the MACs of registered clients
internal class WirelessAcl(private val api: KeeneticCall) : Node("wifi/acl") {
    override val commands = listOf("print", "set")

    override suspend fun arguments() = listOf(
        Argument("type", ValueType.Enumeration(closed = true), listOf("none", "permit", "deny"), labels = mapOf("none" to "Not used", "permit" to "White List", "deny" to "Black List")),
        Argument("addresses", ValueType.Text(), optional = true, description = "MAC addresses of registered clients, separated by commas"),
        Argument("ssid", ValueType.Text(), readOnly = true),
        Argument("segment", ValueType.Text(), readOnly = true),
    )

    override suspend fun read(): List<Item> {
        val (shown, config) = api.shows("interface", "rc/interface")
        val all = interfaces(shown, config)
        return all.filter { it["type"] == "AccessPoint" }.map { point ->
            val list = (((config as? JsonObject)?.get(point.id) as? JsonObject)?.get("mac") as? JsonObject)?.get("access-list") as? JsonObject
            Item(
                point.id,
                linkedMapOf(
                    "name" to point.id,
                    "ssid" to point["ssid"].orEmpty(),
                    "segment" to all.firstOrNull { it.id == point["group"] }?.let(::title).orEmpty(),
                    "type" to (list?.text("type") ?: "none"),
                    "addresses" to addressesOf(list).joinToString(", "),
                ),
            )
        }
    }

    override suspend fun set(id: String?, changes: Map<String, String?>) {
        val name = id ?: throw DeviceException(ErrorCategory.MissingItem, "wifi/acl holds access points, not settings")
        val old = read().firstOrNull { it.id == name } ?: throw DeviceException(ErrorCategory.MissingItem, "wifi/acl has no $name")
        val addresses = mutableListOf<JsonObject>()
        fun list(part: JsonObject) = nested(listOf("interface", name, "mac", "access-list"), part)
        changes["addresses"]?.let { typed ->
            val now = typed.split(',', ' ', ';').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            now.firstOrNull { !it.matches(MAC) }?.let { throw DeviceException(ErrorCategory.ArgumentValue, "$it is no MAC address") }
            val was = old["addresses"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            was.filter { it !in now }.forEach { addresses += list(JsonObject(mapOf("address" to JsonObject(mapOf("no" to JsonPrimitive(true), "address" to JsonPrimitive(it)))))) }
            now.filter { it !in was }.forEach { addresses += list(JsonObject(mapOf("address" to JsonPrimitive(it)))) }
        }
        // the router goes on with a batch after a MAC it does not know, so the type waits for the addresses to be taken
        if (addresses.isNotEmpty()) api.send(addresses)
        changes["type"]?.ifEmpty { null }?.let { api.send(listOf(list(JsonObject(mapOf("type" to JsonPrimitive(it)))))) }
    }

    private fun addressesOf(list: JsonObject?): List<String> = when (val addresses = list?.get("address")) {
        is JsonArray -> addresses.mapNotNull { (it as? JsonPrimitive)?.content ?: (it as? JsonObject)?.text("address") }
        is JsonPrimitive -> listOf(addresses.content)
        is JsonObject -> listOfNotNull(addresses.text("address"))
        else -> emptyList()
    }

    private companion object {
        val MAC = Regex("([0-9a-f]{2}:){5}[0-9a-f]{2}")
    }
}

// the repeaters of the Wi-Fi system with what the controller knows of them
internal class MwsMembers(private val api: KeeneticCall) : Node("mws/member") {
    override suspend fun read(): List<Item> = rows(api.shows("mws/member").single(), "mac")
}
