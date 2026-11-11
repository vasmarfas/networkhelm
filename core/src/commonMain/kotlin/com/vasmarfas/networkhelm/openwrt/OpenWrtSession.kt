package com.vasmarfas.networkhelm.openwrt

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
import com.vasmarfas.networkhelm.model.SafeModeKind
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.model.matches
import com.vasmarfas.networkhelm.packs.NativeMenu
import com.vasmarfas.networkhelm.packs.Pack
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.packs.packName
import com.vasmarfas.networkhelm.routeros.schema.label
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.SafeMode
import com.vasmarfas.networkhelm.session.TextStore
import com.vasmarfas.networkhelm.session.polled
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.SshSession
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class OpenWrtSession(
    private val ubus: Ubus,
    private val uci: Uci,
    facts: OpenWrtFacts,
    ssh: SshSession?,
    encrypted: Boolean,
    private val forms: LuciForms? = null,
    private val pollInterval: Duration = 1.seconds,
) : DeviceSession {
    override val ssh: SshSession? = ssh?.let(::OpenWrtSsh)
    override val fileChannel: SftpClient? = if ("file" in facts.objects) RpcdFiles(ubus) else null
    override val device: DeviceInfo = facts.device
    override val capabilities = Capabilities(
        subscriptions = false,
        streamingCommands = ubus is SshUbus,
        cancel = ubus is SshUbus,
        safeMode = SafeModeKind.ApplyRollback,
        terminal = ssh != null,
        files = ssh != null,
        export = ssh != null,
        encrypted = encrypted,
        menuContents = false,
        nativeMenu = forms != null && "luci-base" in facts.apps,
    )
    override val safeMode: SafeMode = UciApply()
    private val live = LiveLists(ubus, uci, facts)
    private val tools = Tools(ubus, facts.objects)
    private val objects = facts.objects
    private val lock = Mutex()
    private val reading = Mutex()
    private var validation: Map<String, Map<String, Map<String, String>>>? = null
    private var views: Map<String, List<String>>? = null
    private var shown: List<LuciNode>? = null
    private val maps = mutableMapOf<String, List<LuciMap>>()
    private val protocols = mutableMapOf<String, List<LuciOption>>()
    private val edits = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override suspend fun describe(path: MenuPath): MenuSchema = deviceErrors {
        live.schema(path) ?: tools.schema(path) ?: when (path.segments.size) {
            0 -> MenuSchema(path, NodeKind.Directory, uci.configs(), emptyList(), emptyList())
            1 -> MenuSchema(path, NodeKind.Directory, uci.sections(path.segments[0]).map { it.type }.distinct(), emptyList(), emptyList())
            else -> {
                val (config, type) = section(path)
                val sections = uci.sections(config).filter { typed(it, type) }
                val forms = luci(config).filter { it.type == type }
                val protocols = protocols(config, type, sections)
                val offering = protocols.flatMap { (proto, options) -> options.mapNotNull { it.name }.map { it to mapOf("proto" to proto) } }.groupBy({ it.first }, { it.second })
                val tabs = forms.flatMap { it.tabs }
                val offered = protocols.values.flatten().mapNotNull { option -> option.argument(tabs, offering[option.name].orEmpty()) }.groupBy { it.name }.values.map { same ->
                    same.first().takeIf { first -> same.all { it.type == first.type } } ?: same.first().copy(
                        type = if (same.any { it.type is ValueType.Many }) ValueType.Many(ValueType.Text(), LIST) else ValueType.Text(),
                        choices = emptyList(), label = same.map { it.label }.distinct().singleOrNull(), default = null, depends = same.flatMap { it.depends }.distinct(),
                        labels = emptyMap(),
                    )
                }
                val picked = mutableMapOf<String, List<String>>()
                val elsewhere = if (config == "firewall" && type == "zone") setOf("in", "out") else emptySet()
                val renamed = if (config == "wireless" && type == "wifi-iface") mapOf("_wpa_key" to "key") else emptyMap()
                val ofViews = forms.flatMap { section ->
                    val own = section.section?.takeIf { sections.size > 1 && !it.startsWith('@') }?.let { listOf(mapOf(NAME to it)) }.orEmpty()
                    section.options.filter { it.name !in elsewhere }.map { option -> renamed[option.name]?.let { option.copy(name = it) } ?: option }.mapNotNull { option ->
                        option.argument(section.tabs, own)?.let { argument -> argument.copy(choices = argument.choices.ifEmpty { picked.getOrPut(option.kind) { picks(option.kind) } }) }
                    }
                }
                val frequency = forms.firstNotNullOfOrNull { section -> section.options.firstOrNull { it.name == "_freq" }?.let { option -> section.tabs.firstOrNull { it.name == option.tab } } }
                    ?.let { tab -> radioFields(sections, tab.shown, ofViews.firstOrNull { it.name == "txpower" }, ofViews.firstOrNull { it.name == "country" }) }.orEmpty()
                val fromForms = (frequency + ofViews + offered).distinctBy { it.name }.sortedBy { argument -> tabs.indexOfFirst { it.shown == argument.tab } }
                val (types, handled) = types(config, type)
                val wireless = if (config == "wireless") wirelessTypes(type, sections) else emptyMap()
                val names = (types.keys + sections.flatMap { it.options.keys + it.lists.keys }).distinct() - fromForms.map { it.name }.toSet()
                val tabbed = fromForms.any { it.tab != null }
                val labels = fromForms.mapNotNull { it.label }.toSet()
                val arguments = (fromForms + names.map { name ->
                    val argument = (types[name] ?: wireless[name])?.let { uciArgument(name, it) }
                        ?: uciSwitch(name, sections.mapNotNull { it.options[name] })
                        ?: Argument(name, if (sections.any { name in it.lists }) ValueType.Many(ValueType.Text(), LIST) else ValueType.Text())
                    argument.copy(label = name.takeIf { label(name) in labels } ?: argument.label, tab = OTHER.takeIf { tabbed }, depends = handled[name].orEmpty().map { mapOf("proto" to it) }, optional = true)
                }).let { if (config == "wireless" && type == "wifi-iface") withRadio(it, tabs.firstOrNull()?.shown) else it }
                if (forms.isNotEmpty() && forms.all { it.single && it.config == null } && sections.size <= 1) {
                    MenuSchema(path, NodeKind.Settings, emptyList(), listOf("print", "set"), arguments)
                } else {
                    val named = listOf(Argument(NAME, ValueType.Text(), fixed = true)).takeUnless { forms.isNotEmpty() && forms.all { it.anonymous == true && it.named != true } }.orEmpty()
                    MenuSchema(path, NodeKind.List, emptyList(), listOf("print", "add", "set", "remove", "move"), named + arguments, prefill = forms.fold(emptyMap()) { values, form -> values + form.added })
                }
            }
        }
    }

    override fun watch(path: MenuPath, filter: Filter?, properties: List<String>?): Flow<ListEvent> =
        ((if (live.has(path)) live.follow(path) { polled(path, filter) } else null) ?: polled(path, filter)).catch { throw it.toDeviceException() }

    private fun polled(path: MenuPath, filter: Filter?): Flow<ListEvent> = flow {
        val rounds = when {
            !live.has(path) -> 2
            live.watched(path) -> 5
            else -> 1
        }
        emitAll(polled(pollInterval * rounds, merge(edits, if (live.has(path)) live.changes(path) else emptyFlow())) { items(path, filter) })
    }

    override suspend fun get(path: MenuPath, id: String?, properties: List<String>?): Item? = deviceErrors {
        items(path, null).firstOrNull { id == null || it.id == id }
    }

    override suspend fun count(path: MenuPath, filter: Filter?): Int = deviceErrors { items(path, filter).size }

    override suspend fun add(path: MenuPath, values: Map<String, String>): String = deviceErrors {
        if (live.has(path)) return@deviceErrors live.add(path, values)
        val (config, type) = section(path)
        val written = (values - NAME).filterValues { it.isNotEmpty() }
        val lists = lists(path, written, null)
        uci.add(config, type, values[NAME], (written - lists).toMap(), written.filterKeys { it in lists }.mapValues { it.value.split(LIST) })
    }.also { edited() }

    override suspend fun set(path: MenuPath, id: String?, changes: Map<String, String?>) = deviceErrors {
        if (live.has(path)) return@deviceErrors live.set(path, id ?: throw DeviceException(ErrorCategory.MissingItem, "$path has no settings"), changes)
        val (config, _) = section(path)
        val name = id ?: items(path, null).firstOrNull()?.id ?: throw DeviceException(ErrorCategory.MissingItem, "$path has no section")
        val written = changes.mapNotNull { (option, value) -> value?.takeIf { it.isNotEmpty() }?.let { option to it } }.toMap() - NAME
        val section = uci.sections(config).firstOrNull { it.name == name }
        val lists = lists(path, written, section)
        if (written.isNotEmpty()) uci.set(config, name, written - lists, written.filterKeys { it in lists }.mapValues { it.value.split(LIST) })
        // rpcd answers NOT_FOUND for an option the section does not have, which is unset already
        val removed = changes.filterValues { it.isNullOrEmpty() }.keys.filter { section == null || it in section.options || it in section.lists }
        if (removed.isNotEmpty()) uci.delete(config, name, removed)
    }.also { edited() }

    override suspend fun remove(path: MenuPath, ids: List<String>) = deviceErrors {
        if (live.has(path)) return@deviceErrors live.remove(path, ids)
        val (config, _) = section(path)
        ids.forEach { uci.delete(config, it) }
    }.also { edited() }

    // uci order puts the sections named first; the whole config is named so that nothing else moves
    override suspend fun move(path: MenuPath, ids: List<String>, before: String?) = deviceErrors {
        if (live.has(path)) return@deviceErrors live.move(path, ids, before)
        val (config, _) = section(path)
        val rest = uci.sections(config).map { it.name } - ids.toSet()
        val at = before?.let(rest::indexOf)?.takeIf { it >= 0 } ?: rest.size
        uci.order(config, rest.take(at) + ids + rest.drop(at))
    }.also { edited() }

    private fun edited() {
        edits.tryEmit(Unit)
    }

    override fun run(path: MenuPath, command: String, arguments: Map<String, String>): Flow<Item> = when {
        tools.has(path, command) -> tools.run(command, arguments)
        live.has(path) -> flow<Item> {
            live.run(path, command, arguments)
            edited()
        }
        else -> flow {
            val answer = ubus.call(path.segments.joinToString("."), command, JsonObject(arguments.mapValues { JsonPrimitive(it.value) }))
            emit(Item("", answer.mapValues { (_, value) -> (value as? JsonPrimitive)?.content ?: value.toString() }))
        }
    }.catch { throw it.toDeviceException() }

    override suspend fun export(path: MenuPath): String = deviceErrors {
        val shell = ssh ?: throw DeviceException(ErrorCategory.Unsupported, "uci export needs SSH")
        shell.exec("uci export" + path.segments.firstOrNull()?.let { " ${quote(it)}" }.orEmpty()).output
    }

    override fun close() {
        ssh?.close()
    }

    // a router that cannot be asked right now names no port, and the one of the login is tried
    suspend fun sshPort(): Int? = try {
        uci.sections("dropbear").firstOrNull { it.type == "dropbear" }?.let { it.options["Port"] ?: "22" }?.toIntOrNull()
    } catch (e: UbusException) {
        null
    } catch (e: DeviceException) {
        null
    }

    private suspend fun items(path: MenuPath, filter: Filter?): List<Item> {
        val items = if (live.has(path)) {
            live.read(path)
        } else {
            val (config, type) = section(path)
            uci.sections(config).filter { typed(it, type) }.map { section ->
                val values = section.options + section.lists.mapValues { it.value.joinToString(LIST) } +
                    if (type.endsWith('*')) mapOf(TYPE to section.type) else emptyMap()
                Item(section.name, if (section.anonymous) values else values + (NAME to section.name))
            }
        }
        return items.filter { filter?.matches(it) != false }
    }

    private suspend fun lists(path: MenuPath, values: Map<String, String>, section: UciSection?): Set<String> {
        val schema = describe(path)
        return values.filter { (name, value) ->
            section?.lists?.containsKey(name) == true || schema.argument(name)?.type is ValueType.Many || LIST in value
        }.keys
    }

    private suspend fun luci(config: String): List<LuciSection> {
        val forms = forms ?: return emptyList()
        val views = reading.withLock { views ?: forms.views().also { views = it } }
        return views[config].orEmpty().flatMap { maps(forms, it) }.flatMap { map -> map.allSections.filter { (it.config ?: map.config) == config } }
    }

    private suspend fun maps(forms: LuciForms, view: String) = reading.withLock { maps[view] ?: forms.maps(view).also { maps[view] = it } }

    override suspend fun packs(store: TextStore?): List<Pack> = deviceErrors {
        val forms = forms ?: return@deviceErrors emptyList()
        val covered = bundledPacks().values.filter { it.platform == device.platform.packName }.flatMap { it.window.tabs.map { tab -> tab.path.substringBefore('/') } }.toSet()
        val menu = shownMenu(forms)
        val versions = live.read(MenuPath.parse("status/packages")).associate { it.id to it["version"].orEmpty() }
        luciApps(menu, covered).flatMap { app ->
            val name = versions[app.app]?.let { version -> "luci-$WINDOWS-${app.app}-$version-${app.path.substringAfterLast('/')}.json".map { if (it.isLetterOrDigit() || it in ".-_~") it else '_' }.joinToString("") }
            val kept = name?.let { store?.read(it) }?.let { text ->
                try {
                    keptJson.decodeFromString(packList, text)
                } catch (e: SerializationException) {
                    null
                }
            }
            kept ?: listOfNotNull(luciPack(app, menu, covered) { maps(forms, it) }).also { packs -> if (name != null) store?.write(name, keptJson.encodeToString(packList, packs)) }
        }
    }

    override suspend fun nativeMenu(): NativeMenu? = deviceErrors {
        val forms = forms?.takeIf { capabilities.nativeMenu } ?: return@deviceErrors null
        luciMenu(shownMenu(forms)) { maps(forms, it) }
    }

    private suspend fun shownMenu(forms: LuciForms): List<LuciNode> {
        reading.withLock { shown }?.let { return it }
        val nodes = forms.menu()
        val files = fileKinds(ubus, objects, nodes.flatMap { conditionPaths(it.fs) }.toSet())
        val configs = nodes.flatMap { conditionConfigs(it.uci) }.toSet().associateWith { config ->
            try {
                uci.sections(config)
            } catch (e: UbusException) {
                if (e.status != UbusStatus.NOT_FOUND) throw e
                emptyList()
            }
        }
        return nodes.filter { satisfied(it.fs, it.uci, files) { config -> configs[config] } }.also { reading.withLock { shown = it } }
    }

    // a wireless network added without a radio never comes up, so the radio is a choice given as it is added
    private suspend fun withRadio(arguments: List<Argument>, tab: String?): List<Argument> {
        val radios = uci.sections("wireless").filter { it.type == "wifi-device" }.map { it.name }
        val device = arguments.firstOrNull { it.name == "device" } ?: Argument("device", ValueType.Text())
        val radio = device.copy(type = ValueType.Enumeration(), choices = radios, tab = tab, label = device.label ?: "Device", readOnly = false, fixed = true)
        return listOf(radio) + arguments.filter { it.name != "device" }
    }

    private suspend fun wirelessTypes(type: String, sections: List<UciSection>): Map<String, String> {
        val kind = mapOf("wifi-device" to "device", "wifi-iface" to "interface", "wifi-station" to "station", "wifi-vlan" to "vlan")[type] ?: return emptyMap()
        val radios = uci.sections("wireless").filter { it.type == "wifi-device" }.map { it.name }
        val types = radios.flatMap { radio ->
            try {
                ubus.call("network.wireless", "get_validate", JsonObject(mapOf("device" to JsonPrimitive(radio))))[radio]?.jsonObject?.get(kind)?.jsonObject.orEmpty()
                    .map { (option, datatype) -> option to datatype.jsonPrimitive.content }
            } catch (e: UbusException) {
                if (e.status != UbusStatus.METHOD_NOT_FOUND && e.status != UbusStatus.NOT_FOUND && e.status != UbusStatus.PERMISSION_DENIED) throw e
                emptyList()
            }
        }.toMap()
        return types.mapValues { (option, datatype) ->
            if (datatype.startsWith("list(") && sections.none { option in it.lists } && sections.any { option in it.options }) datatype.removePrefix("list(").removeSuffix(")") else datatype
        }
    }

    private suspend fun radioFields(radios: List<UciSection>, tab: String?, power: Argument?, country: Argument?): List<Argument> {
        val channels = mutableListOf<JsonObject>()
        val widths = mutableListOf<String>()
        val powers = mutableListOf<String>()
        val countries = mutableListOf<String>()
        fun results(answer: JsonObject, field: String) = answer["results"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject[field]?.jsonPrimitive?.content }
        for (radio in radios) {
            val device = JsonObject(mapOf("device" to JsonPrimitive(radio.name)))
            try {
                channels += ubus.call("iwinfo", "freqlist", device)["results"]?.jsonArray.orEmpty().map { it.jsonObject }
                widths += ubus.call("iwinfo", "info", device)["htmodes"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
                powers += results(ubus.call("iwinfo", "txpowerlist", device), "dbm")
                countries += results(ubus.call("iwinfo", "countrylist", device), "code")
            } catch (e: UbusException) {
                // a radio iwinfo cannot tell of, one that is down or a router without iwinfo, offers no choices
            }
        }
        return listOfNotNull(
            Argument("band", ValueType.Enumeration(), channels.mapNotNull { it["band"]?.jsonPrimitive?.content }.distinct().map { "${it}g" }, label = "Band", tab = tab),
            Argument("channel", ValueType.Text(), listOf("auto") + channels.mapNotNull { it["channel"]?.jsonPrimitive?.content }.distinct(), label = "Channel", tab = tab),
            Argument("htmode", ValueType.Enumeration(), widths.distinct(), label = "Width", tab = tab),
            power?.copy(choices = powers.distinct().sortedBy { it.toIntOrNull() }),
            country?.copy(choices = countries.distinct()),
        )
    }

    private suspend fun picks(kind: String): List<String> = try {
        when (kind) {
            "CBI.ZoneSelect" -> uci.sections("firewall").filter { it.type == "zone" }.mapNotNull { it.options["name"] }
            "CBI.NetworkSelect" -> uci.sections("network").filter { it.type == "interface" }.map { it.name }
            "CBI.DeviceSelect" -> ubus.call("network.device", "status").keys.toList()
            else -> emptyList()
        }
    } catch (e: UbusException) {
        if (e.status != UbusStatus.NOT_FOUND && e.status != UbusStatus.PERMISSION_DENIED) throw e
        emptyList()
    }

    private suspend fun protocols(config: String, type: String, sections: List<UciSection>): Map<String, List<LuciOption>> {
        val forms = forms ?: return emptyMap()
        if (config != "network" || type != "interface") return emptyMap()
        return (sections.mapNotNull { it.options["proto"] } + ubus.call("network", "get_proto_handlers").keys).distinct().associateWith { proto ->
            reading.withLock { protocols[proto] ?: forms.protocol(proto).also { protocols[proto] = it } }
        }
    }

    private suspend fun types(config: String, type: String): Pair<Map<String, String>, Map<String, List<String>>> {
        val known = lock.withLock {
            validation ?: try {
                ubus.call("service", "validate").mapValues { (_, types) ->
                    types.jsonObject.mapValues { (_, options) -> options.jsonObject.mapValues { it.value.jsonPrimitive.content } }
                }
            } catch (e: UbusException) {
                if (e.status != UbusStatus.PERMISSION_DENIED) throw e
                emptyMap()
            }.also { validation = it }
        }
        val own = known[config]?.get(type).orEmpty()
        if (config != "network" || type != "interface") return own to emptyMap()
        val handlers = ubus.call("network", "get_proto_handlers")
        val handled = handlers.keys.flatMap { proto ->
            handlers[proto]?.jsonObject?.get("validate")?.jsonObject.orEmpty().map { (option, datatype) -> Triple(option, datatype.jsonPrimitive.content, proto) }
        }
        val typed = handled.groupBy({ it.first }, { it.second }).mapValues { (_, datatypes) ->
            datatypes.distinct().singleOrNull() ?: if (datatypes.any { it.startsWith("list(") }) "list(string)" else "string"
        }
        return own + typed to handled.filter { it.first !in own }.groupBy({ it.first }, { it.third })
    }

    private fun typed(section: UciSection, type: String) = if (type.endsWith('*')) section.type.startsWith(type.dropLast(1)) else section.type == type

    private fun section(path: MenuPath): Pair<String, String> {
        val segments = path.segments
        if (segments.size != 2) throw DeviceException(ErrorCategory.MissingItem, "$path is not a UCI config and section type")
        return segments[0] to segments[1]
    }

    private inner class UciApply : SafeMode {
        private var applied: TimeMark? = null

        override val active: Flow<Boolean> = flow {
            while (true) {
                emit(applied?.let { it.elapsedNow() < ROLLBACK } == true)
                delay(pollInterval)
            }
        }

        // the count changes with an edit of this session and seldom from elsewhere
        override val actions: Flow<Int> = flow {
            while (true) {
                emit(deviceErrors { uci.changes().size })
                withTimeoutOrNull(pollInterval * PENDING_ROUNDS) { edits.first() }
            }
        }

        // false while another apply waits on the router, LuCI's for one
        override suspend fun enter(): Boolean = deviceErrors {
            try {
                uci.apply(ROLLBACK.inWholeSeconds.toInt())
                applied = TimeSource.Monotonic.markNow()
                edited()
                true
            } catch (e: UbusException) {
                if (e.status != UbusStatus.PERMISSION_DENIED) throw e
                false
            }
        }

        override suspend fun commit() {
            deviceErrors { uci.confirm() }
            applied = null
            edited()
        }

        override suspend fun rollback() {
            deviceErrors { uci.rollback() }
            applied = null
            edited()
        }
    }

    private companion object {
        val ROLLBACK = 90.seconds
        const val NAME = ".name"
        const val OTHER = "Other"
        const val TYPE = ".type"

        const val WINDOWS = 2
        const val PENDING_ROUNDS = 10
        val packList = ListSerializer(Pack.serializer())
        val keptJson = Json { ignoreUnknownKeys = true }
    }
}

private inline fun <T> deviceErrors(block: () -> T): T = try {
    block()
} catch (e: Exception) {
    throw e.toDeviceException()
}

internal fun Throwable.toDeviceException(): Throwable = when (this) {
    is UbusException -> DeviceException(
        when (status) {
            UbusStatus.NOT_FOUND -> ErrorCategory.MissingItem
            UbusStatus.PERMISSION_DENIED -> ErrorCategory.PermissionDenied
            UbusStatus.INVALID_ARGUMENT -> ErrorCategory.ArgumentValue
            UbusStatus.METHOD_NOT_FOUND, UbusStatus.NOT_SUPPORTED -> ErrorCategory.Unsupported
            UbusStatus.CONNECTION_FAILED -> ErrorCategory.ConnectionLost
            else -> ErrorCategory.General
        },
        message.orEmpty(),
        this,
    )
    else -> this
}
