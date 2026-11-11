package com.vasmarfas.networkhelm.pfsense

import com.vasmarfas.networkhelm.journal.EditKind
import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.Capabilities
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.DeviceInfo
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Filter
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ListEvent
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.SafeModeKind
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.model.matches
import com.vasmarfas.networkhelm.model.syslogLine
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.SafeMode
import com.vasmarfas.networkhelm.session.polled
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.TrustPolicy
import com.vasmarfas.networkhelm.transport.openTlsTrusted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class Endpoint(path: String, val many: String, val one: String? = null, val apply: String? = null) {
    val path = MenuPath.parse(path)
    val writable: Boolean get() = one != null
}

internal val endpoints = listOf(
    Endpoint("interfaces/overview", "status/interfaces"),
    Endpoint("interfaces/assigned", "interfaces", "interface", "interface/apply"),
    Endpoint("interfaces/vlan", "interface/vlans", "interface/vlan", "interface/apply"),
    Endpoint("firewall/rule", "firewall/rules", "firewall/rule", "firewall/apply"),
    Endpoint("firewall/port-forward", "firewall/nat/port_forwards", "firewall/nat/port_forward", "firewall/apply"),
    Endpoint("firewall/outbound-nat", "firewall/nat/outbound/mappings", "firewall/nat/outbound/mapping", "firewall/apply"),
    Endpoint("firewall/alias", "firewall/aliases", "firewall/alias", "firewall/apply"),
    Endpoint("routing/route", "routing/static_routes", "routing/static_route", "routing/apply"),
    Endpoint("routing/gateway", "routing/gateways", "routing/gateway", "routing/apply"),
    Endpoint("routing/gateway-status", "status/gateways"),
    Endpoint("services/dhcp-lease", "status/dhcp_server/leases"),
    Endpoint("services/dns-override", "services/dns_resolver/host_overrides", "services/dns_resolver/host_override", "services/dns_resolver/apply"),
    Endpoint("services/status", "status/services"),
    Endpoint("diagnostics/arp", "diagnostics/arp_table"),
    Endpoint("log/system", "status/logs/system"),
    Endpoint("log/firewall", "status/logs/firewall"),
)

private val RESOURCE = MenuPath.parse("system/resource")
private val SYSTEM_LOG = MenuPath.parse("log/system")
private val FIREWALL_LOG = MenuPath.parse("log/firewall")

class PfsenseSession(
    private val api: PfsenseCall,
    override val device: DeviceInfo,
    private val pollInterval: Duration = 3.seconds,
    private val configInterval: Duration = 10.seconds,
) : DeviceSession {
    override val capabilities = Capabilities(
        subscriptions = false,
        streamingCommands = false,
        cancel = false,
        safeMode = SafeModeKind.ApplyRollback,
        terminal = false,
        files = false,
        export = false,
        menuContents = false,
    )
    override val ssh: SshSession? = null

    private val unapplied = MutableStateFlow(emptyMap<String, Int>())
    private val edits = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val safeMode: SafeMode = Apply()

    private var models: Map<String, JsonObject>? = null

    private var interfaceNames = emptyMap<String, String>()
    private var ruleNames = emptyMap<String, String>()
    private var named: TimeMark? = null

    override suspend fun describe(path: MenuPath): MenuSchema = calls {
        val depth = path.segments.size
        val children = (endpoints.map { it.path } + RESOURCE).filter { it.segments.size > depth && it.segments.take(depth) == path.segments }.map { it.segments[depth] }.distinct()
        val endpoint = endpoints.firstOrNull { it.path == path }
        when {
            path == RESOURCE -> MenuSchema(path, NodeKind.Settings, emptyList(), listOf("print"), resource().keys.map { Argument(it, ValueType.Text(), readOnly = true) })
            endpoint == null && children.isEmpty() -> throw DeviceException(ErrorCategory.MissingItem, "the app knows no list of pfSense at $path")
            endpoint == null -> MenuSchema(path, NodeKind.Directory, children, emptyList(), emptyList())
            endpoint.writable -> {
                val properties = model(endpoint)
                val arguments = properties?.map { (name, property) -> argument(name, property) } ?: rows(endpoint).flatMap { it.values.keys }.distinct().map { Argument(it, ValueType.Text()) }
                MenuSchema(path, NodeKind.List, emptyList(), listOf("print", "add", "set", "remove", "move"), arguments)
            }
            else -> MenuSchema(path, NodeKind.List, emptyList(), listOf("print"), rows(endpoint).flatMap { it.values.keys }.distinct().map { Argument(it, ValueType.Text(), readOnly = true) })
        }
    }

    override fun watch(path: MenuPath, filter: Filter?, properties: List<String>?): Flow<ListEvent> = if (path == RESOURCE) {
        flow { emitAll(polled(pollInterval) { listOf(Item("", calls { resource() })) }) }
    } else {
        flow {
            val endpoint = endpoint(path)
            emitAll(polled(if (endpoint.writable) configInterval else pollInterval, edits) { calls { rows(endpoint) }.filter { filter?.matches(it) != false } })
        }
    }

    override suspend fun get(path: MenuPath, id: String?, properties: List<String>?): Item? = calls {
        if (path == RESOURCE) Item("", resource()) else rows(endpoint(path)).firstOrNull { id == null || it.id == id }
    }

    override suspend fun count(path: MenuPath, filter: Filter?): Int = calls { rows(endpoint(path)).count { filter?.matches(it) != false } }

    override suspend fun add(path: MenuPath, values: Map<String, String>): String = calls {
        val endpoint = model(path)
        val made = api.call("POST", endpoint.one.orEmpty(), emptyMap(), JsonObject(body(endpoint, values))) as? JsonObject
        edited(endpoint)
        made?.get("id")?.let(::flat) ?: throw DeviceException(ErrorCategory.Api, "${endpoint.one} named no new element")
    }

    override suspend fun set(path: MenuPath, id: String?, changes: Map<String, String?>) {
        calls {
            val endpoint = model(path)
            val element = id ?: throw DeviceException(ErrorCategory.MissingItem, "$path holds elements, not settings")
            api.call("PATCH", endpoint.one.orEmpty(), emptyMap(), JsonObject(mapOf("id" to id(element)) + body(endpoint, changes)))
            edited(endpoint)
        }
    }

    // the last first, as an id is the place of its element and those after it move up when it goes
    override suspend fun remove(path: MenuPath, ids: List<String>) {
        calls {
            val endpoint = model(path)
            for (id in ids.sortedByDescending { it.toIntOrNull() ?: 0 }) api.call("DELETE", endpoint.one.orEmpty(), mapOf("id" to id), null)
            edited(endpoint)
        }
    }

    override suspend fun move(path: MenuPath, ids: List<String>, before: String?) {
        calls {
            val endpoint = model(path)
            val element = ids.singleOrNull() ?: throw DeviceException(ErrorCategory.Unsupported, "pfSense moves one element at a time")
            val count = rows(endpoint).size
            val from = element.toIntOrNull() ?: throw DeviceException(ErrorCategory.MissingItem, "$element is no place in $path")
            val target = before?.toIntOrNull()?.let { if (it > from) it - 1 else it } ?: (count - 1)
            api.call("PATCH", endpoint.one.orEmpty(), emptyMap(), JsonObject(mapOf("id" to JsonPrimitive(from), "placement" to JsonPrimitive(target))))
            edited(endpoint)
        }
    }

    override fun run(path: MenuPath, command: String, arguments: Map<String, String>): Flow<Item> = emptyFlow()

    override suspend fun export(path: MenuPath): String = throw DeviceException(ErrorCategory.Unsupported, "the configuration of pfSense is not exported through the app yet")

    override fun close() {}

    private fun endpoint(path: MenuPath) = endpoints.firstOrNull { it.path == path } ?: throw DeviceException(ErrorCategory.MissingItem, "the app knows no list of pfSense at $path")

    private fun model(path: MenuPath) = endpoint(path).takeIf { it.writable } ?: throw DeviceException(ErrorCategory.Unsupported, "$path of pfSense is read only")

    private suspend fun rows(endpoint: Endpoint): List<Item> {
        val data = api.call("GET", endpoint.many, emptyMap(), null) as? JsonArray ?: return emptyList()
        val items = data.mapIndexedNotNull { index, row ->
            (row as? JsonObject)?.let { fields -> Item(fields["id"]?.let(::flat) ?: "$index", fields.filterKeys { it != "id" }.mapValues { flat(it.value) }) }
        }
        return when (endpoint.path) {
            SYSTEM_LOG -> items.map { item -> item.values["text"]?.let(::syslogLine)?.let { item.copy(values = item.values + it) } ?: item }
            FIREWALL_LOG -> packets(items)
            else -> items
        }
    }

    private suspend fun packets(lines: List<Item>): List<Item> {
        val parsed = lines.map { item -> item to item.values["text"]?.let(::syslogLine)?.let { line -> filterLog(line.getValue("message"))?.let { line + it } } }
        val packets = parsed.mapNotNull { it.second }
        if (packets.any { it["interface"] !in interfaceNames || it["tracker"] !in ruleNames } && named?.let { it.elapsedNow() < configInterval } != true) {
            named = TimeSource.Monotonic.markNow()
            interfaceNames = names(endpoint(MenuPath.parse("interfaces/assigned")), "if")
            ruleNames = names(endpoint(MenuPath.parse("firewall/rule")), "tracker")
        }
        return parsed.map { (item, fields) ->
            if (fields == null) return@map item
            val device = fields.getValue("interface")
            val tracker = fields.getValue("tracker")
            item.copy(values = item.values + fields + mapOf("interface" to (interfaceNames[device] ?: device), "rule" to (ruleNames[tracker] ?: tracker)))
        }
    }

    private suspend fun names(endpoint: Endpoint, by: String) =
        rows(endpoint).mapNotNull { row -> row.values[by]?.let { key -> key to (row.values["descr"]?.takeIf { it.isNotEmpty() } ?: key) } }.toMap()

    private suspend fun resource(): Map<String, String> {
        val status = api.call("GET", "status/system", emptyMap(), null) as? JsonObject ?: return emptyMap()
        val values = status.mapValues { flat(it.value) }
        return values + listOfNotNull(
            values["cpu_usage"]?.toDoubleOrNull()?.let { "cpu-load" to "${it.toLong()}" },
            values["mem_usage"]?.toDoubleOrNull()?.let { "memory-usage" to "${it.toLong()}" },
            values["uptime"]?.let { "uptime" to uptime(it) },
        )
    }

    private suspend fun model(endpoint: Endpoint): Map<String, JsonObject>? {
        val one = endpoint.one ?: return null
        val known = models ?: try {
            schemas(api.call("GET", "schema/openapi", emptyMap(), null) as? JsonObject)
        } catch (e: DeviceException) {
            emptyMap()
        }.also { models = it }
        return known[one]?.entries?.mapNotNull { (name, property) -> (property as? JsonObject)?.let { name to it } }?.toMap()
    }

    private suspend fun body(endpoint: Endpoint, values: Map<String, String?>): Map<String, JsonElement> {
        val properties = model(endpoint).orEmpty()
        return values.mapValues { (name, value) -> typed(properties[name], value) }
    }

    private fun edited(endpoint: Endpoint) {
        endpoint.apply?.let { apply -> unapplied.update { it + (apply to (it[apply] ?: 0) + 1) } }
        edits.tryEmit(Unit)
    }

    private suspend fun <T> calls(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: DeviceException) {
        throw e
    } catch (e: Exception) {
        throw DeviceException(ErrorCategory.ConnectionLost, e.message ?: e::class.simpleName.orEmpty(), e)
    }

    private inner class Apply : SafeMode {
        override val active: Flow<Boolean> = flowOf(false)

        override val actions: Flow<Int> = unapplied.map { it.values.sum() }

        override suspend fun enter(): Boolean = calls {
            for (apply in unapplied.value.keys) api.call("POST", apply, emptyMap(), JsonObject(emptyMap()))
            unapplied.value = emptyMap()
            edits.tryEmit(Unit)
            true
        }

        override suspend fun commit() {}

        override suspend fun rollback() {}
    }
}

internal fun schemas(openapi: JsonObject?): Map<String, JsonObject> {
    val paths = openapi?.get("paths") as? JsonObject ?: return emptyMap()
    val components = (openapi["components"] as? JsonObject)?.get("schemas") as? JsonObject
    return endpoints.mapNotNull { it.one }.mapNotNull { one ->
        val body = (((paths["/api/v2/$one"] as? JsonObject)?.get("post") as? JsonObject)?.get("requestBody") as? JsonObject)
        val schema = ((body?.get("content") as? JsonObject)?.get("application/json") as? JsonObject)?.get("schema") as? JsonObject ?: return@mapNotNull null
        val ref = schema["\$ref"] ?: (schema["allOf"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonObject)?.get("\$ref") }
        val model = (ref as? JsonPrimitive)?.contentOrNull?.substringAfterLast('/')?.let { components?.get(it) as? JsonObject } ?: schema
        (model["properties"] as? JsonObject)?.let { one to it }
    }.toMap()
}

internal fun argument(name: String, property: JsonObject): Argument {
    val type = (property["type"] as? JsonPrimitive)?.contentOrNull
    val items = property["items"] as? JsonObject
    val choices = ((property["enum"] ?: items?.get("enum")) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    val readOnly = (property["readOnly"] as? JsonPrimitive)?.booleanOrNull == true
    val valueType = when {
        type == "boolean" -> ValueType.YesNo
        type == "integer" -> ValueType.Integer((property["minimum"] as? JsonPrimitive)?.longOrNull, (property["maximum"] as? JsonPrimitive)?.longOrNull)
        choices.isNotEmpty() -> ValueType.Enumeration(multiple = type == "array")
        type == "array" -> ValueType.Many(ValueType.Text())
        else -> ValueType.Text()
    }
    val description = (property["description"] as? JsonPrimitive)?.contentOrNull?.replace("<br>", "\n")?.replace(Regex("<[^>]+>"), "")?.trim()
    return Argument(name, valueType, choices, description?.ifEmpty { null }, readOnly, optional = (property["nullable"] as? JsonPrimitive)?.booleanOrNull == true)
}

private fun typed(property: JsonObject?, value: String?): JsonElement = when {
    value == null -> JsonNull
    (property?.get("type") as? JsonPrimitive)?.contentOrNull == "boolean" -> JsonPrimitive(value in ValueType.Flag.on)
    (property?.get("type") as? JsonPrimitive)?.contentOrNull == "integer" -> value.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value)
    (property?.get("type") as? JsonPrimitive)?.contentOrNull == "array" -> JsonArray(value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map(::JsonPrimitive))
    else -> JsonPrimitive(value)
}

private fun id(text: String): JsonElement = text.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(text)

private fun flat(value: JsonElement): String = when (value) {
    is JsonPrimitive -> value.contentOrNull.orEmpty()
    is JsonArray -> value.joinToString(",") { flat(it) }
    is JsonObject -> value.toString()
}

private fun uptime(text: String): String = Regex("""(\d+) (Day|Hour|Minute|Second)""").findAll(text)
    .filter { it.groupValues[1].toInt() > 0 }
    .joinToString("") { "${it.groupValues[1].toInt()}${it.groupValues[2].first().lowercaseChar()}" }
    .ifEmpty { text }

class PfsenseLogin(val host: String, val user: String, val secret: String, val port: Int = 443)

suspend fun connectPfsense(login: PfsenseLogin, trust: TrustPolicy): PfsenseSession {
    val api = HttpsPfsense(login.host, login.user, login.secret) { openTlsTrusted(login.host, login.port, trust) }
    val hostname = api.call("GET", "system/hostname", emptyMap(), null) as? JsonObject
    val version = api.call("GET", "system/version", emptyMap(), null) as? JsonObject
    val status = api.call("GET", "status/system", emptyMap(), null) as? JsonObject
    fun text(json: JsonObject?, key: String) = json?.get(key)?.let(::flat).orEmpty()
    val device = DeviceInfo(
        Platform.Pfsense,
        text(hostname, "hostname").ifEmpty { login.host },
        text(version, "base").ifEmpty { text(version, "version") },
        "",
        text(status, "platform"),
        emptySet(),
        setOf("read", "write"),
    )
    return PfsenseSession(api, device)
}

fun pfsenseCall(path: MenuPath, kind: EditKind, ids: List<String>, values: Map<String, String?>): String {
    val endpoint = endpoints.firstOrNull { it.path == path && it.writable } ?: return "# $path ${kind.name.lowercase()} " + ids.joinToString(",")
    val body = JsonObject(values.mapValues { (_, value) -> value?.let(::JsonPrimitive) ?: JsonNull })
    return when (kind) {
        EditKind.Add -> "POST /api/v2/${endpoint.one} $body"
        EditKind.Set -> ids.joinToString("\n") { "PATCH /api/v2/${endpoint.one} ${JsonObject(mapOf("id" to id(it)) + body)}" }
        EditKind.Remove -> ids.joinToString("\n") { "DELETE /api/v2/${endpoint.one}?id=$it" }
        EditKind.Move -> "# ${endpoint.path}: moved by placement"
    }
}
