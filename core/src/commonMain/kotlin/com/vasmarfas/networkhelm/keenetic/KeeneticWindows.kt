package com.vasmarfas.networkhelm.keenetic

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.model.duration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlin.concurrent.Volatile

// the System Dashboard of the web interface in one request, as it asks for it: the router, its way out with the
// counters of the connection that had the default route at the last read, the ports, the home networks and their access
// points; the ports, networks and access points come as port.<label>.<field>, network.<id>.<field> and ap.<id>.<field>
internal class Dashboard(private val api: KeeneticCall) : Node("status", NodeKind.Settings) {
    @Volatile
    private var counted: String? = null

    @Volatile
    private var radios: List<String> = emptyList()

    override suspend fun read(): List<Item> {
        val paths = listOf("system", "version", "internet/status", "interface", "rc/interface", "ip/hotspot", "ndns", "ip/name-server", "clock/date")
        // what one model or KeeneticOS has and another lacks: a command the router refuses leaves its lines out, not the dashboard
        val optional = listOf("identification", "rc/components/auto-update", "rc/dns-proxy/filter/engine", "rc/ip/http/security-level", "mws/member") + APPS.keys.map { "rc/$it" }
        val known = counted
        val measured = radios
        val requests = (paths + optional).map(::showOf) + TOP + measured.map(::utilizationOf)
        val answers = api.post(requests + listOfNotNull(known?.let { statOf(listOf(it)) }) + CPU_AVERAGE, raising = false)
        fun answer(index: Int) = answers.getOrNull(index)?.takeIf { failure(it) == null }
        failure(JsonArray(answers.take(paths.size)))?.let { throw DeviceException(ErrorCategory.Api, it) }
        val (system, version, internet, shown, config) = paths.take(5).mapIndexed { index, path -> shown(answers.getOrNull(index), path) }
        val (hotspot, ndns, servers, date) = paths.drop(5).mapIndexed { index, path -> shown(answers.getOrNull(index + 5), path) }
        val extra = optional.withIndex().associate { (index, path) -> path to answer(paths.size + index)?.let { shown(it, path) } }
        val top = answer(paths.size + optional.size)?.let { rows(shown(it, "ip/hotspot/summary")) }.orEmpty()
        val loads = measured.withIndex().associate { (index, radio) -> radio to answer(paths.size + optional.size + 1 + index)?.let(::loadOf) }
        val interfaces = interfaces(shown, config)
        val hosts = rows(hotspot, "mac")
        val gateway = interfaces.firstOrNull { it["defaultgw"] == "true" }
        counted = gateway?.id
        val counters = when {
            gateway == null -> null
            gateway.id == known -> statsOf(answers.getOrNull(requests.size), listOf(gateway.id))[gateway.id]
            else -> api.stats(listOf(gateway.id))[gateway.id]
        }.orEmpty()
        // a PPP connection has no MAC of its own: the web interface shows the one of the port it goes through
        val carrier = interfaces.firstOrNull { it.id == gateway?.get("via") }
        val own = (system as? JsonObject).orEmpty()
        val (used, total) = own.text("memory").orEmpty().split('/').map { it.trim().toLongOrNull() } + listOf(null, null)
        val connections = own.text("conntotal")?.toLongOrNull()
        val about = (version as? JsonObject)?.let(::fields).orEmpty()
        val name = (ndns as? JsonObject)?.let(::fields).orEmpty()
        val values = linkedMapOf(
            "model" to about["description"].orEmpty(),
            "firmware" to about["title"].orEmpty(),
            "channel" to about["sandbox"].orEmpty(),
            "hostname" to own.text("hostname").orEmpty(),
            "uptime" to (own.text("uptime")?.toLongOrNull()?.let(::duration) ?: own.text("uptime").orEmpty()),
            "time" to clockOf(date).let { listOfNotNull(it["date"], it["time"]).joinToString(" ") },
            "cpu-load" to (averageOf(answers.lastOrNull()) ?: own.text("cpuload").orEmpty()),
            "used-memory" to (used?.let { "${it * 1024}" }.orEmpty()),
            "total-memory" to (total?.let { "${it * 1024}" }.orEmpty()),
            "connections" to (connections?.let { all -> own.text("connfree")?.toLongOrNull()?.let { "${all - it}" } }).orEmpty(),
            "connections-max" to connections?.toString().orEmpty(),
            "internet" to (internet as? JsonObject)?.let(::fields)?.get("internet").orEmpty(),
            "connection" to gateway?.let(::title).orEmpty(),
            "connection-id" to gateway?.id.orEmpty(),
            "connection-type" to gateway?.get("type").orEmpty(),
            "connected" to gateway?.get("uptime").orEmpty(),
            "address" to gateway?.get("address").orEmpty(),
            "mask" to gateway?.get("mask").orEmpty(),
            "remote" to gateway?.get("remote").orEmpty(),
            "mac" to (gateway?.get("mac") ?: carrier?.get("mac")).orEmpty(),
            "carrier" to carrier?.let(::title).orEmpty(),
            "dns-servers" to rows(servers).mapNotNull { it["address"] }.distinct().joinToString(", "),
            "rxspeed" to counters["rxspeed"].orEmpty(),
            "txspeed" to counters["txspeed"].orEmpty(),
            "rxbytes" to counters["rxbytes"].orEmpty(),
            "txbytes" to counters["txbytes"].orEmpty(),
            "keendns" to listOfNotNull(name["name"]?.ifEmpty { null }, name["domain"]?.ifEmpty { null }).joinToString("."),
            "keendns-access" to name["access"].orEmpty(),
            "wifi" to interfaces.filter { it["type"] == "AccessPoint" && it["state"] == "up" }.joinToString(", ") { ap -> "${ap["ssid"].orEmpty()} (${band(interfaces, ap.id)})" },
            "clients-online" to "${hosts.count { it["active"] == "true" }}",
            "clients-registered" to "${hosts.count { it["registered"] == "true" }}",
        )
        for (port in interfaces.filter { it["type"] == "Port" }) {
            val label = port["label"] ?: port["interface-name"] ?: port.id
            values["port.$label.link"] = port["link"].orEmpty()
            values["port.$label.speed"] = port["speed"].orEmpty()
            values["port.$label.duplex"] = port["duplex"].orEmpty()
            values["port.$label.internet"] = "${"inet" in port["role"].orEmpty()}"
        }
        val online = hosts.filter { it["active"] == "true" }
        for (bridge in interfaces.filter { it["type"] == "Bridge" }) {
            val clients = online.filter { it["interface.id"] == bridge.id }
            values["network.${bridge.id}.name"] = title(bridge)
            values["network.${bridge.id}.address"] = bridge["address"].orEmpty()
            values["network.${bridge.id}.wifi"] = "${clients.count { it["ap"] != null }}"
            values["network.${bridge.id}.wired"] = "${clients.count { it["ap"] == null }}"
        }
        for (ap in interfaces.filter { it["type"] == "AccessPoint" }) {
            values["ap.${ap.id}.ssid"] = ap["ssid"].orEmpty()
            values["ap.${ap.id}.band"] = band(interfaces, ap.id)
            values["ap.${ap.id}.channel"] = interfaces.firstOrNull { it.id == ap.id.substringBefore('/') }?.get("channel").orEmpty()
            values["ap.${ap.id}.security"] = ap["encryption"].orEmpty().ifEmpty { "none" }
            values["ap.${ap.id}.network"] = ap["group"].orEmpty()
            values["ap.${ap.id}.up"] = "${ap["state"] == "up"}"
        }
        val wifi = interfaces.filter { it["type"] == "WifiMaster" }
        // a radio the last read did not know is measured at once, as the counters of a new connection are
        val unmeasured = wifi.map { it.id }.filter { it !in measured }
        val fresh = if (unmeasured.isEmpty()) emptyMap() else unmeasured.zip(api.post(unmeasured.map(::utilizationOf), raising = false)).associate { (radio, answer) -> radio to answer.takeIf { failure(it) == null }?.let(::loadOf) }
        for (radio in wifi) {
            values["radio.${radio.id}.band"] = band(interfaces, radio.id)
            values["radio.${radio.id}.channel"] = radio["channel"].orEmpty()
            values["radio.${radio.id}.width"] = radio["bandwidth"].orEmpty()
            values["radio.${radio.id}.temperature"] = radio["temperature"].orEmpty()
            values["radio.${radio.id}.clients"] = "${online.count { it["ap"]?.startsWith("${radio.id}/") == true }}"
            values["radio.${radio.id}.load"] = (loads[radio.id] ?: fresh[radio.id]).orEmpty()
            values["radio.${radio.id}.up"] = "${radio["state"] == "up"}"
        }
        radios = wifi.map { it.id }
        for ((index, host) in top.withIndex()) {
            values["top.$index.name"] = host["name"]?.ifEmpty { null } ?: host["mac"].orEmpty()
            values["top.$index.rx"] = host["rxbytes"].orEmpty()
            values["top.$index.tx"] = host["txbytes"].orEmpty()
        }
        (extra["identification"] as? JsonObject)?.let(::fields)?.let {
            values["service-tag"] = it["servicetag"].orEmpty()
            values["serial"] = it["serial"].orEmpty()
        }
        extra["rc/components/auto-update"]?.let { values["auto-update"] = "${(it as? JsonObject)?.let(::fields)?.get("disable") != "true"}" }
        extra["rc/dns-proxy/filter/engine"]?.let { values["content-filter"] = (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
        extra["rc/ip/http/security-level"]?.let { values["remote-access"] = "${(it as? JsonObject)?.containsKey("public") == true}" }
        extra["mws/member"]?.let { values["extenders"] = "${rows(it).size}" }
        for ((section, name) in APPS) {
            val app = extra["rc/$section"] as? JsonObject ?: continue
            values["app.$section.name"] = name
            values["app.$section.enabled"] = "${fields(app)["config.enable"] == "true"}"
        }
        return listOf(Item("", values))
    }
}

// the servers that the System Dashboard of the web interface shows among its applications, by their sections in show rc
private val APPS = mapOf("wireguard-server" to "WireGuard VPN", "oc-server" to "OpenConnect VPN")

// the hosts that sent and took the most in the last three minutes, asked as the card of the web interface asks
private val TOP = JsonObject(mapOf("show" to nested(listOf("ip", "hotspot", "summary"), JsonObject(mapOf("attribute" to JsonPrimitive("sumbytes"), "detail" to JsonPrimitive(0), "count" to JsonPrimitive(5))))))

private fun utilizationOf(radio: String) =
    JsonObject(mapOf("show" to nested(listOf("interface", "channel-utilization", "chart"), JsonObject(mapOf("name" to JsonPrimitive(radio), "detail" to JsonPrimitive(0))))))

// the load of the channel, in percent, as the radio measured it last
private fun loadOf(answer: JsonElement): String? {
    val data = (shown(answer, "interface/channel-utilization/chart") as? JsonObject)?.get("data") as? JsonArray
    return ((data?.lastOrNull() as? JsonObject)?.get("v") as? JsonObject)?.text("load")
}

// what the web interface lists under Internet: connections with a role or a level of the internet, and tunnels; its pages
// Ethernet Cable, Mobile and Wireless ISP take those of their types and change them as those pages do. A row is one
// interface: the PPP tunnel of the provider is a row of its own, made, swapped and removed from the row it goes through
internal class Connections(
    private val api: KeeneticCall,
    path: String = "internet",
    components: Set<String>? = null,
    private val words: () -> Map<String, String> = { emptyMap() },
    private val types: (String) -> Boolean = { true },
) : Node(path) {
    override val commands = listOf("print", "set")

    private val eap = components == null || "dot1x" in components
    private val checks = components == null || "pingcheck" in components

    override suspend fun arguments(): List<Argument> {
        val (schedules, config) = api.shows("rc/schedule", "rc/interface")
        val described = scheduleNames(schedules)
        val global = (config as? JsonObject).orEmpty().values.count { inside(it, "ip", "global") != null }
        val orders = listOf(UNORDERED) + (0..global).map { "$it" }
        val backup = words()["Backup"] ?: "Backup"
        val numbers = if (types(WISP)) channels() else emptyList()
        val addressed = listOf(ON_IPOE, ON_WISP)
        fun ipv4In(mode: String) = addressed.map { it + ("ipv4" to mode) }
        fun ipv6In(mode: String) = addressed.map { it + ("ipv6" to mode) }
        val account = listOf(ON_PPP, ON_MOBILE) + ISP_TUNNELS.map { ON_IPOE + ("tunnel" to it) }
        val pppoe = listOf(mapOf("type" to "PPPoE"), ON_IPOE + ("tunnel" to "PPPoE"))
        return buildList<Argument> {
            add(Argument("name", ValueType.Text(), readOnly = true))
            add(Argument("description", ValueType.Text(), optional = true))
            add(Argument("disabled", ValueType.YesNo))
            add(Argument("schedule", ValueType.Enumeration(closed = true), listOf("") + described.keys, optional = true, labels = described))
            add(Argument("order", ValueType.Enumeration(closed = true), orders, labels = orders.associateWith { ORDERS[it] ?: "$backup $it" }))
            add(Argument("ipv4", ValueType.Enumeration(closed = true), IPV4_MODES.keys.toList(), labels = IPV4_MODES, depends = addressed))
            add(Argument("ip-address", ValueType.Ip4, depends = ipv4In("manual")))
            add(Argument("ip-mask", ValueType.Ip4, depends = ipv4In("manual")))
            add(Argument("ip-gateway", ValueType.Ip4, optional = true, depends = ipv4In("manual")))
            add(Argument("dns", ValueType.Text(), optional = true, depends = ipv4In(AUTO) + ipv4In("manual") + ON_PPP))
            add(Argument("ignore-dns", ValueType.YesNo, depends = ipv4In(AUTO) + ON_PPP))
            add(Argument("ipv6", ValueType.Enumeration(closed = true), IPV6_MODES.keys.toList(), labels = IPV6_MODES, depends = addressed))
            add(Argument("ipv6-address", ValueType.Text(), depends = ipv6In("manual")))
            add(Argument("ipv6-prefix", ValueType.Text(), optional = true, depends = ipv6In("manual")))
            add(Argument("ipv6-gateway", ValueType.Ip6, optional = true, depends = ipv6In("manual")))
            add(Argument("dns6", ValueType.Text(), optional = true, depends = ipv6In(AUTO) + ipv6In("manual")))
            add(Argument("ignore-dns6", ValueType.YesNo, depends = ipv6In(AUTO)))
            add(Argument("mac-mode", ValueType.Enumeration(closed = true), MAC_MODES.keys.toList(), labels = MAC_MODES, depends = listOf(ON_IPOE)))
            add(Argument("mac-address", ValueType.Mac, depends = listOf(ON_IPOE + ("mac-mode" to "manual"))))
            add(Argument("hostname", ValueType.Text(), optional = true, depends = listOf(ON_IPOE)))
            add(Argument("ip-mtu", ValueType.Integer(576, 9000), optional = true, depends = listOf(ON_IPOE, ON_PPP)))
            add(Argument("tunnel", ValueType.Enumeration(closed = true), listOf(NO_TUNNEL) + ISP_TUNNELS, labels = mapOf(NO_TUNNEL to "Do not use"), depends = listOf(ON_IPOE)))
            add(Argument("user", ValueType.Text(), optional = true, depends = account))
            add(Argument("password", ValueType.Secret, optional = true, depends = account))
            add(Argument("authentication", ValueType.Enumeration(closed = true), PPP_AUTH.keys.toList(), labels = PPP_AUTH, depends = account))
            add(Argument("server", ValueType.Text(), optional = true, depends = listOf("PPTP", "L2TP", "SSTP").map { mapOf("type" to it) } + listOf("PPTP", "L2TP").map { ON_IPOE + ("tunnel" to it) }))
            add(Argument("concentrator", ValueType.Text(), optional = true, depends = pppoe))
            add(Argument("service", ValueType.Text(), optional = true, depends = pppoe))
            if (eap) {
                val methods = DOT1X_FLAGS.keys.map { ON_IPOE + ("dot1x" to it) }
                add(Argument("dot1x", ValueType.Enumeration(closed = true), DOT1X.keys.toList(), labels = DOT1X, depends = listOf(ON_IPOE)))
                add(Argument("dot1x-user", ValueType.Text(), depends = methods))
                add(Argument("dot1x-password", ValueType.Secret, depends = methods))
            }
            add(Argument("ssid", ValueType.Text(), depends = listOf(ON_WISP)))
            add(Argument("security", ValueType.Enumeration(closed = true), STATION_SECURITY.keys.filter { it != "wep" }, labels = STATION_SECURITY, depends = listOf(ON_WISP)))
            add(Argument("psk", ValueType.Secret, optional = true, depends = PSK_SECURITY.map { ON_WISP + ("security" to it) }))
            add(Argument("channel", ValueType.Enumeration(), listOf(AUTO_CHANNEL) + numbers, labels = mapOf(AUTO_CHANNEL to "Auto"), depends = listOf(ON_WISP)))
            add(Argument("bssid", ValueType.Mac, optional = true, depends = listOf(ON_WISP)))
            add(Argument("connection-type", ValueType.Enumeration(closed = true), CONNECTION_TYPES.keys.toList(), labels = CONNECTION_TYPES, depends = listOf(ON_WISP)))
            add(Argument("idle-timeout", ValueType.Integer(1, unit = "s"), optional = true, depends = listOf(ON_WISP + ("connection-type" to ON_DEMAND))))
            add(Argument("ttl-in", ValueType.Integer(1, 255), optional = true, depends = listOf(ON_WISP)))
            add(Argument("ttl-out", ValueType.Integer(1, 255), optional = true, depends = listOf(ON_WISP)))
            add(Argument("apn", ValueType.Text(), optional = true, depends = listOf(ON_MOBILE)))
            add(Argument("network-type", ValueType.Enumeration(closed = true), NETWORKS.keys.toList(), labels = NETWORKS, depends = listOf(ON_MOBILE)))
            add(Argument("roaming", ValueType.YesNo, depends = listOf(ON_MOBILE)))
            if (checks) {
                val probing = PROBES.map { mapOf("ping-check" to it) }
                add(Argument("ping-check", ValueType.Enumeration(closed = true), CHECK_MODES.keys.toList(), labels = CHECK_MODES, depends = listOf(ON_IPOE, ON_WISP, ON_MOBILE)))
                add(Argument("ping-check-host", ValueType.Text(), depends = probing))
                add(Argument("ping-check-port", ValueType.Integer(1, 65535), depends = listOf(mapOf("ping-check" to "connect"))))
                add(Argument("ping-check-interval", ValueType.Integer(3, 3600, unit = "s"), default = "10", depends = probing))
                add(Argument("ping-check-fails", ValueType.Integer(1), default = "5", depends = probing))
            }
        }
    }

    override suspend fun read(): List<Item> {
        val answers = api.post(READS.map(::showOf), raising = false)
        failure(JsonArray(answers.take(2)))?.let { throw DeviceException(ErrorCategory.Api, it) }
        val (state, config, profiles) = READS.mapIndexed { index, asked -> shown(answers.getOrNull(index)?.takeIf { failure(it) == null }, asked) }
        val all = interfaces(state, config)
        val rows = all.filter { connection(it) && types(it["type"].orEmpty()) }
        val counters = api.stats(rows.map { it.id })
        val own = config as? JsonObject ?: JsonObject(emptyMap())
        return rows.map { row ->
            Item(
                row.id,
                linkedMapOf(
                    "name" to row.id,
                    "title" to title(row),
                    "description" to row["description"].orEmpty(),
                    "type" to row["type"].orEmpty(),
                    "connected" to row["connected"].orEmpty(),
                    "disabled" to row["disabled"].orEmpty(),
                    "address" to row["address"].orEmpty(),
                    "mask" to row["mask"].orEmpty(),
                    "uptime" to row["uptime"].orEmpty(),
                    "default" to (row["defaultgw"] ?: "false"),
                    "priority" to row["priority"].orEmpty(),
                    "security-level" to row["security-level"].orEmpty(),
                    "mtu" to row["mtu"].orEmpty(),
                    "mac" to row["mac"].orEmpty(),
                ) + settings(row, all, own, profiles) + counters[row.id].orEmpty(),
            )
        }
    }

    override suspend fun set(id: String?, changes: Map<String, String?>) {
        val name = id ?: throw DeviceException(ErrorCategory.MissingItem, "$path holds elements, not settings")
        val old = read().firstOrNull { it.id == name } ?: throw DeviceException(ErrorCategory.MissingItem, "$path has no $name")
        val (state, rc) = api.shows("interface", "rc/interface")
        val config = rc as? JsonObject ?: JsonObject(emptyMap())
        val changed = buildList<JsonObject> {
            interfaceChange(name, changes.filterKeys { it == "description" || it == "disabled" })?.let { add(it) }
            changes["schedule"]?.let { add(interfaceSetting(name, "schedule", noIfEmpty(it))) }
            changes["order"]?.let { add(interfaceSetting(name, "ip.global", ordered(it))) }
            if (old[KIND] == IPOE || old[KIND] == STATION) addAll(addressing(name, changes, old))
            changes["dns"]?.let { add(interfaceSetting(name, "ip.name-server", nameServers(it, ported = true))) }
            changes["ignore-dns"]?.let { add(interfaceSetting(name, "ip.name-servers", JsonPrimitive(it !in ValueType.Flag.on))) }
            changes["dns6"]?.let { add(interfaceSetting(name, "ipv6.name-server", nameServers(it, ported = false))) }
            changes["ignore-dns6"]?.let { add(interfaceSetting(name, "ipv6.name-servers", JsonPrimitive(it !in ValueType.Flag.on))) }
            changes["hostname"]?.let { add(interfaceSetting(name, "ip.dhcp.client.hostname", JsonPrimitive(it))) }
            changes["ip-mtu"]?.let { add(interfaceSetting(name, "ip.mtu", noIfEmpty(it))) }
            addAll(mac(name, changes, old))
            addAll(dot1x(name, changes, old))
            when (old[KIND]) {
                IPOE -> addAll(tunnel(name, changes, old, interfaces(state, config), config))
                PPP -> addAll(ppp(name, changes, old))
                STATION -> addAll(station(name, changes, old))
                MOBILE -> addAll(modem(name, changes, old, config))
            }
            addAll(pingCheck(name, changes, old, config))
        }
        api.send(changed)
    }

    // the settings of a row by the names of the fields of the page of its type
    private fun settings(row: Item, all: List<Item>, config: JsonObject, profiles: JsonElement): Map<String, String> {
        val own = config[row.id] as? JsonObject ?: JsonObject(emptyMap())
        val values = fields(own)
        val kind = kindOf(row["type"].orEmpty())
        val result = linkedMapOf(KIND to kind, "schedule" to values["schedule"].orEmpty(), "order" to (values["ip.global.order"] ?: UNORDERED))
        if (kind == IPOE || kind == STATION) result += addressingOf(own, values)
        when (kind) {
            IPOE -> {
                result["mac-mode"] = if (values["mac.address.mac"] != null) "manual" else "default"
                result["mac-address"] = values["mac.address.mac"].orEmpty()
                result["hostname"] = values["ip.dhcp.client.hostname"].orEmpty()
                result["ip-mtu"] = values["ip.mtu"].orEmpty()
                val tunnel = tunnelOf(row.id, all, config)
                result["tunnel"] = tunnel?.get("type") ?: NO_TUNNEL
                if (tunnel != null) result += pppOf(config[tunnel.id] as? JsonObject ?: JsonObject(emptyMap()), tunnel["type"].orEmpty())
                if (eap) {
                    val authentication = own["authentication"] as? JsonObject
                    result["dot1x"] = DOT1X_FLAGS.entries.firstOrNull { (_, flags) -> flags.all { flagged(authentication, it) } }?.key ?: NONE
                    result["dot1x-user"] = authentication?.text("identity").orEmpty()
                    result["dot1x-password"] = authentication?.text("password").orEmpty()
                }
            }
            PPP -> {
                result += pppOf(own, row["type"].orEmpty())
                result["dns"] = nameServersOf(inside(own, "ip", "name-server"), ported = true)
                result["ignore-dns"] = "${values["ip.name-servers"] == "false"}"
                result["ip-mtu"] = values["ip.mtu"].orEmpty()
            }
            STATION -> {
                val radio = fields(config[row.id.substringBefore('/')] as? JsonObject ?: JsonObject(emptyMap()))
                result["ssid"] = values["ssid"].orEmpty()
                result["security"] = stationSecurity(own["encryption"] as? JsonObject)
                result["psk"] = values["authentication.wpa-psk.psk"].orEmpty()
                result["channel"] = radio["channel.channel"]?.takeIf { it != "false" } ?: AUTO_CHANNEL
                result["bssid"] = values["mac.bssid"].orEmpty()
                result["connection-type"] = if (values["standby.enable"] == "true") ON_DEMAND else ALWAYS_ON
                result["idle-timeout"] = values["standby.timeout.seconds"].orEmpty()
                result["ttl-in"] = values["ip.adjust-ttl.recv"].orEmpty()
                result["ttl-out"] = values["ip.adjust-ttl.send"].orEmpty()
            }
            MOBILE -> {
                result += accountOf(own)
                result["apn"] = values["mobile.apn.apn"].orEmpty()
                result["network-type"] = values["mobile.acq"] ?: AUTO
                result["roaming"] = "${values["mobile.roaming"] == "true"}"
            }
        }
        if (checks && kind != PPP && kind != VPN) result += pingCheckOf(values["ping-check.profile"].orEmpty(), profiles)
        return result
    }

    // IPv4 and IPv6 as the web interface reads them: DHCP where the address has it, a manual address, or neither
    private fun addressingOf(own: JsonObject, values: Map<String, String>): Map<String, String> {
        val address = inside(own, "ip", "address") as? JsonObject
        val addresses6 = objectsOf(inside(own, "ipv6", "address"))
        val manual6 = addresses6.firstNotNullOfOrNull { it.text("block") ?: it.text("address") }
        return linkedMapOf(
            "ipv4" to when {
                address?.containsKey("dhcp") == true -> AUTO
                address?.text("address") != null -> "manual"
                else -> "disabled"
            },
            "ip-address" to address?.text("address").orEmpty(),
            "ip-mask" to address?.text("mask").orEmpty(),
            "ip-gateway" to values["ip.gateway"].orEmpty(),
            "dns" to nameServersOf(inside(own, "ip", "name-server"), ported = true),
            "ignore-dns" to "${values["ip.name-servers"] == "false"}",
            "ipv6" to when {
                addresses6.any { flagged(it, AUTO) } -> AUTO
                manual6 != null -> "manual"
                else -> "disabled"
            },
            "ipv6-address" to manual6.orEmpty(),
            "ipv6-prefix" to objectsOf(inside(own, "ipv6", "prefix")).firstNotNullOfOrNull { it.text("prefix") }.orEmpty(),
            "ipv6-gateway" to values["ipv6.gateway"].orEmpty(),
            "dns6" to nameServersOf(inside(own, "ipv6", "name-server"), ported = false),
            "ignore-dns6" to "${values["ipv6.name-servers"] == "false"}",
        )
    }

    private fun accountOf(own: JsonObject): Map<String, String> {
        val authentication = own["authentication"] as? JsonObject
        return linkedMapOf(
            "user" to authentication?.text("identity").orEmpty(),
            "password" to authentication?.text("password").orEmpty(),
            "authentication" to (AUTH_FLAGS.firstOrNull { flagged(authentication, it) } ?: AUTO),
        )
    }

    // the peer of PPPoE is the name of the concentrator, that of the others the address of the server
    private fun pppOf(own: JsonObject, type: String): Map<String, String> =
        accountOf(own) + mapOf((if (type == "PPPoE") "concentrator" else "server") to own.text("peer").orEmpty(), "service" to fields(own)["pppoe.service"].orEmpty())

    // the Ping Check of a connection: none, the default profile of the router, or a profile of its own with the mode it checks by
    private fun pingCheckOf(profile: String, profiles: JsonElement): Map<String, String> {
        val own = inside(profiles, "profile", profile) as? JsonObject
        val values = own?.let(::fields).orEmpty()
        return linkedMapOf(
            "ping-check" to when (profile) {
                "" -> NONE
                DEFAULT_CHECK -> DEFAULT_CHECK
                else -> own?.text("mode")?.takeIf { it in PROBES } ?: NONE
            },
            "ping-check-host" to values["host"].orEmpty().substringBefore(','),
            "ping-check-port" to values["port"].orEmpty(),
            "ping-check-interval" to values["update-interval.seconds"].orEmpty(),
            "ping-check-fails" to values["max-fails.count"].orEmpty(),
        )
    }

    // the PPP tunnel of the provider that goes through a connection, as the web interface finds it
    private fun tunnelOf(id: String, all: List<Item>, config: JsonObject): Item? = all.firstOrNull { tunnel ->
        val own = config[tunnel.id]
        tunnel["type"] in ISP_TUNNELS && (inside(own, "connect", "via") as? JsonPrimitive)?.contentOrNull == id && rolesOf(own).let { it.isEmpty() || "inet" in it }
    }

    // the web interface writes a manual address after taking the old one off, and DHCP as a flag of an address it takes off
    private fun addressing(name: String, changes: Map<String, String?>, old: Item): List<JsonObject> = buildList {
        fun value(key: String) = changes[key] ?: old[key].orEmpty()
        val cleared = interfaceSetting(name, "ip.address", JsonObject(NO + ("dhcp" to JsonPrimitive(false))))
        if (listOf("ipv4", "ip-address", "ip-mask").any { it in changes }) {
            when (val mode = value("ipv4")) {
                AUTO -> add(interfaceSetting(name, "ip.address", JsonObject(NO + ("dhcp" to JsonPrimitive(true)))))
                "disabled" -> add(cleared)
                "manual" -> {
                    val address = value("ip-address")
                    val mask = value("ip-mask")
                    if (address.isEmpty() || mask.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "a manual IPv4 address needs its mask")
                    add(cleared)
                    add(interfaceSetting(name, "ip.address", JsonObject(mapOf("dhcp" to JsonPrimitive(false), "address" to JsonPrimitive(address), "mask" to JsonPrimitive(mask)))))
                }
                else -> throw DeviceException(ErrorCategory.ArgumentValue, "$mode is no IPv4 setting of Keenetic")
            }
        }
        if (value("ipv4") == "manual" && ("ip-gateway" in changes || "ipv4" in changes)) add(interfaceSetting(name, "ip.gateway", noIfEmpty(value("ip-gateway"))))
        if (listOf("ipv6", "ipv6-address", "ipv6-prefix", "ipv6-gateway").any { it in changes }) addAll(ipv6(name, value("ipv6"), changes, old))
    }

    // the lists of IPv6 addresses and prefixes go anew, auto or the one given; turned off, the web interface clears what was set
    private fun ipv6(name: String, mode: String, changes: Map<String, String?>, old: Item): List<JsonObject> = buildList {
        fun value(key: String) = changes[key] ?: old[key].orEmpty()
        val auto = JsonArray(listOf(NO, JsonObject(mapOf(AUTO to JsonPrimitive(true)))))
        val prefixed = old["ipv6"] == AUTO || old["ipv6-prefix"].orEmpty().isNotEmpty()
        val gateway = value("ipv6-gateway").takeIf { it != old["ipv6-gateway"].orEmpty() }
        when (mode) {
            AUTO -> {
                add(interfaceSetting(name, "ipv6.address", auto))
                add(interfaceSetting(name, "ipv6.prefix", auto))
                if (old["ipv6-gateway"].orEmpty().isNotEmpty()) add(interfaceSetting(name, "ipv6.gateway", NO))
            }
            "manual" -> {
                val address = value("ipv6-address").ifEmpty { throw DeviceException(ErrorCategory.ArgumentValue, "a manual IPv6 setting needs its address") }
                val prefix = value("ipv6-prefix")
                add(interfaceSetting(name, "ipv6.address", JsonArray(listOf(NO, JsonObject(mapOf((if ('/' in address) "block" else "address") to JsonPrimitive(address)))))))
                if (prefix.isNotEmpty()) add(interfaceSetting(name, "ipv6.prefix", JsonArray(listOf(NO, JsonObject(mapOf("prefix" to JsonPrimitive(prefix)))))))
                else if (prefixed) add(interfaceSetting(name, "ipv6.prefix", NO))
                gateway?.let { add(interfaceSetting(name, "ipv6.gateway", noIfEmpty(it))) }
            }
            "disabled" -> {
                if (old["ipv6"] != "disabled") add(interfaceSetting(name, "ipv6.address", NO))
                if (prefixed) add(interfaceSetting(name, "ipv6.prefix", NO))
                if (old["ipv6-gateway"].orEmpty().isNotEmpty()) add(interfaceSetting(name, "ipv6.gateway", NO))
                if (old["dns6"].orEmpty().isNotEmpty() && "dns6" !in changes) add(interfaceSetting(name, "ipv6.name-server", NO))
                if (old["ignore-dns6"] == "true" && "ignore-dns6" !in changes) add(interfaceSetting(name, "ipv6.name-servers", JsonPrimitive(true)))
            }
            else -> throw DeviceException(ErrorCategory.ArgumentValue, "$mode is no IPv6 setting of Keenetic")
        }
    }

    private fun mac(name: String, changes: Map<String, String?>, old: Item): List<JsonObject> {
        if ("mac-mode" !in changes && "mac-address" !in changes) return emptyList()
        val command = when (val mode = changes["mac-mode"] ?: old["mac-mode"].orEmpty()) {
            "manual" -> {
                val mac = (changes["mac-address"] ?: old["mac-address"].orEmpty()).lowercase()
                if (!mac.matches(MAC)) throw DeviceException(ErrorCategory.ArgumentValue, "$mac is no MAC address")
                interfaceSetting(name, "mac.address", JsonObject(mapOf("mac" to JsonPrimitive(mac))))
            }
            "clone" -> interfaceSetting(name, "mac.clone", JsonObject(emptyMap()))
            "default" -> interfaceSetting(name, "mac.address", NO)
            else -> throw DeviceException(ErrorCategory.ArgumentValue, "$mode is no way of Keenetic to take a MAC address")
        }
        return listOf(command)
    }

    // 802.1x is the authentication of the connection itself, each method a set of flags beside the account
    private fun dot1x(name: String, changes: Map<String, String?>, old: Item): List<JsonObject> {
        if (listOf("dot1x", "dot1x-user", "dot1x-password").none { it in changes }) return emptyList()
        val method = changes["dot1x"] ?: old["dot1x"].orEmpty()
        if (method == NONE) return listOf(interfaceSetting(name, "authentication", NO))
        val flags = DOT1X_FLAGS[method] ?: throw DeviceException(ErrorCategory.ArgumentValue, "$method is no 802.1x method of Keenetic")
        val user = changes["dot1x-user"] ?: old["dot1x-user"].orEmpty()
        val password = changes["dot1x-password"]?.ifEmpty { null } ?: old["dot1x-password"].orEmpty()
        if (user.isEmpty() || password.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "802.1x needs a user and a password")
        return listOf(interfaceSetting(name, "authentication", JsonObject(mapOf("identity" to JsonPrimitive(user), "password" to JsonPrimitive(password)) + flags.associateWith { JsonPrimitive(true) })))
    }

    // another type of tunnel is another interface through this connection, which takes its place in the order of
    // connections; with no tunnel left the connection takes that place back
    private fun tunnel(name: String, changes: Map<String, String?>, old: Item, all: List<Item>, config: JsonObject): List<JsonObject> {
        val current = tunnelOf(name, all, config)
        val type = changes["tunnel"]
        if (type == null || type == (current?.get("type") ?: NO_TUNNEL)) return current?.let { ppp(it.id, changes, old) }.orEmpty()
        if (type != NO_TUNNEL && type !in ISP_TUNNELS) throw DeviceException(ErrorCategory.ArgumentValue, "$type is no tunnel of a provider")
        val own = (changes["order"] ?: old["order"])?.takeIf { it != UNORDERED }
        val inherited = current?.let { fields(config[it.id] as? JsonObject ?: JsonObject(emptyMap()))["ip.global.order"] }
        return buildList {
            current?.let { add(JsonObject(mapOf("parse" to JsonPrimitive("no interface ${it.id}")))) }
            if (type == NO_TUNNEL) {
                if (own == null && inherited != null) add(interfaceSetting(name, "ip.global", ordered(inherited)))
                return@buildList
            }
            val given = changes.filterValues { !it.isNullOrEmpty() }
            if (type != "PPPoE" && given["server"] == null) throw DeviceException(ErrorCategory.ArgumentValue, "a $type tunnel needs the address of its server")
            val made = generateSequence(0) { it + 1 }.map { "$type$it" }.first { free -> all.none { it.id == free } }
            val parts = mapOf(
                "description" to JsonPrimitive((changes["description"] ?: old["description"]).orEmpty().ifEmpty { made }),
                "connect" to JsonObject(mapOf("via" to JsonPrimitive(name))),
                "role" to JsonArray(listOf(JsonPrimitive("inet"))),
                "ipcp" to JsonObject(mapOf("address" to JsonPrimitive(true))),
                "up" to JsonPrimitive(true),
            )
            add(nested(listOf("interface", made), JsonObject(parts)))
            addAll(ppp(made, given, Item(made, emptyMap())))
            (inherited ?: own)?.let { order ->
                add(interfaceSetting(made, "ip.global", ordered(order)))
                if (old["order"] != UNORDERED || "order" in changes) add(interfaceSetting(name, "ip.global", NO))
            }
        }
    }

    private fun ppp(target: String, changes: Map<String, String?>, old: Item): List<JsonObject> = account(target, changes, old) + listOfNotNull(
        (changes["server"] ?: changes["concentrator"])?.let { interfaceSetting(target, "peer", noIfEmpty(it)) },
        changes["service"]?.let { interfaceSetting(target, "pppoe.service", noIfEmpty(it)) },
    )

    // the user and the password go together with the protocol, as the router keeps them; a password left empty stays as it was
    private fun account(target: String, changes: Map<String, String?>, old: Item): List<JsonObject> {
        if (listOf("user", "password", "authentication").none { it in changes }) return emptyList()
        val user = changes["user"] ?: old["user"].orEmpty()
        val password = changes["password"]?.ifEmpty { null } ?: old["password"].orEmpty()
        val type = changes["authentication"]?.ifEmpty { null } ?: old["authentication"]?.ifEmpty { null } ?: AUTO
        if (type !in PPP_AUTH) throw DeviceException(ErrorCategory.ArgumentValue, "$type is no authentication of Keenetic")
        if (user.isEmpty()) return if (old["user"].isNullOrEmpty()) emptyList() else listOf(interfaceSetting(target, "authentication", JsonObject(mapOf("identity" to NO, "password" to NO) + NO)))
        if (password.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "the account of $target needs its password")
        return listOf(interfaceSetting(target, "authentication", JsonObject(mapOf("identity" to JsonPrimitive(user), "password" to JsonPrimitive(password)) + AUTH_FLAGS.associateWith { JsonPrimitive(it == type) })))
    }

    // the channel of a station is that of its radio, which the access points of the radio share
    private fun station(name: String, changes: Map<String, String?>, old: Item): List<JsonObject> = buildList {
        fun value(key: String) = changes[key] ?: old[key].orEmpty()
        changes["ssid"]?.let { add(interfaceSetting(name, "ssid", noIfEmpty(it))) }
        if ("security" in changes) {
            add(nested(listOf("interface", name), stationEncryption(value("security"), changes["psk"]?.ifEmpty { null } ?: old["psk"].orEmpty())))
        } else {
            changes["psk"]?.let { add(interfaceSetting(name, "authentication.wpa-psk", if (it.isEmpty()) NO else JsonObject(mapOf("psk" to JsonPrimitive(it))))) }
        }
        changes["channel"]?.let { add(interfaceSetting(name.substringBefore('/'), "channel.channel", if (it.isEmpty() || it == AUTO_CHANNEL) JsonPrimitive(false) else JsonPrimitive(it))) }
        changes["bssid"]?.let { add(interfaceSetting(name, "mac.bssid", noIfEmpty(it.lowercase()))) }
        if ("connection-type" in changes || "idle-timeout" in changes) {
            val type = value("connection-type")
            if (type !in CONNECTION_TYPES) throw DeviceException(ErrorCategory.ArgumentValue, "$type is no connection type of Wireless ISP")
            val timeout = value("idle-timeout")
            add(interfaceSetting(name, "standby", JsonObject(mapOf("enable" to JsonPrimitive(type == ON_DEMAND), "timeout" to if (timeout.isEmpty()) NO else JsonObject(mapOf("seconds" to JsonPrimitive(timeout)))))))
        }
        changes["ttl-in"]?.let { add(interfaceSetting(name, "ip.adjust-ttl.recv", noIfEmpty(it))) }
        changes["ttl-out"]?.let { add(interfaceSetting(name, "ip.adjust-ttl.send", noIfEmpty(it))) }
    }

    // the comment of the APN names the country and the operator the web interface chose it by, so it stays
    private fun modem(name: String, changes: Map<String, String?>, old: Item, config: JsonObject): List<JsonObject> = account(name, changes, old) + listOfNotNull(
        changes["apn"]?.let { apn ->
            val comment = fields(config[name] as? JsonObject ?: JsonObject(emptyMap()))["mobile.apn.comment"].orEmpty()
            interfaceSetting(name, "mobile.apn", if (apn.isEmpty()) NO else JsonObject(mapOf("apn" to JsonPrimitive(apn), "comment" to JsonPrimitive(comment))))
        },
        changes["network-type"]?.let { type ->
            if (type !in NETWORKS) throw DeviceException(ErrorCategory.ArgumentValue, "$type is no network type of a modem")
            interfaceSetting(name, "mobile.acq", if (type == AUTO) NO else JsonPrimitive(type))
        },
        changes["roaming"]?.let { interfaceSetting(name, "mobile.roaming", JsonPrimitive(it in ValueType.Flag.on)) },
    )

    // a check of its own is a profile named after the connection, as the web interface names it; a profile no other connection
    // takes goes away with the check
    private fun pingCheck(name: String, changes: Map<String, String?>, old: Item, config: JsonObject): List<JsonObject> {
        if (changes.keys.none { it.startsWith("ping-check") }) return emptyList()
        fun value(key: String) = changes[key] ?: old[key].orEmpty()
        fun profileOf(element: JsonElement?) = (inside(element, "ping-check", "profile") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val current = profileOf(config[name]).takeIf { it.isNotEmpty() && it != DEFAULT_CHECK }
        val dropped = current?.takeIf { profile -> config.none { (other, element) -> other != name && profileOf(element) == profile } }
            ?.let { nested(listOf("ping-check", "profile"), JsonObject(mapOf("name" to JsonPrimitive(it), "no" to JsonPrimitive(true)))) }
        return when (val mode = value("ping-check")) {
            NONE -> listOfNotNull(interfaceSetting(name, "ping-check.profile", NO), dropped)
            DEFAULT_CHECK -> listOfNotNull(interfaceSetting(name, "ping-check.profile", JsonPrimitive(DEFAULT_CHECK)), dropped)
            in PROBES -> {
                val profile = current ?: "_WEBADMIN_$name"
                val host = value("ping-check-host").ifEmpty { throw DeviceException(ErrorCategory.ArgumentValue, "Ping Check needs the address or the name it checks") }
                val port = if (mode != "connect") NO else JsonPrimitive(value("ping-check-port").ifEmpty { throw DeviceException(ErrorCategory.ArgumentValue, "a check of a TCP port needs the port") })
                val body = mapOf(
                    "name" to JsonPrimitive(profile),
                    "host" to JsonArray(listOf(JsonPrimitive(host))),
                    "update-interval" to JsonObject(mapOf("seconds" to JsonPrimitive(value("ping-check-interval").ifEmpty { "10" }))),
                    "mode" to JsonPrimitive(mode),
                    "max-fails" to JsonObject(mapOf("count" to JsonPrimitive(value("ping-check-fails").ifEmpty { "5" }))),
                    "port" to port,
                )
                listOf(nested(listOf("ping-check", "profile"), JsonObject(body)), interfaceSetting(name, "ping-check.profile", JsonPrimitive(profile)))
            }
            else -> throw DeviceException(ErrorCategory.ArgumentValue, "$mode is no mode of Ping Check")
        }
    }

    // the channels the radios take, which the page offers a station of Wireless ISP
    private suspend fun channels(): List<String> {
        val radios = interfaces(api.shows("interface").single(), JsonObject(emptyMap())).filter { it["type"] == "WifiMaster" }
        if (radios.isEmpty()) return emptyList()
        val asked = radios.map { JsonObject(mapOf("show" to nested(listOf("interface", "channels"), JsonObject(mapOf("name" to JsonPrimitive(it.id)))))) }
        return api.post(asked, raising = false).flatMap { answer -> rows(shown(answer.takeIf { failure(it) == null }, "interface/channels")).mapNotNull { it["number"] } }
            .distinct().sortedBy { it.toIntOrNull() }
    }

    private fun connection(row: Item): Boolean {
        val type = row["type"].orEmpty()
        if (type in setOf("Port", "Bridge", "AccessPoint", "WifiMaster", "FastEthernet", "GigabitEthernet")) return false
        return type in tunnels || "inet" in row["role"].orEmpty() || row["global"] == "true" || row["security-level"] == "public"
    }

    private companion object {
        val tunnels = setOf("PPPoE", "PPTP", "L2TP", "SSTP", "OpenVPN", "Wireguard", "IKE", "OpenConnect", "UsbLte", "UsbModem", "UsbQmi", "Yota", "WifiStation")
    }
}

// the kind of a connection the fields of its page depend on; a name with a dot is no field of a form
private const val KIND = ".kind"
private const val UNORDERED = "-1"
private const val AUTO = "auto"
private const val AUTO_CHANNEL = "0"
private const val NONE = "none"
private const val NO_TUNNEL = "NONE"
private const val DEFAULT_CHECK = "default"
private const val ALWAYS_ON = "always-on"
private const val ON_DEMAND = "on-demand"
private const val IPOE = "ipoe"
private const val STATION = "wisp"
private const val MOBILE = "mobile"
private const val PPP = "ppp"
private const val VPN = "vpn"

// the shows a read of the connections takes; the last, Ping Check, is there only with its component
private val READS = listOf("interface", "rc/interface", "rc/ping-check")
private val PPP_TYPES = setOf("PPPoE", "PPTP", "L2TP", "SSTP")
private val ISP_TUNNELS = listOf("PPPoE", "PPTP", "L2TP")
private val AUTH_FLAGS = listOf("pap", "chap", "mschap", "mschap-v2")
private val PPP_AUTH = linkedMapOf(AUTO to "Auto", "pap" to "PAP", "chap" to "CHAP", "mschap" to "MS-CHAP", "mschap-v2" to "MS-CHAP v2")
private val DOT1X = linkedMapOf(NONE to "No authentication", "eap-md5" to "EAP-MD5", "chap" to "EAP-TTLS/CHAP", "mschap" to "EAP-TTLS/MS-CHAP", "mschap-v2" to "EAP-TTLS/MS-CHAPv2", "peap" to "EAP-PEAP/MS-CHAPv2")
private val DOT1X_FLAGS = linkedMapOf("eap-md5" to listOf("eap-md5"), "chap" to listOf("eap-ttls", "chap"), "mschap" to listOf("eap-ttls", "mschap"), "mschap-v2" to listOf("eap-ttls", "mschap-v2"), "peap" to listOf("peap", "mschap-v2"))
private val IPV4_MODES = linkedMapOf(AUTO to "Automatic (DHCP)", "manual" to "Manual", "disabled" to "Not used")
private val IPV6_MODES = linkedMapOf(AUTO to "Automatic", "manual" to "Manual", "disabled" to "Not used")
private val MAC_MODES = linkedMapOf("default" to "Default", "manual" to "Manually", "clone" to "Take from this device")
private val ORDERS = mapOf(UNORDERED to "Priority not selected", "0" to "Main connection")
private val CHECK_MODES = linkedMapOf(NONE to "Do not check", DEFAULT_CHECK to "Auto mode", "icmp" to "ICMP echo (ping)", "connect" to "TCP port check", "tls" to "TLS check of a TCP port")
private val PROBES = setOf("icmp", "connect", "tls")
private val STATION_SECURITY = linkedMapOf(
    "disabled" to "Unsecured", "wpa" to "WPA-PSK", "wpa2" to "WPA2-PSK", "wpa+wpa2" to "WPA-PSK + WPA2-PSK", "wpa2+wpa3" to "WPA2-PSK + WPA3-PSK", "wpa3" to "WPA3-PSK",
    "owe" to "OWE", "wep" to "WEP",
)
private val PSK_SECURITY = setOf("wpa", "wpa2", "wpa+wpa2", "wpa2+wpa3", "wpa3")
private val CONNECTION_TYPES = linkedMapOf(ALWAYS_ON to "Always on", ON_DEMAND to "On demand")
private val NETWORKS = linkedMapOf(AUTO to "Any network", "gsm" to "GSM", "umts" to "3G (UMTS)", "lte" to "4G (LTE)", "nr5g" to "5G (NR)")
private val ON_IPOE = mapOf(KIND to IPOE)
private val ON_WISP = mapOf(KIND to STATION)
private val ON_MOBILE = mapOf(KIND to MOBILE)
private val ON_PPP = mapOf(KIND to PPP)

private fun kindOf(type: String) = when (type) {
    in PPP_TYPES -> PPP
    in MODEMS -> MOBILE
    WISP -> STATION
    in TUNNELS -> VPN
    else -> IPOE
}

// the priority of a connection as the web interface writes it: its place in the order, or no place at all
private fun ordered(order: String): JsonElement = if (order == UNORDERED) NO else JsonObject(
    mapOf("enabled" to JsonPrimitive(true), "order" to JsonPrimitive(order.toIntOrNull() ?: throw DeviceException(ErrorCategory.ArgumentValue, "$order is no place in the order of connections"))),
)

private fun rolesOf(own: JsonElement?): List<String> = when (val roles = inside(own, "role")) {
    is JsonArray -> roles.mapNotNull { (it as? JsonPrimitive)?.contentOrNull ?: (it as? JsonObject)?.text("role") }
    is JsonPrimitive -> listOfNotNull(roles.contentOrNull)
    else -> emptyList()
}

// a flag of RCI is on where its key is there and not false
private fun flagged(json: JsonObject?, key: String) = json?.get(key)?.let { (it as? JsonPrimitive)?.booleanOrNull != false } == true

// the security of a Wi-Fi station as the web interface reads it from the modes its encryption has on
private fun stationSecurity(encryption: JsonObject?): String {
    fun on(mode: String) = flagged(encryption, mode)
    return when {
        encryption == null -> "disabled"
        on("wpa") && on("wpa2") -> "wpa+wpa2"
        on("wpa2") && on("wpa3") -> "wpa2+wpa3"
        on("wpa") -> "wpa"
        on("wpa2") -> "wpa2"
        on("wpa3") -> "wpa3"
        on("owe") -> "owe"
        on("enable") -> "wep"
        else -> "disabled"
    }
}

// and as it writes it: every mode turned on or off, the key of WPA beside them
private fun stationEncryption(security: String, psk: String): JsonObject {
    if (security !in STATION_SECURITY || security == "wep") throw DeviceException(ErrorCategory.ArgumentValue, "$security is no security the app sets on a Wi-Fi connection")
    val keyed = security in PSK_SECURITY
    if (keyed && psk.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "the Wi-Fi network needs its key")
    fun turned(on: Boolean) = JsonObject(mapOf("no" to JsonPrimitive(!on)))
    val modes = mapOf(
        "enable" to turned(security != "disabled"),
        "wpa" to turned(security == "wpa" || security == "wpa+wpa2"),
        "wpa2" to turned(security == "wpa+wpa2" || security == "wpa2" || security == "wpa2+wpa3"),
        "owe" to turned(security == "owe"),
        "wpa3" to turned(security == "wpa2+wpa3" || security == "wpa3"),
    )
    return JsonObject(mapOf("encryption" to JsonObject(modes), "authentication" to JsonObject(mapOf("wpa-psk" to if (keyed) JsonObject(mapOf("psk" to JsonPrimitive(psk))) else NO))))
}

// a command to an interface with the value at the path of its keys, one for each field as the web interface makes them
internal fun interfaceSetting(name: String, path: String, value: JsonElement): JsonObject {
    val keys = path.split('.')
    return nested(listOf("interface", name) + keys.dropLast(1), JsonObject(mapOf(keys.last() to value)))
}

internal fun inside(json: JsonElement?, vararg keys: String): JsonElement? = keys.fold(json) { at, key -> (at as? JsonObject)?.get(key) }

internal fun objectsOf(json: JsonElement?): List<JsonObject> = when (json) {
    is JsonArray -> json.mapNotNull { it as? JsonObject }
    is JsonObject -> listOf(json)
    else -> emptyList()
}

internal fun noIfEmpty(value: String): JsonElement = if (value.isEmpty()) NO else JsonPrimitive(value)

// the DNS servers of an interface as the field of the web interface writes them, host:port where IPv4 has a port
internal fun nameServersOf(json: JsonElement?, ported: Boolean): String = (json as? JsonArray ?: listOfNotNull(json)).mapNotNull { server ->
    (server as? JsonObject)?.let { listOfNotNull(it.text("name-server"), it.text("port")?.takeIf { port -> ported && port.isNotEmpty() }).joinToString(":") }
        ?: (server as? JsonPrimitive)?.contentOrNull
}.joinToString(", ")

// and as it sends them: the list taken off, then each server
internal fun nameServers(text: String, ported: Boolean): JsonArray = JsonArray(
    listOf(NO) + text.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }.map { server ->
        val host = if (ported) server.substringBefore(':') else server
        JsonObject(mapOf("name-server" to JsonPrimitive(host), "port" to JsonPrimitive(if (ported) server.substringAfter(':', "") else "")))
    },
)

// clients as the web interface lists them: every host the router has seen, registered or not, with its name, its fixed
// address and whether it may go out; a client is its MAC
internal class Clients(private val api: KeeneticCall) : Node("clients") {
    override val commands = listOf("print", "add", "set", "remove")

    override suspend fun arguments() = listOf(
        Argument("name", ValueType.Text()),
        Argument("mac", ValueType.Mac),
        Argument("access", ValueType.Enumeration(closed = true), ACTIONS.keys.toList(), optional = true, labels = ACTIONS),
        Argument("static-ip", ValueType.Ip4, optional = true),
    )

    override suspend fun read(): List<Item> {
        val answers = api.shows("ip/hotspot", "rc/known", "rc/ip/dhcp/host", "ip/dhcp/bindings", "interface")
        val known = named((answers[1] as? JsonObject)?.get("host") ?: JsonObject(emptyMap())).associate { (name, host) -> host["mac"].orEmpty().lowercase() to name }
        val fixed = rows(answers[2]).associate { it["mac"].orEmpty().lowercase() to it["ip"].orEmpty() }
        val leases = rows(answers[3]).associate { it["mac"].orEmpty().lowercase() to it }
        val interfaces = interfaces(answers[4], JsonObject(emptyMap()))
        val seen = rows(answers[0], "mac").associateBy { it.id.lowercase() }
        return (seen.keys + known.keys + fixed.keys).filter { it.matches(MAC) }.distinct().map { mac ->
            val host = seen[mac]
            val lease = leases[mac]
            val connection = when {
                host?.get("ap") != null -> "${host["ssid"].orEmpty()} (${band(interfaces, host["ap"].orEmpty())})"
                host?.get("port") != null -> "Port ${host["port"]}"
                else -> ""
            }
            Item(
                mac,
                linkedMapOf(
                    "name" to (known[mac] ?: host?.get("name")?.ifEmpty { null } ?: host?.get("hostname")?.ifEmpty { null } ?: lease?.get("name")).orEmpty(),
                    "mac" to mac,
                    "ip" to (host?.get("ip")?.takeIf { it != "0.0.0.0" } ?: lease?.get("ip")).orEmpty(),
                    "hostname" to (host?.get("hostname")?.ifEmpty { null } ?: lease?.get("hostname")).orEmpty(),
                    "segment" to host?.get("interface.name").orEmpty(),
                    "connection" to connection,
                    "online" to (host?.get("active") ?: "false"),
                    "uptime" to host?.get("uptime").orEmpty(),
                    "rxbytes" to host?.get("rxbytes").orEmpty(),
                    "txbytes" to host?.get("txbytes").orEmpty(),
                    "registered" to "${mac in known}",
                    "access" to host?.get("access").orEmpty(),
                    "static-ip" to fixed[mac].orEmpty(),
                    "expires" to lease?.get("expires").orEmpty(),
                    "policy" to host?.get("policy").orEmpty(),
                    "priority" to host?.get("priority").orEmpty(),
                ),
            )
        }
    }

    override suspend fun add(values: Map<String, String>): String {
        val mac = values["mac"].orEmpty().lowercase()
        if (!mac.matches(MAC)) throw DeviceException(ErrorCategory.ArgumentValue, "a client needs its MAC address")
        api.post(commands(mac, values, emptyMap()))
        return mac
    }

    override suspend fun set(id: String?, changes: Map<String, String?>) {
        val old = read().firstOrNull { it.id == id } ?: throw DeviceException(ErrorCategory.MissingItem, "clients has no $id")
        if (changes["mac"]?.let { !it.equals(old.id, ignoreCase = true) } == true) throw DeviceException(ErrorCategory.Unsupported, "a client is known by its MAC; add the new one and remove the old one")
        api.send(commands(old.id, changes, old.values))
    }

    override suspend fun remove(ids: List<String>) {
        val clients = read().associateBy { it.id }
        api.post(
            ids.flatMap { id ->
                val client = clients[id] ?: throw DeviceException(ErrorCategory.MissingItem, "clients has no $id")
                listOfNotNull(
                    nested(listOf("known", "host"), JsonObject(mapOf("no" to JsonPrimitive(true), "mac" to JsonPrimitive(client.id)))).takeIf { client["registered"] == "true" },
                    lease(client.id, "").takeIf { client["static-ip"].orEmpty().isNotEmpty() },
                ).ifEmpty { throw DeviceException(ErrorCategory.ArgumentValue, "$id is neither registered nor given a fixed address") }
            },
        )
    }

    // the name registers the host; the rule of ip hotspot cannot be taken off, only turned to permit or deny
    private fun commands(mac: String, changes: Map<String, String?>, old: Map<String, String>): List<JsonObject> = listOfNotNull(
        changes["name"]?.ifEmpty { null }?.let { nested(listOf("known", "host"), JsonObject(mapOf("name" to JsonPrimitive(it), "mac" to JsonPrimitive(mac)))) },
        changes["access"]?.ifEmpty { null }?.let { nested(listOf("ip", "hotspot", "host"), JsonObject(mapOf("mac" to JsonPrimitive(mac), it to JsonPrimitive(true)))) },
        changes["static-ip"]?.takeIf { it.isNotEmpty() || old["static-ip"].orEmpty().isNotEmpty() }?.let { lease(mac, it) },
    )

    private fun lease(mac: String, ip: String) = nested(
        listOf("ip", "dhcp", "host"),
        JsonObject(if (ip.isEmpty()) mapOf("no" to JsonPrimitive(true), "mac" to JsonPrimitive(mac)) else mapOf("mac" to JsonPrimitive(mac), "ip" to JsonPrimitive(ip))),
    )
}

// access points with the band and channel of their radio, the segment they serve and the clients on them
internal class AccessPoints(private val api: KeeneticCall) : Node("wifi") {
    override val commands = listOf("print", "set")

    override suspend fun arguments() = listOf(
        Argument("name", ValueType.Text(), readOnly = true),
        Argument("ssid", ValueType.Text(), optional = true),
        Argument("security", ValueType.Enumeration(closed = true), SECURITY.keys.toList(), labels = SECURITY),
        Argument("psk", ValueType.Secret, optional = true),
        Argument("disabled", ValueType.YesNo),
        Argument("description", ValueType.Text(), optional = true),
    )

    override suspend fun read(): List<Item> {
        val (shown, config, hotspot) = api.shows("interface", "rc/interface", "ip/hotspot")
        val interfaces = interfaces(shown, config)
        val hosts = rows(hotspot, "mac")
        return interfaces.filter { it["type"] == "AccessPoint" }.map { ap ->
            val radio = interfaces.firstOrNull { it.id == ap.id.substringBefore('/') }
            Item(
                ap.id,
                linkedMapOf(
                    "name" to ap.id,
                    "ssid" to ap["ssid"].orEmpty(),
                    "band" to band(interfaces, ap.id),
                    "channel" to radio?.get("channel").orEmpty(),
                    "security" to ap["encryption"].orEmpty().ifEmpty { "none" },
                    "psk" to ap["psk"].orEmpty(),
                    "disabled" to ap["disabled"].orEmpty(),
                    "running" to ap["running"].orEmpty(),
                    "segment" to (interfaces.firstOrNull { it.id == ap["group"] }?.let(::title) ?: ap["group"]).orEmpty(),
                    "clients" to "${hosts.count { it["ap"] == ap.id && it["active"] == "true" }}",
                    "mac" to ap["mac"].orEmpty(),
                    "description" to ap["description"].orEmpty(),
                    "interface-name" to ap["interface-name"].orEmpty(),
                ),
            )
        }
    }

    override suspend fun set(id: String?, changes: Map<String, String?>) {
        val name = id ?: throw DeviceException(ErrorCategory.MissingItem, "wifi holds elements, not settings")
        if (read().none { it.id == name }) throw DeviceException(ErrorCategory.MissingItem, "wifi has no $name")
        val parts = interfaceParts(changes.filterKeys { it in setOf("ssid", "psk", "disabled", "description") }) + listOfNotNull(changes["security"]?.let { "encryption" to security(it) })
        if (parts.isNotEmpty()) api.post(listOf(nested(listOf("interface", name), JsonObject(parts))))
    }

    // WPA2 is encryption wpa2, WPA3 the flag enabled of encryption wpa3, as show rc gives them
    private fun security(mode: String): JsonObject {
        val modes = mode.split(',').map { it.trim() }.toSet()
        if (modes.any { it !in setOf("none", "wpa2", "wpa3") }) throw DeviceException(ErrorCategory.ArgumentValue, "$mode is no security of Keenetic Wi-Fi")
        if ("none" in modes) return JsonObject(mapOf("wpa2" to NO, "wpa3" to NO, "enable" to NO))
        return JsonObject(
            mapOf(
                "enable" to JsonPrimitive(true),
                "wpa2" to if ("wpa2" in modes) JsonPrimitive(true) else NO,
                "wpa3" to if ("wpa3" in modes) JsonObject(mapOf("enabled" to JsonPrimitive(true))) else NO,
            ),
        )
    }
}

internal val ACTIONS = mapOf("permit" to "Permit", "deny" to "Deny")

internal val SECURITY = mapOf("none" to "Unsecured", "wpa2" to "WPA2-PSK", "wpa2,wpa3" to "WPA2-PSK + WPA3-PSK", "wpa3" to "WPA3-PSK")

internal class Radios(private val api: KeeneticCall) : Node("wifi/radio") {
    override suspend fun read(): List<Item> {
        val (shown, config, hotspot) = api.shows("interface", "rc/interface", "ip/hotspot")
        val interfaces = interfaces(shown, JsonObject(emptyMap()))
        val radios = interfaces.filter { it["type"] == "WifiMaster" }
        val loads = radios.zip(api.post(radios.map { utilizationOf(it.id) }, raising = false)).associate { (radio, answer) -> radio.id to answer.takeIf { failure(it) == null }?.let(::loadOf) }
        val online = rows(hotspot, "mac").filter { it["active"] == "true" }
        return radios.map { radio ->
            val own = (config as? JsonObject)?.get(radio.id) as? JsonObject
            val standard = ((own?.get("compatibility") as? JsonArray)?.firstOrNull() as? JsonObject)?.text("annex")
            Item(
                radio.id,
                linkedMapOf(
                    "name" to radio.id,
                    "band" to band(interfaces, radio.id),
                    "channel" to radio["channel"].orEmpty(),
                    "width" to radio["bandwidth"].orEmpty(),
                    "standard" to standard.orEmpty(),
                    "channel-width" to own?.let(::fields)?.get("channel.width").orEmpty(),
                    "busy-channels" to radio["busy-channels"].orEmpty(),
                    "temperature" to radio["temperature"].orEmpty(),
                    "rate" to radio["bitrate"].orEmpty(),
                    "running" to radio["running"].orEmpty(),
                    "disabled" to radio["disabled"].orEmpty(),
                    "access-points" to "${interfaces.count { it["type"] == "AccessPoint" && it.id.startsWith("${radio.id}/") && it["state"] == "up" }}",
                    "load" to loads[radio.id].orEmpty(),
                    "clients" to "${online.count { it["ap"]?.startsWith("${radio.id}/") == true }}",
                ),
            )
        }
    }
}

// the segments of the web interface are its bridges: a network with its address, DHCP, NAT and members
internal class Segments(private val api: KeeneticCall) : Node("segments") {
    override val commands = listOf("print", "set")

    override suspend fun arguments() = listOf(
        Argument("name", ValueType.Text(), readOnly = true),
        Argument("interface-name", ValueType.Text(), optional = true),
        Argument("description", ValueType.Text(), optional = true),
    )

    override suspend fun read(): List<Item> {
        val answers = api.shows("interface", "rc/interface", "rc/ip/dhcp/pool", "rc/ip/nat", "ip/hotspot")
        val config = answers[1] as? JsonObject ?: JsonObject(emptyMap())
        val interfaces = interfaces(answers[0], config)
        val pools = named(answers[2])
        val nat = rows(answers[3]).mapNotNull { it["interface"] }.toSet()
        val hosts = rows(answers[4], "mac")
        return interfaces.filter { it["type"] == "Bridge" }.map { bridge ->
            val members = ((config[bridge.id] as? JsonObject)?.get("include") as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("interface") as? JsonPrimitive)?.contentOrNull }
            val pool = pools.firstOrNull { it.second["bind.interface"] == bridge.id }?.second
            Item(
                bridge.id,
                linkedMapOf(
                    "name" to bridge.id,
                    "interface-name" to bridge["interface-name"].orEmpty(),
                    "description" to bridge["description"].orEmpty(),
                    "address" to bridge["address"].orEmpty(),
                    "mask" to bridge["mask"].orEmpty(),
                    "dhcp" to pool?.let { listOfNotNull(it["range.begin"], it["range.end"]).joinToString("-") }.orEmpty(),
                    "nat" to "${bridge.id in nat}",
                    "wifi" to members.mapNotNull { member -> interfaces.firstOrNull { it.id == member && it["type"] == "AccessPoint" }?.get("ssid") }.joinToString(", "),
                    "members" to members.joinToString(", ") { member -> interfaces.firstOrNull { it.id == member }?.let(::title) ?: member },
                    "clients" to "${hosts.count { it["interface.id"] == bridge.id && it["active"] == "true" }}",
                    "security-level" to bridge["security-level"].orEmpty(),
                    "running" to bridge["running"].orEmpty(),
                    "disabled" to bridge["disabled"].orEmpty(),
                    "uptime" to bridge["uptime"].orEmpty(),
                    "mac" to bridge["mac"].orEmpty(),
                ),
            )
        }
    }

    override suspend fun set(id: String?, changes: Map<String, String?>) {
        val name = id ?: throw DeviceException(ErrorCategory.MissingItem, "segments holds elements, not settings")
        if (read().none { it.id == name }) throw DeviceException(ErrorCategory.MissingItem, "segments has no $name")
        api.send(listOfNotNull(interfaceChange(name, changes.filterKeys { it == "interface-name" || it == "description" })))
    }
}

// the rules of the firewall, each in the access list its interface takes in or out, numbered in the list; a rule is
// added at the end of its list, a new match goes in by adding the rule anew in the place of the old one
internal class Firewall(private val api: KeeneticCall, private val words: () -> Map<String, String>) : Node("firewall") {
    override val commands = listOf("print", "add", "set", "remove", "move")

    // the protocols help gives an access list of IPv4, a code of another one typed in, and the services of the web interface,
    // each a protocol with the port it is to: such a rule goes to the router as the protocol and that destination port
    override suspend fun arguments(): List<Argument> {
        val protocols = PROTOCOLS + SERVICES.keys.filter { !it.startsWith("tcpudp/") }.associateWith { serviceLabel(it, words()) }
        return api.withInterfaces(
            listOf(
                Argument("interface", ValueType.Text()),
                Argument("direction", ValueType.Enumeration(closed = true), listOf("in", "out"), default = "in"),
                Argument("action", ValueType.Enumeration(closed = true), ACTIONS.keys.toList(), default = "permit", labels = ACTIONS),
                Argument("protocol", ValueType.Enumeration(), protocols.keys.toList(), default = "tcp", labels = protocols),
                Argument("source", ValueType.Text(), optional = true, placeholder = "any"),
                Argument("source-port", ValueType.Text(), optional = true, placeholder = "any"),
                Argument("destination", ValueType.Text(), optional = true, placeholder = "any"),
                Argument("destination-port", ValueType.Text(), optional = true, placeholder = "any"),
                Argument("description", ValueType.Text(), optional = true),
                Argument("disabled", ValueType.YesNo),
            ),
        )
    }

    override suspend fun read(): List<Item> {
        val (lists, config) = api.shows("rc/access-list", "rc/interface")
        val bindings = (config as? JsonObject).orEmpty().flatMap { (name, element) ->
            val groups = (((element as? JsonObject)?.get("ip") as? JsonObject)?.get("access-group") as? JsonArray).orEmpty()
            groups.mapNotNull { it as? JsonObject }.mapNotNull { group -> group.text("acl")?.let { it to (name to group.text("direction").orEmpty()) } }
        }.toMap()
        val places = HashMap<String, Int>()
        return rows(lists).filter { it["action"] != null }.map { rule ->
            val acl = rule["acl"].orEmpty()
            val place = places.getOrElse(acl) { 0 }.also { places[acl] = it + 1 }
            Item(
                rule["index"] ?: "$acl#$place",
                linkedMapOf(
                    "interface" to bindings[acl]?.first.orEmpty(),
                    "direction" to bindings[acl]?.second.orEmpty(),
                    "acl" to acl,
                    "position" to "$place",
                    "action" to rule["action"].orEmpty(),
                    "protocol" to rule["protocol"].orEmpty(),
                    "source" to network(rule["source"], rule["source-mask"]),
                    "source-port" to port(rule["src-port-operator"], rule["source-port"], rule["source-end-port"]),
                    "destination" to network(rule["destination"], rule["destination-mask"]),
                    "destination-port" to port(rule["dst-port-operator"], rule["destination-port"], rule["destination-end-port"]),
                    "description" to rule["description"].orEmpty(),
                    "disabled" to (rule["disable"] ?: "false"),
                ),
            )
        }
    }

    override suspend fun add(values: Map<String, String>): String {
        val face = values["interface"].orEmpty()
        val direction = values["direction"]?.ifEmpty { null } ?: "in"
        if (face.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "a rule needs the interface whose traffic it filters")
        val rules = read()
        val acl = rules.firstOrNull { it["interface"] == face && it["direction"] == direction }?.get("acl") ?: "_WEBADMIN_$face"
        val place = rules.count { it["acl"] == acl }
        api.post(listOfNotNull(JsonObject(mapOf("access-list" to JsonObject(mapOf<String, JsonElement>("acl" to JsonPrimitive(acl)) + match(values)))), binding(face, acl, direction).takeIf { rules.none { it["acl"] == acl && it["interface"] == face } }))
        api.send(extras(acl, place, values, emptyMap()))
        return read().firstOrNull { it["acl"] == acl && it["position"] == "$place" }?.id.orEmpty()
    }

    override suspend fun set(id: String?, changes: Map<String, String?>) {
        val old = read().firstOrNull { it.id == id } ?: throw DeviceException(ErrorCategory.MissingItem, "firewall has no $id")
        if (listOf("interface", "direction").any { name -> changes[name]?.let { it != old[name] } == true }) throw DeviceException(ErrorCategory.Unsupported, "a rule stays with its interface; add it to the other one and remove this one")
        val acl = old["acl"].orEmpty()
        val place = old["position"].orEmpty().toInt()
        val merged = old.values + changes.mapValues { it.value.orEmpty() }
        if (matching.any { merged[it].orEmpty() != old[it].orEmpty() }) {
            val last = read().count { it["acl"] == acl }
            api.post(listOf(JsonObject(mapOf("access-list" to JsonObject(mapOf<String, JsonElement>("acl" to JsonPrimitive(acl)) + match(merged))))))
            api.post(listOf(rule(acl, last, "new-index" to JsonPrimitive(place)), removal(old)) + extras(acl, place, merged, emptyMap()))
        } else {
            api.send(extras(acl, place, changes, old.values))
        }
    }

    // the router keeps a list it has no rule left in, bound to its interface; the web interface leaves none of its own behind
    override suspend fun remove(ids: List<String>) {
        val rules = read()
        val removed = ids.map { id -> rules.firstOrNull { it.id == id } ?: throw DeviceException(ErrorCategory.MissingItem, "firewall has no $id") }
        api.post(removed.map(::removal))
        val emptied = removed.groupBy { it["acl"].orEmpty() }.filter { (acl, gone) -> acl.startsWith("_WEBADMIN_") && rules.count { it["acl"] == acl } == gone.size }
        api.send(
            emptied.flatMap { (acl, gone) ->
                val rule = gone.first()
                listOfNotNull(
                    rule["interface"]?.ifEmpty { null }?.let { face ->
                        nested(listOf("interface", face, "ip", "access-group"), JsonObject(mapOf("no" to JsonPrimitive(true), "acl" to JsonPrimitive(acl), "direction" to JsonPrimitive(rule["direction"].orEmpty()))))
                    },
                    JsonObject(mapOf("parse" to JsonPrimitive("no access-list ${quoted(acl)}"))),
                )
            },
        )
    }

    override suspend fun move(ids: List<String>, before: String?) {
        val rules = read()
        val moved = rules.firstOrNull { it.id == ids.singleOrNull() } ?: throw DeviceException(ErrorCategory.Unsupported, "Keenetic moves one rule at a time")
        val list = rules.filter { it["acl"] == moved["acl"] }.map { it.id }
        val target = before?.let { list.indexOf(it) } ?: list.size
        if (target < 0) throw DeviceException(ErrorCategory.Unsupported, "a rule moves only within the list of its interface")
        val from = list.indexOf(moved.id)
        val place = if (target > from) target - 1 else target
        if (place != from) api.post(listOf(rule(moved["acl"].orEmpty(), from, "new-index" to JsonPrimitive(place))))
    }

    // the router finds the rule to remove by all of its match; an acl alone would remove the whole list
    private fun removal(rule: Item): JsonObject {
        val acl = rule["acl"].orEmpty()
        val match = match(rule.values)
        if (acl.isEmpty() || "action" !in match || "protocol" !in match) throw DeviceException(ErrorCategory.ArgumentValue, "the rule has no match the router can find it by")
        return JsonObject(mapOf("access-list" to JsonObject(mapOf<String, JsonElement>("acl" to JsonPrimitive(acl), "no" to JsonPrimitive(true)) + match)))
    }

    private fun extras(acl: String, place: Int, changes: Map<String, String?>, old: Map<String, String>): List<JsonObject> = listOfNotNull(
        changes["description"]?.takeIf { it != old["description"] && (it.isNotEmpty() || old["description"].orEmpty().isNotEmpty()) }?.let { rule(acl, place, "description" to JsonPrimitive(it)) },
        changes["disabled"]?.let { it in ValueType.Flag.on }?.takeIf { it != (old["disabled"] in ValueType.Flag.on) }?.let { rule(acl, place, "disable" to if (it) JsonPrimitive(true) else NO) },
    )

    private fun rule(acl: String, place: Int, change: Pair<String, JsonElement>) =
        JsonObject(mapOf("access-list" to JsonObject(mapOf("acl" to JsonPrimitive(acl), "rule" to JsonObject(mapOf("index" to JsonPrimitive(place), change))))))

    private fun binding(face: String, acl: String, direction: String) =
        nested(listOf("interface", face, "ip", "access-group"), JsonObject(mapOf("acl" to JsonPrimitive(acl), "direction" to JsonPrimitive(direction))))

    private fun match(values: Map<String, String?>): Map<String, JsonElement> = buildMap {
        val protocol = values["protocol"]?.ifEmpty { null } ?: "ip"
        val service = protocol.takeIf { '/' in it }
        put("action", JsonPrimitive(values["action"]?.ifEmpty { null } ?: "permit"))
        put("protocol", JsonPrimitive(service?.substringBefore('/') ?: protocol))
        for ((side, prefix) in listOf("source" to "source", "destination" to "destination")) {
            val (address, mask) = address(values[side].orEmpty())
            put(prefix, JsonPrimitive(address))
            put("$prefix-mask", JsonPrimitive(mask))
        }
        for ((side, operator, prefix) in listOf(Triple("source-port", "src-port-operator", "source"), Triple("destination-port", "dst-port-operator", "destination"))) {
            val ports = (if (side == "destination-port" && service != null) service.substringAfter('/') else values[side].orEmpty()).let(::ports) ?: continue
            put(operator, JsonPrimitive(ports.first))
            put("$prefix-port", JsonPrimitive(ports.second))
            ports.third?.let { put("$prefix-end-port", JsonPrimitive(it)) }
        }
    }

    private companion object {
        val matching = listOf("action", "protocol", "source", "source-port", "destination", "destination-port")
        val PROTOCOLS = linkedMapOf("tcp" to "TCP", "udp" to "UDP", "icmp" to "ICMP", "esp" to "ESP", "gre" to "GRE", "ip" to "IPv4", "ipip" to "IPIP")
    }
}

// 0.0.0.0 with 0.0.0.0 is any address; a contiguous mask goes as a prefix, any other as it is
internal fun network(address: String?, mask: String?): String {
    if (address.isNullOrEmpty() || address == "0.0.0.0" && mask == "0.0.0.0") return ""
    val bits = mask?.split('.')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 4 }?.fold(0L) { all, part -> all * 256 + part } ?: return "$address/$mask"
    val prefix = (32 downTo 0).firstOrNull { length -> bits == (0xFFFFFFFFL shl (32 - length)) and 0xFFFFFFFFL } ?: return "$address/$mask"
    return if (prefix == 32) address else "$address/$prefix"
}

internal fun address(text: String): Pair<String, String> {
    val value = text.trim()
    if (value.isEmpty() || value == "any") return "0.0.0.0" to "0.0.0.0"
    val address = value.substringBefore('/')
    val suffix = value.substringAfter('/', "32")
    if ('.' in suffix) return address to suffix
    val length = suffix.toIntOrNull()?.takeIf { it in 0..32 } ?: throw DeviceException(ErrorCategory.ArgumentValue, "$text is no address or network")
    val bits = (0xFFFFFFFFL shl (32 - length)) and 0xFFFFFFFFL
    return address to (3 downTo 0).joinToString(".") { "${(bits shr (8 * it)) and 0xFF}" }
}

internal fun port(operator: String?, from: String?, to: String?): String = when (operator) {
    null, "", "any" -> ""
    "eq" -> from.orEmpty()
    "range" -> "${from.orEmpty()}-${to.orEmpty()}"
    "lt" -> "<${from.orEmpty()}"
    "gt" -> ">${from.orEmpty()}"
    else -> "$operator ${from.orEmpty()}"
}

internal fun ports(text: String): Triple<String, String, String?>? {
    val value = text.trim()
    return when {
        value.isEmpty() || value == "any" -> null
        value.startsWith("<") -> Triple("lt", value.drop(1).trim(), null)
        value.startsWith(">") -> Triple("gt", value.drop(1).trim(), null)
        '-' in value -> Triple("range", value.substringBefore('-').trim(), value.substringAfter('-').trim())
        else -> Triple("eq", value, null)
    }.also { it?.let { (_, from, to) -> if (listOfNotNull(from, to).any { port -> port.toIntOrNull() !in 1..65535 }) throw DeviceException(ErrorCategory.ArgumentValue, "$text is no port, range of ports, <port or >port") } }
}

// the name the web interface gives an interface: its description, else the name it was renamed to
internal fun title(row: Item) = row["description"]?.ifEmpty { null } ?: row["interface-name"]?.takeIf { it != row.id } ?: row.id

// the titles of interfaces to choose from: a title two of them share, as Broadband connection of an IPoE and its PPPoE, names its interface too
internal fun titles(rows: List<Item>): Map<String, String> {
    val shared = rows.groupingBy(::title).eachCount().filterValues { it > 1 }.keys
    return rows.associate { row -> row.id to title(row).let { if (it in shared) "$it (${row.id})" else it } }
}

// the band of a radio, or of the radio of an access point, by its channel
internal fun band(interfaces: List<Item>, id: String): String {
    val channel = interfaces.firstOrNull { it.id == id.substringBefore('/') }?.get("channel")?.toIntOrNull() ?: return ""
    return if (channel <= 14) "2.4 GHz" else "5 GHz"
}

