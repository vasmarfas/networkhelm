package com.vasmarfas.networkhelm.keenetic

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.model.duration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.roundToInt

internal val NO = JsonObject(mapOf("no" to JsonPrimitive(true)))
internal val NAME = Regex("[A-Za-z0-9_.-]+")
internal val USER = Regex("[A-Za-z0-9_.]+")
internal val MAC = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")

internal abstract class Node(path: String, val kind: NodeKind = NodeKind.List) {
    val path = MenuPath.parse(path)
    open val commands: List<String> = listOf("print")

    open suspend fun arguments(): List<Argument> = emptyList()

    abstract suspend fun read(): List<Item>

    open suspend fun add(values: Map<String, String>): String = throw readOnly()

    open suspend fun set(id: String?, changes: Map<String, String?>): Unit = throw readOnly()

    open suspend fun remove(ids: List<String>): Unit = throw readOnly()

    open suspend fun move(ids: List<String>, before: String?): Unit = throw DeviceException(ErrorCategory.Unsupported, "Keenetic keeps no order of $path")

    fun readOnly() = DeviceException(ErrorCategory.Unsupported, "$path of Keenetic is only shown")
}

internal class Shown(path: String, kind: NodeKind = NodeKind.List, val reader: suspend () -> List<Item>) : Node(path, kind) {
    override suspend fun read() = reader()
}

// a list of show rc whose elements the router takes back as they come, with "no" to remove one: by its index where
// show rc gives one, else by the key; sent again with the same key, an element changes in place
internal class Listed(
    path: String,
    private val api: KeeneticCall,
    private val fields: List<Argument>,
    private val key: List<String>,
    changes: Boolean = true,
) : Node(path) {
    override val commands = if (changes) listOf("print", "add", "set", "remove") else listOf("print", "add", "remove")

    override suspend fun arguments() = api.withInterfaces(fields)

    override suspend fun read(): List<Item> = rows(api.show("show/rc/$path")).map { row -> Item(row["index"] ?: identity(row.values), row.values) }

    override suspend fun add(values: Map<String, String>): String {
        api.post(listOf(nested(path.segments, element(values))))
        val sent = key.associateWith { values[it].orEmpty() }
        return read().lastOrNull { item -> sent.all { (name, value) -> item[name].orEmpty().equals(value, ignoreCase = true) } }?.id.orEmpty()
    }

    override suspend fun set(id: String?, changes: Map<String, String?>) {
        val old = read().firstOrNull { it.id == id } ?: throw DeviceException(ErrorCategory.MissingItem, "$path has no $id")
        val merged = fields.associate { it.name to old[it.name].orEmpty() }.filterValues { it.isNotEmpty() } + changes.mapValues { it.value.orEmpty() }
        api.post(listOf(nested(path.segments, element(merged))))
        // a new key makes a new element, so the old one goes only once the new one is in; the router may have taken the
        // new one for the old, as a route by a gateway on the interface the old one named
        if (key.any { merged[it].orEmpty() != old[it].orEmpty() } && read().any { it.id == old.id }) api.post(listOf(removal(old)))
    }

    override suspend fun remove(ids: List<String>) {
        val items = read().associateBy { it.id }
        api.post(ids.map { id -> removal(items[id] ?: throw DeviceException(ErrorCategory.MissingItem, "$path has no $id")) })
    }

    private fun identity(values: Map<String, String>) = key.mapNotNull { values[it]?.ifEmpty { null } }.joinToString(" ")

    // RCI drops an argument it does not know, and "no" with no key left removes the whole list
    private fun removal(item: Item): JsonObject {
        val names = item["index"]?.let { mapOf("index" to it) } ?: key.associateWith { item[it].orEmpty() }.filterValues { it.isNotEmpty() }
        if (names.isEmpty() || names.values.any { it.isBlank() }) throw DeviceException(ErrorCategory.ArgumentValue, "$path has an element with no key, which the app does not remove")
        return nested(path.segments, JsonObject(mapOf("no" to JsonPrimitive(true)) + names.mapValues { JsonPrimitive(it.value) }))
    }

    private fun element(values: Map<String, String>) = JsonObject(
        fields.filter { !it.readOnly }.mapNotNull { argument ->
            val value = values[argument.name]?.ifEmpty { null } ?: return@mapNotNull null
            when (argument.type) {
                ValueType.YesNo -> if (value in ValueType.Flag.on) argument.name to JsonPrimitive(true) else null
                else -> argument.name to JsonPrimitive(value)
            }
        }.toMap(),
    )
}

internal class Pools(private val api: KeeneticCall) : Node("ip/dhcp/pool") {
    override val commands = listOf("print", "add", "set", "remove")

    override suspend fun arguments() = api.withInterfaces(
        listOf(
            Argument("name", ValueType.Text()),
            Argument("begin", ValueType.Ip4, optional = true),
            Argument("end", ValueType.Ip4, optional = true),
            Argument("interface", ValueType.Text(), optional = true),
            Argument("lease", ValueType.Integer(0, unit = "s"), optional = true),
            Argument("disabled", ValueType.YesNo),
        ),
    )

    override suspend fun read(): List<Item> {
        val (config, state) = api.shows("rc/ip/dhcp/pool", "ip/dhcp/pool")
        return named(config).map { (name, pool) ->
            val shown = ((state as? JsonObject)?.get(name) as? JsonObject)?.let(::fields).orEmpty()
            Item(
                name,
                linkedMapOf(
                    "name" to name,
                    "begin" to pool["range.begin"].orEmpty(),
                    "end" to pool["range.end"].orEmpty(),
                    "interface" to pool["bind.interface"].orEmpty(),
                    "lease" to pool["lease"].orEmpty(),
                    "disabled" to "${pool["enable"] != "true"}",
                    "network" to shown["network"].orEmpty(),
                    "router" to shown["router.router"].orEmpty(),
                    "state" to shown["state"].orEmpty(),
                    "size" to shown["size"].orEmpty(),
                    "used" to shown["used"].orEmpty(),
                ),
            )
        }
    }

    override suspend fun add(values: Map<String, String>): String {
        val name = values["name"].orEmpty()
        if (!name.matches(NAME)) throw DeviceException(ErrorCategory.ArgumentValue, "a pool name of letters, digits, '_', '.' and '-' is needed")
        api.post(listOf(command(name, values.filterValues { it.isNotEmpty() } + ("disabled" to (values["disabled"] ?: "no")), emptyMap())))
        return name
    }

    override suspend fun set(id: String?, changes: Map<String, String?>) {
        val old = read().firstOrNull { it.id == id } ?: throw DeviceException(ErrorCategory.MissingItem, "ip/dhcp/pool has no $id")
        if (changes["name"]?.let { it != old.id } == true) throw DeviceException(ErrorCategory.Unsupported, "Keenetic does not rename a pool")
        api.post(listOf(command(old.id, changes, old.values)))
    }

    // a pool with no name may stand for every pool, as no without a key stands for the whole list
    override suspend fun remove(ids: List<String>) {
        if (ids.any { it.isBlank() }) throw DeviceException(ErrorCategory.ArgumentValue, "a pool with no name is not removed")
        api.post(ids.map { JsonObject(mapOf("parse" to JsonPrimitive("no ip dhcp pool ${quoted(it)}"))) })
    }

    private fun command(name: String, changes: Map<String, String?>, old: Map<String, String>): JsonObject {
        val parts = buildMap<String, JsonElement> {
            if ("begin" in changes || "end" in changes) {
                val begin = changes["begin"] ?: old["begin"].orEmpty()
                val end = changes["end"] ?: old["end"].orEmpty()
                if (begin.isNotEmpty() && end.isNotEmpty()) put("range", JsonObject(mapOf("begin" to JsonPrimitive(begin), "end" to JsonPrimitive(end))))
            }
            changes["interface"]?.let { put("bind", if (it.isEmpty()) NO else JsonObject(mapOf("interface" to JsonPrimitive(it)))) }
            changes["lease"]?.ifEmpty { null }?.let { put("lease", JsonPrimitive(it)) }
            changes["disabled"]?.let { put("enable", if (it in ValueType.Flag.on) NO else JsonPrimitive(true)) }
        }
        return nested(listOf("ip", "dhcp", "pool", name), JsonObject(parts))
    }
}

// the services of a user as the web interface of KeeneticOS 5.1 names them; show tags lists those of the installed components
internal val TAGS = mapOf(
    "manager" to "Limited rights admin",
    "cli" to "Command line",
    "http" to "Web interface",
    "readonly" to "Prohibit saving system settings",
    "cifs" to "SMB shares",
    "printers" to "Printers",
    "ftp" to "FTP server",
    "sftp" to "SFTP server",
    "webdav" to "WebDAV server",
    "vpn" to "PPTP VPN server",
    "sstp" to "SSTP VPN server",
    "ipsec-l2tp" to "L2TP/IPsec VPN server",
    "ipsec-ike1" to "IKEv1/IPsec VPN server",
    "ipsec-ike2" to "IKEv2/IPsec VPN server",
    "ipsec-xauth" to "IKEv1 & IKEv2/IPsec VPN servers",
    "vpn-oc" to "OpenConnect VPN Server",
    "vpn-dlna" to "DLNA over VPN",
    "http-proxy" to "HTTP Proxy",
    "torrent" to "Download Station",
    "opt" to "OptWare",
)

internal class Users(private val api: KeeneticCall) : Node("user") {
    override val commands = listOf("print", "add", "set", "remove")

    override suspend fun arguments(): List<Argument> {
        val tags = ((api.show("show/tags") as? JsonObject)?.get("tag") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        return listOf(
            Argument("name", ValueType.Text()),
            Argument("password", ValueType.Secret, optional = true),
            Argument("tag", ValueType.Enumeration(multiple = true), tags, optional = true, labels = TAGS),
        )
    }

    override suspend fun read(): List<Item> = (api.show("show/rc/user") as? JsonObject).orEmpty().map { (name, user) ->
        val tags = ((user as? JsonObject)?.get("tag") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        Item(name, linkedMapOf("name" to name, "tag" to tags.joinToString(",")))
    }

    override suspend fun add(values: Map<String, String>): String {
        val name = values["name"].orEmpty()
        if (!name.matches(USER)) throw DeviceException(ErrorCategory.ArgumentValue, "a user name of letters, digits, '_' and '.' is needed")
        api.post(listOf(command(name, values + ("tag" to values["tag"].orEmpty()))))
        return name
    }

    override suspend fun set(id: String?, changes: Map<String, String?>) {
        val name = id ?: throw DeviceException(ErrorCategory.MissingItem, "user holds elements, not settings")
        if (changes["name"]?.let { it != name } == true) throw DeviceException(ErrorCategory.Unsupported, "Keenetic does not rename a user")
        if (read().none { it.id == name }) throw DeviceException(ErrorCategory.MissingItem, "user has no $name")
        api.post(listOf(command(name, changes)))
    }

    override suspend fun remove(ids: List<String>) {
        if (ids.any { !it.matches(USER) }) throw DeviceException(ErrorCategory.ArgumentValue, "a user name of letters, digits, '_' and '.' is needed")
        api.post(ids.map { JsonObject(mapOf("parse" to JsonPrimitive("no user ${quoted(it)}"))) })
    }

    // the router takes no plain password over RCI, only the hashes it keeps: MD5 of user:realm:password for the web
    // interface, RCI and the CLI, and the NT hash for the VPN servers that check MS-CHAP
    private suspend fun command(name: String, changes: Map<String, String?>): JsonObject {
        val parts = buildMap<String, JsonElement> {
            changes["tag"]?.let { tags -> put("tag", JsonArray(tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map(::JsonPrimitive))) }
            changes["password"]?.ifEmpty { null }?.let { password ->
                fun hash(value: String) = JsonObject(mapOf("hash" to JsonPrimitive(value)))
                put("password", JsonObject(mapOf("nt" to hash(ntHash(password)), "md5" to hash(storedHash(name, api.realm(), password)))))
            }
        }
        return nested(listOf("user", name), JsonObject(parts))
    }
}

// interfaces as show interface gives them, with what show rc keeps of their name, state and Wi-Fi and the counters of
// show interface stat; name is the system name, by which the table lays the live rates over the rows
internal class Interfaces(private val api: KeeneticCall) : Node("interface") {
    override val commands = listOf("print", "set", "monitor-traffic")

    override suspend fun arguments() = listOf(
        Argument("name", ValueType.Text(), readOnly = true),
        Argument("interface-name", ValueType.Text(), optional = true),
        Argument("description", ValueType.Text(), optional = true),
        Argument("disabled", ValueType.YesNo),
        Argument("ssid", ValueType.Text(), optional = true),
        Argument("psk", ValueType.Secret, optional = true),
    )

    override suspend fun read(): List<Item> {
        val (shown, config) = api.shows("interface", "rc/interface")
        val rows = interfaces(shown, config)
        val counters = api.stats(rows.map { it.id })
        return rows.map { Item(it.id, it.values + counters[it.id].orEmpty()) }
    }

    override suspend fun set(id: String?, changes: Map<String, String?>) {
        val name = id ?: throw DeviceException(ErrorCategory.MissingItem, "interface holds elements, not settings")
        if (interfaces(api.show("show/interface"), JsonObject(emptyMap())).none { it.id == name }) throw DeviceException(ErrorCategory.MissingItem, "interface has no $name")
        api.send(listOfNotNull(interfaceChange(name, changes)))
    }
}

internal fun interfaces(shown: JsonElement, config: JsonElement): List<Item> = (shown as? JsonObject).orEmpty().values.mapNotNull { row ->
    val values = (row as? JsonObject)?.let { JsonObject(it - "port") }?.let(::fields) ?: return@mapNotNull null
    val id = values["id"] ?: return@mapNotNull null
    val own = ((config as? JsonObject)?.get(id) as? JsonObject)?.let(::fields).orEmpty()
    val item = LinkedHashMap<String, String>()
    item["name"] = id
    item.putAll(values)
    values["link"]?.let { item["running"] = "${it == "up"}" }
    values["state"]?.let { item["disabled"] = "${it == "down"}" }
    own["ssid"]?.let { item["ssid"] = it }
    own["authentication.wpa-psk.psk"]?.let { item["psk"] = it }
    Item(id, item)
}

internal fun interfaceParts(changes: Map<String, String?>): Map<String, JsonElement> = buildMap {
    changes["interface-name"]?.ifEmpty { null }?.let { put("rename", JsonPrimitive(it)) }
    changes["description"]?.let { put("description", if (it.isEmpty()) NO else JsonPrimitive(it)) }
    changes["disabled"]?.let { put("up", if (it in ValueType.Flag.on) NO else JsonPrimitive(true)) }
    changes["ssid"]?.let { put("ssid", if (it.isEmpty()) NO else JsonPrimitive(it)) }
    changes["psk"]?.let { put("authentication", JsonObject(mapOf("wpa-psk" to if (it.isEmpty()) NO else JsonObject(mapOf("psk" to JsonPrimitive(it)))))) }
}

internal fun interfaceChange(name: String, changes: Map<String, String?>): JsonObject? =
    interfaceParts(changes).takeIf { it.isNotEmpty() }?.let { nested(listOf("interface", name), JsonObject(it)) }

internal class Identity(private val api: KeeneticCall) : Node("system", NodeKind.Settings) {
    override val commands = listOf("print", "set")

    override suspend fun arguments() = listOf(Argument("hostname", ValueType.Text()), Argument("domainname", ValueType.Text(), optional = true))

    override suspend fun read(): List<Item> {
        val system = (api.show("show/rc/system") as? JsonObject).orEmpty()
        return listOf(Item("", linkedMapOf("hostname" to system.text("hostname").orEmpty(), "domainname" to system.text("domainname").orEmpty(), "description" to system.text("description").orEmpty())))
    }

    override suspend fun set(id: String?, changes: Map<String, String?>) {
        val parts = listOf("hostname", "domainname").mapNotNull { name -> changes[name]?.let { name to JsonPrimitive(it) } }
        if (parts.any { it.second.content.isEmpty() }) throw DeviceException(ErrorCategory.ArgumentValue, "Keenetic needs a host name and a domain name")
        if (parts.isNotEmpty()) api.post(listOf(JsonObject(mapOf("system" to JsonObject(parts.toMap())))))
    }
}

internal suspend fun KeeneticCall.send(commands: List<JsonObject>) {
    if (commands.isNotEmpty()) post(commands)
}

// the names of RouterOS, date as yyyy-MM-dd: the log window reads the clock of the router from them
internal fun clockOf(date: JsonElement): Map<String, String> {
    val parts = (date as? JsonObject).orEmpty()
    fun part(name: String, width: Int) = parts.text(name)?.padStart(width, '0')
    val zone = ((parts["tz"] as? JsonArray)?.firstOrNull() as? JsonObject)?.let(::fields).orEmpty()
    return listOfNotNull(
        part("year", 4)?.let { year -> part("month", 2)?.let { month -> part("day", 2)?.let { "date" to "$year-$month-$it" } } },
        part("hour", 2)?.let { hour -> part("min", 2)?.let { minute -> part("sec", 2)?.let { "time" to "$hour:$minute:$it" } } },
        zone["locality"]?.let { "time-zone-name" to it },
        parts.text("dst")?.let { "dst" to it },
    ).toMap()
}

// several show commands in one request, as the web interface asks: through KeenDNS a request costs 0.3 s or more
internal suspend fun KeeneticCall.shows(vararg paths: String): List<JsonElement> {
    val answers = post(paths.map(::showOf))
    return paths.mapIndexed { index, path -> shown(answers.getOrNull(index), path) }
}

internal fun showOf(path: String) = JsonObject(mapOf("show" to nested(path.split('/'), JsonObject(emptyMap()))))

// the load of the processor as the router averages it for its own charts, newest first: cpuload of show system is the moment
// of the request, which the request itself loads
internal val CPU_AVERAGE = JsonObject(mapOf("show" to nested(listOf("system", "rrd", "cpu"), JsonObject(mapOf("detail" to JsonPrimitive(0), "attribute" to JsonPrimitive("avg"))))))

internal fun averageOf(answer: JsonElement?): String? {
    val data = (shown(answer, "system/rrd/cpu") as? JsonObject)?.get("data") as? JsonArray
    val value = (data?.firstOrNull() as? JsonObject)?.get("v")
    return (((value as? JsonObject)?.get("avg") ?: value) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.roundToInt()?.toString()
}

internal fun shown(answer: JsonElement?, path: String): JsonElement =
    path.split('/').fold((answer as? JsonObject)?.get("show")) { at, key -> (at as? JsonObject)?.get(key) } ?: JsonObject(emptyMap())

// the counters of interfaces with rxspeed and txspeed in bits per second, which the router works out itself
internal suspend fun KeeneticCall.stats(names: List<String>): Map<String, Map<String, String>> =
    if (names.isEmpty()) emptyMap() else statsOf(post(listOf(statOf(names)), raising = false).firstOrNull(), names)

internal fun statOf(names: List<String>) =
    JsonObject(mapOf("show" to JsonObject(mapOf("interface" to JsonObject(mapOf("stat" to JsonArray(names.map { JsonObject(mapOf("name" to JsonPrimitive(it))) })))))))

// the answers come in the order of the names; one the router does not know has a status in its place
internal fun statsOf(answer: JsonElement?, names: List<String>): Map<String, Map<String, String>> {
    val stat = (((answer as? JsonObject)?.get("show") as? JsonObject)?.get("interface") as? JsonObject)?.get("stat")
    val elements = stat as? JsonArray ?: listOfNotNull(stat as? JsonObject)
    return names.zip(elements).mapNotNull { (name, element) ->
        (element as? JsonObject)?.takeIf { "status" !in it }?.let { name to fields(it).filterKeys { key -> key in counters } }
    }.toMap()
}

private val counters = setOf("rxbytes", "txbytes", "rxpackets", "txpackets", "rxerrors", "txerrors", "rxdropped", "txdropped", "rxspeed", "txspeed")

// the interfaces a route, a rule or a pool can name, shown by the name the web interface gives them
internal suspend fun KeeneticCall.withInterfaces(arguments: List<Argument>): List<Argument> {
    if (arguments.none { it.name == "interface" }) return arguments
    val names = interfaces(show("show/interface"), JsonObject(emptyMap())).filter { it["type"] != "Port" }.associate { row ->
        val own = row["description"]?.ifEmpty { null } ?: row["interface-name"]?.takeIf { it != row.id }
        row.id to (own?.let { "$it (${row.id})" } ?: row.id)
    }
    return arguments.map { if (it.name == "interface") it.copy(type = ValueType.Enumeration(), choices = names.keys.toList(), labels = names) else it }
}

internal fun rows(json: JsonElement, key: String? = null): List<Item> = when (json) {
    is JsonArray -> json.mapIndexedNotNull { index, row -> (row as? JsonObject)?.let(::fields)?.let { Item(key?.let(it::get) ?: "$index", it) } }
    is JsonObject -> json.values.singleOrNull { it is JsonArray }?.let { rows(it, key) }
        ?: json.mapNotNull { (name, row) -> (row as? JsonObject)?.let(::fields)?.let { Item(key?.let(it::get) ?: name, it) } }
    else -> emptyList()
}

internal fun named(json: JsonElement): List<Pair<String, Map<String, String>>> =
    (json as? JsonObject).orEmpty().mapNotNull { (name, value) -> (value as? JsonObject)?.let { name to fields(it) } }

internal fun fields(json: JsonObject, prefix: String = ""): Map<String, String> = buildMap {
    for ((key, value) in json) {
        val name = prefix + key
        when (value) {
            is JsonObject -> putAll(fields(value, "$name."))
            is JsonArray -> put(name, value.joinToString(",") { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() })
            is JsonPrimitive -> {
                val text = value.contentOrNull.orEmpty()
                put(name, if (key == "uptime" || key == "expires") text.toLongOrNull()?.let(::duration) ?: text else text)
            }
        }
    }
}

internal fun Map<String, JsonElement>.text(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull

internal fun nested(path: List<String>, leaf: JsonObject): JsonObject = path.foldRight(leaf) { segment, inner -> JsonObject(mapOf(segment to inner)) }

// a name goes in double quotes, as running-config gives a known host with spaces in its name
internal fun quoted(word: String) = "\"$word\""
