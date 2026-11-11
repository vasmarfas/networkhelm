package com.vasmarfas.networkhelm.opnsense

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
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.SafeMode
import com.vasmarfas.networkhelm.session.polled
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.TrustPolicy
import com.vasmarfas.networkhelm.transport.openTlsTrusted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class Grid(
    path: String,
    val controller: String,
    val item: String = "item",
    val key: String = "",
    val apply: String? = null,
    val savepoint: Boolean = false,
    search: String? = null,
    val method: String = "POST",
    val several: Set<String> = emptySet(),
    val lists: Set<String> = emptySet(),
    val dump: String? = null,
    val switch: String = "enabled",
    val ordered: Boolean = false,
    val readonly: Set<String> = emptySet(),
    val count: Int = -1,
    val skip: Set<String> = emptySet(),
    val dates: Set<String> = emptySet(),
) {
    val path = MenuPath.parse(path)
    val search = search ?: action("search")
    val writable: Boolean get() = key.isNotEmpty()

    fun action(verb: String) = if (item.isEmpty()) "$controller/$verb" else "$controller/${verb}_$item"

    fun shown(values: Map<String, String>): Map<String, String> {
        val bit = values[switch] ?: return values
        return values - switch + ("disabled" to "${(bit in ValueType.Flag.on) == (switch == "disabled")}")
    }

    fun written(values: Map<String, String?>): Map<String, String?> {
        if ("disabled" !in values) return values
        val off = values["disabled"] in ValueType.Flag.on
        return values - "disabled" + (switch to if (off == (switch == "disabled")) "1" else "0")
    }
}

internal val grids = listOf(
    Grid("interfaces/overview", "interfaces/overview", search = "interfaces/overview/interfaces_info", method = "GET"),
    Grid("interfaces/vlan", "interfaces/vlan_settings", key = "vlan", apply = "interfaces/vlan_settings/reconfigure"),
    Grid("interfaces/bridge", "interfaces/bridge_settings", key = "bridge", apply = "interfaces/bridge_settings/reconfigure"),
    Grid(
        "firewall/filter", "firewall/filter", item = "rule", key = "rule", apply = "firewall/filter/apply", savepoint = true,
        several = setOf("interface", "categories"), dump = "filter.rules.rule", ordered = true,
    ),
    Grid(
        "firewall/source-nat", "firewall/source_nat", item = "rule", key = "rule", apply = "firewall/source_nat/apply", savepoint = true,
        several = setOf("categories"), dump = "filter.snatrules.rule",
    ),
    Grid(
        "firewall/alias", "firewall/alias", key = "alias", apply = "firewall/alias/reconfigure", several = setOf("proto", "categories"), lists = setOf("content"),
        readonly = setOf(
            "current_items", "last_updated", "eval_nomatch", "eval_match", "in_block_p", "in_block_b", "in_pass_p", "in_pass_b",
            "out_block_p", "out_block_b", "out_pass_p", "out_pass_b",
        ),
    ),
    Grid("routes/routes", "routes/routes", item = "route", key = "route", apply = "routes/routes/reconfigure", switch = "disabled"),
    Grid("unbound/host-override", "unbound/settings", item = "host_override", key = "host", apply = "unbound/service/reconfigure"),
    Grid("wireguard/server", "wireguard/server", item = "server", key = "server", apply = "wireguard/service/reconfigure", several = setOf("peers")),
    Grid("wireguard/client", "wireguard/client", item = "client", key = "client", apply = "wireguard/service/reconfigure", several = setOf("servers")),
    Grid("dnsmasq/range", "dnsmasq/settings", item = "range", key = "range", apply = "dnsmasq/service/reconfigure"),
    Grid("dnsmasq/host", "dnsmasq/settings", item = "host", key = "host", apply = "dnsmasq/service/reconfigure"),
    Grid("dnsmasq/option", "dnsmasq/settings", item = "option", key = "option", apply = "dnsmasq/service/reconfigure", several = setOf("tag")),
    Grid("kea/subnet", "kea/dhcpv4", item = "subnet", key = "subnet4", apply = "kea/service/reconfigure"),
    Grid("kea/reservation", "kea/dhcpv4", item = "reservation", key = "reservation", apply = "kea/service/reconfigure"),
    Grid("system/gateways", "routing/settings", search = "routing/settings/search_gateway"),
    Grid("system/cron", "cron/settings", item = "job", key = "job", apply = "cron/service/reconfigure", search = "cron/settings/search_jobs", readonly = setOf("origin")),
    Grid("system/tunables", "core/tunables", key = "sysctl", apply = "core/tunables/reconfigure", readonly = setOf("default_value", "type")),
    Grid(
        "system/users", "auth/user", item = "", key = "user", switch = "disabled", several = setOf("priv", "group_memberships"),
        readonly = setOf("uid", "scope", "pwd_changed_at", "otp_uri", "apikeys"), skip = setOf("password", "%password", "apikeys", "%apikeys", "otp_seed"),
    ),
    Grid("system/groups", "auth/group", item = "", key = "group", several = setOf("priv", "member"), readonly = setOf("gid", "scope")),
    Grid("system/certificates", "trust/cert", item = "", skip = setOf("crt", "csr", "prv", "crt_payload", "csr_payload", "prv_payload"), dates = setOf("valid_from", "valid_to")),
    Grid("services/kea-leases", "kea/leases4", search = "kea/leases4/search"),
    Grid("services/dhcp-leases", "dhcpv4/leases", search = "dhcpv4/leases/search_lease"),
    Grid("services/dnsmasq-leases", "dnsmasq/leases", search = "dnsmasq/leases/search"),
    Grid("services/services", "core/service", search = "core/service/search"),
    Grid("diagnostics/arp", "diagnostics/interface", search = "diagnostics/interface/search_arp", method = "GET"),
    Grid("diagnostics/ndp", "diagnostics/interface", search = "diagnostics/interface/search_ndp", method = "GET"),
    Grid("log/system", "diagnostics/log", search = "diagnostics/log/core/system", count = 500),
    Grid("log/firewall", "diagnostics/firewall", search = "diagnostics/firewall/log", method = "GET"),
)

private val SYSTEM_LOG = MenuPath.parse("log/system")
private val FIREWALL_LOG = MenuPath.parse("log/firewall")
private val OVERVIEW = MenuPath.parse("interfaces/overview")
private val RESOURCES = MenuPath.parse("system/resources")
private val FIRMWARE = MenuPath.parse("system/firmware")
private val SETTINGS = listOf(RESOURCES, FIRMWARE)

private val switches = setOf(
    "log", "quick", "nosync", "source_not", "destination_not", "nonat", "counters", "disableroutes", "local", "ignore", "force", "scrambled_password",
    "option_data_autocollect", "match-client-id", "ddns_override_no_update", "ddns_override_client_update", "ddns_update_on_renew",
)

class OpnsenseSession(
    private val api: OpnsenseCall,
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
    // the savepoint is taken before the first edit since the last apply, as OPNsense writes each edit at once,
    // so one taken at the apply would hold the edits and the rollback would bring them back
    private val points = mutableMapOf<Grid, String?>()
    private val edits = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val safeMode: SafeMode = Apply()

    private var packets = emptyList<Item>()
    private var interfaceNames = emptyMap<String, String>()
    private var named: TimeMark? = null

    override suspend fun describe(path: MenuPath): MenuSchema = calls {
        val depth = path.segments.size
        val children = (grids.map { it.path } + SETTINGS).filter { it.segments.size > depth && it.segments.take(depth) == path.segments }.map { it.segments[depth] }.distinct()
        val grid = grids.firstOrNull { it.path == path }
        when {
            path in SETTINGS -> MenuSchema(path, NodeKind.Settings, emptyList(), listOf("print"), settings(path).keys.map { Argument(it, ValueType.Text(), readOnly = true) })
            grid == null && children.isEmpty() -> throw DeviceException(ErrorCategory.MissingItem, "the app knows no list of OPNsense at $path")
            grid == null -> MenuSchema(path, NodeKind.Directory, children, emptyList(), emptyList())
            grid.writable -> MenuSchema(
                path, NodeKind.List, emptyList(), listOf("print", "add", "set", "remove") + listOfNotNull("move".takeIf { grid.ordered }),
                element(grid, null).map { (name, value) -> argument(grid, name, value) },
            )
            else -> MenuSchema(path, NodeKind.List, emptyList(), listOf("print"), rows(grid).flatMap { it.values.keys }.distinct().map { Argument(it, ValueType.Text(), readOnly = true) })
        }
    }

    override fun watch(path: MenuPath, filter: Filter?, properties: List<String>?): Flow<ListEvent> = flow {
        if (path in SETTINGS) {
            emitAll(polled(configInterval) { listOf(Item("", calls { settings(path) })) })
            return@flow
        }
        val grid = grid(path)
        emitAll(polled(if (grid.writable) configInterval else pollInterval, edits) { calls { rows(grid) }.filter { filter?.matches(it) != false } })
    }

    override suspend fun get(path: MenuPath, id: String?, properties: List<String>?): Item? = calls {
        if (path in SETTINGS) return@calls Item("", settings(path))
        val grid = grid(path)
        if (grid.writable && id != null) {
            Item(id, grid.shown(element(grid, id).mapValues { (name, value) -> flat(value, if (name in grid.lists) "\n" else ",") }))
        } else {
            rows(grid).firstOrNull { id == null || it.id == id }
        }
    }

    override suspend fun count(path: MenuPath, filter: Filter?): Int = calls { rows(grid(path)).count { filter?.matches(it) != false } }

    override suspend fun add(path: MenuPath, values: Map<String, String>): String = calls {
        val grid = model(path)
        before(grid)
        val answer = api.call("POST", grid.action("add"), body(grid, values))
        saved(answer)
        edited(grid)
        (answer as? JsonObject)?.get("uuid")?.jsonPrimitive?.contentOrNull ?: throw DeviceException(ErrorCategory.Api, "${grid.action("add")} named no new element")
    }

    override suspend fun set(path: MenuPath, id: String?, changes: Map<String, String?>) {
        calls {
            val grid = model(path)
            val element = id ?: throw DeviceException(ErrorCategory.MissingItem, "$path holds elements, not settings")
            before(grid)
            saved(api.call("POST", "${grid.action("set")}/$element", body(grid, changes)))
            edited(grid)
        }
    }

    override suspend fun remove(path: MenuPath, ids: List<String>) {
        calls {
            val grid = model(path)
            before(grid)
            for (id in ids) {
                val answer = api.call("POST", "${grid.action("del")}/$id", JsonObject(emptyMap()))
                if ((answer as? JsonObject)?.get("result")?.jsonPrimitive?.contentOrNull != "deleted") throw DeviceException(ErrorCategory.MissingItem, "OPNsense did not remove $id: $answer")
            }
            edited(grid)
        }
    }

    override suspend fun move(path: MenuPath, ids: List<String>, before: String?) {
        calls {
            val grid = model(path).takeIf { it.ordered } ?: throw DeviceException(ErrorCategory.Unsupported, "$path of OPNsense keeps no order")
            val rules = rows(grid)
            val moving = ids.map { id -> rules.firstOrNull { it.id == id } ?: throw DeviceException(ErrorCategory.MissingItem, "OPNsense has no rule $id") }
            val rest = rules.filter { it.id !in ids }
            val at = if (before == null) rest.size else rest.indexOfFirst { it.id == before }.takeIf { it >= 0 } ?: throw DeviceException(ErrorCategory.MissingItem, "OPNsense has no rule $before")
            val low = rest.getOrNull(at - 1)?.let(::sequence) ?: 0
            val high = rest.getOrNull(at)?.let(::sequence)
            val numbers = if (high == null || high - low > moving.size) {
                val step = if (high == null) 10 else (high - low) / (moving.size + 1)
                moving.mapIndexed { i, rule -> rule to low + step * (i + 1) }
            } else {
                (moving + rest.drop(at)).mapIndexed { i, rule -> rule to low + 10 * (i + 1) }
            }
            before(grid)
            for ((rule, number) in numbers) {
                if (number != sequence(rule)) saved(api.call("POST", "${grid.action("set")}/${rule.id}", body(grid, mapOf("sequence" to "$number"))))
            }
            edited(grid)
        }
    }

    private fun sequence(rule: Item) = rule["sequence"]?.toIntOrNull() ?: throw DeviceException(ErrorCategory.Api, "the rule ${rule.id} of OPNsense has no sequence")

    override fun run(path: MenuPath, command: String, arguments: Map<String, String>): Flow<Item> = emptyFlow()

    override suspend fun export(path: MenuPath): String = throw DeviceException(ErrorCategory.Unsupported, "the configuration of OPNsense is not exported through the app yet")

    override fun close() {}

    private fun grid(path: MenuPath) = grids.firstOrNull { it.path == path } ?: throw DeviceException(ErrorCategory.MissingItem, "the app knows no list of OPNsense at $path")

    private fun model(path: MenuPath) = grid(path).takeIf { it.writable } ?: throw DeviceException(ErrorCategory.Unsupported, "$path of OPNsense is read only")

    private suspend fun rows(grid: Grid): List<Item> {
        if (grid.path == FIREWALL_LOG) return packets(grid)
        if (grid.dump != null) {
            val elements = grid.dump.split('.').fold<String, JsonElement?>(api.call("GET", "${grid.controller}/get", null)) { node, key -> (node as? JsonObject)?.get(key) }
            return (elements as? JsonObject).orEmpty()
                .map { (uuid, fields) -> Item(uuid, grid.shown((fields as? JsonObject).orEmpty().mapValues { flat(it.value) })) }
                .sortedBy { it["sequence"]?.toIntOrNull() ?: Int.MAX_VALUE }
        }
        val all = JsonObject(mapOf("current" to JsonPrimitive(1), "rowCount" to JsonPrimitive(grid.count), "sort" to JsonObject(emptyMap()), "searchPhrase" to JsonPrimitive("")))
        val rows = when (val answer = api.call(grid.method, grid.search, if (grid.method == "GET") null else all)) {
            is JsonArray -> answer
            is JsonObject -> (answer["rows"] ?: answer["results"] ?: answer["routes"]) as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        val items = rows.mapIndexedNotNull { index, row ->
            (row as? JsonObject)?.let { fields ->
                val values = fields.mapValues { (name, value) -> flat(value).let { if (name in grid.dates) moment(it) else it } }
                Item(listOf("uuid", "identifier", "id", "name").firstNotNullOfOrNull { values[it]?.ifEmpty { null } } ?: "$index", grid.shown(values - "uuid" - grid.skip))
            }
        }
        val shown = if (grid.path == SYSTEM_LOG) {
            items.reversed().map { item -> item.copy(id = with(item.values) { "${get("timestamp")} ${get("pid")} ${get("line")}" }, values = systemLine(item.values)) }
        } else {
            items
        }
        return if (shown.distinctBy { it.id }.size == shown.size) shown else shown.mapIndexed { index, item -> item.copy(id = "$index") }
    }

    private suspend fun packets(grid: Grid): List<Item> {
        val since = packets.lastOrNull()?.id
        val answer = api.call("GET", grid.search + "?limit=100" + (since?.let { "&digest=$it" } ?: ""), null) as? JsonArray ?: JsonArray(emptyList())
        val known = packets.mapTo(HashSet()) { it.id }
        val fresh = answer.mapNotNull { row -> (row as? JsonObject)?.mapValues { flat(it.value) } }.filter { it["__digest__"] !in known }.reversed()
        if (fresh.any { it["interface"] !in interfaceNames } && named?.let { it.elapsedNow() < configInterval } != true) {
            named = TimeSource.Monotonic.markNow()
            interfaceNames = rows(grid(OVERVIEW)).mapNotNull { row -> row["device"]?.let { device -> device to (row["description"]?.ifEmpty { null } ?: device) } }.toMap()
        }
        packets = (packets + fresh.map { Item(it["__digest__"].orEmpty(), packet(it, interfaceNames)) }).takeLast(500)
        return packets
    }

    private suspend fun element(grid: Grid, id: String?): Map<String, JsonElement> {
        val answer = api.call("GET", grid.action("get") + (id?.let { "/$it" } ?: ""), null)
        val element = (answer as? JsonObject)?.get(grid.key) as? JsonObject ?: throw DeviceException(ErrorCategory.MissingItem, "OPNsense has no element ${id.orEmpty()} in ${grid.path}")
        return leaves(element)
    }

    private suspend fun settings(path: MenuPath): Map<String, String> = if (path == FIRMWARE) firmware() else resources()

    private suspend fun resources(): Map<String, String> {
        val memory = (api.call("GET", "diagnostics/system/systemResources", null) as? JsonObject)?.get("memory") as? JsonObject
        val time = api.call("GET", "diagnostics/system/systemTime", null) as? JsonObject
        val disks = (api.call("GET", "diagnostics/system/systemDisk", null) as? JsonObject)?.get("devices") as? JsonArray
        return buildMap {
            put("hostname", device.identity)
            put("version", device.version)
            put("architecture", device.architecture)
            put("cpu", flat(api.call("GET", "diagnostics/cpu_usage/getCPUType", null)))
            time?.forEach { (name, value) -> put(name, flat(value)) }
            memory?.get("total")?.let { put("memory_total", flat(it)) }
            memory?.get("used")?.let { put("memory_used", flat(it)) }
            disks?.filterIsInstance<JsonObject>()?.map { disk -> disk.mapValues { flat(it.value) } }?.let { devices ->
                put("disks", devices.joinToString("\n") { "${it["mountpoint"]}: ${it["used"]}/${it["blocks"]} (${it["used_pct"]}%)" })
            }
        }
    }

    private suspend fun firmware(): Map<String, String> {
        val status = api.call("GET", "core/firmware/status", null) as? JsonObject ?: throw DeviceException(ErrorCategory.Api, "Firmware status of OPNsense answered no object")
        return ((status["product"] as? JsonObject).orEmpty() + (status - "product")).mapValues { flat(it.value) }
    }

    private fun saved(answer: JsonElement) {
        val reply = answer as? JsonObject ?: throw DeviceException(ErrorCategory.Api, "OPNsense answered $answer")
        if (reply["result"]?.jsonPrimitive?.contentOrNull == "saved") return
        val validations = (reply["validations"] as? JsonObject)?.entries?.joinToString("; ") { (field, message) -> "${field.substringAfterLast('.')}: ${flat(message)}" }
        throw DeviceException(ErrorCategory.ArgumentValue, validations ?: "OPNsense did not save: $reply")
    }

    private suspend fun before(grid: Grid) {
        if (grid.savepoint && grid !in points) points[grid] = savepoint(grid)
    }

    private suspend fun savepoint(grid: Grid): String? {
        val point = try {
            api.call("POST", "${grid.controller}/savepoint", JsonObject(emptyMap()))
        } catch (e: DeviceException) {
            if (e.category == ErrorCategory.MissingItem) return null
            throw e
        }
        return (point as? JsonObject)?.get("revision")?.jsonPrimitive?.contentOrNull ?: throw DeviceException(ErrorCategory.Api, "savepoint of ${grid.path} named no revision")
    }

    private fun edited(grid: Grid) {
        grid.apply?.let { apply -> unapplied.update { it + (apply to (it[apply] ?: 0) + 1) } }
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
        private var revisions = emptyMap<Grid, String>()
        private var applied: TimeMark? = null

        override val active: Flow<Boolean> = flow {
            while (true) {
                emit(applied?.let { it.elapsedNow() < ROLLBACK } == true)
                delay(1.seconds)
            }
        }

        override val actions: Flow<Int> = unapplied.map { it.values.sum() }

        override suspend fun enter(): Boolean = calls {
            for (apply in unapplied.value.keys) {
                val grid = grids.first { it.apply == apply }
                val revision = points.remove(grid)
                if (revision != null) {
                    api.call("POST", "$apply/$revision", JsonObject(emptyMap()))
                    revisions = revisions + (grid to revision)
                } else {
                    api.call("POST", apply, JsonObject(emptyMap()))
                }
            }
            unapplied.value = emptyMap()
            applied = if (revisions.isEmpty()) null else TimeSource.Monotonic.markNow()
            edits.tryEmit(Unit)
            true
        }

        override suspend fun commit() {
            calls { for ((grid, revision) in revisions) api.call("POST", "${grid.controller}/cancel_rollback/$revision", JsonObject(emptyMap())) }
            revisions = emptyMap()
            applied = null
        }

        override suspend fun rollback() {
            calls {
                for ((grid, revision) in revisions) {
                    val answer = api.call("POST", "${grid.controller}/revert/$revision", JsonObject(emptyMap()))
                    val status = (answer as? JsonObject)?.get("status")?.jsonPrimitive?.contentOrNull
                    if (status != "ok") throw DeviceException(ErrorCategory.Api, "OPNsense did not go back to $revision of ${grid.path}: ${status ?: answer}")
                    // the timer of the apply would go back once more within the minute, over what was done meanwhile
                    api.call("POST", "${grid.controller}/cancel_rollback/$revision", JsonObject(emptyMap()))
                }
            }
            revisions = emptyMap()
            applied = null
            edits.tryEmit(Unit)
        }
    }

    private companion object {
        val ROLLBACK = 60.seconds
    }
}

private fun argument(grid: Grid, name: String, value: JsonElement): Argument {
    val options = options(value).orEmpty()
    val choices = options.keys.filter { it.isNotEmpty() }
    return when {
        name == grid.switch -> Argument("disabled", ValueType.YesNo)
        name in grid.readonly -> Argument(name, ValueType.Text(), readOnly = true)
        name in grid.lists -> Argument(name, ValueType.Many(ValueType.Text(), "\n"), choices)
        choices.isNotEmpty() -> Argument(name, ValueType.Enumeration(multiple = name in grid.several), choices, optional = "" in options.keys, labels = labels(options))
        name in switches -> Argument(name, ValueType.Flag)
        else -> Argument(name, ValueType.Text())
    }
}

// a list of options whose keys run 0, 1, 2... comes as a JSON array, as the members of a group do
private fun options(value: JsonElement): Map<String, JsonElement>? = when {
    value is JsonObject && value.isNotEmpty() && value.values.all(::option) -> value
    value is JsonArray && value.isNotEmpty() && value.all(::option) -> value.withIndex().associate { (index, choice) -> "$index" to choice }
    else -> null
}

private fun option(value: JsonElement) = value is JsonObject && "selected" in value

private fun labels(options: Map<String, JsonElement>) = options.mapNotNull { (key, choice) ->
    (choice as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull?.takeIf { key.isNotEmpty() && it != key }?.let { key to it }
}.toMap()

private fun moment(seconds: String) = seconds.toLongOrNull()?.let { Instant.fromEpochSeconds(it).toString().removeSuffix("Z").replace('T', ' ') } ?: seconds

private fun selected(option: JsonElement) = (option as? JsonObject)?.get("selected")?.jsonPrimitive?.let { it.intOrNull == 1 || it.booleanOrNull == true } == true

private fun flat(value: JsonElement, separator: String = ","): String {
    val options = options(value)
    return when {
        value is JsonPrimitive -> value.contentOrNull.orEmpty()
        options != null -> options.filterValues(::selected).keys.joinToString(separator)
        value is JsonArray -> value.joinToString(separator) { flat(it) }
        value is JsonObject && value.size == 1 -> flat(value.values.single(), separator)
        else -> value.toString()
    }
}

// Kea keeps the options of a subnet in a nested object, which its search shows flat with dots
private fun leaves(node: JsonObject, prefix: String = ""): Map<String, JsonElement> = buildMap {
    for ((name, value) in node) {
        if (value is JsonObject && value.isNotEmpty() && options(value) == null) putAll(leaves(value, "$prefix$name.")) else put(prefix + name, value)
    }
}

private fun nested(values: Map<String, String?>): JsonObject = JsonObject(
    values.entries.groupBy({ it.key.substringBefore('.') }, { it.key.substringAfter('.', "") to it.value }).mapValues { (_, parts) ->
        if (parts.first().first.isEmpty()) JsonPrimitive(parts.first().second.orEmpty()) else nested(parts.associate { it })
    },
)

private fun body(grid: Grid, values: Map<String, String?>) = JsonObject(mapOf(grid.key to nested(grid.written(values))))

class OpnsenseLogin(val host: String, val key: String, val secret: String, val port: Int = 443)

suspend fun connectOpnsense(login: OpnsenseLogin, trust: TrustPolicy): OpnsenseSession {
    val api = HttpsOpnsense(login.host, login.key, login.secret) { openTlsTrusted(login.host, login.port, trust) }
    val information = api.call("GET", "diagnostics/system/system_information", null) as? JsonObject
        ?: throw DeviceException(ErrorCategory.Api, "System Information of OPNsense answered no object")
    val product = (information["versions"] as? JsonArray)?.firstOrNull()?.let(::flat).orEmpty().removePrefix("OPNsense").trim()
    val device = DeviceInfo(
        Platform.Opnsense,
        information["name"]?.let(::flat)?.ifEmpty { null } ?: login.host,
        product.substringBeforeLast('-'),
        product.substringAfterLast('-', ""),
        "",
        emptySet(),
        setOf("read", "write"),
    )
    return OpnsenseSession(api, device)
}

fun opnsenseCall(path: MenuPath, kind: EditKind, ids: List<String>, values: Map<String, String?>, moveBefore: String? = null): String {
    val grid = grids.firstOrNull { it.path == path && it.writable } ?: return "# $path ${kind.name.lowercase()} " + ids.joinToString(",")
    val body = form(body(grid, values))
    return when (kind) {
        EditKind.Add -> "POST /api/${grid.action("add")} $body"
        EditKind.Set -> ids.joinToString("\n") { "POST /api/${grid.action("set")}/$it $body" }
        EditKind.Remove -> ids.joinToString("\n") { "POST /api/${grid.action("del")}/$it" }
        EditKind.Move -> "# ${grid.path}: ${ids.joinToString(",")} " + (moveBefore?.let { "before $it" } ?: "to the end") + " by their sequence"
    }
}
