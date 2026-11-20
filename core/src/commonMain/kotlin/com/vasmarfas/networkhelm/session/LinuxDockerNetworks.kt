package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.openwrt.quote
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.urlEncoded
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// the networks of the engine with the containers on them. A macvlan or ipvlan network on the interface this session came in by
// is made, and a container joins one, under the guard of the host: a container that takes an address the host or its gateway has
// cuts the host off its network, and the guard takes the change back when the session stops answering
internal class DockerNetworks(private val ssh: SshSession, private val api: DockerApi, private val guard: LinuxGuard) {
    // a container on a network by the ID of the network, empty while a stopped container of Docker holds no endpoint, and its name
    private class Attachment(val id: String, val network: String, val row: Item) {
        fun on(network: JsonObject) = id.isNotEmpty() && id == network.text("Id") || this.network == network.text("Name")
    }

    fun table(present: suspend () -> Boolean) = HostTable(
        NETWORK_COLUMNS, ::list, arguments = ::arguments, add = ::create, remove = ::remove, commands = NETWORK_COMMANDS, run = ::command, present = present,
    )

    private suspend fun list(): List<Item> {
        val networks = objects(api.get("/networks"), "/networks")
        val attached = attached()
        val offset = api.offset()
        return networks.map { network -> networkRow(network, attached.count { it.on(network) }, offset) }
    }

    // every container on every network, stopped ones too, which the networks of Docker leave out
    private suspend fun attached(): List<Attachment> = objects(api.get("/containers/json?all=1"), "/containers/json").flatMap { container ->
        container.inner("NetworkSettings")?.inner("Networks").orEmpty().map { (name, value) ->
            val endpoint = value as? JsonObject ?: JsonObject(emptyMap())
            Attachment(endpoint.text("NetworkID"), name, endpointRow(container, endpoint))
        }
    }

    // the parent of a macvlan or ipvlan network is one of the interfaces of the host, or typed
    private suspend fun arguments(): List<Argument> {
        val interfaces = lines(ssh.exec("ls /sys/class/net").output).filter { it != "lo" }.sorted()
        return NETWORK_ARGUMENTS.map { if (it.name == "parent") it.copy(choices = interfaces) else it }
    }

    private suspend fun create(values: Map<String, String>): String {
        val name = values["name"].orEmpty()
        if (name.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "the network needs a name")
        val driver = values["driver"]?.ifEmpty { null } ?: "bridge"
        val body = networkBody(values, driver, api.isPodman())
        if (!guarded(driver, values["parent"].orEmpty())) return api.created("/networks/create", body).text("Id")
        // the guard takes the network back by its name, which must not be that of a network made before
        if (objects(api.get("/networks"), "/networks").any { it.text("Name") == name }) throw DeviceException(ErrorCategory.ArgumentValue, "the engine has a network $name already")
        val cli = api.bareCli()
        var id = ""
        guard.guarded(LinuxGuard.Backup("docker", "true", "$cli network rm ${quote(name)}")) { id = api.created("/networks/create", body).text("Id") }
        return id
    }

    private suspend fun remove(ids: List<String>) {
        for (id in ids) removed { api.send("DELETE", "/networks/${urlEncoded(id)}") }
    }

    private suspend fun command(command: String, arguments: Map<String, String>): List<Item> {
        val network by lazy { arguments["id"]?.ifEmpty { null } ?: throw DeviceException(ErrorCategory.ArgumentValue, "the command needs a network") }
        return when (command) {
            "containers" -> {
                val inspected = inspect(network)
                attached().filter { it.on(inspected) }.map { it.row }
            }
            "inspect" -> listOf(Item(network, mapOf("json" to laidOut(inspect(network)))))
            "connect" -> {
                connect(network, arguments)
                emptyList()
            }
            "disconnect" -> {
                api.send("POST", "/networks/${urlEncoded(network)}/disconnect", buildJsonObject {
                    put("Container", member(arguments))
                    put("Force", arguments["force"] in ValueType.Flag.on)
                })
                emptyList()
            }
            // what docker network prune takes: networks of the user no container is on
            "unused" -> list().filter { it["containers"] == "0" && it["name"] !in PREDEFINED }
            else -> throw DeviceException(ErrorCategory.MissingItem, "container/network/$command is not on this host")
        }
    }

    private suspend fun inspect(network: String) =
        api.get("/networks/${urlEncoded(network)}") as? JsonObject ?: throw DeviceException(ErrorCategory.Api, "/networks/$network gave no JSON")

    private suspend fun connect(network: String, arguments: Map<String, String>) {
        val container = member(arguments)
        val aliases = arguments["aliases"].orEmpty().split(',', ' ', '\n').filter { it.isNotEmpty() }
        val v4 = arguments["ipv4-address"].orEmpty()
        val v6 = arguments["ipv6-address"].orEmpty()
        val body = buildJsonObject {
            put("Container", container)
            putJsonObject("EndpointConfig") {
                if (aliases.isNotEmpty()) put("Aliases", JsonArray(aliases.map(::JsonPrimitive)))
                if (v4.isNotEmpty() || v6.isNotEmpty()) {
                    putJsonObject("IPAMConfig") {
                        if (v4.isNotEmpty()) put("IPv4Address", v4)
                        if (v6.isNotEmpty()) put("IPv6Address", v6)
                    }
                }
            }
        }
        val path = "/networks/${urlEncoded(network)}/connect"
        val inspected = inspect(network)
        // Docker gives aliases on networks of the user alone and refuses the whole endpoint on its default bridge
        if (aliases.isNotEmpty() && inspected.inner("Options")?.text(DEFAULT_BRIDGE) == "true") {
            throw DeviceException(ErrorCategory.ArgumentValue, "the default bridge of Docker takes no aliases: they work on networks of the user")
        }
        if (!guarded(inspected.text("Driver"), inspected.inner("Options")?.text("parent").orEmpty())) {
            api.send("POST", path, body)
            return
        }
        // the guard takes the container off the network, which it must not have been on before
        if (attached().any { it.on(inspected) && (it.row.id == container || it.row["name"] == container) }) {
            throw DeviceException(ErrorCategory.ArgumentValue, "$container is on $network already")
        }
        val cli = api.bareCli()
        guard.guarded(LinuxGuard.Backup("docker", "true", "$cli network disconnect -f ${quote(network)} ${quote(container)}")) { api.send("POST", path, body) }
    }

    private fun member(arguments: Map<String, String>) =
        arguments["container"]?.ifEmpty { null } ?: throw DeviceException(ErrorCategory.ArgumentValue, "the command needs the id of a container")

    // a network that shares an interface of the host on its interface of the session, or on a VLAN of it, for an engine of root: a
    // rootless engine reaches no interface of the host
    private suspend fun guarded(driver: String, parent: String): Boolean {
        if (driver !in SHARING || parent.isEmpty()) return false
        val session = sessionInterface() ?: return false
        if (parent.substringBefore('.') != session && parent != session) return false
        return !api.rootless()
    }

    // the interface with the address this session came to
    private suspend fun sessionInterface(): String? {
        val lines = ssh.exec("echo \"\$SSH_CONNECTION\"; ip -o addr show").output.lines()
        val local = lines.firstOrNull()?.trim()?.split(' ')?.getOrNull(2)?.removePrefix("::ffff:")?.ifEmpty { null } ?: return null
        val face = lines.drop(1).map { it.trim().split(BLANKS) }.firstOrNull { it.size >= 4 && it[3].substringBefore('/') == local }
        return face?.get(1)?.removeSuffix(":")?.substringBefore('@')
    }
}

internal fun networkRow(network: JsonObject, containers: Int, offset: String): Item {
    val (v6, v4) = network.inner("IPAM")?.objects("Config").orEmpty().partition { ':' in it.text("Subnet") }
    val options = network.inner("Options").orEmpty()
    return Item(network.text("Id"), linkedMapOf(
        "name" to network.text("Name"),
        "driver" to network.text("Driver"),
        "scope" to network.text("Scope"),
        "subnet" to v4.firstOrNull()?.text("Subnet").orEmpty(),
        "gateway" to v4.firstOrNull()?.text("Gateway").orEmpty(),
        "ip-range" to v4.firstOrNull()?.text("IPRange").orEmpty(),
        "ipv6" to "${network.flag("EnableIPv6")}",
        "ipv6-subnet" to v6.firstOrNull()?.text("Subnet").orEmpty(),
        "ipv6-gateway" to v6.firstOrNull()?.text("Gateway").orEmpty(),
        "internal" to "${network.flag("Internal")}",
        "attachable" to "${network.flag("Attachable")}",
        "parent" to options.text("parent"),
        "mode" to MODES.firstNotNullOfOrNull { options.text(it).ifEmpty { null } }.orEmpty(),
        "containers" to "$containers",
        "created" to moment(network.text("Created"), offset),
        "options" to pairsOf(JsonObject(options.filterKeys { it != "parent" && it !in MODES })),
        "labels" to pairsOf(network.inner("Labels")),
        "id" to network.text("Id"),
    ))
}

// a container on a network as docker network inspect shows it, with the aliases it asked for: Podman adds the short ID by itself
internal fun endpointRow(container: JsonObject, endpoint: JsonObject): Item {
    val v4 = endpoint.text("IPAddress")
    return Item(container.text("Id"), linkedMapOf(
        "name" to container.texts("Names").firstOrNull().orEmpty().removePrefix("/"),
        "state" to container.text("State"),
        "ipv4-address" to if (v4.isEmpty()) "" else "$v4/${endpoint.text("IPPrefixLen")}",
        "ipv6-address" to endpoint.text("GlobalIPv6Address"),
        "mac-address" to endpoint.text("MacAddress"),
        "aliases" to endpoint.texts("Aliases").filter { it != container.text("Id").take(12) }.joinToString(", "),
    ))
}

// the body of docker network create; Podman calls the mode of macvlan and ipvlan mode, Docker names it by the driver
internal fun networkBody(values: Map<String, String>, driver: String, podman: Boolean): JsonObject = buildJsonObject {
    put("Name", values["name"].orEmpty())
    put("Driver", driver)
    val options = pairs(values["options"].orEmpty()).orEmpty().toMutableMap()
    if (driver in SHARING) {
        values["parent"]?.ifEmpty { null }?.let { options["parent"] = JsonPrimitive(it) }
        values["mode"]?.ifEmpty { null }?.let { options[if (podman) "mode" else "${driver}_mode"] = JsonPrimitive(it) }
    }
    if (options.isNotEmpty()) put("Options", JsonObject(options))
    val configs = listOfNotNull(
        ipam(values["subnet"].orEmpty(), values["gateway"].orEmpty(), values["ip-range"].orEmpty()),
        ipam(values["ipv6-subnet"].orEmpty(), values["ipv6-gateway"].orEmpty(), ""),
    )
    if (configs.isNotEmpty()) putJsonObject("IPAM") { put("Config", JsonArray(configs)) }
    if (values["ipv6"] in ValueType.Flag.on || values["ipv6-subnet"].orEmpty().isNotEmpty()) put("EnableIPv6", true)
    if (values["internal"] in ValueType.Flag.on) put("Internal", true)
    if (values["attachable"] in ValueType.Flag.on) put("Attachable", true)
    pairs(values["labels"].orEmpty())?.let { put("Labels", it) }
}

private fun ipam(subnet: String, gateway: String, range: String): JsonObject? = if (subnet.isEmpty() && gateway.isEmpty() && range.isEmpty()) null else buildJsonObject {
    if (subnet.isNotEmpty()) put("Subnet", subnet)
    if (gateway.isNotEmpty()) put("Gateway", gateway)
    if (range.isNotEmpty()) put("IPRange", range)
}

private val NETWORK_COLUMNS = listOf(
    "name", "driver", "subnet", "gateway", "ipv6-subnet", "parent", "mode", "internal", "containers", "scope", "created",
)

private val SHARING = setOf("macvlan", "ipvlan")

private val SHARED = listOf(mapOf("driver" to "macvlan"), mapOf("driver" to "ipvlan"))

private val NETWORK_ARGUMENTS = listOf(
    Argument("name", ValueType.Text()),
    Argument("driver", ValueType.Enumeration(), listOf("bridge", "macvlan", "ipvlan"), optional = true, default = "bridge"),
    Argument("parent", ValueType.Enumeration(), optional = true, depends = SHARED),
    Argument("mode", ValueType.Enumeration(), listOf("bridge", "private", "vepa", "passthru", "l2", "l3", "l3s"), optional = true, depends = SHARED),
    Argument("subnet", ValueType.Ip4Prefix, optional = true),
    Argument("gateway", ValueType.Ip4, optional = true),
    Argument("ip-range", ValueType.Ip4Prefix, optional = true),
    Argument("ipv6", ValueType.YesNo, optional = true),
    Argument("ipv6-subnet", ValueType.Ip6Prefix, optional = true),
    Argument("ipv6-gateway", ValueType.Ip6, optional = true),
    Argument("internal", ValueType.YesNo, optional = true),
    Argument("attachable", ValueType.YesNo, optional = true),
    many("options"),
    many("labels"),
) + listOf("scope", "created", "containers", "id").map { Argument(it, ValueType.Text(), readOnly = true) }

private val NETWORK_COMMANDS = listOf("containers", "inspect", "connect", "disconnect", "unused")

// the networks every engine has: docker network prune leaves them alone
private val PREDEFINED = setOf("bridge", "host", "none", "podman")

private val MODES = listOf("macvlan_mode", "ipvlan_mode", "mode")

private const val DEFAULT_BRIDGE = "com.docker.network.bridge.default_bridge"

private val BLANKS = Regex("\\s+")
