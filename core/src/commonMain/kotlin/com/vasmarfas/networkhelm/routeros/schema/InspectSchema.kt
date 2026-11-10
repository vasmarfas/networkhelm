package com.vasmarfas.networkhelm.routeros.schema

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.routeros.api.RouterOsLink
import com.vasmarfas.networkhelm.routeros.api.call
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

interface SchemaCache {
    suspend fun load(build: String): Map<String, List<Map<String, String>>>

    suspend fun store(build: String, answers: Map<String, List<Map<String, String>>>)

    suspend fun forget(build: String)
}

class MemorySchemaCache : SchemaCache {
    private val builds = HashMap<String, Map<String, List<Map<String, String>>>>()

    override suspend fun load(build: String) = builds[build].orEmpty()

    override suspend fun store(build: String, answers: Map<String, List<Map<String, String>>>) {
        builds[build] = answers
    }

    override suspend fun forget(build: String) {
        builds.remove(build)
    }
}

private val leading = listOf("name", "enabled", "interface")

private val commandOnly = setOf("numbers", "copy-from", "place-before")

private val ticked = setOf("days", "dhcp-options", "policy", "slaves", "trap-generators")
private val lined = setOf(
    "address-lists", "arp-ip-targets", "communities", "default-route-tables", "discovery-interfaces", "dns-servers", "eap-methods", "export-route-targets",
    "export.route-targets", "import-route-targets", "import.route-targets", "downstream-interfaces", "interfaces", "mdns-repeat-ifaces",
    "mvrp-forbidden", "networks", "pppoe-over-vlan-range", "ranges", "servers", "tagged", "topics", "trap-interfaces", "untagged", "vlan-ids", "vlan-mapping",
)

private val ported = setOf("ports", "to-ports")

private val prefixed = setOf(listOf("ip", "address"), listOf("ip", "dhcp-server", "network"))

private val offsets = setOf("time-zone", "dst-delta")

private val flows = listOf("dst-address", "dst-address-and-port", "src-address", "src-and-dst-addresses", "addresses-and-dst-port")

private val adjusted = setOf("new-ttl", "new-hop-limit")
private val adjustment = ValueType.Adjustment(listOf("set", "increment", "decrement"), ValueType.Integer(0, 255))

class InspectSchema(
    private val link: RouterOsLink,
    private val cache: SchemaCache,
    private val build: String,
    private val scope: CoroutineScope? = null,
) {
    private val lock = Mutex()
    private val writing = Mutex()
    private var answers: MutableMap<String, List<Map<String, String>>>? = null
    private var unsaved = false
    private var saving: Job? = null
    private val asking = HashMap<String, Mutex>()
    private val live = HashMap<String, Pair<TimeMark, List<Map<String, String>>>>()

    suspend fun outline(path: MenuPath): MenuSchema {
        val shape = shape(path)
        return when {
            shape.command -> MenuSchema(path, NodeKind.Command, emptyList(), emptyList(), emptyList())
            "print" !in shape.commands -> MenuSchema(path, NodeKind.Directory, shape.submenus, shape.commands, emptyList())
            else -> {
                val print = arguments(path.segments + "print").also { link.printTakes(path, it) }
                MenuSchema(path, if ("count-only" in print) NodeKind.List else NodeKind.Settings, shape.submenus, shape.commands, emptyList(), "follow-only" in print)
            }
        }
    }

    suspend fun describe(path: MenuPath): MenuSchema = coroutineScope {
        val shape = shape(path)
        val commands = shape.commands
        when {
            shape.command -> MenuSchema(path, NodeKind.Command, emptyList(), emptyList(), shape.arguments.map { async { argument(path.segments, it) } }.awaitAll())
            "print" !in commands -> MenuSchema(path, NodeKind.Directory, shape.submenus, commands, emptyList())
            else -> {
                val (print, add, set) = listOf("print", "add", "set")
                    .map { command -> async { if (command in commands) arguments(path.segments + command) else emptyList() } }
                    .awaitAll()
                link.printTakes(path, print)
                val optional = if ("unset" in commands) {
                    cached("completion", path.segments + "unset" + "value-name").filter { it["show"] == "true" }.mapNotNull { it["completion"] }.toSet()
                } else {
                    emptySet()
                }
                val kind = if ("count-only" in print) NodeKind.List else NodeKind.Settings
                val fields = (add.map { "add" to it } + set.map { "set" to it }).distinctBy { it.second }.filter { it.second !in commandOnly }
                val arguments = fields.map { (command, name) -> async { argument(path.segments + command, name, name in optional) } }.awaitAll()
                    .sortedBy { leading.indexOf(it.name).takeIf { at -> at >= 0 } ?: if (it.optional) leading.size + 1 else leading.size }
                MenuSchema(path, kind, shape.submenus, commands, arguments, "follow-only" in print)
            }
        }
    }

    private class Shape(val command: Boolean, val commands: List<String>, val submenus: List<String>, val arguments: List<String>)

    private suspend fun shape(path: MenuPath): Shape {
        val nodes = cached("child", path.segments)
        val self = nodes.firstOrNull { it["type"] == "self" }?.get("node-type")
            ?: throw DeviceException(ErrorCategory.MissingItem, "no menu or command at /$path")
        val named = nodes.filter { it["type"] == "child" }
        fun names(vararg types: String) = named.filter { it["node-type"] in types }.mapNotNull { it["name"] }
        return Shape(self == "cmd", names("cmd"), names("dir", "path"), names("arg"))
    }

    suspend fun forgetNames() {
        lock.withLock { live.clear() }
    }

    // what was learned about a menu of this build besides its schema, under a key of its own
    suspend fun recalled(key: String): List<Map<String, String>>? = lock.withLock { answers()[key] }

    suspend fun remember(key: String, rows: List<Map<String, String>>) {
        lock.withLock { answers()[key] = rows }
        changed()
    }

    suspend fun properties(path: MenuPath): List<String> {
        if ("get" !in shape(path).commands) return emptyList()
        return cached("completion", path.segments + "get" + "value-name")
            .filter { it["show"] == "true" && it["style"] != "syntax-meta" }
            .mapNotNull { it["completion"] }
            .filter { name -> name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '-' || it == '.' || it == '_' } }
    }

    suspend fun forget() {
        saving?.cancel()
        writing.withLock {
            lock.withLock {
                answers = null
                unsaved = false
                live.clear()
            }
            cache.forget(build)
        }
    }

    suspend fun flush() {
        saving?.cancel()
        save()
    }

    private suspend fun arguments(command: List<String>) =
        cached("child", command).filter { it["type"] == "child" && it["node-type"] == "arg" }.mapNotNull { it["name"] }

    private suspend fun argument(command: List<String>, name: String, optional: Boolean = false): Argument {
        val rows = cached("syntax", command + name)
            .map { SyntaxRow(it["nested"]?.toIntOrNull() ?: 0, it["symbol-type"] != "explanation", it["symbol"].orEmpty(), it["text"].orEmpty()) }
        val syntax = parseSyntax(rows)
        val offered = if (syntax.choices) completion(command + name) else emptyList()
        val single = (syntax.type as? ValueType.Enumeration)?.multiple == false
        if (single && syntax.choices && offered.isEmpty()) return Argument(name, ValueType.Switch, optional = optional)
        val choices = offered.filter { it["show"] == "true" && it["style"] != "syntax-meta" }.mapNotNull { it["completion"]?.removeSurrounding("\"") }
        if (name == "subject-alt-name") return Argument(name, ValueType.Many(ValueType.Text()), choices.map { "$it:" }, optional = optional)
        if (name == "dst-limit") return Argument(name, ValueType.Rate(flows, perFlow = true), optional = optional)
        if (name in adjusted) return Argument(name, adjustment, optional = optional)
        if (name == "connection-bytes" && syntax.type is ValueType.Integer) return Argument(name, ValueType.Span(syntax.type, listOf("from", "to"), open = true), optional = optional)
        if (name == "signal-range" && syntax.type is ValueType.Integer) return Argument(name, ValueType.Span(syntax.type, listOf("min", "max"), open = true, separator = ".."), optional = optional)
        if (command.take(2) == listOf("ip", "kid-control") && syntax.type == ValueType.Time) return Argument(name, ValueType.TimeWindow(emptyList()), optional = optional)
        if (single && name in ticked) return Argument(name, ValueType.Enumeration(multiple = true), choices, optional = optional)
        val lone = choices.size == 1 && offered.none { it["completion"] == "<value>" }
        if (syntax.type == ValueType.Enumeration(closed = true) && lone) return Argument(name, ValueType.Text(), choices, negatable = syntax.negatable, optional = optional)
        val yesNo = single && choices.toSet() == setOf("yes", "no")
        val ipv6 = syntax.type == ValueType.Unparsed(UNDOCUMENTED) && (command.firstOrNull() == "ipv6" || command.getOrNull(1)?.endsWith("6") == true)
        val type = when {
            yesNo -> ValueType.YesNo
            ipv6 -> ValueType.Ip6Prefix
            syntax.type == ValueType.Ip4 && name == "address" && command.dropLast(1) in prefixed -> ValueType.Ip4Prefix
            syntax.type == ValueType.Unparsed(UNDOCUMENTED) && name in offsets -> ValueType.Offset
            syntax.type == ValueType.Unparsed(UNDOCUMENTED) -> ValueType.Unparsed("")
            syntax.type is ValueType.Icmp && command.firstOrNull() == "ipv6" -> ValueType.Icmp(six = true)
            name in ported && syntax.type is ValueType.Integer -> ValueType.PortList
            else -> syntax.type
        }
        val several = name in lined || name == "target" && command.firstOrNull() == "queue"
        if (several && type !is ValueType.Many) return Argument(name, ValueType.Many(type), choices, optional = optional)
        return Argument(name, type, choices, negatable = syntax.negatable, optional = optional)
    }

    private suspend fun completion(path: List<String>): List<Map<String, String>> {
        val key = "completion:" + path.joinToString(",")
        lock.withLock { answers()[key] }?.let { return it }
        val gate = lock.withLock { asking.getOrPut(key) { Mutex() } }
        return gate.withLock {
            lock.withLock { answers()[key] ?: live[key]?.takeIf { it.first.elapsedNow() < NAMES_FOR }?.second }?.let { return@withLock it }
            val rows = link.call("/console/inspect", "=request=completion", "=path=" + path.joinToString(",")).rows
            if (rows.none { it["completion"] == "<value>" }) {
                lock.withLock { answers()[key] = rows }
                changed()
            } else {
                lock.withLock { live[key] = TimeSource.Monotonic.markNow() to rows }
            }
            rows
        }
    }

    private suspend fun cached(request: String, path: List<String>): List<Map<String, String>> {
        val key = request + ":" + path.joinToString(",")
        lock.withLock { answers()[key] }?.let { return it }
        val gate = lock.withLock { asking.getOrPut(key) { Mutex() } }
        return gate.withLock {
            lock.withLock { answers()[key] }?.let { return@withLock it }
            val rows = link.call("/console/inspect", "=request=$request", "=path=" + path.joinToString(",")).rows
            lock.withLock { answers()[key] = rows }
            changed()
            rows
        }
    }

    private suspend fun answers() = answers ?: cache.load(build).toMutableMap().also { answers = it }

    private suspend fun changed() {
        lock.withLock { unsaved = true }
        val waiting = scope
        if (waiting == null) {
            save()
        } else if (saving?.isActive != true) {
            saving = waiting.launch {
                delay(SAVE_AFTER)
                save()
            }
        }
    }

    private suspend fun save() = writing.withLock {
        val fresh = lock.withLock {
            if (!unsaved) return@withLock null
            unsaved = false
            answers().toMap()
        }
        if (fresh != null) cache.store(build, fresh)
    }

    private companion object {
        val NAMES_FOR = 5.seconds
        val SAVE_AFTER = 2.seconds
    }
}
