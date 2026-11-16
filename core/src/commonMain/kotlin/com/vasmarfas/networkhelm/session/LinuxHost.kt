package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.discovery.formatIpv6
import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.model.duration
import com.vasmarfas.networkhelm.openwrt.quote
import com.vasmarfas.networkhelm.ssh.SshSession
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.Instant

// a table of a Linux host: what it shows, what it reads with, and what changes it; slow ones are read seldom and again after a change
internal class HostTable(
    val fields: List<String>,
    val read: suspend () -> List<Item>,
    val settings: Boolean = false,
    val slow: Boolean = false,
    val arguments: suspend () -> List<Argument> = { emptyList() },
    val add: (suspend (Map<String, String>) -> String)? = null,
    val set: (suspend (String?, Map<String, String?>) -> Unit)? = null,
    val remove: (suspend (List<String>) -> Unit)? = null,
    val move: (suspend (List<String>, String?) -> Unit)? = null,
    val commands: List<String> = emptyList(),
    val run: (suspend (String, Map<String, String>) -> List<Item>)? = null,
    val present: suspend () -> Boolean = { true },
)

internal class LinuxHost(private val ssh: SshSession, password: String? = null, login: (suspend () -> Unit)? = null) {
    private var cpu: List<Long>? = null
    private var found: Set<String>? = null
    private val root = Root(ssh, password)
    private val firewall = LinuxFirewall(ssh, root, ::tools, login)
    private val packages = LinuxPackages(ssh, root, ::tools) { found = null }
    private val storage = LinuxStorage(ssh, root, ::tools)
    private val config = LinuxNetworkConfig(ssh, root, ::tools)
    private val logs = LinuxLogs(ssh, root, ::tools)

    val tables: Map<String, HostTable> = mapOf(
        "interface" to HostTable(INTERFACE, ::interfaces, arguments = { INTERFACE_ARGUMENTS + PERSIST }, set = ::setInterface, commands = listOf("monitor-traffic")),
        "interface/virtual" to HostTable(VIRTUAL, ::virtuals, arguments = { named(VIRTUAL_ARGUMENTS + PERSIST) }, add = ::addVirtual, remove = ::removeVirtuals),
        "ip/address" to HostTable(
            listOf("address", "network", "interface", "scope", "dynamic"), { addresses(v6 = false) }, arguments = { named(ADDRESS_ARGUMENTS + PERSIST) },
            add = { addAddress(it, v6 = false) }, set = { id, changes -> setAddress(id, changes, v6 = false) }, remove = ::removeAddresses,
        ),
        "ipv6/address" to HostTable(
            listOf("address", "interface", "scope", "dynamic"), { addresses(v6 = true) }, arguments = { named(ADDRESS_ARGUMENTS + PERSIST) },
            add = { addAddress(it, v6 = true) }, set = { id, changes -> setAddress(id, changes, v6 = true) }, remove = ::removeAddresses,
        ),
        "ip/route" to HostTable(
            ROUTE, { routes(v6 = false) }, arguments = { named(ROUTE_ARGUMENTS + PERSIST) },
            add = { addRoute(it, v6 = false) }, set = { id, changes -> setRoute(id, changes, v6 = false) }, remove = { removeRoutes(it, v6 = false) },
        ),
        "ipv6/route" to HostTable(
            ROUTE, { routes(v6 = true) }, arguments = { named(ROUTE_ARGUMENTS + PERSIST) },
            add = { addRoute(it, v6 = true) }, set = { id, changes -> setRoute(id, changes, v6 = true) }, remove = { removeRoutes(it, v6 = true) },
        ),
        "ip/arp" to HostTable(
            NEIGHBOUR, { neighbours(v6 = false) }, arguments = { named(NEIGHBOUR_ARGUMENTS) },
            add = { addNeighbour(it, v6 = false) }, set = { id, changes -> setNeighbour(id, changes, v6 = false) }, remove = { removeNeighbours(it, v6 = false) },
        ),
        "ipv6/neighbor" to HostTable(
            NEIGHBOUR + "router", { neighbours(v6 = true) }, arguments = { named(NEIGHBOUR_ARGUMENTS) },
            add = { addNeighbour(it, v6 = true) }, set = { id, changes -> setNeighbour(id, changes, v6 = true) }, remove = { removeNeighbours(it, v6 = true) },
        ),
        "ip/socket" to HostTable(SOCKET, ::sockets, present = { "ss" in tools() }),
        "ip/socket/listen" to HostTable(LISTEN, ::listening, present = { "ss" in tools() }),
        "system/resource" to HostTable(
            listOf("uptime", "load", "cpu-load", "cpu-count", "total-memory", "free-memory", "total-swap", "free-swap", "version", "architecture-name", "board-name"),
            { listOf(Item("", resource())) }, settings = true,
        ),
        "status" to HostTable(STATUS, { listOf(Item("", resource() + status())) }, settings = true),
        "system/clock" to HostTable(listOf("date", "time", "gmt-offset"), { listOf(Item("", clock())) }, settings = true),
        "system/process" to HostTable(listOf("pid", "user", "cpu", "memory", "vsz", "stat", "command"), ::processes, commands = SIGNALS.keys.toList(), run = ::signal),
        "system/service" to HostTable(
            listOf("name", "enabled", "load", "status", "running", "disabled", "description"), ::services,
            commands = listOf("start", "stop", "restart", "reload", "enable", "disable"), run = ::service, present = { "systemctl" in tools() || "rc-service" in tools() },
        ),
    ) + firewall.tables + packages.tables + storage.tables + logs.tables

    // the tools of the host by name, wherever they lie: over SSH Debian and openSUSE leave /usr/sbin out of PATH, and
    // command -v of busybox answers for the first name only
    suspend fun tools(): Set<String> = found ?: ssh.exec(TOOLS).output.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet().also { found = it }

    suspend fun traffic(): Map<String, List<Long>> = output("cat /proc/net/dev").lines().drop(2).mapNotNull { line ->
        val name = line.substringBefore(':', "").trim().ifEmpty { return@mapNotNull null }
        val fields = line.substringAfter(':').trim().split(blanks).mapNotNull(String::toLongOrNull)
        if (fields.size < 10) null else name to listOf(fields[0], fields[8], fields[1], fields[9])
    }.toMap()

    private suspend fun output(command: String): String {
        val result = ssh.exec(command)
        if (result.exitStatus != 0 && result.output.isBlank()) throw DeviceException(ErrorCategory.Api, "$command: ${result.error.trim().ifEmpty { "exit ${result.exitStatus}" }}")
        return result.output
    }

    private suspend fun json(command: String): JsonArray = try {
        Json.parseToJsonElement(output(command).ifBlank { "[]" }).jsonArray
    } catch (e: SerializationException) {
        throw DeviceException(ErrorCategory.Api, "$command gave no JSON", e)
    } catch (e: IllegalArgumentException) {
        throw DeviceException(ErrorCategory.Api, "$command gave no list", e)
    }

    private var iproute: Boolean? = null

    private suspend fun iproute(): Boolean = iproute ?: ssh.exec("ip -j link show lo").output.trimStart().startsWith("[").also { iproute = it }

    private suspend fun interfaces(): List<Item> = if (iproute()) json("ip -j -d -s link show").mapNotNull { it as? JsonObject }.map { link ->
        val flags = link.strings("flags")
        val kind = (link["linkinfo"] as? JsonObject)?.text("info_kind")
        val stats = link["stats64"] as? JsonObject
        fun counter(direction: String, name: String) = ((stats?.get(direction) as? JsonObject)?.get(name) as? JsonPrimitive)?.longOrNull?.toString().orEmpty()
        val linkType = link.text("link_type")
        link(
            link.text("ifindex"), link.text("ifname"), types[kind] ?: kind ?: types[linkType] ?: linkType, link.text("mtu"),
            link.text("address").takeIf { linkType == "ether" }.orEmpty(), "LOWER_UP" in flags || link.text("operstate") == "UP", "UP" !in flags,
            link.text("ifalias"), link.text("master"), link.text("link"),
            listOf("rx" to "bytes", "tx" to "bytes", "rx" to "packets", "tx" to "packets", "rx" to "dropped", "tx" to "dropped", "rx" to "errors", "tx" to "errors").map { (d, n) -> counter(d, n) },
        )
    } else {
        output(SYSFS).lines().map { it.split('|') }.filter { it.size >= 18 }.map { field ->
            val type = field[16].ifEmpty { arphrd[field[2]] ?: field[2] }
            val up = (field[3].removePrefix("0x").toIntOrNull(16) ?: 0) and 1 == 1
            link(field[1], field[0], types[type] ?: type, field[4], field[6].takeIf { field[2] == "1" }.orEmpty(), field[5] == "up" || field[5] == "unknown" && up, !up, field[17], field[15], "", field.subList(7, 15))
        }
    }

    // the interface of a form is chosen from those of the host
    private suspend fun named(arguments: List<Argument>): List<Argument> {
        val names = interfaces().mapNotNull { it["name"] }
        return arguments.map { if (it.name == "interface" || it.name == "parent") it.copy(type = ValueType.Enumeration(), choices = names) else it }
    }

    private fun link(index: String, name: String, type: String, mtu: String, mac: String, running: Boolean, disabled: Boolean, alias: String, master: String, parent: String, counters: List<String>) =
        Item(index, buildMap {
            put("name", name)
            put("type", type)
            put("mtu", mtu)
            put("mac-address", mac.uppercase())
            put("running", "$running")
            put("disabled", "$disabled")
            put("comment", alias)
            if (master.isNotEmpty()) put("master", master)
            if (parent.isNotEmpty()) put("parent", parent)
            listOf("rx-byte", "tx-byte", "rx-packet", "tx-packet", "rx-drop", "tx-drop", "rx-error", "tx-error").zip(counters).forEach { (key, value) -> put(key, value) }
        })

    // what is changed with ip is the state of the running system: a reboot brings back what the network configuration says
    private suspend fun setInterface(id: String?, changes: Map<String, String?>) {
        val name = interfaces().firstOrNull { it.id == id }?.get("name") ?: throw DeviceException(ErrorCategory.MissingItem, "the host has no interface $id")
        val device = quote(name)
        val steps = buildList {
            changes["mtu"]?.ifEmpty { null }?.let { mtu -> add("ip link set dev $device mtu ${mtu.toIntOrNull() ?: throw DeviceException(ErrorCategory.ArgumentValue, "mtu: $mtu is no number")}") }
            changes["comment"]?.let { add("ip link set dev $device alias ${quote(it)}") }
            changes["disabled"]?.let { off ->
                if (off in ValueType.Flag.on && name == sessionInterface()) throw DeviceException(ErrorCategory.ArgumentValue, "this session goes over $name, and it would end with it")
                add("ip link set dev $device ${if (off in ValueType.Flag.on) "down" else "up"}")
            }
        }
        if (steps.isNotEmpty()) root.output(steps.joinToString(" && "))
        if (changes["persist"] in ValueType.Flag.on) {
            changes["mtu"]?.ifEmpty { null }?.toIntOrNull()?.let { config.setMtu(name, it) }
            changes["disabled"]?.let { config.setEnabled(name, it !in ValueType.Flag.on) }
        }
    }

    // VLANs, bridges and dummies: made with ip, and written to the network configuration where asked
    private suspend fun virtuals(): List<Item> {
        val ids = if (iproute()) {
            json("ip -j -d link show type vlan").mapNotNull { it as? JsonObject }
                .associate { it.text("ifname") to ((it["linkinfo"] as? JsonObject)?.get("info_data") as? JsonObject)?.text("id").orEmpty() }
        } else {
            emptyMap()
        }
        return interfaces().filter { it["type"] in VIRTUAL_KINDS }.map { Item(it.id, it.values + ("vlan-id" to ids[it["name"]].orEmpty())) }
    }

    private suspend fun addVirtual(values: Map<String, String>): String {
        val kind = values["type"].orEmpty()
        val name = values["name"].orEmpty().also { if (!DEVICE.matches(it)) throw DeviceException(ErrorCategory.ArgumentValue, "name: $it is no name of an interface") }
        val parent = values["parent"].orEmpty()
        val id = values["vlan-id"]?.toIntOrNull()
        val command = when (kind) {
            "vlan" -> {
                if (!DEVICE.matches(parent)) throw DeviceException(ErrorCategory.ArgumentValue, "parent: a VLAN goes on an interface")
                if (id == null || id !in 1..4094) throw DeviceException(ErrorCategory.ArgumentValue, "vlan-id: a VLAN has a number from 1 to 4094")
                "ip link add link ${quote(parent)} name ${quote(name)} type vlan id $id"
            }
            "bridge", "dummy" -> "ip link add name ${quote(name)} type $kind"
            else -> throw DeviceException(ErrorCategory.ArgumentValue, "type: $kind is none of ${VIRTUAL_KINDS.joinToString()}")
        }
        root.output("$command && ip link set dev ${quote(name)} up")
        if (values["persist"] in ValueType.Flag.on) config.addLink(kind, name, parent, id)
        return interfaces().firstOrNull { it["name"] == name }?.id.orEmpty()
    }

    private suspend fun removeVirtuals(ids: List<String>) {
        val rows = virtuals().associateBy { it.id }
        val names = ids.map { id -> rows[id]?.get("name") ?: throw DeviceException(ErrorCategory.MissingItem, "the host has no virtual interface $id") }
        sessionInterface()?.takeIf { it in names }?.let { throw DeviceException(ErrorCategory.ArgumentValue, "this session goes over $it, and it would end with it") }
        root.output(names.joinToString(" && ") { "ip link del dev ${quote(it)}" })
        for (name in names) alsoKept { config.removeLink(name) }
    }

    // what goes from the running system goes from the network configuration too, where it is there
    private suspend fun alsoKept(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: DeviceException) {
            if (e.category != ErrorCategory.MissingItem && e.category != ErrorCategory.Unsupported) throw e
        }
    }

    // the interface with the address this session came to
    private suspend fun sessionInterface(): String? {
        val local = sessionAddress() ?: return null
        return (addressRows(v6 = false) + addressRows(v6 = true)).firstOrNull { it["local"] == local }?.get("ifname")
    }

    private suspend fun addressRows(v6: Boolean): List<Map<String, String>> = if (iproute()) {
        json(if (v6) "ip -j -6 addr show" else "ip -j -4 addr show").mapNotNull { it as? JsonObject }.flatMap { link ->
            link["addr_info"]?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }.map { address -> address.mapValues { plain(it.value) } + ("ifname" to link.text("ifname")) }
        }
    } else {
        output(if (v6) "ip -o -6 addr show" else "ip -o -4 addr show").lines().map { it.substringBefore('\\').trim().split(blanks) }.filter { it.size >= 4 }.map { words ->
            val (local, prefix) = words[3].split('/').let { it[0] to it.getOrElse(1) { "" } }
            val scope = words.indexOf("scope").takeIf { it > 0 }?.let { words.getOrNull(it + 1) }.orEmpty()
            val label = words.last().takeIf { !v6 && words.size > words.indexOf("scope") + 2 && words.indexOf("scope") > 0 }.orEmpty()
            mapOf("ifname" to words[1].removeSuffix(":"), "local" to local, "prefixlen" to prefix, "scope" to scope, "label" to label, "dynamic" to "${"dynamic" in words}")
        }
    }

    private suspend fun addresses(v6: Boolean): List<Item> = addressRows(v6).map { address ->
        val local = address["local"].orEmpty()
        val prefix = address["prefixlen"].orEmpty()
        val name = address["ifname"].orEmpty()
        Item("$name/$local/$prefix", buildMap {
            put("address", "$local/$prefix")
            if (!v6) prefix.toIntOrNull()?.let { put("network", network(local, it)) }
            put("interface", address["label"].orEmpty().ifEmpty { name })
            put("scope", address["scope"].orEmpty())
            put("dynamic", "${address["dynamic"] == "true"}")
        })
    }

    private suspend fun addAddress(values: Map<String, String>, v6: Boolean): String {
        val address = values["address"].orEmpty().also { if (!PREFIXED.matches(it)) throw DeviceException(ErrorCategory.ArgumentValue, "address: $it is not an address with its prefix, as 192.0.2.5/24") }
        val name = values["interface"].orEmpty().also { if (!DEVICE.matches(it)) throw DeviceException(ErrorCategory.ArgumentValue, "interface: $it is no interface") }
        root.output("ip ${if (v6) "-6" else "-4"} addr add $address dev ${quote(name)}")
        if (values["persist"] in ValueType.Flag.on) config.addAddress(name, address)
        return "$name/$address"
    }

    // the new address goes on first, so one the host refuses leaves the old in place
    private suspend fun setAddress(id: String?, changes: Map<String, String?>, v6: Boolean) {
        val old = addresses(v6).firstOrNull { it.id == id } ?: throw DeviceException(ErrorCategory.MissingItem, "the host has no address $id")
        val name = old.id.substringBefore('/')
        val address = changes["address"]?.ifEmpty { null } ?: old["address"].orEmpty()
        val face = changes["interface"]?.ifEmpty { null } ?: name
        if (address != old["address"] || face != name) {
            if (old["address"].orEmpty().substringBefore('/') == sessionAddress()) throw DeviceException(ErrorCategory.ArgumentValue, "this session came to ${old["address"]}, and it would end with it")
            addAddress(mapOf("address" to address, "interface" to face), v6)
            root.output("ip addr del ${old["address"]} dev ${quote(name)}")
        }
        // the address as the form has it is what the host comes up with, in place of the old one
        if (changes["persist"] in ValueType.Flag.on) {
            alsoKept { config.removeAddress(name, old["address"].orEmpty()) }
            config.addAddress(face, address)
        }
    }

    private suspend fun sessionAddress(): String? = ssh.exec("echo \$SSH_CONNECTION").output.trim().split(' ').getOrNull(2)?.removePrefix("::ffff:")

    private suspend fun removeAddresses(ids: List<String>) {
        val local = sessionAddress()
        val steps = ids.map { id ->
            val name = id.substringBefore('/')
            val address = id.substringAfter('/')
            if (!DEVICE.matches(name) || !PREFIXED.matches(address)) throw DeviceException(ErrorCategory.MissingItem, "the host has no address $id")
            if (address.substringBefore('/') == local) throw DeviceException(ErrorCategory.ArgumentValue, "this session came to $address, and it would end with it")
            "ip addr del $address dev ${quote(name)}"
        }
        root.output(steps.joinToString(" && "))
        for (id in ids) alsoKept { config.removeAddress(id.substringBefore('/'), id.substringAfter('/')) }
    }

    private suspend fun routeRows(v6: Boolean): List<Map<String, String>> = if (iproute()) {
        json(if (v6) "ip -j -6 route show table all" else "ip -j route show table all").mapNotNull { it as? JsonObject }.map { route -> route.mapValues { plain(it.value) } }
    } else {
        output(if (v6) "ip -6 route show table all" else "ip route show table all").lines().map { it.trim().split(blanks) }.filter { it.isNotEmpty() && it[0].isNotEmpty() }.map { words ->
            val typed = words[0] in routeTypes
            val rest = words.drop(if (typed) 2 else 1)
            val named = rest.zipWithNext().filter { it.first in routeKeys }.associate { (key, value) -> routeKeys.getValue(key) to value }
            named + mapOf("type" to if (typed) words[0] else "", "dst" to words.getOrElse(if (typed) 1 else 0) { "" })
        }
    }

    private suspend fun routes(v6: Boolean): List<Item> = routeRows(v6)
        .filter { it["table"] != "local" && it["type"] !in setOf("local", "broadcast", "anycast", "multicast") }
        .map { route ->
            val destination = route["dst"].orEmpty().let { if (it == "default") (if (v6) "::/0" else "0.0.0.0/0") else if ('/' in it) it else "$it/${if (v6) 128 else 32}" }
            val protocol = route["protocol"].orEmpty()
            val gateway = route["gateway"].orEmpty()
            val static = protocol == "static" || protocol == "boot" || protocol.isEmpty() && gateway.isNotEmpty()
            val table = route["table"].orEmpty().ifEmpty { "main" }
            Item(listOf(destination, gateway, route["dev"].orEmpty(), table, route["metric"].orEmpty()).joinToString("/"), buildMap {
                put("dst-address", destination)
                put("gateway", gateway)
                put("interface", route["dev"].orEmpty())
                put("protocol", protocol)
                put("metric", route["metric"].orEmpty())
                put("routing-table", table)
                put("pref-src", route["prefsrc"].orEmpty())
                put("type", route["type"].orEmpty().ifEmpty { "unicast" })
                put("active", "true")
                put("connect", "${(protocol == "kernel" || protocol.isEmpty()) && route["scope"] == "link" && gateway.isEmpty()}")
                put("static", "$static")
                put("dynamic", "${!static}")
            })
        }

    private suspend fun addRoute(values: Map<String, String>, v6: Boolean): String {
        val destination = values["dst-address"].orEmpty().also { if (it != "default" && !PREFIXED.matches(it)) throw DeviceException(ErrorCategory.ArgumentValue, "dst-address: $it is not a network, as 192.0.2.0/24") }
        val gateway = values["gateway"].orEmpty().also { if (it.isNotEmpty() && !ADDRESS.matches(it)) throw DeviceException(ErrorCategory.ArgumentValue, "gateway: $it is no address") }
        val name = values["interface"].orEmpty().also { if (it.isNotEmpty() && !DEVICE.matches(it)) throw DeviceException(ErrorCategory.ArgumentValue, "interface: $it is no interface") }
        if (gateway.isEmpty() && name.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "a route goes through a gateway or an interface")
        root.output("ip ${if (v6) "-6" else "-4"} route add $destination${routeTail(gateway, name, values["metric"].orEmpty(), values["routing-table"].orEmpty())}")
        if (values["persist"] in ValueType.Flag.on) keepRoute(values)
        return destination
    }

    // a route keeps its place in the table under the same destination, metric and table; another key is a new route, and the old one goes after it is in
    private suspend fun setRoute(id: String?, changes: Map<String, String?>, v6: Boolean) {
        val old = routes(v6).firstOrNull { it.id == id } ?: throw DeviceException(ErrorCategory.MissingItem, "the host has no route $id")
        if (old["type"] != "unicast") throw DeviceException(ErrorCategory.ArgumentValue, "a ${old["type"]} route is removed and added again")
        val merged = ROUTE_ARGUMENTS.associate { it.name to (changes[it.name] ?: old[it.name].orEmpty()) }
        if (merged.any { (name, value) -> value != old[name].orEmpty() }) {
            if (listOf("dst-address", "metric", "routing-table").all { merged[it] == old[it].orEmpty() }) {
                root.output("ip ${if (v6) "-6" else "-4"} route replace ${merged["dst-address"]}${routeTail(merged["gateway"].orEmpty(), merged["interface"].orEmpty(), merged["metric"].orEmpty(), merged["routing-table"].orEmpty())}")
            } else {
                addRoute(merged, v6)
                removeRoutes(listOf(old.id), v6)
            }
        }
        if (changes["persist"] in ValueType.Flag.on) {
            alsoKept { forgetRoute(old.values) }
            keepRoute(merged)
        }
    }

    // the configuration names the main table by leaving it out, and a default route as default
    private fun routeOf(values: Map<String, String>) = listOf(
        values["dst-address"].orEmpty().let { if (it == "0.0.0.0/0" || it == "::/0") "default" else it },
        values["gateway"].orEmpty(), values["interface"].orEmpty(), values["metric"].orEmpty(), values["routing-table"].orEmpty().takeIf { it != "main" }.orEmpty(),
    )

    private suspend fun keepRoute(values: Map<String, String>) = routeOf(values).let { (destination, gateway, name, metric, table) -> config.addRoute(destination, gateway, name, metric, table) }

    private suspend fun forgetRoute(values: Map<String, String>) = routeOf(values).let { (destination, gateway, name, metric, table) -> config.removeRoute(destination, gateway, name, metric, table) }

    private suspend fun removeRoutes(ids: List<String>, v6: Boolean) {
        val rows = routes(v6).associateBy { it.id }
        val steps = ids.map { id ->
            val route = rows[id] ?: throw DeviceException(ErrorCategory.MissingItem, "the host has no route $id")
            val type = route["type"].orEmpty().takeIf { it != "unicast" }?.let { "$it " }.orEmpty()
            "ip ${if (v6) "-6" else "-4"} route del $type${route["dst-address"]}${routeTail(route["gateway"].orEmpty(), route["interface"].orEmpty(), route["metric"].orEmpty(), route["routing-table"].orEmpty())}"
        }
        root.output(steps.joinToString(" && "))
        for (id in ids) rows[id]?.takeIf { it["type"] == "unicast" }?.let { route -> alsoKept { forgetRoute(route.values) } }
    }

    private fun routeTail(gateway: String, name: String, metric: String, table: String): String = buildString {
        if (gateway.isNotEmpty()) append(" via $gateway")
        if (name.isNotEmpty()) append(" dev ${quote(name)}")
        metric.ifEmpty { null }?.let { append(" metric ${it.toIntOrNull() ?: throw DeviceException(ErrorCategory.ArgumentValue, "metric: $it is no number")}") }
        table.ifEmpty { null }?.takeIf { it != "main" }?.let { if (DEVICE.matches(it)) append(" table $it") else throw DeviceException(ErrorCategory.ArgumentValue, "routing-table: $it is no table") }
    }

    private suspend fun neighbourRows(v6: Boolean): List<Map<String, String>> = if (iproute()) {
        json(if (v6) "ip -j -6 neigh show" else "ip -j -4 neigh show").mapNotNull { it as? JsonObject }.map { neighbour ->
            neighbour.mapValues { plain(it.value) } + ("state" to neighbour.strings("state").joinToString(","))
        }
    } else {
        output(if (v6) "ip -6 neigh show" else "ip -4 neigh show").lines().map { it.trim().split(blanks) }.filter { it.size >= 3 }.map { words ->
            val named = words.zipWithNext().filter { it.first == "dev" || it.first == "lladdr" }.associate { it }
            buildMap {
                put("dst", words[0])
                put("dev", named["dev"].orEmpty())
                put("lladdr", named["lladdr"].orEmpty())
                put("state", words.last().takeIf { word -> word.all { it.isUpperCase() } }.orEmpty())
                if ("router" in words) put("router", "true")
            }
        }
    }

    private suspend fun neighbours(v6: Boolean): List<Item> = neighbourRows(v6).map { neighbour ->
        val states = neighbour["state"].orEmpty().split(',').filter { it.isNotEmpty() }
        val mac = neighbour["lladdr"].orEmpty().uppercase()
        Item("${neighbour["dev"]}/${neighbour["dst"]}", buildMap {
            put("address", neighbour["dst"].orEmpty())
            put("mac-address", mac)
            put("interface", neighbour["dev"].orEmpty())
            put("status", states.joinToString(",") { it.lowercase() })
            put("dynamic", "${"PERMANENT" !in states}")
            put("complete", "${mac.isNotEmpty()}")
            if ("router" in neighbour) put("router", "true")
        })
    }

    private suspend fun addNeighbour(values: Map<String, String>, v6: Boolean): String {
        val address = values["address"].orEmpty().also { if (!ADDRESS.matches(it)) throw DeviceException(ErrorCategory.ArgumentValue, "address: $it is no address") }
        val mac = values["mac-address"].orEmpty().also { if (!MAC.matches(it)) throw DeviceException(ErrorCategory.ArgumentValue, "mac-address: $it is no MAC address") }
        val name = values["interface"].orEmpty().also { if (!DEVICE.matches(it)) throw DeviceException(ErrorCategory.ArgumentValue, "interface: $it is no interface") }
        root.output("ip ${if (v6) "-6" else "-4"} neigh replace $address lladdr $mac dev ${quote(name)} nud permanent")
        return "$name/$address"
    }

    private suspend fun setNeighbour(id: String?, changes: Map<String, String?>, v6: Boolean) {
        val old = neighbours(v6).firstOrNull { it.id == id } ?: throw DeviceException(ErrorCategory.MissingItem, "the host has no neighbour $id")
        val values = NEIGHBOUR_ARGUMENTS.associate { it.name to (changes[it.name]?.ifEmpty { null } ?: old[it.name].orEmpty()) }
        addNeighbour(values, v6)
        if (values["address"] != old["address"] || values["interface"] != old["interface"]) removeNeighbours(listOf(old.id), v6)
    }

    private suspend fun removeNeighbours(ids: List<String>, v6: Boolean) {
        val steps = ids.map { id ->
            val name = id.substringBefore('/')
            val address = id.substringAfter('/')
            if (!DEVICE.matches(name) || !ADDRESS.matches(address)) throw DeviceException(ErrorCategory.MissingItem, "the host has no neighbour $id")
            "ip ${if (v6) "-6" else "-4"} neigh del $address dev ${quote(name)}"
        }
        root.output(steps.joinToString(" && "))
    }

    // udp UNCONN 0 0 0.0.0.0:68 0.0.0.0:* users:(("dhclient",pid=94,fd=7)); without root ss names the processes of the login only
    private suspend fun sockets(): List<Item> = output("ss -H -tunap").lines().map { it.trim().split(blanks, limit = 7) }.filter { it.size >= 6 }.map { row ->
        fun address(text: String) = text.substringBeforeLast(':').removePrefix("[").removeSuffix("]").removePrefix("::ffff:")
        val process = Regex("""\("([^"]*)",pid=(\d+)""").findAll(row.getOrElse(6) { "" }).map { "${it.groupValues[1]} (${it.groupValues[2]})" }.distinct().joinToString(", ")
        Item(
            "${row[0]} ${row[4]} ${row[5]}",
            linkedMapOf(
                "protocol" to row[0], "state" to row[1].lowercase(), "local-address" to address(row[4]), "local-port" to row[4].substringAfterLast(':'),
                "remote-address" to address(row[5]), "remote-port" to row[5].substringAfterLast(':'), "process" to process, "receive-queue" to row[2], "send-queue" to row[3],
            ),
        )
    }

    // the ports something waits on and what: root sees the processes of every user, a login without it its own
    private suspend fun listening(): List<Item> {
        val command = "ss -H -tulnpe; echo $SECTION; cat /etc/passwd"
        val text = try {
            root.output(command)
        } catch (e: DeviceException) {
            if (e.category != ErrorCategory.PermissionDenied) throw e
            output(command)
        }
        val (sockets, passwd) = text.split("$SECTION\n").let { it[0] to it.getOrElse(1) { "" } }
        return parseListening(sockets, passwd)
    }

    private suspend fun sections(vararg commands: String): List<String> =
        output(commands.joinToString("; echo $SECTION; ") { "$it 2>/dev/null" }).split("$SECTION\n")

    private suspend fun resource(): Map<String, String> {
        val parts = sections(
            "cat /proc/uptime", "cat /proc/loadavg", "cat /proc/meminfo", "head -1 /proc/stat", "cat /etc/os-release", "uname -m", "grep -c ^processor /proc/cpuinfo",
            "cat /proc/device-tree/model || cat /sys/class/dmi/id/product_name",
        )
        val (uptime, load, memory, stat) = parts
        val (release, machine, count, model) = parts.drop(4) + List(4) { "" }
        val meminfo = memory.lines().associate { it.substringBefore(':') to (it.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0) }
        val times = stat.trim().split(blanks).drop(1).mapNotNull(String::toLongOrNull)
        val previous = cpu
        cpu = times
        return buildMap {
            uptime.trim().substringBefore(' ').substringBefore('.').toLongOrNull()?.let { put("uptime", duration(it)) }
            put("load", load.trim().split(blanks).take(3).joinToString(" "))
            if (previous != null && times.size >= 8 && previous.size >= 8) {
                val busy = listOf(0, 1, 2, 5, 6, 7).sumOf { times[it] - previous[it] }
                val all = busy + times[3] - previous[3] + times[4] - previous[4]
                if (all > 0) put("cpu-load", "${busy * 100 / all}")
            }
            put("cpu-count", count.trim())
            meminfo["MemTotal"]?.let { put("total-memory", "${it * 1024}") }
            (meminfo["MemAvailable"] ?: meminfo["MemFree"])?.let { put("free-memory", "${it * 1024}") }
            meminfo["SwapTotal"]?.let { put("total-swap", "${it * 1024}") }
            meminfo["SwapFree"]?.let { put("free-swap", "${it * 1024}") }
            put("version", release.lines().firstOrNull { it.startsWith("PRETTY_NAME=") }?.substringAfter('=')?.trim('"').orEmpty())
            put("architecture-name", machine.trim())
            put("board-name", model.trim().trimEnd('\u0000'))
        }
    }

    // what the dashboard shows besides the resources, in one command; busybox ps sorts nothing, and its lists stay empty
    private suspend fun status(): Map<String, String> = parseHostStatus(
        sections(
            "hostname", "uname -r", "systemd-detect-virt", "grep -m1 -i -E '^(model name|hardware|cpu model)' /proc/cpuinfo", "df -P -k", "cat /proc/mounts",
            "ip route show default", "grep '^nameserver' /etc/resolv.conf", "ip -o addr show", "systemctl --failed --no-legend --plain --no-pager",
            "systemctl list-units --type=service --state=running --no-legend --plain --no-pager", "who", "ps -eo pid=,user=,pcpu=,rss=,comm= --sort=-pcpu",
            "ps -eo pid=,user=,pcpu=,rss=,comm= --sort=-rss", "for z in /sys/class/thermal/thermal_zone*; do echo \"\$(cat \$z/type) \$(cat \$z/temp)\"; done",
            "ss -H -tuln", "for s in firewalld ufw nftables; do echo \"\$s \$(systemctl is-active \$s)\"; done", "[ -f /var/run/reboot-required ] && echo yes",
            "date '+%Y-%m-%d %H:%M:%S'",
        ),
    )

    private suspend fun clock(): Map<String, String> {
        val now = output("date '+%Y-%m-%d %H:%M:%S %z'").trim().split(' ')
        if (now.size < 3) return emptyMap()
        return mapOf("date" to now[0], "time" to now[1], "gmt-offset" to now[2])
    }

    private suspend fun processes(): List<Item> {
        val full = ssh.exec("ps -eo pid=,user=,pcpu=,rss=,stat=,args=")
        if (full.exitStatus == 0 && full.output.isNotBlank()) {
            return full.output.lines().map { it.trim().split(blanks, limit = 6) }.filter { it.size >= 6 }.map { row ->
                Item(row[0], mapOf("pid" to row[0], "user" to row[1], "cpu" to row[2], "memory" to (row[3].toLongOrNull()?.times(1024)?.toString().orEmpty()), "stat" to row[4], "command" to row[5]))
            }
        }
        // busybox: Alpine prints the columns asked for, OpenWrt only those of ps w
        val busybox = ssh.exec("ps -o pid,user,vsz,stat,args").takeIf { it.exitStatus == 0 && it.output.isNotBlank() }?.output ?: output("ps w")
        return busybox.lines().drop(1).map { it.trim().split(blanks, limit = 5) }.filter { it.size >= 5 }.map { row ->
            Item(row[0], mapOf("pid" to row[0], "user" to row[1], "vsz" to (row[2].toLongOrNull()?.times(1024)?.toString() ?: row[2]), "stat" to row[3], "command" to row[4]))
        }
    }

    private suspend fun signal(command: String, arguments: Map<String, String>): List<Item> {
        val pids = arguments["numbers"].orEmpty().split(',').filter { it.isNotEmpty() }
        if (pids.any { pid -> !pid.all(Char::isDigit) }) throw DeviceException(ErrorCategory.ArgumentValue, "$pids are not process numbers")
        root.output("kill -${SIGNALS.getValue(command)} ${pids.joinToString(" ")}")
        return emptyList()
    }

    private suspend fun services(): List<Item> = if ("systemctl" in tools()) systemdServices() else openRcServices()

    // the units systemd has loaded, then the ones it only has files of, which enable and start as well
    private suspend fun systemdServices(): List<Item> {
        val (units, files) = sections(
            "systemctl list-units --type=service --all --no-legend --plain --no-pager",
            "systemctl list-unit-files --type=service --no-legend --plain --no-pager",
        ) + List(2) { "" }
        val states = files.lines().map { it.trim().split(blanks) }.filter { it.size >= 2 && it[0].endsWith(".service") }.associate { it[0] to it[1] }
        val loaded = units.lines().map { it.trim().split(blanks, limit = 5) }.filter { it.size >= 4 && it[0].endsWith(".service") && it[1] != "not-found" }
        val unloaded = states.filterKeys { name -> !name.endsWith("@.service") && loaded.none { it[0] == name } }.filterValues { it != "alias" }
        return loaded.map { row ->
            Item(row[0], linkedMapOf(
                "name" to row[0].removeSuffix(".service"),
                "enabled" to states[row[0]].orEmpty(),
                "load" to row[1],
                "status" to "${row[2]} (${row[3]})",
                "running" to "${row[3] == "running"}",
                "disabled" to "${row[1] == "masked"}",
                "description" to row.getOrElse(4) { "" },
            ))
        } + unloaded.map { (name, state) ->
            Item(name, linkedMapOf("name" to name.removeSuffix(".service"), "enabled" to state, "load" to "", "status" to "inactive (dead)", "running" to "false", "disabled" to "${state == "masked"}", "description" to ""))
        }
    }

    // OpenRC: rc-status has the state of what ran, rc-update the runlevels of every service
    private suspend fun openRcServices(): List<Item> {
        val (status, levels) = sections("rc-status -a", "rc-update show -v") + List(2) { "" }
        val states = status.lines().filter { it.startsWith(" ") && '[' in it }.associate { it.trim().substringBefore(' ') to it.substringAfter('[').substringBefore(']').trim() }
        return levels.lines().filter { '|' in it }.map { line ->
            val name = line.substringBefore('|').trim()
            val state = states[name] ?: "stopped"
            Item(name, linkedMapOf(
                "name" to name,
                "enabled" to line.substringAfter('|').trim().split(blanks).filter { it.isNotEmpty() }.joinToString(" "),
                "load" to "",
                "status" to state,
                "running" to "${state == "started"}",
                "disabled" to "false",
                "description" to "",
            ))
        }
    }

    private suspend fun service(command: String, arguments: Map<String, String>): List<Item> {
        val names = arguments["numbers"].orEmpty().split(',').filter { it.isNotEmpty() }
        names.firstOrNull { !DEVICE.matches(it) }?.let { throw DeviceException(ErrorCategory.ArgumentValue, "$it is no service") }
        if (names.isEmpty()) return emptyList()
        val line = if ("systemctl" in tools()) {
            "systemctl $command ${names.joinToString(" ")}"
        } else {
            names.joinToString(" && ") { name ->
                when (command) {
                    "enable" -> "rc-update add $name default"
                    "disable" -> "rc-update -a del $name"
                    else -> "rc-service $name $command"
                }
            }
        }
        root.output(line)
        return emptyList()
    }

    private companion object {
        val INTERFACE = listOf(
            "name", "type", "mtu", "mac-address", "running", "disabled", "comment", "master", "parent", "rx-byte", "tx-byte", "rx-packet", "tx-packet",
            "rx-drop", "tx-drop", "rx-error", "tx-error",
        )
        val INTERFACE_ARGUMENTS = listOf(
            Argument("mtu", ValueType.Integer(min = 68, max = 65535)),
            Argument("disabled", ValueType.YesNo),
            Argument("comment", ValueType.Text()),
        )
        val ADDRESS_ARGUMENTS = listOf(
            Argument("address", ValueType.Text(), description = "192.0.2.5/24"),
            Argument("interface", ValueType.Text()),
        )
        val ROUTE = listOf("dst-address", "gateway", "interface", "protocol", "metric", "routing-table", "pref-src", "type", "active", "connect", "static", "dynamic")
        val ROUTE_ARGUMENTS = listOf(
            Argument("dst-address", ValueType.Text(), description = "192.0.2.0/24 or default"),
            Argument("gateway", ValueType.Text(), optional = true),
            Argument("interface", ValueType.Text(), optional = true),
            Argument("metric", ValueType.Integer(min = 0), optional = true),
            Argument("routing-table", ValueType.Text(), optional = true),
        )
        val PERSIST = Argument("persist", ValueType.YesNo, optional = true)
        val VIRTUAL_KINDS = listOf("vlan", "bridge", "dummy")
        val VIRTUAL = listOf("name", "type", "parent", "vlan-id", "mtu", "mac-address", "running", "disabled", "comment")
        val VIRTUAL_ARGUMENTS = listOf(
            Argument("type", ValueType.Enumeration(closed = true), VIRTUAL_KINDS, default = "vlan"),
            Argument("name", ValueType.Text()),
            Argument("parent", ValueType.Text(), optional = true, depends = listOf(mapOf("type" to "vlan"))),
            Argument("vlan-id", ValueType.Integer(min = 1, max = 4094), optional = true, depends = listOf(mapOf("type" to "vlan"))),
        )
        val NEIGHBOUR = listOf("address", "mac-address", "interface", "status", "dynamic", "complete")
        val NEIGHBOUR_ARGUMENTS = listOf(
            Argument("address", ValueType.Text()),
            Argument("mac-address", ValueType.Mac),
            Argument("interface", ValueType.Text()),
        )
        val SOCKET = listOf("protocol", "state", "local-address", "local-port", "remote-address", "remote-port", "process", "receive-queue", "send-queue")
        val LISTEN = listOf("protocol", "address", "port", "process", "pid", "user", "interface")
        val STATUS = listOf(
            "hostname", "version", "kernel", "architecture-name", "virtualization", "cpu-model", "cpu-count", "cpu-load", "load", "uptime", "time",
            "total-memory", "free-memory", "total-swap", "free-swap", "gateway", "gateway-interface", "dns-servers", "failed", "running-services", "users",
            "listening", "firewall", "reboot-required",
        )
        val SIGNALS = linkedMapOf("hang-up" to "HUP", "terminate" to "TERM", "kill" to "KILL")
        val DEVICE = Regex("[A-Za-z0-9_.@:-]+")
        val ADDRESS = Regex("[0-9A-Fa-f.:]+")
        val PREFIXED = Regex("[0-9A-Fa-f.:]+/\\d{1,3}")
        val MAC = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
    }
}

private const val SECTION = "--rd-section--"

private const val TOOLS = $$"""for t in ufw firewall-cmd nft iptables-save ip6tables-save apt-get dnf yum zypper pacman apk systemctl rc-service journalctl ss findmnt lsblk smartctl nmcli netplan networkctl ifup; do """ +
    $$"""for d in $(echo "$PATH" | tr : ' ') /usr/local/sbin /usr/sbin /sbin /usr/bin /bin; do if [ -x "$d/$t" ]; then echo "$t"; break; fi; done; done"""

private val blanks = Regex("\\s+")

private val types = mapOf("ether" to "ether", "loopback" to "loopback", "wireguard" to "wg", "bridge" to "bridge", "vlan" to "vlan", "bond" to "bond", "none" to "tunnel", "wlan" to "wlan")

private val arphrd = mapOf("1" to "ether", "772" to "loopback", "65534" to "none")

private const val SYSFS = $$"""for d in /sys/class/net/*; do n=${d##*/}; s=$d/statistics; m=$(readlink $d/master 2>/dev/null); """ +
    $$"""t=$(sed -n 's/^DEVTYPE=//p' $d/uevent 2>/dev/null); echo "$n|$(cat $d/ifindex)|$(cat $d/type)|$(cat $d/flags)|$(cat $d/mtu)|$(cat $d/operstate)|""" +
    $$"""$(cat $d/address)|$(cat $s/rx_bytes)|$(cat $s/tx_bytes)|$(cat $s/rx_packets)|$(cat $s/tx_packets)|$(cat $s/rx_dropped)|$(cat $s/tx_dropped)|""" +
    $$"""$(cat $s/rx_errors)|$(cat $s/tx_errors)|${m##*/}|$t|$(cat $d/ifalias 2>/dev/null)"; done"""

private val routeTypes = setOf("unicast", "unreachable", "blackhole", "prohibit", "local", "broadcast", "anycast", "multicast", "throw", "nat")
private val routeKeys = mapOf("via" to "gateway", "dev" to "dev", "proto" to "protocol", "scope" to "scope", "src" to "prefsrc", "metric" to "metric", "table" to "table")

private val facilities = listOf("kern", "user", "mail", "daemon", "auth", "syslog", "lpr", "news", "uucp", "cron", "authpriv", "ftp", "ntp", "security", "console", "solaris-cron", "local0", "local1", "local2", "local3", "local4", "local5", "local6", "local7")
private val levels = listOf("emerg", "alert", "crit", "err", "warning", "notice", "info", "debug")

private val REAL_FILE_SYSTEMS = setOf("ext2", "ext3", "ext4", "xfs", "btrfs", "zfs", "f2fs", "vfat", "exfat", "ntfs", "ntfs3", "fuseblk", "jfs", "reiserfs", "hfsplus", "nfs", "nfs4", "cifs", "smb3")

// the sections of the dashboard command in its order; a disk is a file system of a device or a share, the root whatever it is
internal fun parseHostStatus(parts: List<String>): Map<String, String> {
    fun part(index: Int) = parts.getOrElse(index) { "" }
    val types = part(5).lines().map { it.split(' ') }.filter { it.size >= 3 }.associate { it[1] to it[2] }
    return buildMap {
        put("hostname", part(0).trim())
        put("kernel", part(1).trim())
        put("virtualization", part(2).trim().takeIf { it != "none" }.orEmpty())
        put("cpu-model", part(3).substringAfter(':').trim())
        part(4).lines().drop(1).map { it.trim().split(blanks) }.filter { it.size >= 6 }.filter { row ->
            val mount = row.drop(5).joinToString(" ")
            mount == "/" || row[0].startsWith("/") && types[mount] != "squashfs" || types[mount] in REAL_FILE_SYSTEMS
        }.forEachIndexed { index, row ->
            val mount = row.drop(5).joinToString(" ")
            put("disk.$index.mount", mount)
            put("disk.$index.device", row[0])
            put("disk.$index.type", types[mount].orEmpty())
            put("disk.$index.size", row[1].toLongOrNull()?.times(1024)?.toString().orEmpty())
            put("disk.$index.used", row[2].toLongOrNull()?.times(1024)?.toString().orEmpty())
            put("disk.$index.free", row[3].toLongOrNull()?.times(1024)?.toString().orEmpty())
        }
        val route = part(6).lineSequence().firstOrNull { it.startsWith("default") }.orEmpty().split(' ')
        fun after(words: List<String>, word: String) = words.indexOf(word).takeIf { it >= 0 }?.let { words.getOrNull(it + 1) }.orEmpty()
        put("gateway", after(route, "via"))
        put("gateway-interface", after(route, "dev"))
        put("dns-servers", part(7).lines().mapNotNull { it.trim().split(blanks).getOrNull(1) }.joinToString(", "))
        part(8).lines().map { it.substringBefore('\\').trim().split(blanks) }.filter { it.size >= 4 && "link" != after(it, "scope") && it[1] != "lo" }
            .groupBy({ it[1] }, { it[3] }).forEach { (name, addresses) -> put("net.$name.addresses", addresses.joinToString(", ")) }
        val failed = part(9).lines().map { it.trim() }.filter { it.isNotEmpty() }.map { it.substringBefore(' ').removeSuffix(".service") }
        put("failed", failed.joinToString(", "))
        put("running-services", "${part(10).lines().count { it.isNotBlank() }}")
        put("users", part(11).lines().mapNotNull { it.trim().split(blanks).firstOrNull()?.ifEmpty { null } }.distinct().joinToString(", "))
        for ((key, index) in listOf("top-cpu" to 12, "top-memory" to 13)) {
            part(index).lines().map { it.trim().split(blanks, limit = 5) }.filter { it.size == 5 }.take(5).forEachIndexed { place, row ->
                put("$key.$place.pid", row[0])
                put("$key.$place.user", row[1])
                put("$key.$place.cpu", row[2])
                put("$key.$place.memory", row[3].toLongOrNull()?.times(1024)?.toString().orEmpty())
                put("$key.$place.name", row[4])
            }
        }
        part(14).lines().map { it.trim().split(' ') }.filter { it.size == 2 && it[1].toLongOrNull() != null }.forEachIndexed { index, (zone, milli) ->
            put("temperature.$index.zone", zone)
            put("temperature.$index.celsius", "${milli.toLong() / 1000}")
        }
        put("listening", "${part(15).lines().count { it.isNotBlank() }}")
        put("firewall", part(16).lines().map { it.trim().split(' ') }.filter { it.size == 2 && it[1] == "active" }.joinToString(", ") { it[0] })
        put("reboot-required", "${part(17).trim() == "yes"}")
        put("time", part(18).trim())
    }
}

// tcp LISTEN 0 4096 [::]:22 [::]:* users:(("sshd",pid=1,fd=4)) ino:7812 sk:9 v6only:1 <->; ss -e leaves out the uid of root
internal fun parseListening(sockets: String, passwd: String): List<Item> {
    val users = passwd.lines().map { it.split(':') }.filter { it.size > 2 }.associate { it[2] to it[0] }
    val seen = HashMap<String, Int>()
    return sockets.lines().map { it.trim().split(blanks, limit = 7) }.filter { it.size >= 5 }.map { row ->
        val local = row[4]
        val address = local.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        val rest = row.getOrElse(6) { "" }
        val processes = Regex("""\("([^"]*)",pid=(\d+)""").findAll(rest).map { it.groupValues[1] to it.groupValues[2] }.distinct().toList()
        val uid = Regex("""\buid:(\d+)""").find(rest)?.groupValues?.get(1) ?: if ("ino:" in rest) "0" else null
        val protocol = row[0] + if (':' in address.substringBefore('%')) "6" else ""
        val key = "$protocol $local"
        val count = (seen[key] ?: 0) + 1
        seen[key] = count
        Item(
            if (count == 1) key else "$key#$count",
            linkedMapOf(
                "protocol" to protocol,
                "address" to address.substringBefore('%').removePrefix("::ffff:"),
                "port" to local.substringAfterLast(':'),
                "process" to processes.map { it.first }.distinct().joinToString(", "),
                "pid" to processes.map { it.second }.distinct().joinToString(", "),
                "user" to uid?.let { users[it] ?: it }.orEmpty(),
                "interface" to address.substringAfter('%', ""),
            ),
        )
    }
}

private fun JsonObject.text(key: String) = this[key]?.let(::plain).orEmpty()

private fun JsonObject.strings(key: String) = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

private fun plain(value: JsonElement) = (value as? JsonPrimitive)?.contentOrNull ?: value.toString()

private fun network(address: String, prefix: Int): String {
    val bytes = address.split('.').mapNotNull(String::toIntOrNull)
    if (bytes.size != 4) return ""
    return bytes.mapIndexed { i, byte -> byte and (0xFF shl (8 - (prefix - i * 8).coerceIn(0, 8))) and 0xFF }.joinToString(".")
}

internal fun journalEntry(line: String, offset: String): Item? {
    val entry = try {
        Json.parseToJsonElement(line).jsonObject
    } catch (e: SerializationException) {
        return null
    }
    val micros = entry.text("__REALTIME_TIMESTAMP").toLongOrNull() ?: return null
    val priority = entry.text("PRIORITY").toIntOrNull()
    val facility = entry.text("SYSLOG_FACILITY").toIntOrNull()
    val message = when (val value = entry["MESSAGE"]) {
        is JsonArray -> value.mapNotNull { it.jsonPrimitive.longOrNull?.toByte() }.toByteArray().decodeToString()
        else -> value?.let(::plain).orEmpty()
    }
    val identifier = entry.text("SYSLOG_IDENTIFIER")
    return Item(entry.text("__CURSOR").ifEmpty { "$micros" }, mapOf(
        "time" to local(micros / 1_000_000, offset),
        "topics" to listOfNotNull(facility?.let { facilities.getOrNull(it) }, priority?.let { levels.getOrNull(it) }).joinToString("."),
        "message" to if (identifier.isEmpty()) message else "$identifier: $message",
    ))
}

internal fun local(seconds: Long, offset: String): String {
    val sign = if (offset.startsWith("-")) -1 else 1
    val digits = offset.trimStart('+', '-')
    val shift = sign * ((digits.take(2).toLongOrNull() ?: 0) * 3600 + (digits.drop(2).take(2).toLongOrNull() ?: 0) * 60)
    return Instant.fromEpochSeconds(seconds + shift).toString().removeSuffix("Z").replace('T', ' ')
}
