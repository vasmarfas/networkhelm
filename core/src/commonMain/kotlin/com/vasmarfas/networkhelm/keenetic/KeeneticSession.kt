package com.vasmarfas.networkhelm.keenetic

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
import com.vasmarfas.networkhelm.packs.DEFAULT_MENU_PRIO
import com.vasmarfas.networkhelm.packs.Pack
import com.vasmarfas.networkhelm.packs.PackTab
import com.vasmarfas.networkhelm.packs.PackWindow
import com.vasmarfas.networkhelm.packs.packName
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.SafeMode
import com.vasmarfas.networkhelm.session.TextStore
import com.vasmarfas.networkhelm.session.keep
import com.vasmarfas.networkhelm.session.polled
import com.vasmarfas.networkhelm.ssh.LazySsh
import com.vasmarfas.networkhelm.ssh.SshAuth
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.ssh.connectSshTrusted
import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.transport.TrustPolicy
import com.vasmarfas.networkhelm.transport.openTcp
import com.vasmarfas.networkhelm.transport.openTlsTrusted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlin.concurrent.Volatile
import kotlin.math.floor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private val INTERFACE = MenuPath.parse("interface")
private val TOOLS = MenuPath.parse("tools")
private const val LOG_LINES = 500
private val RATES = 1.seconds

class KeeneticSession(
    call: KeeneticCall,
    override val device: DeviceInfo,
    encrypted: Boolean = false,
    private val pollInterval: Duration = 3.seconds,
    console: (() -> KeeneticCall)? = null,
    shell: SshSession? = null,
    private val language: String = "en",
    private val dictionary: suspend (String) -> JsonElement? = { null },
    // the model, firmware and components of the router: what help and the dictionary of the web interface depend on
    private val build: String = "",
    // the components installed and the mode, which decide the pages of the web interface; unknown components hide nothing
    private val components: Set<String>? = null,
    private val mode: String = "router",
) : DeviceSession {
    private val api = KeeneticCache(call)

    override val capabilities = Capabilities(
        subscriptions = false,
        streamingCommands = false,
        cancel = false,
        safeMode = SafeModeKind.None,
        terminal = console != null || shell != null,
        files = false,
        export = true,
        encrypted = encrypted,
        menuContents = false,
    )
    override val safeMode: SafeMode? = null
    override val ssh: SshSession? = shell ?: console?.let(::KeeneticConsole)

    private val edits = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val beats = TimeSource.Monotonic.markNow()

    // the router's words, read with the menu, name the choices of the fields too
    @Volatile
    private var named: Map<String, String> = emptyMap()
    private val nodes = nodes(api, components) { named }.associateBy { it.path }

    // the windows of All Settings, one for each context of the CLI, made as they are opened; two made at once cost a
    // second help, no more
    @Volatile
    private var settings: Map<MenuPath, Node> = emptyMap()

    @Volatile
    private var contexts: List<Context>? = null

    private fun find(path: MenuPath): Node? = if (!fits(path)) null else nodes[path] ?: when {
        path.segments.size > 1 && path.segments.first() == CONFIG -> settings[path] ?: Settings(api, configContext(path)).also { settings = settings + (path to it) }
        path.parent == TOOLS -> settings[path] ?: Tool(api, path.segments.last()) { named }.also { settings = settings + (path to it) }
        else -> null
    }

    // a page the web interface would not show this router is not offered here either, nor its menu entry
    private fun fits(path: MenuPath): Boolean {
        val name = path.toString()
        val needs = NEEDS.entries.filter { name == it.key || name.startsWith("${it.key}/") }.maxByOrNull { it.key.length }?.value ?: return true
        return (!needs.router || mode == "router") && (needs.components.isEmpty() || components == null || needs.components.any { it in components })
    }

    // what a path holds, for the menu, without a read: a read of every window at once would keep a router of a weak
    // processor busy for half a minute
    override suspend fun outline(path: MenuPath): MenuSchema {
        val node = find(path)
        val children = children(path)
        return when {
            node != null -> MenuSchema(path, node.kind, children, node.commands, emptyList())
            children.isNotEmpty() -> MenuSchema(path, NodeKind.Directory, children, emptyList(), emptyList())
            else -> throw missing(path)
        }
    }

    private fun children(path: MenuPath): List<String> {
        val depth = path.segments.size
        return nodes.keys.filter { it.segments.size > depth && it.segments.take(depth) == path.segments && fits(it) }.map { it.segments[depth] }.distinct()
    }

    override suspend fun describe(path: MenuPath): MenuSchema = calls {
        val node = find(path)
        val children = children(path)
        when {
            node != null -> {
                val own = node.arguments().map { argument -> if (argument.labels.isEmpty()) argument else argument.copy(labels = argument.labels.mapValues { named[it.value] ?: it.value }) }
                val names = own.map { it.name }.toSet()
                val shown = node.read().flatMap { it.values.keys }.distinct().filter { it !in names }.map { Argument(it, ValueType.Text(), readOnly = true) }
                MenuSchema(path, node.kind, children, node.commands, own + shown)
            }
            children.isNotEmpty() -> MenuSchema(path, NodeKind.Directory, children, emptyList(), emptyList())
            else -> throw missing(path)
        }
    }

    // the windows read on the same beats, and their reads go to the router as one request
    override fun watch(path: MenuPath, filter: Filter?, properties: List<String>?): Flow<ListEvent> = flow {
        emitAll(polled(pollInterval, edits, beats) { calls { node(path).read() }.filter { filter?.matches(it) != false } })
    }

    override suspend fun get(path: MenuPath, id: String?, properties: List<String>?): Item? = calls { node(path).read().firstOrNull { id == null || it.id == id } }

    override suspend fun count(path: MenuPath, filter: Filter?): Int = calls { node(path).read().count { filter?.matches(it) != false } }

    override suspend fun add(path: MenuPath, values: Map<String, String>): String = edit { node(path).add(values) }

    override suspend fun set(path: MenuPath, id: String?, changes: Map<String, String?>) {
        edit { node(path).set(id, changes) }
    }

    override suspend fun remove(path: MenuPath, ids: List<String>) {
        edit { node(path).remove(ids) }
    }

    override suspend fun move(path: MenuPath, ids: List<String>, before: String?) {
        edit { node(path).move(ids, before) }
    }

    // the rates of interfaces each second, as the router works them out for show interface stat
    override fun run(path: MenuPath, command: String, arguments: Map<String, String>): Flow<Item> = flow {
        if (path == TOOLS) return@flow emitAll(tool(command, arguments))
        (find(path) as? Commanded)?.let { node -> return@flow edit { node.run(command, arguments) }.forEach { emit(it) } }
        if (path != INTERFACE || command != "monitor-traffic") throw DeviceException(ErrorCategory.MissingItem, "Keenetic has no $path/$command")
        val names = arguments["interface"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        var round = 0
        while (names.isNotEmpty()) {
            for ((name, counters) in calls { api.stats(names) }) {
                emit(Item(name, mapOf("name" to name, "rx-bits-per-second" to counters["rxspeed"].orEmpty(), "tx-bits-per-second" to counters["txspeed"].orEmpty(), ".section" to "$round")))
            }
            round++
            // on the beats of the windows, so the rates go in their request
            val offset = beats.elapsedNow()
            delay(RATES * (floor(offset / RATES) + 1) - offset)
        }
    }

    // the lines a tool prints while it goes on: the answer to its start, then a read at its path each second; a tool the
    // window stopped is stopped in the router too, which would otherwise go on pinging
    private fun tool(command: String, arguments: Map<String, String>): Flow<Item> = flow {
        val target = "tools/$command"
        var answer = calls { api.rest("POST", target, JsonObject(arguments.mapValues { JsonPrimitive(it.value) })) } as? JsonObject
        var line = 0
        try {
            while (answer != null) {
                failure(answer)?.let { throw DeviceException(ErrorCategory.ArgumentValue, it) }
                for (text in (answer["message"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }) emit(Item("${line++}", mapOf("line" to text)))
                if ((answer["continued"] as? JsonPrimitive)?.booleanOrNull != true) answer = null else {
                    delay(1.seconds)
                    answer = calls { api.rest("GET", target) } as? JsonObject
                }
            }
        } finally {
            if (answer != null) withContext(NonCancellable) { api.rest("DELETE", target) }
        }
    }

    // the dictionary of the web interface in the language of the app; a router without one leaves the windows in their own words
    override suspend fun words(store: TextStore?): Map<String, String> = calls {
        store?.let { api.keep(it, build) }
        // the dictionary is half a megabyte through the cloud: it is kept with the build of the router
        val name = "keenetic-locale-${safe(build)}-${safe(language)}.json"
        val kept = store?.read(name)?.let {
            try {
                Json.parseToJsonElement(it)
            } catch (e: SerializationException) {
                null
            }
        }
        val words = kept ?: dictionary(language)?.also { read -> store?.keep(name, read.toString()) }
        wordsOf(words, language).also { named = it }
    }

    // All Settings: a window for each group of the contexts of the CLI that hold settings, as help describes them on this
    // router, and a context of the group chosen in the list beside its title; the group comes after the windows of the web
    // interface in the menu
    override suspend fun packs(store: TextStore?): List<Pack> = calls {
        store?.let { api.keep(it, build) }
        val all = named["All Settings"] ?: "All Settings"
        val shown = (contexts ?: api.contexts().also { contexts = it }).filter { it.words.first() !in HIDDEN }.distinctBy { it.words }
        // a context whose commands all do something or hold lists, such as dns-proxy https, has no field to show
        val fields = api.fieldsOf(shown.map { it.words })
        shown.filter { fields[it.words].orEmpty().isNotEmpty() }
            .groupBy { GROUPS[it.words.first()] ?: "Other" }
            .map { (group, members) ->
                val title = named[group] ?: group
                Pack(
                    platform = Platform.Keenetic.packName,
                    menu = listOf(all, title),
                    window = PackWindow(title, members.map { PackTab(it.title, configPath(it.words)) }, selector = true),
                    prio = DEFAULT_MENU_PRIO + 1,
                )
            }
    }

    override suspend fun export(path: MenuPath): String = calls {
        val lines = (api.show("show/running-config") as? JsonObject)?.get("message") as? JsonArray
        lines.orEmpty().joinToString("\n") { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
    }

    override fun close() {
        api.close()
        ssh?.close()
    }

    private fun node(path: MenuPath) = find(path) ?: throw missing(path)

    private fun missing(path: MenuPath) = DeviceException(ErrorCategory.MissingItem, "the app knows no list of Keenetic at $path")

    private suspend fun <T> edit(block: suspend () -> T): T = calls(block).also { edits.tryEmit(Unit) }

    private suspend fun <T> calls(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: DeviceException) {
        throw e
    } catch (e: Exception) {
        throw DeviceException(ErrorCategory.ConnectionLost, e.message ?: e::class.simpleName.orEmpty(), e)
    }
}

private class Needs(val router: Boolean = true, val components: Set<String> = emptySet())

// the conditions of the menu of the web interface, by the path of the window that stands for its page: the mode of a router and
// one of the components; the longest path that holds a window decides, and a window under none is on every Keenetic
private val NEEDS = mapOf(
    "traffic" to Needs(),
    "internet" to Needs(),
    "internet/mobile" to Needs(components = setOf("usbmodem", "usblte", "usbnet", "usbqmi")),
    "internet/wisp" to Needs(components = setOf("corewireless")),
    "internet/dsl" to Needs(components = setOf("dsl", "usbdsl")),
    "internet/sms" to Needs(components = setOf("usbqmi", "usblte")),
    "internet/ussd" to Needs(components = setOf("usbqmi", "usblte")),
    "vpn" to Needs(),
    "vpn/wireguard" to Needs(components = setOf("wireguard")),
    "vpn/openvpn" to Needs(components = setOf("openvpn")),
    "vpn/tunnel" to Needs(components = setOf("ipip", "gre", "eoip")),
    "ip/policy" to Needs(),
    "clients" to Needs(),
    "segments" to Needs(),
    "qos" to Needs(components = setOf("ntce")),
    "wifi" to Needs(router = false, components = setOf("corewireless")),
    "mws" to Needs(components = setOf("mws")),
    "rc/mws" to Needs(components = setOf("mws")),
    "rc/dns-proxy" to Needs(),
    "firewall" to Needs(),
    "ip/static" to Needs(),
    "show/ndns" to Needs(router = false, components = setOf("ndns", "ddns")),
    "rc/oc-server" to Needs(components = setOf("ocserver")),
    "rc/wireguard-server" to Needs(components = setOf("wireguard-server")),
    "system/storage" to Needs(router = false, components = setOf("storage")),
    "system/opkg" to Needs(router = false, components = setOf("opkg")),
)

private fun nodes(api: KeeneticCall, components: Set<String>?, words: () -> Map<String, String>): List<Node> = listOf(
    Dashboard(api),
    Connections(api, components = components, words = words),
    Connections(api, "internet/wired", components, words) { it !in MODEMS && it != WISP && it !in TUNNELS },
    Connections(api, "internet/mobile", components, words) { it in MODEMS },
    Connections(api, "internet/wisp", components, words) { it == WISP },
    WireguardConnections(api),
    WireguardPeers(api),
    OpenVpnConnections(api),
    IpTunnels(api, components),
    SixInFourTunnels(api),
    DslLine(api, components),
    Messages(api),
    Ussd(api),
    Storages(api),
    Opkg(api, components),
    IntelliQos(api),
    Apps(components, words),
    Clients(api),
    AccessPoints(api),
    Radios(api),
    Segments(api),
    Firewall(api, words),
    Firewall6(api, words),
    Interfaces(api),
    Listed(
        "ip/route", api,
        listOf(
            Argument("network", ValueType.Ip4, optional = true),
            Argument("mask", ValueType.Ip4, optional = true),
            Argument("host", ValueType.Ip4, optional = true),
            Argument("gateway", ValueType.Ip4, optional = true),
            Argument("interface", ValueType.Text(), optional = true),
            Argument("metric", ValueType.Integer(0), optional = true),
            Argument("auto", ValueType.YesNo),
            Argument("comment", ValueType.Text(), optional = true),
        ),
        key = listOf("network", "mask", "host", "gateway", "interface"),
    ),
    Forwarding(api, words),
    Listed("ip/nat", api, listOf(Argument("interface", ValueType.Text())), key = listOf("interface"), changes = false),
    Listed("ip/dhcp/host", api, listOf(Argument("mac", ValueType.Mac), Argument("ip", ValueType.Ip4)), key = listOf("mac")),
    Listed("ip/name-server", api, listOf(Argument("address", ValueType.Ip), Argument("domain", ValueType.Text(), optional = true)), key = listOf("address", "domain")),
    Pools(api),
    Users(api),
    Identity(api),
    Top(api),
    Tunnels(api),
    Policies(api),
    WirelessAcl(api),
    MwsMembers(api),
    Schedules(api),
    Leds(api),
    LedBindings(api, words),
    Buttons(api),
    Firmware(api),
    Shown("show/ip/route") { rows(api.show("show/ip/route")) },
    Shown("show/ip/arp") { rows(api.show("show/ip/arp")) },
    Shown("show/ip/dhcp/bindings") { rows(api.show("show/ip/dhcp/bindings"), "mac") },
    Shown("show/ip/nat") { rows(api.show("show/ip/nat")) },
    Shown("show/ip/name-server") { rows(api.show("show/ip/name-server")) },
    Shown("show/ip/service") { rows(api.show("show/ip/service")).map { Item("${it["service-name"]} ${it["port"]} ${it["family"]}", it.values) } },
    Shown("show/upnp/redirect") { rows(api.show("show/upnp/redirect")) },
    Shown("show/ipv6/addresses") { rows(api.show("show/ipv6/addresses")) },
    Shown("show/ipv6/prefixes") { rows(api.show("show/ipv6/prefixes")) },
    Journal(api),
    Shown("show/internet/status", NodeKind.Settings) { listOf(Item("", (api.show("show/internet/status") as? JsonObject)?.let(::fields).orEmpty())) },
    Shown("show/ndns", NodeKind.Settings) { listOf(Item("", (api.show("show/ndns") as? JsonObject)?.let(::fields).orEmpty())) },
    Shown("system/clock", NodeKind.Settings) {
        val (date, ntp) = api.shows("clock/date", "ntp/status")
        listOf(Item("", clockOf(date) + (ntp as? JsonObject)?.let(::fields).orEmpty().mapKeys { "ntp-${it.key}" }))
    },
    Shown("system/resource", NodeKind.Settings) { listOf(Item("", resource(api))) },
)

private suspend fun resource(api: KeeneticCall): Map<String, String> {
    val answers = api.post(listOf(showOf("system"), showOf("version"), CPU_AVERAGE), raising = false)
    val (system, version) = listOf("system", "version").mapIndexed { index, path -> (shown(answers.getOrNull(index), path) as? JsonObject)?.let(::fields).orEmpty() }
    return system + listOfNotNull(
        (averageOf(answers.getOrNull(2)) ?: system["cpuload"])?.let { "cpu-load" to it },
        system["memtotal"]?.toLongOrNull()?.let { "total-memory" to "${it * 1024}" },
        system["memfree"]?.toLongOrNull()?.let { "free-memory" to "${it * 1024}" },
        version["title"]?.let { "version" to it },
        version["description"]?.let { "board-name" to it },
        version["arch"]?.let { "architecture-name" to it },
    )
}

// the log as the web interface reads it: the last lines once, then what came since, which the router keeps for the session
// and gives at a GET of the path; a router that ended it starts again with the last lines
private class Journal(private val api: KeeneticCall) : Node("show/log") {
    private val lock = Mutex()
    private var lines: List<Item>? = null

    override suspend fun read(): List<Item> = lock.withLock {
        val known = lines
        val answer = if (known == null) null else api.rest("GET", "show/log") as? JsonObject
        val read = if (known != null && answer != null && (answer["continued"] as? JsonPrimitive)?.booleanOrNull == true) {
            (known + log(answer)).distinctBy { it.id }.takeLast(LOG_LINES)
        } else {
            log(api.rest("POST", "show/log", JsonObject(mapOf("max-lines" to JsonPrimitive(LOG_LINES)))) as? JsonObject ?: JsonObject(emptyMap()))
        }
        read.also { lines = it }
    }
}

// the lines come as an object by line number, with the level inside the message
private fun log(answer: JsonObject): List<Item> {
    val lines = answer["log"] as? JsonObject ?: return emptyList()
    return lines.values.mapNotNull { it as? JsonObject }.map { line ->
        val message = line["message"] as? JsonObject
        val topics = listOf(line.text("ident").orEmpty(), message?.text("level").orEmpty().lowercase()).filter { it.isNotEmpty() }
        Item(line.text("id").orEmpty(), linkedMapOf("time" to line.text("timestamp").orEmpty(), "topics" to topics.joinToString(","), "message" to message?.text("message").orEmpty().trimEnd()))
    }.sortedBy { it.id.toLongOrNull() }
}

// an edit as RCI takes it, for the journal: the REST tab of the web CLI of the router runs it as it is
fun keeneticCall(path: MenuPath, kind: EditKind, ids: List<String>, values: Map<String, String?>, moveBefore: String? = null): String {
    val named = path.toString() in setOf("interface", "user", "ip/dhcp/pool")
    val fields = values.mapValues { (_, value) -> value?.let(::JsonPrimitive) ?: NO }
    fun at(id: String?) = path.segments + listOfNotNull(id.takeIf { named })
    return when (kind) {
        EditKind.Add -> nested(at(values["name"]), JsonObject(if (named) fields - "name" else fields)).toString()
        EditKind.Set -> ids.ifEmpty { listOf(null) }.joinToString("\n") { nested(at(it), JsonObject(fields)).toString() }
        EditKind.Remove -> ids.joinToString("\n") { id -> nested(at(id), if (named) NO else JsonObject(mapOf("no" to JsonPrimitive(true), "index" to JsonPrimitive(id)))).toString() }
        EditKind.Move -> "# $path: ${ids.joinToString()} before ${moveBefore ?: "the end"}"
    }
}

// the CLI over SSH is reached where a port of it is open to the program, from the home network or forwarded to it: KeenDNS
// carries HTTPS alone, so its address is given apart. Through a tunnel, host and port are those of SSH, and RCI goes over it
class KeeneticLogin(
    val host: String,
    val user: String,
    val secret: String,
    val tls: Boolean = false,
    val port: Int = if (tls) 443 else 80,
    val language: String = "en",
    val sshHost: String = "",
    val sshPort: Int = 22,
    val tunnel: Boolean = false,
)

suspend fun connectKeenetic(
    login: KeeneticLogin,
    trust: TrustPolicy,
    open: suspend () -> ByteStream = { if (login.tls) openTlsTrusted(login.host, login.port, trust) else openTcp(login.host, login.port) },
): KeeneticSession {
    if (login.tunnel) return tunnelled(login, trust)
    val shell = login.sshHost.takeIf { it.isNotEmpty() && login.user.isNotEmpty() }?.let { host ->
        LazySsh { connectSshTrusted(host, login.sshPort, login.user, SshAuth.of(login.secret, null, false, null), trust) }
    }
    return connected(login, login.host, login.tls, shell, open)
}

// RCI over SSH alone: the web server of the router refuses what comes from its own loopback, so the tunnel goes to the address
// of its home network, which show interface names; the console goes over the same SSH
private suspend fun tunnelled(login: KeeneticLogin, trust: TrustPolicy): KeeneticSession {
    val ssh = connectSshTrusted(login.host, login.port, login.user, SshAuth.of(login.secret, null, false, null), trust)
    try {
        val home = homeAddress(ssh.exec("show interface Bridge0").output) ?: homeAddress(ssh.exec("show interface").output)
            ?: throw DeviceException(ErrorCategory.Unsupported, "the router named no address of its home network over SSH")
        return connected(login, home, true, ssh) { ssh.forward(home, 80) }
    } catch (e: Throwable) {
        ssh.close()
        throw e
    }
}

// the address of the home network in the text of show interface: of the first interface of the private level with an IPv4 one
internal fun homeAddress(text: String): String? {
    class Face(var address: String? = null, var level: String? = null)
    val faces = mutableListOf<Face>()
    for (line in text.lines()) {
        val (key, value) = line.split(':', limit = 2).map { it.trim() }.takeIf { it.size == 2 } ?: continue
        when {
            key == "id" -> faces += Face()
            key == "address" && IPV4.matches(value) -> faces.lastOrNull()?.let { if (it.address == null) it.address = value }
            key == "security-level" -> faces.lastOrNull()?.level = value
        }
    }
    return faces.firstOrNull { it.level == "private" && it.address != null }?.address
}

private val IPV4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}")

private suspend fun connected(login: KeeneticLogin, host: String, encrypted: Boolean, shell: SshSession?, open: suspend () -> ByteStream): KeeneticSession {
    val api = HttpKeenetic(host, login.user, login.secret, open)
    api.login()
    // show system mode is younger than KeeneticOS 2: a router that does not know it is a router
    val answers = api.post(listOf(showOf("system"), showOf("version"), showOf("system/mode")), raising = false)
    val (system, version, mode) = listOf("system", "version", "system/mode").mapIndexed { index, path -> (shown(answers.getOrNull(index), path) as? JsonObject)?.let(::fields).orEmpty() }
    val readOnly = login.user.isNotEmpty() && try {
        ((api.show("show/user") as? JsonObject)?.get("users") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .firstOrNull { it.text("user") == login.user }?.get("readonly")?.let { (it as? JsonPrimitive)?.booleanOrNull } == true
    } catch (e: DeviceException) {
        if (e.category == ErrorCategory.ConnectionLost) throw e
        false
    }
    val device = DeviceInfo(
        Platform.Keenetic,
        system["hostname"].orEmpty().ifEmpty { login.host },
        version["title"].orEmpty().ifEmpty { version["release"].orEmpty() },
        version["arch"].orEmpty(),
        version["description"].orEmpty().ifEmpty { version["model"].orEmpty() },
        emptySet(),
        if (readOnly) setOf("read") else setOf("read", "write"),
    )
    // the console has a session of its own: the CLI keeps the context of the last command in it
    return KeeneticSession(
        api, device, encrypted,
        console = { HttpKeenetic(host, login.user, login.secret, open) },
        shell = shell,
        language = login.language,
        dictionary = { language -> api.page("/assets/language/locale.$language.json") },
        build = listOf(version["hw_id"], version["release"], version["ndw.components"]?.hashCode()?.toUInt()?.toString(16)).joinToString(" ") { it.orEmpty() },
        components = version["ndw.components"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet(),
        mode = mode["active"]?.ifEmpty { null } ?: "router",
    )
}

// a name of a file the store keeps, of what the router named itself
private fun safe(text: String) = text.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("")
